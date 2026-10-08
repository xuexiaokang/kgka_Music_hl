import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

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
  Timer? _syncTimer;
  bool _active = false;
  bool _handlerBound = false;
  String _lastSongKey = '';
  void Function()? _onClosed;

  bool get isActive => _active;

  void onClosed(void Function() cb) => _onClosed = cb;

  Future<void> open(PlayerController player) async {
    if (_active) return;
    _player = player;
    if (!_handlerBound) {
      _fromNative.setMethodCallHandler(_onNativeCall);
      _handlerBound = true;
    }
    _active = true;
    _lastSongKey = _songKey(player);
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

  Map<String, dynamic> _payload(PlayerController p) => {
        'meta': _metaMap(p),
        'styles': _styles(),
        'lyrics': {'lines': _encodeLyrics(p.lyrics)},
        'transport': _transportMap(p),
      };

  Map<String, dynamic> _metaMap(PlayerController p) {
    final s = p.currentSong;
    return {
      'title': s?.title ?? '',
      'artist': s?.artist ?? '',
      'album': s?.albumName ?? '',
      'coverUrl': s?.coverUrl,
    };
  }

  Map<String, dynamic> _transportMap(PlayerController p) => {
        'positionMs': p.smoothPosition.inMilliseconds,
        'durationMs': p.duration.inMilliseconds,
        'isPlaying': p.isPlaying,
      };

  Map<String, dynamic> _styles() => {
        'activeSizeSp': 30.0,
        'inactiveSizeSp': 20.0,
        'translationSizeSp': 15.0,
        'lineGapDp': 14.0,
        'paddingHorizontalDp': 8.0,
        'paddingVerticalDp': 24.0,
        'baseColor': 0x66FFFFFF,
        'activeColor': 0xFFFFFFFF.toInt(),
        'transColor': 0x40FFFFFF,
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
    if (key != _lastSongKey) {
      _lastSongKey = key;
      _toNative.invokeMethod<void>('meta', _metaMap(p));
      _toNative.invokeMethod<void>('lyrics', {'lines': _encodeLyrics(p.lyrics)});
    }
    _pushSync();
  }

  void _pushSync() {
    final p = _player;
    if (p == null) return;
    _toNative.invokeMethod<void>('sync', _transportMap(p));
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
