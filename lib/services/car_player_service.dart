import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../controllers/auth_controller.dart';
import '../controllers/player_controller.dart';
import '../models/music_models.dart';

/// 全原生车机播放器的 Dart 侧控制器。
///
/// 通过 [CarPlayerBridge] 对应的两条 MethodChannel 与原生 `CarPlayerActivity` 通信：
///  - `ka.car_player/native`：Dart→原生（open/sync/meta/lyrics/close）
///  - `ka.car_player/flutter`：原生→Dart（playPause/seek/next/prev/closed/requestSync）
///
/// 进入车机全屏时调 [open] 拉起原生 Activity；此后 FlutterActivity 被覆盖、引擎停帧，
/// 全屏交由原生 HWUI 渲染。播放动作仍回传到现有 [PlayerController]（音频链路不变）。
class CarPlayerService {
  CarPlayerService._();
  static final CarPlayerService instance = CarPlayerService._();

  static const MethodChannel _toNative = MethodChannel('ka.car_player/native');
  static const MethodChannel _fromNative = MethodChannel('ka.car_player/flutter');

  PlayerController? _player;
  AuthController? _auth;
  Timer? _syncTimer;
  bool _active = false;
  bool _handlerBound = false;
  String _lastSongKey = '';
  String _lastMetaSig = '';
  void Function()? _onClosed;

  bool get isActive => _active;

  void onClosed(void Function() cb) => _onClosed = cb;

  Future<void> open(PlayerController player, AuthController auth) async {
    if (_active) return;
    _player = player;
    _auth = auth;
    if (!_handlerBound) {
      _fromNative.setMethodCallHandler(_onNativeCall);
      _handlerBound = true;
    }
    _active = true;
    _lastSongKey = _songKey(player);
    _lastMetaSig = _metaSig(player);
    player.addListener(_onPlayerChanged);
    try {
      await _toNative.invokeMethod<void>('open', _payload(player));
      _syncTimer?.cancel();
      _syncTimer = Timer.periodic(const Duration(seconds: 1), (_) => _pushSync());
    } on MissingPluginException {
      close();
    }
  }

  void close() {
    if (!_active) return;
    _active = false;
    _syncTimer?.cancel();
    _syncTimer = null;
    _player?.removeListener(_onPlayerChanged);
    _player = null;
    _auth = null;
  }

  /// 关闭原生页（用户从 Flutter 侧返回时）。
  Future<void> dismissNative() async {
    try {
      await _toNative.invokeMethod<void>('close');
    } catch (_) {}
  }

  String _songKey(PlayerController p) {
    final s = p.currentSong;
    return s == null ? '' : '${s.hash}|${s.id}|${p.lyrics.length}';
  }

  /// "更多"面板相关状态签名：任一变化即需把 meta 重推给原生以刷新勾选/副标题。
  String _metaSig(PlayerController p) => [
        p.isSleepTimerActive,
        p.isSleepFinishCurrentSong,
        p.sleepTimerRemaining?.inSeconds,
        p.audioEffectsLabel,
        p.audioQuality.index,
        p.playbackSpeed,
        p.desktopLyricsEnabled,
        p.climax?.startTime.inMilliseconds,
      ].join('|');

  Map<String, dynamic> _payload(PlayerController p) => {
        'meta': _metaMap(p),
        'styles': _styles(),
        'lyrics': {'lines': _encodeLyrics(p.lyrics)},
        'transport': _transportMap(p),
      };

  Map<String, dynamic> _metaMap(PlayerController p) {
    final s = p.currentSong;
    final liked = (_auth != null && s != null) ? _auth!.isLiked(s) : false;
    final isKugou = s?.source == SongSource.kugou;
    final climax = p.climax;
    return {
      'title': s?.title ?? '',
      'artist': s?.artist ?? '',
      'album': s?.albumName ?? '',
      'coverUrl': s?.coverUrl,
      'playMode': p.playbackMode.index,
      'playModeLabel': p.playbackModeLabel,
      'isLiked': liked,
      'canLike': isKugou,
      'isKugou': isKugou,
      'queue': _queueList(p),
      // ---- "更多"面板：当前选择快照，供原生渲染勾选/副标题 ----
      'audioQualityIndex': p.audioQuality.index,
      'audioQualityLabel': p.audioQuality.label,
      'playbackSpeed': p.playbackSpeed,
      'playbackSpeedLabel': p.playbackSpeedLabel,
      'audioEffectLabel': p.audioEffectsLabel,
      'effectsSupported': p.isAudioEffectsSupported,
      'effectNames': [for (final e in PlayerController.equalizerPresets) e.name],
      'desktopLyricsSupported': p.isDesktopLyricsSupported,
      'desktopLyricsEnabled': p.desktopLyricsEnabled,
      'lyricBlurEnabled': p.lyricBlurEnabled,
      'sleepActive': p.isSleepTimerActive,
      'sleepFinishCurrent': p.isSleepFinishCurrentSong,
      'sleepRemainingMs': p.sleepTimerRemaining?.inMilliseconds,
      'climaxStartMs':
          (climax != null && climax.isValid) ? climax.startTime.inMilliseconds : null,
    };
  }

  /// 播放队列快照，供原生全屏的"歌单/队列"面板展示。
  List<Map<String, dynamic>> _queueList(PlayerController p) {
    final cur = p.currentSong;
    return [
      for (var i = 0; i < p.queue.length; i++)
        {
          'index': i,
          'title': p.queue[i].title,
          'artist': p.queue[i].artist,
          'active': cur != null && p.queue[i].hash == cur.hash,
        },
    ];
  }

  Map<String, dynamic> _transportMap(PlayerController p) {
    final s = p.currentSong;
    return {
      'positionMs': p.smoothPosition.inMilliseconds,
      'durationMs': p.duration.inMilliseconds,
      'isPlaying': p.isPlaying,
      'isBuffering': p.isBuffering,
      'isPreparing': p.isPreparing,
      'playMode': p.playbackMode.index,
      'isLiked': (_auth != null && s != null) ? _auth!.isLiked(s) : false,
    };
  }

  Map<String, dynamic> _styles() => {
        'activeSizeSp': 48.0,
        'inactiveSizeSp': 34.0,
        'translationSizeSp': 20.0,
        'lineGapDp': 20.0,
        'paddingHorizontalDp': 24.0,
        'paddingVerticalDp': 40.0,
        'baseColor': 0x57FFFFFF,
        'activeColor': 0xFFFFFFFF.toInt(),
        'transColor': 0x3DFFFFFF,
        'showTranslation': true,
      };

  List<Map<String, dynamic>> _encodeLyrics(List<LyricLine> lyrics) {
    return [
      for (var i = 0; i < lyrics.length; i++)
        {
          'text': lyrics[i].text,
          'translation': lyrics[i].translation,
          'startMs': lyrics[i].time.inMilliseconds,
          'endMs': i + 1 < lyrics.length
              ? lyrics[i + 1].time.inMilliseconds
              : lyrics[i].time.inMilliseconds + 5000,
          'words': [
            for (final w in lyrics[i].words)
              {
                'text': w.text,
                'startMs': w.time.inMilliseconds,
                'endMs': (w.time + w.duration).inMilliseconds,
              },
          ],
        },
    ];
  }

  void _onPlayerChanged() {
    if (!_active) return;
    final p = _player;
    if (p == null) return;
    final key = _songKey(p);
    final sig = _metaSig(p);
    if (key != _lastSongKey) {
      _lastSongKey = key;
      _lastMetaSig = sig;
      _toNative.invokeMethod<void>('meta', _metaMap(p));
      _toNative.invokeMethod<void>('lyrics', {'lines': _encodeLyrics(p.lyrics)});
    } else if (sig != _lastMetaSig) {
      _lastMetaSig = sig;
      _toNative.invokeMethod<void>('meta', _metaMap(p));
    }
    _pushSync();
  }

  void _pushSync() {
    final p = _player;
    if (p == null) return;
    _toNative.invokeMethod<void>('sync', _transportMap(p));
  }

  void _pushMeta() {
    final p = _player;
    if (p == null) return;
    _toNative.invokeMethod<void>('meta', _metaMap(p));
  }

  Future<void> _onNativeCall(MethodCall call) async {
    final p = _player;
    switch (call.method) {
      case 'playPause':
        await p?.togglePlay();
        _pushSync();
        break;
      case 'seek':
        final args = call.arguments as Map?;
        final ms = (args?['ms'] as num?)?.toInt() ?? 0;
        await p?.seek(Duration(milliseconds: ms));
        _pushSync();
        break;
      case 'next':
        await p?.next();
        break;
      case 'prev':
        await p?.previous();
        break;
      case 'playMode':
        await p?.cyclePlaybackMode();
        _pushMeta();
        _pushSync();
        break;
      case 'like':
        final song = p?.currentSong;
        final auth = _auth;
        if (song != null && auth != null) {
          await auth.toggleLike(song);
          _pushSync();
        }
        break;
      case 'playQueueIndex':
        final args = call.arguments as Map?;
        final idx = (args?['index'] as num?)?.toInt() ?? -1;
        if (p != null && idx >= 0 && idx < p.queue.length) {
          await p.playSong(p.queue[idx], queue: p.queue);
        }
        break;
      case 'setSpeed':
        final speed = (call.arguments as Map?)?['speed'] as num?;
        if (p != null && speed != null) {
          await p.setPlaybackSpeed(speed.toDouble());
          _pushMeta();
          _pushSync();
        }
        break;
      case 'setQuality':
        final qIdx = (call.arguments as Map?)?['qualityIndex'] as num?;
        if (p != null && qIdx != null) {
          final q = AudioQuality
              .values[qIdx.toInt().clamp(0, AudioQuality.values.length - 1)];
          await p.setAudioQuality(q, reloadCurrent: true);
          _pushMeta();
          _pushSync();
        }
        break;
      case 'setEffect':
        final name = (call.arguments as Map?)?['name'] as String?;
        if (p != null && name != null) {
          final preset = PlayerController.equalizerPresets
              .firstWhere((e) => e.name == name, orElse: () => PlayerController.equalizerPresets.first);
          // 车机面板无独立 EQ 开关：选预设即视为开启均衡器，否则 applyEqualizerPreset 不会下发到音频。
          await p.setEqualizerEnabled(true);
          await p.applyEqualizerPreset(preset);
          _pushMeta();
        }
        break;
      case 'climax':
        if (p != null) {
          final ok = await p.playClimaxPreview();
          _pushMeta();
          _pushSync();
          _toNative.invokeMethod<void>('toast', {
            'text': ok ? '已跳转到高潮片段' : '暂无高潮片段',
          });
        }
        break;
      case 'sleepTimer':
        if (p != null) {
          final args = call.arguments as Map?;
          final minutes = (args?['minutes'] as num?)?.toInt() ?? 0;
          final finishCurrent = args?['finishCurrent'] == true;
          if (minutes <= 0 && !finishCurrent) {
            p.cancelSleepTimer();
          } else if (finishCurrent) {
            p.setSleepFinishCurrentSongNow();
          } else {
            p.setSleepTimer(Duration(minutes: minutes));
          }
          _pushMeta();
        }
        break;
      case 'toggleDesktopLyrics':
        if (p != null) {
          await p.setDesktopLyricsEnabled(!p.desktopLyricsEnabled);
          _pushMeta();
        }
        break;
      case 'playNext':
        if (p != null) {
          final ok = await p.playCurrentSongNext();
          _pushMeta();
          _toNative.invokeMethod<void>('toast', {
            'text': ok ? '已添加到下一首播放' : '添加失败',
          });
        }
        break;
      case 'requestSync':
        if (p == null) break;
        _toNative.invokeMethod<void>('meta', _metaMap(p));
        _toNative.invokeMethod<void>('lyrics', {'lines': _encodeLyrics(p.lyrics)});
        _pushSync();
        break;
      case 'closed':
        close();
        _onClosed?.call();
        break;
    }
  }
}
