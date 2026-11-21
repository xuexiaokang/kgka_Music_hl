import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:audio_session/audio_session.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';
import 'package:flutter/scheduler.dart';
import 'package:just_audio/just_audio.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../core/shuffle_cycle.dart';
import '../models/music_models.dart';
import '../services/audio_effects_service.dart';
import '../services/cache_service.dart';
import '../services/cover_service.dart';
import '../services/desktop_lyrics_service.dart';
import '../services/music_api.dart';
import '../services/local_lyrics_service.dart';
import '../services/music_audio_handler.dart';
import '../services/bluetooth_lyrics_service.dart';
import '../services/playback_history_service.dart';
import '../services/playback_stats_service.dart';
import '../services/super_lyric_service.dart';
import '../services/netease_image_provider.dart';
import 'download_controller.dart';
import 'local_music_controller.dart';

enum PlaybackMode { playlistLoop, shuffle, singleLoop }

/// 音量均衡的增益策略。
/// - off：关闭均衡（原生释放效果器）。
/// - fixed：固定增益档位（旧行为，不需要录音权限）。
/// - auto：实时响度 AGC，按每首歌实际音量自动调增益（需 RECORD_AUDIO）。
enum VolumeGainMode { fixed, auto }

/// 当前曲整曲响度元数据的来源。
///
/// 设置页「实时增益」详情据此如实标注，不要把估算说成实测。
enum SongLoudnessSource {
  /// 本次 `/song/url` 实测返回 `volume`（含 `volume_peak`）。
  url,

  /// 本次没走音源（本地文件 / 缓存命中），用历史缓存里的实测值。
  cache,

  /// 上一首播放时端上 Visualizer 累计整曲 RMS 得到的值（续十一）。
  /// 已按音源 `volume` 口径标定，精度约 ±1 dB，但不是音源下发的官方实测。
  measured,

  /// 无任何实测元数据（刚发行新歌返回哨兵值 0 / 本地文件无缓存），
  /// 用曲库统计中位数先验兜底。
  fallback,
}

/// 设置页「实时增益」展示用的当前曲响度元数据快照。
///
/// 只读、按切歌时刻采集，不驱动任何增益决策；增益的真实计算仍在
/// [_presetGainForVolume]（本文件下方）完成。这里只是把诊断所需的
/// 输入（音源元数据）、推导（目标 − 电平）与结果摆到 UI 上。
class SongLoudnessMeta {
  const SongLoudnessMeta({
    required this.songTitle,
    required this.artist,
    required this.hash,
    required this.songSource,
    required this.source,
    required this.volumeDb,
    required this.volumePeakDb,
    required this.effectiveVolumeDb,
    required this.fallbackVolumeDb,
    required this.targetRmsDb,
    required this.presetGainDb,
    this.calibrationOffsetDb = 0.0,
    this.calibrationSamples = 0,
    this.calibrationReady = false,
  });

  final String songTitle;
  final String artist;
  final String hash;
  final SongSource songSource;

  /// 整曲响度元数据的来源（实测 / 缓存 / 先验）。
  final SongLoudnessSource source;

  /// 音源实测整曲平均电平 `volume`（dBFS）；null 表示无元数据。
  final double? volumeDb;

  /// 音源实测峰值 `volume_peak`（dBFS）；仅 [SongLoudnessSource.url] 时有值。
  /// 基准未公开（实测有正值），仅展示、不参与削波保护。
  final double? volumePeakDb;

  /// 实际用于计算增益的电平：有元数据取实测，否则取曲库先验。
  final double effectiveVolumeDb;
  final double fallbackVolumeDb;
  final double targetRmsDb;
  final double presetGainDb;

  /// 端上整曲 RMS 与音源 `volume` 的口径标定偏置（全局，滑动中位数）。
  final double calibrationOffsetDb;
  final int calibrationSamples;
  final bool calibrationReady;

  /// 峰均比（crest）：`volume_peak − volume`。实测中位约 11.1 dB。
  double? get crestDb => (volumeDb != null && volumePeakDb != null)
      ? volumePeakDb! - volumeDb!
      : null;

  /// 是否只是曲库均值先验（不是任何实测值）。缓存/端上实测都有可用电平。
  bool get isEstimate => source == SongLoudnessSource.fallback;

  String get sourceLabel => switch (source) {
        SongLoudnessSource.url => '音源实测',
        SongLoudnessSource.cache => '历史缓存',
        SongLoudnessSource.measured => '端上整曲实测',
        SongLoudnessSource.fallback => '曲库均值先验',
      };

  String get songSourceLabel => switch (songSource) {
        SongSource.kugou => '酷狗',
        SongSource.netease => '网易云',
        SongSource.local => '本地',
      };
}

/// 自动增益的目标响度档位，映射到原生 DynamicsProcessing 的 targetRmsDb。
/// 数值越接近 0 越响。
///
/// 2026-11-20 续九：整套档位相对续一~续八**整体 +4 dB**（原 -20/-16/-12）。
/// 依据实车听感：原标准 -16dBFS（≈ -14 LUFS，对齐 Spotify/YouTube）在车机上
/// 明显比网易云小，只有 -12 档（≈ -10 LUFS）才接近。国内平台归一化目标未公开、
/// 实测行为也不统一（不少曲目不做全量归一化或目标更响），所以以实车为准。
/// 代价：峰值余量减少 4 dB，标准档约 36% 曲目输出峰值会越过 0dBFS，由
/// limiter（-1dBFS / attack 0.3ms）兜住而非硬削波——车机噪音环境下听感可接受。
/// 响亮 -8 档限幅明显（约 99% 曲目），仅建议高速/极嘈杂场景使用。
/// 注意：整体推高与「另加车机音量补偿」物理等价，不存在无损变响的方案。
enum AutoLoudnessLevel { gentle, standard, loud }

extension AutoLoudnessLevelX on AutoLoudnessLevel {
  double get targetRmsDb => switch (this) {
    AutoLoudnessLevel.gentle => -16.0,
    AutoLoudnessLevel.standard => -12.0,
    AutoLoudnessLevel.loud => -8.0,
  };

  String get label => switch (this) {
    AutoLoudnessLevel.gentle => '轻柔',
    AutoLoudnessLevel.standard => '标准',
    AutoLoudnessLevel.loud => '响亮',
  };
}

/// 播放队列的来源（歌单 / 专辑）。
///
/// 只持久化队列本身不够：进程被系统回收时快照可能缺失或残缺，重启后队列
/// 退化成单曲，点「下一首」看起来毫无反应。记住来源后，恢复时发现队列
/// 不完整就能按来源重新拉一遍，保证切歌始终在原来的歌单里进行。
class QueueOrigin {
  const QueueOrigin({required this.kind, required this.id, this.title});

  const QueueOrigin.playlist({required String id, String? title})
    : this(kind: 'playlist', id: id, title: title);

  const QueueOrigin.album({required String id, String? title})
    : this(kind: 'album', id: id, title: title);

  /// 排行榜来源：重启后可重新拉取整榜，保证「下一首」仍在榜内。
  const QueueOrigin.rank({required String id, String? title})
    : this(kind: 'rank', id: id, title: title);

  final String kind;
  final String id;
  final String? title;

  bool get isAlbum => kind == 'album';

  bool get isRank => kind == 'rank';

  Map<String, dynamic> toJson() => {
    'kind': kind,
    'id': id,
    if (title != null) 'title': title,
  };

  static QueueOrigin? fromJson(Object? value) {
    if (value is! Map) return null;
    final map = asMap(value);
    final kind = asString(map['kind']);
    final id = asString(map['id']);
    if (kind == null || id == null || id.isEmpty) return null;
    return QueueOrigin(kind: kind, id: id, title: asString(map['title']));
  }
}

/// 解析磁盘上保存的播放进度。
///
/// 新格式是 `{"hash": "...", "ms": 12345}`，只有 hash 与 [songHash] 一致
/// 才采用，避免把上一首的位置套到新歌上；旧格式是纯 int 毫秒值，直接兼容。
/// 返回 null 表示没有可用进度。
Duration? parseSavedPosition(Object? raw, String? songHash) {
  if (raw is int) {
    return raw > 0 ? Duration(milliseconds: raw) : null;
  }
  if (raw is! String || raw.isEmpty) return null;
  try {
    final decoded = jsonDecode(raw);
    if (decoded is! Map<String, dynamic>) return null;
    final hash = asString(decoded['hash']);
    final ms = asInt(decoded['ms']);
    if (ms == null || ms <= 0) return null;
    if (songHash != null && songHash.isNotEmpty && hash != songHash) {
      return null;
    }
    return Duration(milliseconds: ms);
  } catch (_) {
    return null;
  }
}

/// 生成要落盘的播放进度记录（JSON 字符串）。
///
/// 返回 `null` 表示「保持磁盘上已有记录不变，不要改写」。
///
/// 恢复播放后音源还没加载完成时，[loadedSongHash] 为空，此时
/// [playerPosition] 通常是 0（恢复进度还没 seek 到引擎上）。若照写，退到后台
/// 的立即落盘就会把磁盘里恢复出来的进度（例如 120 秒）抹成 0。
/// 因此：音源已加载时按实际加载的歌曲写实时位置；音源未加载但有待应用的
/// [pendingResume] 时写回该恢复进度；否则不改写磁盘。
String? resolvePositionRecord({
  required String? currentSongHash,
  required String? loadedSongHash,
  required Duration playerPosition,
  Duration? pendingResume,
}) {
  final hasSource = loadedSongHash != null && loadedSongHash.isNotEmpty;
  if (hasSource) {
    return jsonEncode({
      'hash': loadedSongHash,
      'ms': playerPosition.inMilliseconds,
    });
  }
  if (currentSongHash == null || currentSongHash.isEmpty) return null;
  if (pendingResume != null && pendingResume > Duration.zero) {
    return jsonEncode({
      'hash': currentSongHash,
      'ms': pendingResume.inMilliseconds,
    });
  }
  // 没有可用进度可写：保留磁盘原值，避免用 0 覆盖，也不要误删。
  return null;
}

/// 合并「按来源重建的队列」与重建期间用户手动插入的歌曲。
///
/// 后台按来源拉完整歌单时，用户可能已用「下一首播放」插入了歌曲。直接用
/// [rebuilt] 覆盖 [currentQueue] 会把这些歌弄丢。这里以 [rebuilt] 为骨架，
/// 把 [currentQueue] 相对 [originalQueue] 新增、且 [rebuilt] 不含的歌曲，
/// 按原顺序插回当前歌曲之后。
List<Song> mergeRebuiltQueue({
  required List<Song> originalQueue,
  required List<Song> currentQueue,
  required List<Song> rebuilt,
  required Song currentSong,
}) {
  String id(Song song) => song.hash.isNotEmpty ? song.hash : song.id;
  final currentId = id(currentSong);
  final originalIds = originalQueue.map(id).toSet();
  final rebuiltIds = rebuilt.map(id).toSet();
  final insertions = currentQueue
      .where(
        (song) =>
            !originalIds.contains(id(song)) && !rebuiltIds.contains(id(song)),
      )
      .toList();
  if (insertions.isEmpty) return rebuilt;

  final result = List<Song>.of(rebuilt);
  final currentIndex = result.indexWhere((song) => id(song) == currentId);
  final base = currentIndex < 0 ? 0 : currentIndex + 1;
  final insertAt = base > result.length ? result.length : base;
  result.insertAll(insertAt, insertions);
  return result;
}

class AudioEffectPreset {
  const AudioEffectPreset({required this.name, required this.levels});

  final String name;
  final List<int> levels;
}

class PlayerController extends ChangeNotifier {
  static const _listenTimeSettingKey = 'settings.add_listening_time_enabled';
  static const _audioQualitySettingKey = 'settings.audio_quality';
  static const _equalizerEnabledSettingKey = 'settings.equalizer_enabled';
  static const _equalizerLevelsSettingKey = 'settings.equalizer_levels';
  static const _equalizerPresetSettingKey = 'settings.equalizer_preset';
  static const _bassBoostEnabledSettingKey = 'settings.bass_boost_enabled';
  static const _bassBoostStrengthSettingKey = 'settings.bass_boost_strength';
  static const _audioInterruptionEnabledSettingKey =
      'settings.audio_interruption_enabled';
  static const _autoResumeAfterInterruptionSettingKey =
      'settings.auto_resume_after_interruption';
  static const _playbackSpeedSettingKey = 'settings.playback_speed';
  static const _desktopLyricsEnabledSettingKey =
      'settings.desktop_lyrics_enabled';
  static const _desktopLyricsSettingsKey = 'settings.desktop_lyrics_settings';
  static const _smartQualitySettingKey = 'settings.smart_quality_enabled';
  static const _autoPlayOnStartupSettingKey = 'settings.auto_play_on_startup';
  static const _resumeLastPlaylistOnStartupSettingKey =
      'settings.resume_last_playlist_on_startup';
  static const _autoPlayOnDeviceConnectedSettingKey =
      'settings.auto_play_on_device_connected';
  static const _autoPauseOnDeviceDisconnectSettingKey =
      'settings.auto_pause_on_device_disconnect';
  static const _volumeNormalizationEnabledSettingKey =
      'settings.volume_normalization_enabled';
  static const _volumeGainDbSettingKey = 'settings.volume_gain_db';
  static const _volumeGainModeSettingKey = 'settings.volume_gain_mode';
  static const _autoLoudnessLevelSettingKey = 'settings.auto_loudness_level';

  /// 音源整曲响度缓存：song.hash → `volume`（dBFS）。
  ///
  /// 音源 `/song/url` 每次播放都会下发 `volume`，但缓存播放/本地文件不会走该
  /// 接口，因此把拿到过的值落盘，下次即使命中本地文件也能直接预置增益。
  static const _songLoudnessCacheKey = 'settings.song_loudness_cache';

  /// auto 模式增益上限，必须与原生 `AGC_AUTO_MAX_GAIN_DB` 保持一致。
  static const _agcAutoMaxGainDb = 15.0;

  /// auto 模式预置增益的下限（允许衰减），必须与原生 `AGC_PRESET_MIN_GAIN_DB`
  /// 保持一致。偏响的歌靠负增益压到目标响度，做到所有歌同一响度。
  static const _agcPresetMinGainDb = 24.0;

  /// 无整曲响度元数据时使用的先验电平（dBFS）。
  ///
  /// 来源：2026-11-20 对 music.api.hoilai.cn 在线曲库做 100 首随机抽样
  /// （中文歌为主），`volume` 中位数 -10.70、均值 -11.15、sd 2.67。
  /// 取中位数而非均值，避免少数极轻曲目（最小 -25.2）拉偏。
  /// 用于刚发行新歌（`volume=0` 哨兵值）、本地文件、缓存播放等场景，
  /// 让开场就有一个合理电平，且整曲不跳变。
  static const _fallbackSongVolumeDb = -10.7;

  // ---- 续十一：端上整曲实测 + 口径标定 ----
  /// 响度缓存里每个 hash 的来源（音源官方 / 端上实测）。
  /// 旧版本没有这张表：缺失一律视为 url，因为那时缓存只来自 `/song/url`。
  static const _songLoudnessSourceCacheKey = 'settings.song_loudness_sources';

  /// 标定样本：每首「有音源 volume + 端上整曲 RMS」的歌记一个差值。
  static const _loudnessCalibrationDeltasKey =
      'settings.loudness_calibration_deltas';
  static const _maxCalibrationSamples = 20;

  /// 有效采样门槛：非静音 400ms 样本数对应的秒数，且必须覆盖几乎整曲。
  /// MuMu 实证：18 秒片段的 `volume − 端上 rms` 在 +3.5 ~ +14 dB 之间乱跳
  /// （不同歌开场段落响度差异极大），比曲库先验 ±2.7 dB 还差，所以只接受
  /// 基本播完的结算值；曲长未知时一律不结算，宁可继续用先验。
  static const _trackRmsMinSeconds = 60.0;
  static const _trackRmsMinDurationRatio = 0.85;

  /// 音源对新歌是小时~天级异步批处理，同一首歌 30 分钟内不重复补拉。
  static const Duration _loudnessWarmRetryInterval = Duration(minutes: 30);

  static const _bluetoothLyricsEnabledSettingKey =
      'settings.bluetooth_lyrics_enabled';
  static const _keepScreenOnSettingKey = 'settings.keep_screen_on';
  static const _lyricBlurSettingKey = 'settings.lyric_blur_enabled';
  static const _playerGameEnabledSettingKey = 'settings.player_game_enabled';
  static const _queueKey = 'playback.queue';
  static const _currentSongKey = 'playback.current_song';
  static const _currentPositionKey = 'playback.current_position';
  static const _playbackModeKey = 'playback.mode';

  /// 播放状态快照（队列 + 当前歌曲 + 播放模式一次写入）。
  ///
  /// 旧实现把这几个字段拆成多次 `setString` 写入，进程在两次写入之间被杀
  /// 就会出现「当前歌曲已保存、队列没保存」的残缺状态：重启后只剩一首歌，
  /// 点「下一首」永远原地不动，必须重新进歌单点播才能恢复。
  static const _stateKey = 'playback.state_snapshot';
  static const _queueSaveDebounceMs = 500;
  static const _positionSaveInterval = Duration(seconds: 5);
  static const _listenTimeReportInterval = Duration(minutes: 5);
  static const _listenTimeCheckInterval = Duration(seconds: 15);
  static const _pendingListenTimeKey = 'settings.pending_listen_time_ms';
  static const _bluetoothLyricsTitleSyncSettingKey =
      'settings.bluetooth_lyrics_title_sync_enabled';
  static const _defaultEqualizerLevels = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
  static const equalizerPresets = [
    AudioEffectPreset(name: '平直', levels: _defaultEqualizerLevels),
    AudioEffectPreset(
      name: '流行',
      levels: [0, 250, 450, 350, 100, -100, 50, 300, 450, 500],
    ),
    AudioEffectPreset(
      name: '摇滚',
      levels: [500, 350, 150, -100, -250, -150, 150, 350, 550, 650],
    ),
    AudioEffectPreset(
      name: '人声',
      levels: [-250, -150, 0, 250, 500, 550, 350, 100, -100, -200],
    ),
    AudioEffectPreset(
      name: '低音',
      levels: [750, 650, 500, 250, 0, -100, -150, -200, -250, -300],
    ),
    AudioEffectPreset(
      name: '古典',
      levels: [350, 250, 100, 0, 150, 250, 300, 350, 250, 100],
    ),
    AudioEffectPreset(
      name: '电子',
      levels: [650, 450, 120, -120, -180, 100, 350, 550, 650, 700],
    ),
  ];

  /// 下载控制器（由 main.dart 在创建后注入，供 UI 访问下载功能）。
  DownloadController? downloadController;

  /// 缓存服务（由 main.dart 在创建后注入，用于歌词等缓存）。
  CacheService? cacheService;

  /// 本地音乐控制器（由 main.dart 在创建后注入，用于读取内嵌歌词等）。
  LocalMusicController? localMusic;

  PlayerController(
    this._api,
    this._audioHandler, {
    Future<AudioSession> Function()? audioSessionProvider,
  }) : _audioSessionProvider =
           audioSessionProvider ?? (() => AudioSession.instance) {
    final settingsRestore = _restoreSettings();
    unawaited(settingsRestore);
    unawaited(_superLyric.registerPublisher());
    _audioHandler.attachTransportControls(
      onNext: next,
      onPrevious: previous,
      onPause: _cancelAutomaticResume,
      onPlay: _handleManualPlay,
    );
    _desktopLyrics.setVisibilityChangedHandler(_handleDesktopLyricsVisibility);
    _positionSub = audioPlayer.positionStream.listen((value) {
      if (!_isSeeking) {
        // 缓冲期间时钟必须停走：否则 smoothPosition 会按墙钟虚增，
        // 磁带卷盘/进度条在卡顿时抢先走完，恢复后又回跳（与真实播放脱节）。
        _setPositionBase(value, playing: isPlaying && !isBuffering);
      }
      _maybeCompleteFromPosition(value);
      _maybeStopClimaxPreview(value);
      _maybeSyncDesktopLyricFromPosition();
      _syncSuperLyricFromPosition();
      _syncBluetoothLyricsFromPosition();
      // position 高频变化仅推送到 positionNotifier，避免全量广播触发整页 rebuild
    });
    // Send timing anchors; Android animates karaoke progress at display refresh.
    SchedulerBinding.instance.addPersistentFrameCallback((_) {
      if (_shouldShowDesktopLyrics &&
          isPlaying &&
          lyrics.isNotEmpty &&
          !_isScrubbing) {
        _syncDesktopKaraokeProgress();
      }
    });
    _durationSub = audioPlayer.durationStream.listen((value) {
      duration = value ?? Duration.zero;
      notifyListeners();
    });
    _stateSub = audioPlayer.playerStateStream.listen((value) {
      isPlaying = value.playing;
      isBuffering =
          value.processingState == ProcessingState.loading ||
          value.processingState == ProcessingState.buffering;
      if (!_isSeeking) {
        // 同上：缓冲时按暂停处理，时钟停走，位置锚定在真实播放位置。
        _setPositionBase(audioPlayer.position, playing: isPlaying && !isBuffering);
      }
      _syncListeningTimeTracker();
      _syncDesktopPlayState();
      _updateStallWatchdog();
      // 播放确认恢复健康（在播且不再缓冲）→ 清零自动恢复计数，给下一段
      // 卡顿重新计数的机会。
      if (isPlaying && !isBuffering && _recoveryAttempts != 0) {
        _recoveryAttempts = 0;
      }
      _syncBluetoothLyricsFromPosition();
      notifyListeners();
    });
    _processingStateSub = audioPlayer.processingStateStream.distinct().listen((
      state,
    ) {
      if (state == ProcessingState.completed) {
        if (!_isChangingSource) {
          unawaited(_handleCompleted());
        }
      }
    });
    _androidAudioSessionSub = audioPlayer.androidAudioSessionIdStream.listen((
      sessionId,
    ) {
      _androidAudioSessionId = sessionId;
      unawaited(_refreshEqualizerConfig());
      unawaited(_applyEqualizer());
      unawaited(_applyBassBoost());
      unawaited(_applyVolumeNormalization());
    });
    _playerErrorSub = audioPlayer.errorStream.listen((err) {
      // 播放期错误（多为网络中断 / 流地址过期）→ 走自动恢复，而非直接卡停。
      debugPrint(
        '[九九音乐][player] errorStream: code=${err.code} ${err.message}',
      );
      unawaited(_scheduleRecovery(reason: 'player-error'));
    });
    // 先恢复用户的打断设置，再配置 AudioSession，避免首次播放与
    // 异步会话配置竞态，导致点歌后停在暂停状态。
    _audioSessionReady = settingsRestore.then(
      (_) => _setupAudioSessionListeners(),
    );
    unawaited(_audioSessionReady);
  }

  final MusicApi _api;
  final MusicAudioHandler _audioHandler;
  final Future<AudioSession> Function() _audioSessionProvider;
  late final Future<void> _audioSessionReady;

  /// 持久化设置恢复完成信号。
  ///
  /// 用于“开机自启播放”等依赖持久化开关值的流程等待设置加载完成，
  /// 避免在 [_restoreSettings] 完成前把开关误读为默认值。
  final Completer<void> _settingsRestored = Completer<void>();

  /// 设置（含开机自启开关）从本地恢复完成的 Future。
  Future<void> get settingsRestored => _settingsRestored.future;
  final AudioEffectsService _audioEffects = AudioEffectsService();
  final DesktopLyricsService _desktopLyrics = DesktopLyricsService();
  final PlaybackHistoryService _historyService = PlaybackHistoryService();
  final PlaybackStatsService _statsService = PlaybackStatsService();
  final SuperLyricService _superLyric = SuperLyricService();
  final BluetoothLyricsService _bluetoothLyrics = BluetoothLyricsService();

  AudioPlayer get audioPlayer => _audioHandler.audioPlayer;

  MusicApi get api => _api;

  late final StreamSubscription<Duration> _positionSub;
  late final StreamSubscription<Duration?> _durationSub;
  late final StreamSubscription<PlayerState> _stateSub;
  late final StreamSubscription<ProcessingState> _processingStateSub;
  late final StreamSubscription<int?> _androidAudioSessionSub;
  StreamSubscription<AudioInterruptionEvent>? _interruptionSub;
  StreamSubscription<void>? _becomingNoisySub;
  StreamSubscription<Set<AudioDevice>>? _devicesSub;
  Set<AudioDevice>? _previousDevices;
  final Stopwatch _positionClock = Stopwatch();
  // 平滑位置的上一次取值：用于过滤位置流的小幅倒退（音频缓冲/时钟抖动），
  // 避免歌词高亮和卡拉OK进度出现回跳。seek/换歌的大跨度回退会重建基线。
  Duration _lastSmoothPosition = Duration.zero;
  final _shuffleCycle = ShuffleCycle<String>();

  /// 高潮试听结束时间（播放到该时间自动暂停）。
  Duration? _climaxEndTime;

  /// 当前歌曲的高潮片段时间（用于进度条标记），可能为 null。
  SongClimax? climax;
  Timer? _completionFallbackTimer;

  // —— 网络不稳定 / 播放停滞 运行时兜底 ——
  /// 缓冲看门狗超时：进入 loading/buffering 后若在该时长内仍未恢复播放，
  /// 触发自动「重取播放地址 →（仅智能音质开启时）降音质 → 续播」，多次仍
  /// 失败则跳到下一首，避免弱网/URL 过期把播放永久卡死。
  static const _stallWatchdogTimeout = Duration(seconds: 20);
  /// 单首歌曲「卡住/报错 → 自动恢复」的最大尝试次数，超过则跳下一首。
  static const _maxRecoveryAttempts = 2;
  Timer? _stallWatchdogTimer;
  Timer? _recoveryRetryTimer;
  StreamSubscription<PlayerException>? _playerErrorSub;
  bool _recovering = false;
  int _recoveryAttempts = 0;

  /// 网络中断等待恢复态：为 true 时不跳歌，按 [_networkPollInterval] 轮询，
  /// 网络恢复后自动续播中断前那一首的 [_savedResumePos] 进度。
  bool _waitingForNetwork = false;
  Duration? _savedResumePos;
  static const _networkPollInterval = Duration(seconds: 5);

  /// UI 可读：当前是否处于「网络中断，等待恢复」状态。
  bool get isWaitingForNetwork => _waitingForNetwork;

  Timer? _listenTimeTimer;
  Timer? _queueSaveTimer;
  Timer? _positionSaveTimer;
  Timer? _playCacheTimer;
  Timer? _bluetoothAnnounceTimer;
  DateTime? _listenTimeStartedAt;
  Duration _pendingListenTime = Duration.zero;

  /// 上一次写入本地听歌统计的累计时长（用于增量落盘）。
  Duration _lastStatsFlush = Duration.zero;
  bool _isReportingListenTime = false;
  int _seekSerial = 0;
  bool _isSeeking = false;
  bool _isScrubbing = false;
  bool _isHandlingCompletion = false;
  int _playRequestSerial = 0;

  /// 队列重建代次。
  ///
  /// 队列重建是「后台补全」，与播放请求（[prepareRestoredSong]、用户点歌）
  /// 是两条独立的链路，不能共用 [_playRequestSerial]：恢复预加载会自增
  /// 播放序号，若共用同一个序号，网络稍慢的重建就会被误判为过期而直接
  /// 放弃，导致 `QueueOrigin` 补全永远不生效。这里单独计数，只用于让
  /// 新的重建/用户换队列使旧重建失效。
  int _queueRebuildSerial = 0;
  Future<void> _sourceChangeTail = Future<void>.value();
  bool _isPersonalFmActive = false;
  bool _isLoadingPersonalFm = false;
  int _personalFmRequestSerial = 0;
  String? _completedSongHash;
  bool _isAppForeground = true;
  bool _desktopLyricsPreviewVisible = false;

  /// 用户是否在本次后台运行中主动点击悬浮窗“关闭”按钮临时关闭了桌面歌词。
  /// 当用户再次“点开软件”（应用回到前台）时，会自动重置为 false，
  /// 使得桌面歌词重新“自启动”，而不会因为临时关闭悬浮窗而永久关闭设置开关。
  bool _desktopLyricsUserDismissed = false;

  Song? currentSong;
  List<Song> queue = const [];

  /// 当前队列来自哪个歌单/专辑（用于重启后重建完整队列）。
  QueueOrigin? _queueOrigin;

  QueueOrigin? get queueOrigin => _queueOrigin;

  /// 从磁盘恢复出来、尚未在音频引擎上生效的播放进度。
  ///
  /// 恢复队列时音频源还没加载，此时 seek 无效；由 [prepareRestoredSong]
  /// 在音源加载完成后取走并真正跳转，跳完即置空。
  Duration? _resumePosition;

  /// 是否还有待应用的恢复进度（UI 可据此显示「已恢复」提示）。
  bool get hasPendingResumePosition => _resumePosition != null;

  List<LyricLine> lyrics = const [];
  PlaybackMode playbackMode = PlaybackMode.playlistLoop;
  Duration position = Duration.zero;

  /// 播放进度的高频通道。position 每 ~200ms 随音频 tick 变化，
  /// 若走 notifyListeners 会让所有订阅者全量 rebuild；改为独立
  /// ValueNotifier 后，只有进度条/时间等真正需要 position 的 UI 订阅它。
  final ValueNotifier<Duration> positionNotifier = ValueNotifier(Duration.zero);
  Duration duration = Duration.zero;
  bool isPlaying = false;
  bool isBuffering = false;
  bool isPreparing = false;
  /// 当前歌曲是否已自然播放完毕（processingState=completed）。
  /// 车机原生页据此把磁带卷盘/进度推到终点，保证"歌结束=带走到头"。
  bool get isCompleted =>
      audioPlayer.processingState == ProcessingState.completed;
  bool _isChangingSource = false;
  bool addListeningTimeEnabled = true;
  bool keepScreenOnEnabled = false;
  bool lyricBlurEnabled = false;
  bool playerGameEnabled = true;
  AudioQuality audioQuality = AudioQuality.standard;

  bool get isPersonalFmActive => _isPersonalFmActive;

  bool get isLoadingPersonalFm => _isLoadingPersonalFm;

  /// 是否开启音质智能切换（播放失败时自动降级重试）。
  bool smartQualityEnabled = false;
  bool autoPlayOnStartupEnabled = false;
  bool resumeLastPlaylistOnStartupEnabled = false;
  double playbackSpeed = 1.0;
  bool equalizerEnabled = false;
  List<int> equalizerLevels = List<int>.of(_defaultEqualizerLevels);
  String equalizerPresetName = '平直';
  EqualizerConfig equalizerConfig = EqualizerConfig.fallback(
    _defaultEqualizerLevels,
  );
  bool bassBoostEnabled = false;
  double bassBoostStrength = 0.45;
  bool audioInterruptionEnabled = true;
  bool autoResumeAfterInterruption = true;
  bool autoPlayOnDeviceConnected = false;

  /// 蓝牙/耳机断开（becomingNoisy）时是否立即停止播放，防止误外放。
  ///
  /// 默认 true（断开即停止，不再自动恢复）。关闭后回到旧行为：
  /// 断开时依据 [autoResumeAfterInterruption] 决定是否恢复。
  bool autoPauseOnDeviceDisconnect = true;
  bool volumeNormalizationEnabled = false;

  /// 音量均衡的静态增益补偿（dB），下发到 DynamicsProcessing 的 MbcBand.postGain。
  /// 默认 +3dB：保持 2026-11-19 恢复增益补偿后的听感，不回退到上游的 0dB。
  /// 仅在 [volumeGainMode] == fixed 时作为固定值生效；auto 模式下作为反馈环起点。
  double volumeGainDb = 3.0;

  /// 可选增益档位。越高越容易触发 limiter，可能重现上游 #153 的削顶/破音。
  static const volumeGainOptions = <double>[0.0, 3.0, 6.0, 9.0, 12.0];

  /// 增益策略：fixed=固定档位；auto=按每首歌实际响度自动调（默认）。
  /// auto 需要 RECORD_AUDIO 权限；拒绝时自动回退 fixed 并[notifyListeners]。
  VolumeGainMode volumeGainMode = VolumeGainMode.auto;

  /// 自动增益的目标响度档位。默认标准（-12dBFS，续九整体 +4 dB 后）。
  AutoLoudnessLevel autoLoudnessLevel = AutoLoudnessLevel.standard;

  /// 实时 AGC 的最新快照，供设置页展示；auto 模式且原生支持时非空。
  AgcStatus? agcStatus;

  /// 整曲响度缓存（song.hash → volume dBFS）。
  /// 来源有两类：音源 `/song/url` 官方实测、端上 Visualizer 整曲累计（续十一）。
  final Map<String, double> _songLoudnessCache = <String, double>{};

  /// 每个缓存值的来源，与 [_songLoudnessCache] 同键；缺失按 url 处理（旧数据兼容）。
  final Map<String, SongLoudnessSource> _songLoudnessSourceCache =
      <String, SongLoudnessSource>{};

  /// 口径标定样本（`volume` − 端上整曲 RMS），取滑动中位数得到全局偏置。
  /// 音源统计口径未公开且可能调整，用自适应标定比写死常量可靠。
  final List<double> _calibrationDeltas = <double>[];

  /// 补拉响度元数据的退避时间戳（hash → 上次请求时间），仅内存、不持久化。
  final Map<String, DateTime> _loudnessWarmLastAttempt = <String, DateTime>{};

  /// 最近一次切歌结算出来的整曲 RMS（供设置页诊断展示）。
  double? lastTrackRmsDb;
  int lastTrackRmsSamples = 0;

  /// 当前曲的整曲响度（来自音源或缓存）；为空表示无元数据、用曲库先验。
  double? _currentSongVolumeDb;

  /// 当前曲的峰值电平 `volume_peak`（仅本次 `/song/url` 实测时有值）。
  /// 只用于设置页元数据展示，不参与增益计算（基准未公开）。
  double? _currentSongVolumePeakDb;

  /// 当前曲整曲响度的来源，供设置页如实标注「实测 / 缓存 / 先验」。
  SongLoudnessSource _currentSongLoudnessSource =
      SongLoudnessSource.fallback;

  /// 麦克风权限缓存：进程内已授予就不再重复 platform channel 往返，
  /// 避免每次切到 auto 都 await 权限查询造成菜单卡顿。被拒存 false，下次仍会重试申请。
  bool _micPermissionGranted = false;

  /// 正在进行的录音权限申请。冷启动恢复设置与首次 audioSession 上报可能几乎
  /// 同时触发 _applyVolumeNormalization，合并成一次系统弹窗，避免并发 request。
  Future<bool>? _recordAudioPermissionRequest;

  bool bluetoothLyricsEnabled = false;
  // 普通蓝牙车机只读取 AVRCP 标题，不会读取手机上的歌词广播或 extras。
  bool bluetoothLyricsTitleSyncEnabled = true;
  bool desktopLyricsEnabled = false;
  DesktopLyricsSettings desktopLyricsSettings = const DesktopLyricsSettings();
  Timer? _autoResumeTimer;

  /// 进入打断/断连事件时播放器是否正在播放。
  ///
  /// 用于打断结束后只恢复「打断前本来就在播」的场景，避免把用户主动暂停
  /// 误判成被打断、从而在电话等操作结束后自动恢复播放。
  bool _wasPlayingAtInterruption = false;
  bool _isAudioInterrupted = false;
  bool _interruptionRequiresPause = false;
  bool _resumeBlockedByDisconnect = false;
  int _autoResumeSerial = 0;
  int _playbackIntentSerial = 0;
  int? _pendingPlaybackIntent;
  double? _volumeBeforeDuck;
  double? _duckedVolume;
  Duration? sleepTimerRemaining;
  Timer? _sleepTimer;
  DateTime? _sleepTimerEnd;
  bool _sleepFinishCurrentSong = false;
  bool _sleepFinishCurrentSongOption = false;
  String? errorMessage;
  int seekRevision = 0;
  int? _androidAudioSessionId;
  bool get isScrubbing => _isScrubbing;
  bool get isAudioEffectsSupported => _audioEffects.isAudioEffectsSupported;
  bool get isBassBoostSupported => _audioEffects.isBassBoostSupported;
  String get audioEffectsLabel {
    if (!isAudioEffectsSupported) {
      return '当前平台暂不支持';
    }
    if (equalizerEnabled) {
      return '均衡器：$equalizerPresetName';
    }
    if (bassBoostEnabled) {
      return 'Bass ${(bassBoostStrength * 100).round()}%';
    }
    return '关闭';
  }

  String get playbackSpeedLabel {
    if (playbackSpeed == playbackSpeed.roundToDouble()) {
      return '${playbackSpeed.round()}x';
    }
    return '${playbackSpeed}x';
  }

  Duration get smoothPosition {
    final raw = _isScrubbing
        ? position
        : (!isPlaying ? position : position + _positionClock.elapsed);
    var value = raw;
    if (value < Duration.zero) {
      value = Duration.zero;
    } else if (duration > Duration.zero && value > duration) {
      value = duration;
    }
    if (_isScrubbing) {
      // 拖动进度条时位置必须严格跟随手指。
      _lastSmoothPosition = value;
      return value;
    }
    if (value < _lastSmoothPosition) {
      if (_lastSmoothPosition - value > const Duration(milliseconds: 250)) {
        // 明显回退：视为 seek 或切歌，直接重建基线。
        _lastSmoothPosition = value;
      }
    } else {
      _lastSmoothPosition = value;
    }
    return _lastSmoothPosition;
  }

  int get currentIndex {
    final song = currentSong;
    if (song == null) {
      return -1;
    }
    final identicalIndex = queue.indexWhere((item) => identical(item, song));
    if (identicalIndex >= 0) {
      return identicalIndex;
    }
    final identity = _songIdentity(song);
    if (identity.isNotEmpty) {
      final idIndex = queue.indexWhere(
        (item) => _songIdentity(item) == identity,
      );
      if (idIndex >= 0) {
        return idIndex;
      }
    }
    if (song.hash.isNotEmpty) {
      final hashIndex = queue.indexWhere((item) => item.hash == song.hash);
      if (hashIndex >= 0) {
        return hashIndex;
      }
    }
    if (song.id.isNotEmpty) {
      final idIndex = queue.indexWhere((item) => item.id == song.id);
      if (idIndex >= 0) {
        return idIndex;
      }
    }
    return -1;
  }

  int get activeLyricIndex {
    if (lyrics.isEmpty) {
      return -1;
    }
    var index = 0;
    for (var i = 0; i < lyrics.length; i++) {
      if (smoothPosition >= lyrics[i].time) {
        index = i;
      } else {
        break;
      }
    }
    return index;
  }

  String get playbackModeLabel {
    return switch (playbackMode) {
      PlaybackMode.playlistLoop => '歌单循环',
      PlaybackMode.shuffle => '随机播放',
      PlaybackMode.singleLoop => '单曲循环',
    };
  }

  PlaybackMode cyclePlaybackMode() {
    _shuffleCycle.clear();
    playbackMode = switch (playbackMode) {
      PlaybackMode.playlistLoop => PlaybackMode.shuffle,
      PlaybackMode.shuffle => PlaybackMode.singleLoop,
      PlaybackMode.singleLoop => PlaybackMode.playlistLoop,
    };
    if (playbackMode == PlaybackMode.shuffle && queue.isNotEmpty) {
      final currentIdentity = currentSong == null
          ? null
          : _songIdentity(currentSong!);
      _shuffleCycle.reset(
        queue.map(_songIdentity).where((identity) => identity.isNotEmpty),
        current: currentIdentity,
      );
    }
    _saveQueueState();
    notifyListeners();
    return playbackMode;
  }

  Future<void> setAddListeningTimeEnabled(bool enabled) async {
    if (addListeningTimeEnabled == enabled) {
      return;
    }
    addListeningTimeEnabled = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_listenTimeSettingKey, enabled);
    if (!enabled) {
      _resetListeningTimeTracker();
    } else {
      _syncListeningTimeTracker();
    }
    notifyListeners();
  }

  Future<void> setKeepScreenOnEnabled(bool enabled) async {
    if (keepScreenOnEnabled == enabled) {
      return;
    }
    keepScreenOnEnabled = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_keepScreenOnSettingKey, enabled);
    notifyListeners();
  }

  Future<void> setLyricBlurEnabled(bool enabled) async {
    if (lyricBlurEnabled == enabled) {
      return;
    }
    lyricBlurEnabled = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_lyricBlurSettingKey, enabled);
    notifyListeners();
  }

  Future<void> setPlayerGameEnabled(bool enabled) async {
    if (playerGameEnabled == enabled) {
      return;
    }
    playerGameEnabled = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_playerGameEnabledSettingKey, enabled);
    notifyListeners();
  }

  Future<void> playSong(
    Song song, {
    List<Song>? queue,
    bool preservePersonalFmSession = false,
    QueueOrigin? origin,
    bool userInitiated = true,
  }) async {
    if (userInitiated) _handleManualPlay();
    final intentSerial = _playbackIntentSerial;
    _pendingPlaybackIntent = intentSerial;
    final requestSerial = ++_playRequestSerial;
    // 续十一：切歌前结算上一曲的端上整曲 RMS（此时 currentSong 仍是旧歌）。
    await _settlePreviousTrackRms();
    debugPrint(
      '[九九音乐][playback] request=$requestSerial begin '
      'incomingQueue=${queue?.length ?? 0}',
    );
    final isPlayingWithinCurrentQueue =
        _isPersonalFmActive &&
        (queue == null ||
            identical(queue, this.queue) ||
            _isSameQueue(queue, this.queue));
    final shouldPreserveFm =
        preservePersonalFmSession || isPlayingWithinCurrentQueue;
    if (!shouldPreserveFm) {
      _isPersonalFmActive = false;
      _isLoadingPersonalFm = false;
      _personalFmRequestSerial++;
    }
    _climaxEndTime = null;
    climax = null;
    _completionFallbackTimer?.cancel();
    _resetRecovery();
    _playCacheTimer?.cancel();
    unawaited(downloadController?.cancelActivePlayCache());
    _completedSongHash = null;
    isPreparing = true;
    _isChangingSource = true;
    errorMessage = null;
    final replacesQueue =
        queue != null && queue.isNotEmpty && !_isSameQueue(queue, this.queue);
    final previousSong = currentSong;
    final wasPlaying =
        isPlaying &&
        audioPlayer.processingState != ProcessingState.completed &&
        !_isHandlingCompletion;
    if (previousSong != null && previousSong != song) {
      if (wasPlaying) {
        // 用户手动切歌且旧音频正在播放时，仅静音旧音频，
        // 避免网络解析加载期间继续外放，同时保持 Audio Focus 与 WakeLock 不中断；
        // 绝不能在后台切歌或自然播完切歌时调用 pause()，否则会导致系统释放 WakeLock 并撤销 Audio Focus，
        // 造成黑屏切歌卡住不播、必须点亮屏幕才恢复播放。
        unawaited(audioPlayer.setVolume(0.0));
      }
    }
    currentSong = song;
    // 用户主动点歌/切歌后，磁盘上待恢复的进度不再适用
    _resumePosition = null;
    if (queue != null && queue.isNotEmpty) {
      this.queue = queue;
      // 只有换了一整套队列时才更新来源；在队列内部切歌不能把来源丢掉
      if (replacesQueue) {
        _queueOrigin = origin;
      } else if (origin != null) {
        _queueOrigin = origin;
      }
    } else if (this.queue.isEmpty) {
      this.queue = [song];
      _queueOrigin = origin;
    }
    final songIdentity = _songIdentity(song);
    if (playbackMode == PlaybackMode.shuffle && replacesQueue) {
      _shuffleCycle.reset(
        this.queue.map(_songIdentity).where((identity) => identity.isNotEmpty),
        current: songIdentity,
      );
    } else if (playbackMode == PlaybackMode.shuffle &&
        songIdentity.isNotEmpty) {
      _shuffleCycle.markPlayed(songIdentity);
    }
    lyrics = const [];
    _lastDesktopLyricIndex = -1;
    _lastSuperLyricIndex = -1;
    _lastSuperLyricPlaying = true;
    _lastBluetoothLyricIndex = -1;
    // 新歌曲尚未起播，首个位置 tick 应能重新发布真实播放状态。
    _lastBluetoothPlaying = false;
    _saveQueueState();
    _startPositionSaving();
    notifyListeners();
    // 预缓存封面图，避免打开播放页时出现纯色背景闪烁
    _precacheCover(song);
    unawaited(_syncDesktopLyricsVisibility());
    unawaited(_loadClimax(song));

    try {
      String url;
      String? networkUrl;
      PlayUrl? resolvedPlayUrl;
      debugPrint(
        '[九九音乐][playback] request=$requestSerial local-lookup begin',
      );
      final local = await downloadController?.localPathFor(song, audioQuality);
      if (requestSerial != _playRequestSerial) return;
      debugPrint(
        '[九九音乐][playback] request=$requestSerial local-lookup complete '
        'found=${local != null}',
      );
      if (local != null) {
        url = local;
        // 已下载/已缓存：顺手补一次整曲响度，下次播放即可直接预置。
        unawaited(_warmLoudnessMetadata(song));
      } else if (song.source == SongSource.local) {
        url = song.id;
      } else {
        debugPrint(
          '[九九音乐][playback] request=$requestSerial url-resolve begin',
        );
        final playUrl = await _resolvePlayUrl(song);
        resolvedPlayUrl = playUrl;
        if (requestSerial != _playRequestSerial) return;
        debugPrint(
          '[九九音乐][playback] request=$requestSerial url-resolve complete '
          'empty=${playUrl.url.isEmpty}',
        );
        if (playUrl.url.isEmpty) {
          throw Exception(
            song.isCloudDrive
                ? '云盘歌曲暂时没有可播放地址'
                : song.source == SongSource.netease
                ? '网易云歌曲暂时没有可播放地址'
                : '这首歌暂时没有可播放地址',
          );
        }
        url = playUrl.url;
        networkUrl = playUrl.url;
      }
      if (requestSerial != _playRequestSerial) return;

      // setUrl/setAudioSource 不能并发执行。快速点击“下一曲”时，让已经开始的
      // 音源切换先安全结束，再由最后一次请求接管，避免旧请求反向覆盖新歌曲。
      debugPrint(
        '[九九音乐][playback] request=$requestSerial source-queue enter',
      );
      await _serializeSourceChange(() async {
        if (requestSerial != _playRequestSerial) return;
        debugPrint(
          '[九九音乐][playback] request=$requestSerial source-load begin',
        );
        await _audioHandler.loadSong(
          song: song,
          url: url,
          queueSongs: this.queue,
          queueIndex: currentIndex,
        );
        if (requestSerial != _playRequestSerial) return;
        debugPrint(
          '[九九音乐][playback] request=$requestSerial source-loaded '
          'local=${networkUrl == null}',
        );

        isPreparing = false;
        notifyListeners();
        unawaited(loadLyrics(song));
        if (wasPlaying) {
          unawaited(audioPlayer.setVolume(1.0));
        }
        // 切歌归位 + 预置增益：新歌真正出声前确定增益并写进 postGain。
        // 有元数据按音源整曲响度（`volume`）算、无元数据按曲库均值先验算，
        // 两种情况都直接锁定，整曲不变。续十二已删除开场 4.8 秒测定路径。
        // 必须 await 完成再 play，避免上一首的高增益被带过来造成开头爆音。
        _captureSongLoudnessMeta(song, resolvedPlayUrl);
        // 音源未给实测值时后台等它补齐（新歌批处理常要数小时~一天），
        // 补上后自动升级为官方值；内部有 30 分钟退避，不会每次切歌都打接口。
        unawaited(_warmLoudnessMetadata(song));
        await _applyPresetGainForCurrentSong();
        await _startPlayback(expectedIntent: intentSerial);
        if (requestSerial != _playRequestSerial) return;
        unawaited(_applyVolumeNormalization());
        unawaited(_applyEqualizer());
        unawaited(_applyBassBoost());
        debugPrint(
          '[九九音乐][playback] request=$requestSerial started '
          'playing=${audioPlayer.playing} '
          'state=${audioPlayer.processingState.name}',
        );

        // 记录播放历史与本地播放统计（后台执行，不阻塞播放）
        unawaited(_historyService.record(song));
        unawaited(_statsService.recordPlay(song));
        // 稳定播放 15 秒后再后台缓存（避免快速切歌重复消耗大量流量）
        if (networkUrl != null) {
          _schedulePlayCache(song, audioQuality, networkUrl);
        }
      });
    } catch (error) {
      if (requestSerial != _playRequestSerial) return;
      debugPrint('[九九音乐][playback] request=$requestSerial failed: $error');
      errorMessage = error.toString();
      unawaited(_audioHandler.pause());
      if (wasPlaying) {
        unawaited(audioPlayer.setVolume(1.0));
      }
      isPreparing = false;
      notifyListeners();
    } finally {
      if (requestSerial == _playRequestSerial) {
        _pendingPlaybackIntent = null;
        _isChangingSource = false;
        if (isPreparing) {
          isPreparing = false;
          notifyListeners();
        }
      }
    }
  }

  Future<void> _serializeSourceChange(Future<void> Function() operation) async {
    final previous = _sourceChangeTail;
    final turnCompleted = Completer<void>();
    _sourceChangeTail = turnCompleted.future;
    try {
      await previous;
      await operation();
    } finally {
      turnCompleted.complete();
    }
  }

  Future<void> _startPlayback({
    required int expectedIntent,
    bool allowDuringInterruption = false,
  }) async {
    await _audioSessionReady;
    // A disconnect may arrive while a song URL or source is still loading.
    // Only a later explicit play request may release this block.
    if (expectedIntent != _playbackIntentSerial ||
        _resumeBlockedByDisconnect ||
        (_interruptionRequiresPause &&
            audioInterruptionEnabled &&
            !allowDuringInterruption)) {
      debugPrint(
        '[九九音乐][playback] start blocked: intent=$expectedIntent/'
        '$_playbackIntentSerial disconnect=$_resumeBlockedByDisconnect '
        'interruption=$_interruptionRequiresPause '
        'allowDuringInterruption=$allowDuringInterruption',
      );
      return;
    }
    // User intent is recorded at the public entry point, before any network or
    // source loading. Completing that work must never grant fresh permission.
    await _audioHandler.play(userInitiated: false);
    // 每次（重新）起播都刷新一次当前歌词元数据。车机重新连接、
    // 应用重启恢复播放时，旧的 MediaSession/广播状态可能已失效。
    _announceBluetoothLyrics();
  }

  /// 启动固定为“红心 Radio + 根据口味”的私人 FM 会话。
  Future<bool> startPersonalFm({List<Song>? initialSongs}) async {
    if (_isLoadingPersonalFm) {
      return false;
    }
    _handleManualPlay();
    final intentSerial = _playbackIntentSerial;
    _pendingPlaybackIntent = intentSerial;

    _isLoadingPersonalFm = true;
    final requestSerial = ++_personalFmRequestSerial;
    errorMessage = null;
    notifyListeners();
    try {
      final songs = initialSongs?.isNotEmpty == true
          ? List<Song>.of(initialSongs!)
          : await _api.personalFm();
      if (requestSerial != _personalFmRequestSerial ||
          intentSerial != _playbackIntentSerial) {
        return false;
      }
      if (songs.isEmpty) {
        errorMessage = '暂时没有新的私人 FM 推荐';
        return false;
      }

      _isPersonalFmActive = true;
      await playSong(
        songs.first,
        queue: songs,
        preservePersonalFmSession: true,
        userInitiated: false,
      );
      return errorMessage == null;
    } catch (error) {
      if (requestSerial == _personalFmRequestSerial) {
        errorMessage = error.toString();
      }
      return false;
    } finally {
      if (requestSerial == _personalFmRequestSerial) {
        if (_pendingPlaybackIntent == intentSerial) {
          _pendingPlaybackIntent = null;
        }
        _isLoadingPersonalFm = false;
        notifyListeners();
      }
    }
  }

  /// 预缓存歌曲封面到 Flutter ImageCache，打开播放页时可立即显示。
  ///
  /// 地址按显示尺寸规格化：网易云封面原图 1~2MB，取 `?param=480y480` 只有
  /// 300KB 左右（见 [CoverService.sized]）。歌曲完全没有封面时再按来源补取。
  void _precacheCover(Song song) {
    final coverUrl = CoverService.sized(song.coverUrl, 240);
    if (coverUrl != null && coverUrl.isNotEmpty) {
      _resolveCoverProvider(coverUrl);
      return;
    }
    unawaited(
      CoverService.instance.resolve(song).then((url) {
        if (url == null || url.isEmpty) return;
        _resolveCoverProvider(CoverService.sized(url, 240) ?? url);
      }),
    );
  }

  /// 按来源选择合适的 ImageProvider 预热。
  ///
  /// 网易云必须走 [NeteaseImageProvider]：它在 HttpClient 层用 `set` 覆盖
  /// User-Agent；用 `NetworkImage(headers:)` 会变成两个 User-Agent 而被 403。
  void _resolveCoverProvider(String url) {
    if (url.startsWith('content://')) return; // 本地封面走原生加载，不预缓存
    final ImageProvider provider = CoverService.isNeteaseImage(url)
        ? NeteaseImageProvider(url)
        : NetworkImage(url);
    provider.resolve(ImageConfiguration.empty);
  }

  /// 解析播放地址。
  ///
  /// - 云盘歌曲走 [MusicApi.cloudSongUrl]
  /// - 网易云歌曲使用外链地址
  /// - 其它歌曲走 [MusicApi.songUrl]，开启智能音质时在网络请求失败
  ///   或返回空地址时自动降级重试（lossless -> high -> standard）。
  ///
  /// 顺带把音源下发的整曲响度（`volume`）写进缓存：预加载阶段就能拿到，
  /// 之后即便命中本地缓存文件、不再请求 `/song/url`，也能直接预置增益。
  Future<PlayUrl> _resolvePlayUrl(Song song) async {
    final playUrl = await _resolvePlayUrlInner(song);
    // 本次已经向音源要过地址（无论 volume 是否补齐），30 分钟内不再重复补拉。
    _loudnessWarmLastAttempt[song.hash] = DateTime.now();
    final volume = validLoudnessDb(playUrl.volumeDb);
    if (volume != null) {
      _rememberSongLoudness(song.hash, volume, SongLoudnessSource.url);
    }
    debugPrint(
      '[九九音乐][playback] loudness hash=${song.hash} '
      'source=${_playUrlLoudnessSource(song, playUrl)} '
      'cached=${_songLoudnessCache[song.hash]}',
    );
    return playUrl;
  }

  /// 仅用于日志：本次响度来自音源还是历史缓存。
  String _playUrlLoudnessSource(Song song, PlayUrl playUrl) {
    final fresh = playUrl.volumeDb;
    if (fresh != null) return 'url:${fresh.toStringAsFixed(1)}';
    final cached = _songLoudnessCache[song.hash];
    return cached == null ? 'none' : 'cache:${cached.toStringAsFixed(1)}';
  }

  Future<PlayUrl> _resolvePlayUrlInner(Song song) async {
    if (song.source == SongSource.local) {
      return PlayUrl(url: song.id, hash: song.hash);
    }
    if (song.isCloudDrive) {
      return _api.cloudSongUrl(song);
    }
    if (song.source == SongSource.netease) {
      // 网易云歌曲使用外链播放地址
      return PlayUrl(
        url: 'https://music.163.com/song/media/outer/url?id=${song.id}.mp3',
        hash: song.hash,
      );
    }

    try {
      final playUrl = await _api.songUrl(
        song,
        quality: audioQuality,
        hashOnly: _isPersonalFmActive,
      );
      if (playUrl.url.isNotEmpty || !smartQualityEnabled) {
        return playUrl;
      }
      // 返回空地址：按智能音质策略降级重试
      final fallback = _nextLowerQuality(audioQuality);
      if (fallback == null) return playUrl;
      return await _api.songUrl(
        song,
        quality: fallback,
        hashOnly: _isPersonalFmActive,
      );
    } catch (error) {
      if (!smartQualityEnabled) rethrow;
      // 网络请求失败：尝试降级重试
      final fallback = _nextLowerQuality(audioQuality);
      if (fallback == null) rethrow;
      try {
        final retryUrl = await _api.songUrl(
          song,
          quality: fallback,
          hashOnly: _isPersonalFmActive,
        );
        if (retryUrl.url.isNotEmpty) {
          debugPrint(
            '[九九音乐][smart-quality] ${audioQuality.badge} 失败，'
            '已降级为 ${fallback.badge}',
          );
          return retryUrl;
        }
      } catch (_) {
        // 降级也失败，抛出原始错误
      }
      rethrow;
    }
  }

  /// 返回更低一档的音质；已是最低档时返回 null。
  AudioQuality? _nextLowerQuality(AudioQuality quality) {
    switch (quality) {
      case AudioQuality.lossless:
        return AudioQuality.high;
      case AudioQuality.high:
        return AudioQuality.standard;
      case AudioQuality.standard:
        return null;
    }
  }

  // ================= 网络不稳定 / 播放停滞 自动恢复 =================

  /// 根据当前缓冲状态挂起/撤销「卡顿看门狗」。
  ///
  /// 仅当【有当前歌曲】且【正处于 loading/buffering】且【不在恢复过程中】时
  /// 才计时；一旦超过 [_stallWatchdogTimeout] 仍未恢复播放，就触发
  /// [_scheduleRecovery]。正常切歌/主动暂停/已 ready 都会撤销计时。
  void _updateStallWatchdog() {
    final shouldWatch =
        isBuffering && currentSong != null && !_recovering && !_waitingForNetwork;
    if (shouldWatch) {
      if (_stallWatchdogTimer?.isActive != true) {
        _stallWatchdogTimer = Timer(_stallWatchdogTimeout, () {
          unawaited(_scheduleRecovery(reason: 'buffering-timeout'));
        });
      }
    } else {
      _stallWatchdogTimer?.cancel();
    }
  }

  /// 播放卡住 / 报错时的自动恢复。
  ///
  /// 关键区分：
  /// - **网络中断**（重取播放地址时抛连接类异常）→ 进入「等待网络恢复」态，
  ///   不跳歌、不消耗跳歌预算，按 [_networkPollInterval] 轮询，网络回来后自动
  ///   续播中断前那一首的原进度。
  /// - **音源本身不可用**（网络正常但该曲取不到地址 / 反复卡顿）→ 计数重试，
  ///   达上限则跳到下一首。
  ///
  /// 注意：音质降级只在「智能音质」开启时发生——恢复内部复用
  /// [_resolvePlayUrl]，而后者仅在 [smartQualityEnabled] 为 true 时才降档。
  Future<void> _scheduleRecovery({required String reason}) async {
    if (_recovering) return;
    final song = currentSong;
    if (song == null) return;

    final wasWaiting = _waitingForNetwork;
    // 等待网络恢复期间沿用中断前的进度；正常卡顿用当前进度。
    final resumePos = wasWaiting ? (_savedResumePos ?? smoothPosition) : smoothPosition;

    // 网络轮询不计入跳歌预算；真实卡顿/报错才计数并在超上限时跳歌。
    if (!wasWaiting) {
      _recoveryAttempts++;
      if (_recoveryAttempts > _maxRecoveryAttempts) {
        debugPrint('[九九音乐][recover] 恢复尝试超上限，跳到下一首');
        _resetRecovery();
        unawaited(next());
        return;
      }
    }

    _recovering = true;
    _stallWatchdogTimer?.cancel();
    debugPrint(
      '[九九音乐][recover] 尝试恢复 原因=$reason 计数=$_recoveryAttempts '
      '等待网络=$_waitingForNetwork smart=$smartQualityEnabled',
    );
    try {
      await _recoverReload(song, resumePos);
      if (wasWaiting) {
        // 网络恢复，成功续播同一首。
        _waitingForNetwork = false;
        _savedResumePos = null;
        errorMessage = null;
        notifyListeners();
      }
      // 成功重加载后不立即清零计数：等 _stateSub 确认「在播且不缓冲」再清零，
      // 避免「能取地址但流媒体反复卡顿」时无限循环不跳歌。
    } catch (error) {
      if (_isNetworkError(error)) {
        _enterWaitingForNetwork(resumePos);
      } else {
        // 非网络错误：安排下一次尝试；若已超上限，下次进入时顶部守卫会跳歌。
        _recoveryRetryTimer?.cancel();
        _recoveryRetryTimer = Timer(const Duration(seconds: 2), () {
          unawaited(_scheduleRecovery(reason: '$reason/retry'));
        });
      }
    } finally {
      _recovering = false;
    }
  }

  /// 进入「等待网络恢复」态：记录中断前进度、清零跳歌计数、按固定间隔轮询。
  void _enterWaitingForNetwork(Duration resumePos) {
    _waitingForNetwork = true;
    _savedResumePos = resumePos;
    _recoveryAttempts = 0; // 网络等待不占用跳歌预算
    errorMessage = '网络已中断，等待恢复后将自动继续播放';
    notifyListeners();
    debugPrint('[九九音乐][recover] 检测到网络中断，进入等待网络恢复状态');
    _recoveryRetryTimer?.cancel();
    _recoveryRetryTimer = Timer(_networkPollInterval, () {
      unawaited(_scheduleRecovery(reason: 'network-poll'));
    });
  }

  /// 判定一次异常是否为「网络不可用」类（连接失败 / DNS 解析失败 / 超时）。
  ///
  /// 恢复流程第一步总会重新请求 API 取播放地址，因此网络断时会先在这里抛出，
  /// 是可靠的断网信号（本地缓存 / 网易外链等不走 API 的源除外）。
  bool _isNetworkError(Object e) {
    if (e is TimeoutException) return true;
    if (e is SocketException) return true;
    if (e is HandshakeException) return true;
    final s = e.toString();
    return s.contains('ClientException') ||
        s.contains('SocketException') ||
        s.contains('Failed host lookup') ||
        s.contains('Unable to resolve host') ||
        s.contains('ENETUNREACH') ||
        s.contains('EHOSTUNREACH') ||
        s.contains('Network is unreachable') ||
        s.contains('connection to server') ||
        s.contains('请求超时');
  }

  /// 以当前进度重新加载正在播放的歌曲（重取地址，处理 URL 过期 / 网络中断）。
  Future<void> _recoverReload(Song song, Duration resumePos) async {
    _isChangingSource = true; // 抑制恢复过程中的 completed 误判
    isPreparing = true;
    errorMessage = null;
    notifyListeners();
    try {
      String url;
      String? networkUrl;
      final local = await downloadController?.localPathFor(song, audioQuality);
      if (local != null) {
        url = local;
      } else if (song.source == SongSource.local) {
        url = song.id;
      } else {
        final playUrl = await _resolvePlayUrl(song);
        if (playUrl.url.isEmpty) {
          throw Exception('这首歌暂时没有可播放地址');
        }
        url = playUrl.url;
        networkUrl = playUrl.url;
      }
      await _audioHandler.loadSong(
        song: song,
        url: url,
        queueSongs: queue,
        queueIndex: currentIndex,
      );
      if (resumePos > Duration.zero) {
        await _audioHandler.seek(_clampPosition(resumePos));
      }
      await _audioHandler.play();
      if (networkUrl != null) {
        unawaited(
          downloadController?.cacheForPlayback(song, audioQuality, networkUrl),
        );
      }
    } finally {
      isPreparing = false;
      _isChangingSource = false;
      notifyListeners();
    }
  }

  /// 复位卡顿恢复状态（换歌 / 跳歌 / 恢复成功确认健康后调用）。
  void _resetRecovery() {
    _stallWatchdogTimer?.cancel();
    _stallWatchdogTimer = null;
    _recoveryRetryTimer?.cancel();
    _recoveryRetryTimer = null;
    _recoveryAttempts = 0;
    _waitingForNetwork = false;
    _savedResumePos = null;
  }

  Future<bool> addToQueue(Song song) async {
    final songKey = song.hash.isNotEmpty ? song.hash : song.id;
    final currentSongKey = currentSong == null
        ? ''
        : (currentSong!.hash.isNotEmpty ? currentSong!.hash : currentSong!.id);
    if (songKey.isNotEmpty && songKey == currentSongKey) {
      return false;
    }

    final nextQueue = List<Song>.of(queue);
    final existingIndex = nextQueue.indexWhere((item) {
      final itemKey = item.hash.isNotEmpty ? item.hash : item.id;
      return itemKey.isNotEmpty && itemKey == songKey;
    });
    if (existingIndex >= 0) {
      nextQueue.removeAt(existingIndex);
    }

    if (nextQueue.isEmpty) {
      nextQueue.add(song);
    } else {
      final index = currentIndex;
      final insertIndex = index < 0
          ? 0
          : (index + 1).clamp(0, nextQueue.length);
      nextQueue.insert(insertIndex, song);
    }

    queue = nextQueue;
    await _audioHandler.setSongQueue(
      queueSongs: queue,
      queueIndex: currentIndex,
      currentSong: currentSong,
    );
    _saveQueueState();
    notifyListeners();
    return true;
  }

  /// 车机全屏「下一首播放」：把当前歌曲的副本插入到 currentIndex+1，
  /// 绕开 [addToQueue] 的"与当前曲相同即忽略"guard 与去重移除逻辑。
  Future<bool> playCurrentSongNext() async {
    final cur = currentSong;
    final index = currentIndex;
    if (cur == null || index < 0 || index >= queue.length) {
      return false;
    }
    final nextQueue = List<Song>.of(queue);
    nextQueue.insert(index + 1, cur);
    queue = nextQueue;
    await _audioHandler.setSongQueue(
      queueSongs: queue,
      queueIndex: currentIndex,
      currentSong: currentSong,
    );
    _saveQueueState();
    notifyListeners();
    return true;
  }

  /// 当当前队列仍是 [expectedQueue] 时，用 [newQueue] 补齐播放队列。
  ///
  /// 歌单页会先用已经展示的歌曲立即开始播放，再在后台拉取完整歌单。
  /// 只有用户期间没有切换到其他队列时才应用刷新结果，避免后台请求覆盖
  /// 用户随后主动选择的新歌单。
  Future<bool> replaceQueueIfMatches({
    required List<Song> expectedQueue,
    required List<Song> newQueue,
  }) async {
    final song = currentSong;
    if (song == null ||
        newQueue.isEmpty ||
        !_isSameQueue(queue, expectedQueue)) {
      return false;
    }

    final currentIdentity = _songIdentity(song);
    if (!newQueue.any((item) => _songIdentity(item) == currentIdentity)) {
      return false;
    }

    queue = List<Song>.of(newQueue);
    if (playbackMode == PlaybackMode.shuffle) {
      _shuffleCycle.reset(
        queue.map(_songIdentity).where((identity) => identity.isNotEmpty),
        current: currentIdentity,
      );
    }
    await _audioHandler.setSongQueue(
      queueSongs: queue,
      queueIndex: currentIndex,
      currentSong: song,
    );
    _saveQueueState();
    notifyListeners();
    return true;
  }

  Future<void> setAudioQuality(
    AudioQuality quality, {
    bool reloadCurrent = false,
  }) async {
    final sameQuality = audioQuality == quality;
    if (sameQuality && !reloadCurrent) {
      return;
    }

    audioQuality = quality;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_audioQualitySettingKey, quality.apiValue);
    notifyListeners();

    if (reloadCurrent && currentSong != null && !sameQuality) {
      await _reloadCurrentSongForQuality();
    }
  }

  /// 开关音质智能切换（播放失败时自动降级重试）。
  Future<void> setSmartQualityEnabled(bool enabled) async {
    if (smartQualityEnabled == enabled) return;
    smartQualityEnabled = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_smartQualitySettingKey, enabled);
    notifyListeners();
  }

  /// 开机自启播放开关当前是否已开启（从持久化存储读取）。
  ///
  /// 供启动流程判断是否需要预加载上次播放：开启时由首页自动播放逻辑
  /// 接管，避免两套加载流程竞争覆盖同一播放源。
  static Future<bool> isAutoPlayOnStartup() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool(_autoPlayOnStartupSettingKey) ?? false;
  }

  /// “恢复上次播放列表”开关当前是否已开启（从持久化存储读取）。
  static Future<bool> isResumeLastPlaylistOnStartup() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool(_resumeLastPlaylistOnStartupSettingKey) ?? false;
  }

  /// 开关开机自启播放（推荐歌单）。
  ///
  /// 与“恢复上次播放列表”互斥：开启时自动关闭另一项，两项只能启用其一。
  Future<void> setAutoPlayOnStartupEnabled(bool enabled) async {
    if (autoPlayOnStartupEnabled == enabled) return;
    autoPlayOnStartupEnabled = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_autoPlayOnStartupSettingKey, enabled);
    if (enabled && resumeLastPlaylistOnStartupEnabled) {
      resumeLastPlaylistOnStartupEnabled = false;
      await prefs.setBool(_resumeLastPlaylistOnStartupSettingKey, false);
    }
    notifyListeners();
  }

  /// 开关“打开应用时自动加载并播放上一次播放的列表”。
  ///
  /// 与“开机自启播放（推荐歌单）”互斥：开启时自动关闭另一项，保持只启用其一。
  Future<void> setResumeLastPlaylistOnStartupEnabled(bool enabled) async {
    if (resumeLastPlaylistOnStartupEnabled == enabled) return;
    resumeLastPlaylistOnStartupEnabled = enabled;
    if (enabled && autoPlayOnStartupEnabled) {
      autoPlayOnStartupEnabled = false;
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool(_autoPlayOnStartupSettingKey, false);
    }
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_resumeLastPlaylistOnStartupSettingKey, enabled);
    notifyListeners();
  }

  /// 开关连接新音频设备自动播放功能。
  Future<void> setAutoPlayOnDeviceConnected(bool enabled) async {
    if (autoPlayOnDeviceConnected == enabled) return;
    autoPlayOnDeviceConnected = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_autoPlayOnDeviceConnectedSettingKey, enabled);
    notifyListeners();
  }

  /// 开关蓝牙/耳机断开时是否立即停止播放（防误外放）。
  Future<void> setAutoPauseOnDeviceDisconnect(bool enabled) async {
    if (autoPauseOnDeviceDisconnect == enabled) return;
    autoPauseOnDeviceDisconnect = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_autoPauseOnDeviceDisconnectSettingKey, enabled);
    notifyListeners();
  }

  /// 开关音量均衡功能。返回 false 表示「本想启用自动适配，但录音权限被拒，
  /// 已回退固定增益」，供设置页提示用户。
  ///
  /// 权限申请必须先于原生 configure 完成：否则原生 startLoudnessAgc 会因缺
  /// RECORD_AUDIO 静默跳过，表现为开关已开、实时增益却始终拿不到数据。
  Future<bool> setVolumeNormalizationEnabled(bool enabled) async {
    if (volumeNormalizationEnabled == enabled) return true;
    volumeNormalizationEnabled = enabled;
    // 先刷新开关状态，后续 await 权限对话框期间 UI 不会假死。
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_volumeNormalizationEnabledSettingKey, enabled);
    final autoOk = await _applyVolumeNormalization();
    if (enabled && autoOk) {
      // 播放中打开开关：configure 会把增益归零。按整曲响度（或曲库均值先验）
      // 立即下发预置并锁定，一开就准。
      await _applyPresetGainForCurrentSong();
      await refreshAgcStatus();
    }
    notifyListeners();
    return autoOk;
  }

  /// 调整音量均衡的静态增益补偿。
  /// 先刷新 UI 再异步下发，避免效果器重建的 binder 调用阻塞设置菜单。
  Future<void> setVolumeGainDb(double gainDb) async {
    if (volumeGainDb == gainDb) return;
    volumeGainDb = gainDb;
    notifyListeners();
    unawaited(() async {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setDouble(_volumeGainDbSettingKey, gainDb);
      await _applyVolumeNormalization();
      notifyListeners();
    }());
  }

  /// 切换增益策略。切到 auto 时先申请 RECORD_AUDIO 权限，被拒则保持 fixed 并返回 false。
  /// 返回是否成功切换到目标模式，供 UI 决定是否提示用户。
  Future<bool> setVolumeGainMode(VolumeGainMode mode) async {
    if (volumeGainMode == mode && (mode == VolumeGainMode.fixed || agcStatus?.active == true)) {
      return true;
    }
    // 权限已授予时用缓存快速路径，零 platform channel 往返；
    // 仅首次切 auto 才真正 await 权限弹窗（ unavoidable 的用户交互）。
    if (mode == VolumeGainMode.auto &&
        isAudioEffectsSupported &&
        !_micPermissionGranted) {
      final granted = await _ensureRecordAudioPermission();
      if (!granted) {
        // 录音权限被拒：保持 fixed 模式并落盘，避免 auto 静默失效，
        // 也避免每次冷启动恢复都重新弹一次权限对话框。
        volumeGainMode = VolumeGainMode.fixed;
        notifyListeners();
        unawaited(_persistVolumeGainMode(VolumeGainMode.fixed));
        return false;
      }
    }

    // 立即更新 UI + 下发轻量通道，不等效果器 / Visualizer 重建。
    volumeGainMode = mode;
    // 同步更新 AGC 快照，避免 UI 显示旧状态；真实值由后续 refresh 校正。
    if (mode == VolumeGainMode.auto) {
      agcStatus = agcStatus?.copyWith(active: true) ??
          AgcStatus(
            active: true,
            gainDb: volumeGainDb,
            measuredRmsDb: 0.0,
            targetRmsDb: autoLoudnessLevel.targetRmsDb,
            locked: false,
          );
    } else {
      agcStatus = null;
    }
    notifyListeners();

    unawaited(() async {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_volumeGainModeSettingKey, mode.name);
      if (isAudioEffectsSupported) {
        if (volumeNormalizationEnabled) {
          // 轻量切换：原生只翻 agcActive 开关，不重建效果器 / Visualizer。
          await _audioEffects.setAgcActive(
            active: mode == VolumeGainMode.auto,
            gainDb: volumeGainDb,
          );
          if (mode == VolumeGainMode.auto) {
            // 从 fixed 切回 auto 时原生会把 postGain 归零并清掉锁定状态：
            // 按整曲响度（或曲库均值先验）立即重新预置并锁定。
            await _applyPresetGainForCurrentSong();
            await refreshAgcStatus();
          }
        }
      }
      notifyListeners();
    }());
    return true;
  }

  /// 调整自动增益目标响度档位。
  ///
  /// 只更新轻量字段并异步下发 setAgcTarget（不重建效果器 / Visualizer），
  /// 先 notifyListeners 让菜单立即刷新，避免原生 binder 调用阻塞 UI 造成假死。
  Future<void> setAutoLoudnessLevel(AutoLoudnessLevel level) async {
    if (autoLoudnessLevel == level) return;
    autoLoudnessLevel = level;
    // 立即刷新 UI，不等原生调用（原生方法很轻，但主线程 binder 仍可能偶发排队）。
    notifyListeners();
    // 持久化与下发都异步进行，失败不影响 UI 响应。
    unawaited(() async {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_autoLoudnessLevelSettingKey, level.name);
      if (volumeNormalizationEnabled && volumeGainMode == VolumeGainMode.auto) {
        // 有整曲响度按实测重算、无元数据按曲库均值先验重算，两种情况都
        // 直接锁定，改档瞬间生效（不需要等 AGC 慢降追上）。
        await _applyPresetGainForCurrentSong();
        await refreshAgcStatus();
      }
      notifyListeners();
    }());
  }

  /// 申请 RECORD_AUDIO。已授权直接返回 true；并发调用合并为一次系统弹窗。
  /// 权限被拒时由上层降级为 fixed + Toast，不在此直接跳系统设置页打断操作。
  Future<bool> _ensureRecordAudioPermission() async {
    if (!Platform.isAndroid) return false;
    if (_micPermissionGranted) return true;
    final inflight = _recordAudioPermissionRequest;
    if (inflight != null) return inflight;
    final future = _requestRecordAudioPermission();
    _recordAudioPermissionRequest = future;
    try {
      return await future;
    } finally {
      _recordAudioPermissionRequest = null;
    }
  }

  Future<bool> _requestRecordAudioPermission() async {
    final status = await Permission.microphone.status;
    if (status.isGranted) {
      _micPermissionGranted = true;
      return true;
    }
    final result = await Permission.microphone.request();
    _micPermissionGranted = result.isGranted;
    return _micPermissionGranted;
  }

  /// 权限被拒时把策略落盘为 fixed，避免每次冷启动都重新弹权限对话框。
  Future<void> _persistVolumeGainMode(VolumeGainMode mode) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_volumeGainModeSettingKey, mode.name);
  }

  /// 拉取一次 AGC 快照（设置页点击「实时增益」或模式切换后调用）。
  Future<void> refreshAgcStatus() async {
    if (!isAudioEffectsSupported || !volumeNormalizationEnabled) {
      if (agcStatus != null) {
        agcStatus = null;
        notifyListeners();
      }
      return;
    }
    final next = await _audioEffects.getAgcStatus();
    // 轮询每 2 秒一次：值没变就不重建，避免无谓 notifyListeners 引发卡顿。
    if (_agcStatusEquals(agcStatus, next)) return;
    agcStatus = next;
    notifyListeners();
  }

  bool _agcStatusEquals(AgcStatus? a, AgcStatus? b) {
    if (a == null || b == null) return a == b;
    return a.active == b.active &&
        a.locked == b.locked &&
        (a.gainDb - b.gainDb).abs() < 0.05 &&
        (a.measuredRmsDb - b.measuredRmsDb).abs() < 0.1 &&
        (a.targetRmsDb - b.targetRmsDb).abs() < 0.05;
  }

  /// 车载蓝牙歌词功能是否受支持（仅 Android）。
  bool get isBluetoothLyricsSupported =>
      BluetoothLyricsService.isSupportedPlatform;

  /// 开关向普通蓝牙车机同步歌词标题。
  Future<void> setBluetoothLyricsTitleSyncEnabled(bool enabled) async {
    if (bluetoothLyricsTitleSyncEnabled == enabled) return;
    bluetoothLyricsTitleSyncEnabled = enabled;
    _audioHandler.setBluetoothLyricsTitleSync(
      bluetoothLyricsEnabled && enabled,
    );
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_bluetoothLyricsTitleSyncSettingKey, enabled);
    _announceBluetoothLyrics();
    notifyListeners();
  }

  Future<void> setBluetoothLyricsEnabled(bool enabled) async {
    if (bluetoothLyricsEnabled == enabled) return;
    bluetoothLyricsEnabled = enabled;
    _audioHandler.setBluetoothLyricsTitleSync(
      enabled && bluetoothLyricsTitleSyncEnabled,
    );
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_bluetoothLyricsEnabledSettingKey, enabled);
    // 关闭时，清理残留广播
    if (!enabled && currentSong != null) {
      unawaited(
        _bluetoothLyrics.broadcastMetaChanged(
          title: currentSong!.title,
          artist: currentSong!.artist,
          album: currentSong!.albumName,
          lyric: '',
          position: position,
          duration: currentSong!.duration ?? Duration.zero,
          playing: isPlaying,
          trackIndex: currentIndex,
          listSize: queue.length,
        ),
      );
      _audioHandler.updateLyricMetadata(lyricText: null);
    } else if (enabled) {
      // 打开时立即推送一次当前状态
      _announceBluetoothLyrics();
    }
    notifyListeners();
  }

  /// 读取本地播放统计。
  Future<PlaybackStats> getPlaybackStats() => _statsService.getStats();

  /// 清空本地播放统计。
  Future<void> clearPlaybackStats() => _statsService.clear();

  /// 读取播放历史。
  Future<List<Song>> getPlaybackHistory({int limit = 100}) =>
      _historyService.getHistory(limit: limit);

  /// 读取播放历史总数（轻量计数，不反序列化 Song 对象）。
  Future<int> getPlaybackHistoryCount() => _historyService.count();

  /// 清空播放历史。
  Future<void> clearPlaybackHistory() => _historyService.clear();

  Future<void> setPlaybackSpeed(double speed) async {
    final clamped = speed.clamp(0.5, 3.0);
    if ((playbackSpeed - clamped).abs() < 0.001) {
      return;
    }
    playbackSpeed = clamped;
    await audioPlayer.setSpeed(clamped);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setDouble(_playbackSpeedSettingKey, clamped);
    notifyListeners();
  }

  Future<void> setBassBoostEnabled(bool enabled) async {
    if (bassBoostEnabled == enabled) {
      return;
    }
    bassBoostEnabled = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_bassBoostEnabledSettingKey, enabled);
    await _applyBassBoost();
    notifyListeners();
  }

  Future<void> setBassBoostStrength(
    double strength, {
    bool persist = true,
  }) async {
    final nextStrength = strength.clamp(0.0, 1.0);
    if ((bassBoostStrength - nextStrength).abs() < 0.001) {
      return;
    }
    bassBoostStrength = nextStrength;
    if (bassBoostEnabled) {
      unawaited(_applyBassBoost());
    }
    notifyListeners();

    if (persist) {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setDouble(_bassBoostStrengthSettingKey, nextStrength);
    }
  }

  Future<void> setEqualizerEnabled(bool enabled) async {
    if (equalizerEnabled == enabled) {
      return;
    }
    equalizerEnabled = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_equalizerEnabledSettingKey, enabled);
    await _applyEqualizer();
    notifyListeners();
  }

  Future<void> setEqualizerBandLevel(
    int index,
    int levelMillibels, {
    bool persist = true,
  }) async {
    if (index < 0 || index >= equalizerLevels.length) {
      return;
    }
    final clamped = levelMillibels.clamp(
      equalizerConfig.minMillibels,
      equalizerConfig.maxMillibels,
    );
    if (equalizerLevels[index] == clamped) {
      return;
    }
    equalizerLevels = List<int>.of(equalizerLevels)..[index] = clamped;
    equalizerPresetName = '自定义';
    if (equalizerEnabled) {
      unawaited(_applyEqualizer());
    }
    notifyListeners();

    if (persist) {
      await _persistEqualizer();
    }
  }

  Future<void> applyEqualizerPreset(AudioEffectPreset preset) async {
    equalizerPresetName = preset.name;
    equalizerLevels = _levelsForBandCount(
      preset.levels,
      equalizerLevels.length,
    );
    await _persistEqualizer();
    if (equalizerEnabled) {
      await _applyEqualizer();
    }
    notifyListeners();
  }

  Future<void> resetEqualizer() async {
    await applyEqualizerPreset(equalizerPresets.first);
  }

  Future<void> loadLyrics(Song song) async {
    final cache = cacheService;
    final cacheKey = 'cache_lyric_${song.hash}';

    if (song.source == SongSource.local) {
      final lines = await LocalLyricsService(
        api: _api,
        cache: cache,
        embeddedLyricsReader: localMusic?.getEmbeddedLyrics,
      ).load(song);
      if (currentSong?.hash == song.hash) {
        lyrics = lines;
        notifyListeners();
        _syncDesktopLyrics();
        _announceBluetoothLyrics();
      }
      return;
    }

    // 1. 先读缓存，命中则立即显示（无感）
    if (cache != null) {
      try {
        final cached = await cache.read<List<LyricLine>>(
          cacheKey,
          decode: (json) => (json['lines'] as List? ?? const [])
              .whereType<Map<String, dynamic>>()
              .map(LyricLine.fromCache)
              .toList(),
          ttl: const Duration(days: 30),
        );
        if (cached != null &&
            !listEquals(lyrics, cached.data) &&
            currentSong?.hash == song.hash) {
          lyrics = cached.data;
          notifyListeners();
          _syncDesktopLyrics();
          _announceBluetoothLyrics();
        }
      } catch (_) {}
    }

    // 2. 后台静默刷新
    try {
      final fresh = await _api.lyrics(song);
      if (currentSong?.hash != song.hash) return; // 已切歌，丢弃
      if (!listEquals(lyrics, fresh)) {
        lyrics = fresh;
        notifyListeners();
      }
      // 写缓存（空歌词也缓存，避免重复请求）
      if (cache != null) {
        unawaited(
          cache.write(cacheKey, {
            'lines': fresh.map((l) => l.toCache()).toList(),
          }),
        );
      }
    } catch (_) {
      if (currentSong?.hash == song.hash && lyrics.isEmpty) {
        lyrics = const [];
        notifyListeners();
      }
    }
    if (currentSong?.hash == song.hash) {
      _syncDesktopLyrics();
      _announceBluetoothLyrics();
    }
  }

  Future<void> togglePlay() async {
    _cancelAutomaticResume();
    if (audioPlayer.playing) {
      await _audioHandler.pause();
      // 暂停瞬间落盘一次进度：用户经常暂停后直接退出，5 秒定时器
      // 可能还没触发，位置会停留在上一次保存点。
      _saveCurrentPosition();
    } else {
      _handleManualPlay();
      final intentSerial = _playbackIntentSerial;
      if (audioPlayer.processingState == ProcessingState.completed) {
        await _audioHandler.seek(Duration.zero);
      }
      // 用户主动点播放属于新的播放意图：即使此刻系统焦点事件尚未回 end，
      // 也应尝试重新夺取焦点，而不是被打断守卫静默吞掉。
      await _startPlayback(
        expectedIntent: intentSerial,
        allowDuringInterruption: true,
      );
    }
  }

  /// 当前歌曲是否支持高潮片段。
  ///
  /// 只有酷狗提供 `/song/climax`；网易云的 hash 是 `ne_<id>`，接口只返回
  /// 空对象 `{}`，本地歌曲同理，所以直接判定不支持，避免点了才报错。
  bool get isClimaxSupported {
    final song = currentSong;
    if (song == null) return false;
    if (song.source != SongSource.kugou) return false;
    return song.hash.isNotEmpty && !song.hash.startsWith('ne_');
  }

  /// 试听当前歌曲的高潮片段：定位到高潮开始并播放，到高潮结束自动暂停。
  /// 返回是否成功（无高潮片段或失败时返回 false）。
  Future<bool> playClimaxPreview() async {
    final song = currentSong;
    if (song == null || !isClimaxSupported) return false;
    try {
      // 切歌时已经预取过就直接用，避免每次点击都多打一次接口
      final cached = climax;
      final data = cached != null && cached.isValid
          ? cached
          : await _api.songClimax(song.hash);
      if (data == null) return false;
      climax = data;
      _climaxEndTime = data.endTime;
      await seek(data.startTime);
      await play();
      return true;
    } catch (_) {
      return false;
    }
  }

  /// 高潮试听播放到结束时间时自动暂停。
  void _maybeStopClimaxPreview(Duration value) {
    final end = _climaxEndTime;
    if (end == null || value < end) return;
    _climaxEndTime = null;
    unawaited(pause());
  }

  /// 异步获取当前歌曲高潮时间，用于进度条标记（失败静默）。
  Future<void> _loadClimax(Song song) async {
    // 网易云 / 本地歌曲没有高潮数据，不发无效请求
    if (song.source != SongSource.kugou ||
        song.hash.isEmpty ||
        song.hash.startsWith('ne_')) {
      if (currentSong?.hash == song.hash && climax != null) {
        climax = null;
        notifyListeners();
      }
      return;
    }
    try {
      final result = await _api.songClimax(song.hash);
      if (currentSong?.hash != song.hash) return;
      climax = result;
      notifyListeners();
    } catch (_) {
      if (currentSong?.hash == song.hash) {
        climax = null;
      }
    }
  }

  Future<void> play() async {
    if (!audioPlayer.playing) {
      await togglePlay();
    }
  }

  Future<void> pause() async {
    _cancelAutomaticResume();
    if (audioPlayer.playing) {
      await togglePlay();
    }
  }

  void previewSeek(Duration position) {
    _isScrubbing = true;
    _isSeeking = true;
    _setPositionBase(position, playing: false);
    notifyListeners();
  }

  Future<void> seek(Duration position) async {
    final serial = ++_seekSerial;
    final target = _clampPosition(position);
    seekRevision++;
    _isScrubbing = false;
    _isSeeking = true;
    _setPositionBase(target, playing: isPlaying);
    notifyListeners();

    try {
      await _audioHandler.seek(target);
      if (serial != _seekSerial) {
        return;
      }
      _setPositionBase(target, playing: isPlaying);
      notifyListeners();
    } finally {
      if (serial == _seekSerial) {
        _isSeeking = false;
        _isScrubbing = false;
      }
    }
  }

  Future<void> next() async {
    _handleManualPlay();
    if (_isPersonalFmActive && _isAtQueueEnd) {
      await _loadNextPersonalFmBatch(isOverplay: false);
      return;
    }
    final nextSong = _nextSong();
    if (nextSong == null) return;
    await playSong(
      nextSong,
      queue: queue,
      preservePersonalFmSession: _isPersonalFmActive,
      userInitiated: false,
    );
  }

  Future<void> previous() async {
    _handleManualPlay();
    final prevSong = _previousSong();
    if (prevSong != null) {
      await playSong(
        prevSong,
        queue: queue,
        preservePersonalFmSession: _isPersonalFmActive,
        userInitiated: false,
      );
    } else {
      await seek(Duration.zero);
    }
  }

  Future<void> _handleCompleted() async {
    if (_isHandlingCompletion || currentSong == null) return;
    if (_completedSongHash == currentSong!.hash) return;
    _isHandlingCompletion = true;
    _completionFallbackTimer?.cancel();
    _completedSongHash = currentSong!.hash;
    final intentSerial = _playbackIntentSerial;

    try {
      if (_sleepFinishCurrentSong) {
        _sleepFinishCurrentSong = false;
        _sleepFinishCurrentSongOption = false;
        sleepTimerRemaining = null;
        notifyListeners();
        unawaited(_audioHandler.pause());
        return;
      }

      // Windows 上 just_audio_windows 的 WinRT MediaPlayer 在触发 completed
      // 事件时，native 回调仍在后台线程执行。若立即调用 setUrl() 加载新音源，
      // 会与 COM 平台线程产生竞态，导致 "Lost connection to device" 进程崩溃。
      // 延迟 100ms 让 native 层完成 completed 状态的清理，再切换到下一首。
      if (Platform.isWindows) {
        await Future<void>.delayed(const Duration(milliseconds: 100));
        // 延迟后重新检查状态，避免在延迟期间用户手动切歌
        if (_completedSongHash != currentSong?.hash ||
            intentSerial != _playbackIntentSerial) {
          return;
        }
      }

      if (_isPersonalFmActive && _isAtQueueEnd) {
        await _loadNextPersonalFmBatch(isOverplay: true);
        return;
      }

      if (!_isPersonalFmActive && playbackMode == PlaybackMode.singleLoop) {
        _completedSongHash = null;
        await _audioHandler.seek(Duration.zero);
        await _startPlayback(expectedIntent: intentSerial);
        return;
      }

      final nextSong = _nextSong();
      if (nextSong == null) {
        await _audioHandler.seek(Duration.zero);
        return;
      }
      await playSong(
        nextSong,
        queue: queue,
        preservePersonalFmSession: _isPersonalFmActive,
        userInitiated: false,
      );
    } finally {
      _isHandlingCompletion = false;
    }
  }

  void _maybeCompleteFromPosition(Duration value) {
    if (_isSeeking || _isScrubbing || !isPlaying || duration <= Duration.zero) {
      return;
    }
    if (audioPlayer.processingState == ProcessingState.completed) {
      return;
    }

    final remaining = duration - value;
    if (remaining.inMilliseconds <= 750 &&
        (_completionFallbackTimer?.isActive != true)) {
      final delay =
          (remaining > Duration.zero ? remaining : Duration.zero) +
          const Duration(milliseconds: 180);
      _completionFallbackTimer = Timer(delay, () {
        if (!isPlaying || _isSeeking || _isScrubbing) return;
        final currentPosition = audioPlayer.position;
        if (duration > Duration.zero &&
            duration - currentPosition <= const Duration(milliseconds: 220)) {
          unawaited(_handleCompleted());
        }
      });
    }
  }

  Future<void> _reloadCurrentSongForQuality() async {
    final song = currentSong;
    if (song == null) {
      return;
    }

    final resumePlayback = isPlaying;
    final intentSerial = _playbackIntentSerial;
    if (resumePlayback) _pendingPlaybackIntent = intentSerial;
    final targetPosition = smoothPosition;
    isPreparing = true;
    errorMessage = null;
    notifyListeners();

    try {
      String url;
      String? networkUrl;
      final local = await downloadController?.localPathFor(song, audioQuality);
      if (local != null) {
        url = local;
      } else if (song.source == SongSource.local) {
        url = song.id;
      } else {
        final PlayUrl playUrl;
        if (song.isCloudDrive) {
          playUrl = await _api.cloudSongUrl(song);
        } else if (song.source == SongSource.netease) {
          playUrl = PlayUrl(
            url: 'https://music.163.com/song/media/outer/url?id=${song.id}.mp3',
            hash: song.hash,
          );
        } else {
          playUrl = await _api.songUrl(
            song,
            quality: audioQuality,
            hashOnly: _isPersonalFmActive,
          );
        }
        if (playUrl.url.isEmpty) {
          throw Exception('当前音质暂时没有可播放地址');
        }
        url = playUrl.url;
        networkUrl = playUrl.url;
      }
      await _audioHandler.loadSong(
        song: song,
        url: url,
        queueSongs: queue,
        queueIndex: currentIndex,
      );
      if (targetPosition > Duration.zero) {
        await _audioHandler.seek(_clampPosition(targetPosition));
      }
      if (resumePlayback) {
        await _startPlayback(expectedIntent: intentSerial);
      }
      // 切音质稳定播放后再后台缓存
      if (networkUrl != null) {
        _schedulePlayCache(song, audioQuality, networkUrl);
      }
    } catch (error) {
      errorMessage = error.toString();
    } finally {
      if (_pendingPlaybackIntent == intentSerial) _pendingPlaybackIntent = null;
      isPreparing = false;
      notifyListeners();
    }
  }

  Future<void> _setupAudioSessionListeners() async {
    try {
      final session = await _audioSessionProvider();
      await session.configure(_audioSessionConfiguration);
      _interruptionSub = session.interruptionEventStream.listen((event) {
        if (event.begin) {
          _autoResumeTimer?.cancel();
          _autoResumeSerial++;
          if (!_isAudioInterrupted) {
            _wasPlayingAtInterruption =
                audioPlayer.playing ||
                _pendingPlaybackIntent == _playbackIntentSerial;
          }
          _isAudioInterrupted = true;
          // just_audio 的自动打断已关闭，duck 的音量恢复也由这里接管。
          if (event.type == AudioInterruptionType.duck) {
            if (audioInterruptionEnabled && _volumeBeforeDuck == null) {
              _volumeBeforeDuck = audioPlayer.volume;
              _duckedVolume = audioPlayer.volume / 2;
              unawaited(audioPlayer.setVolume(_duckedVolume!));
            }
            return;
          }
          _interruptionRequiresPause = true;
          if (audioInterruptionEnabled) {
            unawaited(_audioHandler.pause(userInitiated: false));
          } else if (_wasPlayingAtInterruption) {
            _scheduleAutomaticResume(
              const Duration(milliseconds: 300),
              allowDuringInterruption: true,
            );
          }
        } else {
          final shouldResume = _wasPlayingAtInterruption;
          _wasPlayingAtInterruption = false;
          _isAudioInterrupted = false;
          _interruptionRequiresPause = false;
          _restoreDuckedVolume();
          if (event.type != AudioInterruptionType.duck &&
              audioInterruptionEnabled &&
              (!autoResumeAfterInterruption ||
                  event.type != AudioInterruptionType.pause)) {
            // The source may still be loading. A denied resume also revokes
            // that pending request, so its eventual completion cannot play.
            _cancelAutomaticResume();
          }
          if (event.type == AudioInterruptionType.pause &&
              shouldResume &&
              (autoResumeAfterInterruption || (!audioInterruptionEnabled)) &&
              currentSong != null) {
            _scheduleAutomaticResume(const Duration(milliseconds: 500));
          }
        }
      });
      _becomingNoisySub = session.becomingNoisyEventStream.listen((_) {
        if (autoPauseOnDeviceDisconnect) {
          _resumeBlockedByDisconnect = true;
          _cancelAutomaticResume();
          _restoreDuckedVolume();
          // 即使来电已经暂停，也要撤销正在启动的播放请求。
          unawaited(_audioHandler.pause(userInitiated: false));
          return;
        }
        if (!audioInterruptionEnabled) {
          // 阻止打断模式下忽略耳机拔出
          return;
        }
        final wasPlaying = audioPlayer.playing;
        unawaited(_audioHandler.pause(userInitiated: false));
        if (wasPlaying && autoResumeAfterInterruption && currentSong != null) {
          _scheduleAutomaticResume(const Duration(milliseconds: 500));
        }
      });
      _previousDevices = await session.getDevices();
      _devicesSub = session.devicesStream.listen((devices) {
        if (_previousDevices != null) {
          final addedDevices = devices.difference(_previousDevices!);
          if (addedDevices.isNotEmpty) {
            // ignore: experimental_member_use
            final hasNewAudioDevice = addedDevices.any(
              (d) =>
                  // ignore: experimental_member_use
                  d.type == AudioDeviceType.bluetoothA2dp ||
                  // ignore: experimental_member_use
                  d.type == AudioDeviceType.bluetoothLe ||
                  // ignore: experimental_member_use
                  d.type == AudioDeviceType.bluetoothSco ||
                  // ignore: experimental_member_use
                  d.type == AudioDeviceType.wiredHeadset ||
                  // ignore: experimental_member_use
                  d.type == AudioDeviceType.wiredHeadphones ||
                  // ignore: experimental_member_use
                  d.type == AudioDeviceType.carAudio,
            );

            if (hasNewAudioDevice &&
                autoPlayOnDeviceConnected &&
                currentSong != null &&
                !audioPlayer.playing &&
                !_isAudioInterrupted) {
              _resumeBlockedByDisconnect = false;
              _scheduleAutomaticResume(const Duration(milliseconds: 500));
            }
            if (hasNewAudioDevice) {
              // 车机刚建立连接时接收端可能尚未注册完成：先主动刷新一次，
              // 再延迟补发一次，避免第二次连接后一直收不到歌词（#161）。
              _announceBluetoothLyrics();
              _scheduleBluetoothLyricsAnnouncement();
            }
          }
        }
        _previousDevices = devices;
      });
    } catch (_) {
      // AudioSession not available on this platform
    }
  }

  void _cancelAutomaticResume() {
    _autoResumeTimer?.cancel();
    _autoResumeTimer = null;
    _autoResumeSerial++;
    _playbackIntentSerial++;
    _wasPlayingAtInterruption = false;
  }

  void _handleManualPlay() {
    _cancelAutomaticResume();
    _resumeBlockedByDisconnect = false;
    // 打断事件只收到 begin、没有 end 时（例如对方 App 抢焦点后未 abandon，
    // 或切后台期间 end 丢失），这两个标志会一直为真，_startPlayback 会
    // 静默拒绝一切起播——表现为“切回来点播放没反应”。用户主动播放就是
    // 明确要求重新夺取音频焦点，必须清掉过期的打断态。
    // 正在加载中的旧请求不受影响：若此刻真的发生新打断，begin 会再次置位。
    _isAudioInterrupted = false;
    _interruptionRequiresPause = false;
    _restoreDuckedVolume();
  }

  void _restoreDuckedVolume() {
    final previous = _volumeBeforeDuck;
    final ducked = _duckedVolume;
    _volumeBeforeDuck = null;
    _duckedVolume = null;
    // 用户或切歌流程已经改变音量时，不用旧值覆盖新的选择。
    if (previous != null && audioPlayer.volume == ducked) {
      unawaited(audioPlayer.setVolume(previous));
    }
  }

  void _scheduleAutomaticResume(
    Duration delay, {
    bool allowDuringInterruption = false,
  }) {
    _autoResumeTimer?.cancel();
    final serial = ++_autoResumeSerial;
    final intentSerial = _playbackIntentSerial;
    _autoResumeTimer = Timer(delay, () async {
      await _audioSessionReady;
      if (serial != _autoResumeSerial ||
          _resumeBlockedByDisconnect ||
          (_isAudioInterrupted && !allowDuringInterruption) ||
          audioPlayer.playing ||
          isPreparing ||
          _isChangingSource ||
          currentSong == null) {
        return;
      }
      await _startPlayback(
        expectedIntent: intentSerial,
        allowDuringInterruption: allowDuringInterruption,
      );
    });
  }

  /// 根据打断设置生成 AudioSessionConfiguration。
  ///
  /// 阻止打断时使用 [AndroidAudioFocusGainType.gain] 并禁用 androidWillPauseWhenDucked，
  /// 向系统声明不希望被其他 App 打断。同时配合 interruptionEventStream 中的
  /// 主动恢复播放作为双保险。
  AudioSessionConfiguration get _audioSessionConfiguration {
    if (audioInterruptionEnabled) {
      return const AudioSessionConfiguration.music();
    }
    // 阻止打断模式：声明需要独占音频焦点，不因降音暂停
    return const AudioSessionConfiguration(
      androidAudioAttributes: AndroidAudioAttributes(
        contentType: AndroidAudioContentType.music,
        usage: AndroidAudioUsage.media,
      ),
      androidAudioFocusGainType: AndroidAudioFocusGainType.gain,
      // 不因其他 App 降音而暂停
      androidWillPauseWhenDucked: false,
    );
  }

  Future<void> setAudioInterruptionEnabled(bool enabled) async {
    if (audioInterruptionEnabled == enabled) return;
    audioInterruptionEnabled = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_audioInterruptionEnabledSettingKey, enabled);
    // 设置变更后立即重新配置 AudioSession，使新策略生效
    unawaited(_reconfigureAudioSession());
    notifyListeners();
  }

  /// 重新配置 AudioSession 以应用最新的打断策略。
  Future<void> _reconfigureAudioSession() async {
    try {
      final session = await _audioSessionProvider();
      await session.configure(_audioSessionConfiguration);
    } catch (_) {
      // AudioSession not available on this platform
    }
  }

  Future<void> setAutoResumeAfterInterruption(bool enabled) async {
    if (autoResumeAfterInterruption == enabled) return;
    autoResumeAfterInterruption = enabled;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_autoResumeAfterInterruptionSettingKey, enabled);
    notifyListeners();
  }

  Future<void> setDesktopLyricsEnabled(bool enabled) async {
    if (desktopLyricsEnabled == enabled) return;
    desktopLyricsEnabled = enabled;
    _desktopLyricsUserDismissed = false;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_desktopLyricsEnabledSettingKey, enabled);
    notifyListeners();

    if (enabled) {
      final hasPermission = await _desktopLyrics.checkPermission();
      if (!hasPermission) {
        desktopLyricsEnabled = false;
        await prefs.setBool(_desktopLyricsEnabledSettingKey, false);
        notifyListeners();
        await _desktopLyrics.requestPermission();
        return;
      }
      final song = currentSong;
      if (song != null) {
        await _syncDesktopLyricsVisibility();
      }
    } else {
      await _desktopLyrics.hide();
    }
  }

  bool get _shouldShowDesktopLyrics {
    return desktopLyricsEnabled &&
        (_desktopLyricsPreviewVisible ||
            (!_desktopLyricsUserDismissed &&
                currentSong != null &&
                !_isAppForeground));
  }

  Future<void> _syncDesktopLyricsVisibility() async {
    if (!_shouldShowDesktopLyrics) {
      await _desktopLyrics.hide();
      return;
    }

    final song = currentSong;
    if (song == null) return;
    final shown = await _desktopLyrics.show(
      title: song.title,
      artist: song.artist,
    );
    if (shown) {
      _syncDesktopLyrics();
      _syncDesktopPlayState();
      _syncDesktopKaraokeProgress();
    }
  }

  void _syncDesktopLyrics() {
    if (!_shouldShowDesktopLyrics) return;
    final index = activeLyricIndex;
    if (lyrics.isEmpty) {
      _desktopLyrics.updateLyrics(current: '', next: '');
      return;
    }
    final current = lyrics[index.clamp(0, lyrics.length - 1)].text;
    final nextIndex = index + 1;
    final next = nextIndex < lyrics.length ? lyrics[nextIndex].text : '';
    _desktopLyrics.updateLyrics(current: current, next: next);
  }

  void _syncDesktopPlayState() {
    if (!_shouldShowDesktopLyrics) return;
    _desktopLyrics.updatePlayState(isPlaying: isPlaying);
  }

  int _lastDesktopLyricIndex = -1;
  int _lastSuperLyricIndex = -1;
  // 初始为 false：App 启动时通常处于暂停态，若初始为 true，
  // 首个 position tick 就会向系统发送一次无意义的 stop/playstate 广播。
  bool _lastSuperLyricPlaying = false;
  int _lastBluetoothLyricIndex = -1;
  bool _lastBluetoothPlaying = false;

  void _maybeSyncDesktopLyricFromPosition() {
    if (!_shouldShowDesktopLyrics || lyrics.isEmpty) return;
    final index = activeLyricIndex;
    if (index != _lastDesktopLyricIndex) {
      _lastDesktopLyricIndex = index;
      _syncDesktopLyrics();
    }
    // Karaoke progress for current line
    _syncDesktopKaraokeProgress();
  }

  void _syncSuperLyricFromPosition() {
    if (currentSong == null) return;
    if (lyrics.isEmpty) {
      // 无歌词时仅处理播放状态变化
      if (!isPlaying && _lastSuperLyricPlaying) {
        _lastSuperLyricPlaying = false;
        _lastSuperLyricIndex = -1;
        unawaited(_superLyric.sendStop());
      } else if (isPlaying && !_lastSuperLyricPlaying) {
        _lastSuperLyricPlaying = true;
      }
      return;
    }
    final index = activeLyricIndex;
    // 行变化且正在播放 → 发歌词
    final lineChanged = isPlaying && (index != _lastSuperLyricIndex);
    // 从暂停切到播放 → 重新发送当前行
    final resumed = isPlaying && !_lastSuperLyricPlaying;
    if (lineChanged || resumed) {
      _lastSuperLyricIndex = index;
      _lastSuperLyricPlaying = true;
      final clampedIndex = index.clamp(0, lyrics.length - 1);
      final line = lyrics[clampedIndex];
      final lineEndTime =
          line.time +
          (line.duration ??
              _estimatedLineDuration(clampedIndex) ??
              Duration.zero);
      unawaited(
        _superLyric.sendLyric(
          song: currentSong!,
          line: line,
          lineEndTime: lineEndTime,
        ),
      );
    } else if (!isPlaying && _lastSuperLyricPlaying) {
      // 切到暂停：发送 stop
      _lastSuperLyricPlaying = false;
      unawaited(_superLyric.sendStop());
    }
  }

  /// 位置流中的车载蓝牙歌词同步入口。
  void _syncBluetoothLyricsFromPosition() {
    if (!bluetoothLyricsEnabled) return;
    if (currentSong == null) return;
    final index = lyrics.isEmpty ? -1 : activeLyricIndex;
    final lineChanged = index != _lastBluetoothLyricIndex;
    final playingChanged = isPlaying != _lastBluetoothPlaying;
    // 状态或行变化时推送
    if (lineChanged || playingChanged) {
      _pushBluetoothLyricForCurrentLine(
        index: index,
        forcePlayState: playingChanged,
      );
    }
  }

  /// 强制重新发布当前歌曲的 MediaSession 歌词与播放状态。
  ///
  /// 播放/暂停状态和歌词行缓存只能避免重复广播，无法覆盖以下场景：
  /// - 应用重启或恢复上次播放后，MediaSession 已被系统重建；
  /// - 车机断开后重新连接，接收端重新注册广播；
  /// - 用户重新打开「车载蓝牙歌词」开关。
  ///
  /// 这些场景必须忽略缓存，主动发送一次完整状态。
  void _announceBluetoothLyrics() {
    if (!bluetoothLyricsEnabled || currentSong == null) return;
    _lastBluetoothLyricIndex = -1;
    _pushBluetoothLyricForCurrentLine(force: true, forcePlayState: true);
  }

  /// 延迟补发歌词状态，等待车机端接收器完成注册。
  void _scheduleBluetoothLyricsAnnouncement({
    Duration delay = const Duration(milliseconds: 900),
  }) {
    if (!bluetoothLyricsEnabled || currentSong == null) return;
    _bluetoothAnnounceTimer?.cancel();
    _bluetoothAnnounceTimer = Timer(delay, _announceBluetoothLyrics);
  }

  /// 推送当前行歌词到车载蓝牙（MediaSession extras + 系统广播）。
  ///
  /// - [force]：忽略行/状态缓存直接发送一次（用于用户刚打开开关时）
  /// - [index]：当前歌词行索引，-1 表示无歌词；默认取 activeLyricIndex
  /// - [forcePlayState]：即使行未变也发送 playStateChanged 广播
  void _pushBluetoothLyricForCurrentLine({
    bool force = false,
    int? index,
    bool forcePlayState = false,
  }) {
    if (!bluetoothLyricsEnabled || currentSong == null) return;
    final song = currentSong!;
    final resolvedIndex = index ?? (lyrics.isEmpty ? -1 : activeLyricIndex);
    // 行变化判定基准快照（下方 if/else 会更新 _lastBluetoothLyricIndex）
    final prevIndex = _lastBluetoothLyricIndex;

    final String lyricText;
    final String? translationText;
    final String? romanizationText;
    if (lyrics.isEmpty || resolvedIndex < 0) {
      lyricText = '';
      translationText = null;
      romanizationText = null;
      _lastBluetoothLyricIndex = -1;
    } else {
      final clampedIndex = resolvedIndex.clamp(0, lyrics.length - 1);
      final line = lyrics[clampedIndex];
      lyricText = line.text;
      translationText = line.translation;
      romanizationText = line.romanization;
      _lastBluetoothLyricIndex = clampedIndex;
    }

    _lastBluetoothPlaying = isPlaying;

    // 1. MediaSession extras 歌词字段（部分 App/Xposed 读取 MediaSession）
    _audioHandler.updateLyricMetadata(
      lyricText: lyricText.isEmpty ? null : lyricText,
      translationText: translationText,
      romanizationText: romanizationText,
    );

    // 2. 系统广播 + 各 App 自定义广播
    // 行变化判定必须用快照对比：不能用恒真的 lyricText.isNotEmpty，
    // 否则 lineChanged 恒真、forcePlayState 分支永远无法执行，
    // 播放/暂停状态广播会被静默吞掉。
    final lineChanged = force || prevIndex != _lastBluetoothLyricIndex;
    if (lineChanged) {
      unawaited(
        _bluetoothLyrics.broadcastMetaChanged(
          title: song.title,
          artist: song.artist,
          album: song.albumName,
          lyric: lyricText,
          position: position,
          duration: song.duration ?? Duration.zero,
          playing: isPlaying,
          trackIndex: currentIndex,
          listSize: queue.length,
        ),
      );
    }
    // 播放/暂停状态变化独立发送，不依赖行是否变化。
    if (forcePlayState) {
      unawaited(
        _bluetoothLyrics.broadcastPlayStateChanged(
          title: song.title,
          artist: song.artist,
          album: song.albumName,
          position: position,
          duration: song.duration ?? Duration.zero,
          playing: isPlaying,
        ),
      );
    }
  }

  void _syncDesktopKaraokeProgress() {
    if (!_shouldShowDesktopLyrics || lyrics.isEmpty) return;
    final index = activeLyricIndex;
    final line = lyrics[index.clamp(0, lyrics.length - 1)];
    final position = smoothPosition;
    final lineDuration = line.duration ?? _estimatedLineDuration(index);

    if (line.words.isEmpty) {
      // No word-level data: estimate progress from line duration
      final lineStart = line.time.inMilliseconds;
      final lineDurationMs = lineDuration?.inMilliseconds ?? 0;
      if (lineDurationMs > 0) {
        final elapsed = position.inMilliseconds - lineStart;
        final progress = (elapsed / lineDurationMs).clamp(0.0, 1.0);
        _desktopLyrics.updateKaraokeProgress(
          progress: progress,
          lineDuration: lineDuration,
          isPlaying: isPlaying,
        );
      } else {
        _desktopLyrics.updateKaraokeProgress(
          progress: 1.0,
          lineDuration: null,
          isPlaying: isPlaying,
        );
      }
    } else {
      // Word-level: find active word and compute progress
      final lineStart = line.time.inMilliseconds;
      final lineDurationMs = lineDuration?.inMilliseconds ?? 0;
      if (lineDurationMs > 0) {
        final elapsed = position.inMilliseconds - lineStart;
        final progress = (elapsed / lineDurationMs).clamp(0.0, 1.0);
        _desktopLyrics.updateKaraokeProgress(
          progress: progress,
          lineDuration: lineDuration,
          isPlaying: isPlaying,
        );
      }
    }
  }

  Duration? _estimatedLineDuration(int index) {
    if (index < 0 || index >= lyrics.length) {
      return null;
    }
    final explicit = lyrics[index].duration;
    if (explicit != null && explicit > Duration.zero) {
      return explicit;
    }
    if (index + 1 < lyrics.length) {
      final nextDuration = lyrics[index + 1].time - lyrics[index].time;
      if (nextDuration > Duration.zero) {
        return nextDuration;
      }
    }
    if (duration > lyrics[index].time) {
      final tailDuration = duration - lyrics[index].time;
      if (tailDuration > Duration.zero) {
        return tailDuration;
      }
    }
    return null;
  }

  Future<void> updateDesktopLyricsSettings(
    DesktopLyricsSettings settings,
  ) async {
    desktopLyricsSettings = settings;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      _desktopLyricsSettingsKey,
      jsonEncode(settings.toMap()),
    );
    notifyListeners();
    await _desktopLyrics.updateSettings(settings);
  }

  bool get isDesktopLyricsSupported => DesktopLyricsService.isSupportedPlatform;

  void setAppForeground(bool isForeground) {
    if (isForeground) {
      // 点开软件或切回前台时，重置用户本次临时关闭悬浮窗的标记，恢复自启动
      _desktopLyricsUserDismissed = false;
    } else {
      // 退到后台时立刻落盘一次完整队列。系统可能在任意时刻回收进程，
      // 只依赖 500ms 防抖 + dispose 会导致「歌曲存了、队列丢了」，
      // 重启后队列退化成单曲，无法在歌单内切歌。
      _queueSaveTimer?.cancel();
      unawaited(_persistQueueState());
    }
    if (_isAppForeground == isForeground) return;
    _isAppForeground = isForeground;
    if (isForeground) {
      // 用户重新打开应用时，车机可能早已连接，不会再有 devicesStream 事件。
      _scheduleBluetoothLyricsAnnouncement(
        delay: const Duration(milliseconds: 300),
      );
    }
    if (desktopLyricsEnabled) {
      _desktopLyrics.setAppForeground(isForeground: isForeground);
      unawaited(_syncDesktopLyricsVisibility());
    }
  }

  Future<void> setDesktopLyricsPreviewVisible(bool visible) async {
    if (_desktopLyricsPreviewVisible == visible) return;
    _desktopLyricsPreviewVisible = visible;
    if (visible) {
      _desktopLyricsUserDismissed = false;
    }
    await _syncDesktopLyricsVisibility();
  }

  Future<void> _handleDesktopLyricsVisibility({
    required bool visible,
    required bool userClosed,
  }) async {
    if (!userClosed) {
      return;
    }
    // 用户在桌面歌词悬浮窗点击“关闭”按钮：仅临时隐藏本次后台悬浮窗，
    // 不修改持久化设置开关 desktopLyricsEnabled，保证下次“点开软件自启动”。
    _desktopLyricsUserDismissed = true;
    _desktopLyricsPreviewVisible = false;
    notifyListeners();
  }

  /// 获取用户是否在当前后台会话中临时关闭了桌面歌词
  bool get isDesktopLyricsUserDismissed => _desktopLyricsUserDismissed;

  Future<bool> checkDesktopLyricsPermission() =>
      _desktopLyrics.checkPermission();

  Future<void> requestDesktopLyricsPermission() =>
      _desktopLyrics.requestPermission();

  bool get isSleepTimerActive =>
      sleepTimerRemaining != null && sleepTimerRemaining! > Duration.zero;

  bool get isSleepFinishCurrentSong => _sleepFinishCurrentSong;
  bool get sleepFinishCurrentSongOption => _sleepFinishCurrentSongOption;

  /// Set a sleep timer that pauses playback immediately or after current song finishes when it expires.
  void setSleepTimer(Duration duration, {bool finishCurrentSong = false}) {
    _sleepFinishCurrentSongOption = finishCurrentSong;
    _sleepFinishCurrentSong = false;
    _sleepTimer?.cancel();
    _sleepTimerEnd = DateTime.now().add(duration);
    sleepTimerRemaining = duration;
    notifyListeners();

    _sleepTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      final end = _sleepTimerEnd;
      if (end == null) return;
      final remaining = end.difference(DateTime.now());
      if (remaining <= Duration.zero) {
        if (_sleepFinishCurrentSongOption) {
          _sleepTimer?.cancel();
          _sleepTimer = null;
          _sleepTimerEnd = null;
          _sleepFinishCurrentSong = true;
          notifyListeners();
        } else {
          _executeSleepTimer();
        }
      } else {
        sleepTimerRemaining = remaining;
        notifyListeners();
      }
    });
  }

  /// Set a sleep timer that finishes the current song, then stops.
  void setSleepTimerFinishSong(Duration duration) {
    setSleepTimer(duration, finishCurrentSong: true);
  }

  /// 车机「播完当前单曲」：立即置位（不依赖 1s tick），使原生面板即时反映勾选与副标题。
  void setSleepFinishCurrentSongNow() {
    _sleepTimer?.cancel();
    _sleepTimer = null;
    _sleepTimerEnd = null;
    sleepTimerRemaining = null;
    _sleepFinishCurrentSongOption = true;
    _sleepFinishCurrentSong = true;
    notifyListeners();
  }

  /// Update the sleep timer finish song option dynamically.
  void updateSleepTimerOption(bool finishCurrentSong) {
    if (_sleepTimer != null || _sleepFinishCurrentSong) {
      _sleepFinishCurrentSongOption = finishCurrentSong;
      // If the timer has already expired and is waiting for song to finish,
      // and they turn it OFF, we should stop immediately.
      if (!finishCurrentSong && _sleepFinishCurrentSong) {
        _executeSleepTimer();
      } else {
        notifyListeners();
      }
    }
  }

  void cancelSleepTimer() {
    _sleepTimer?.cancel();
    _sleepTimer = null;
    _sleepTimerEnd = null;
    _sleepFinishCurrentSong = false;
    _sleepFinishCurrentSongOption = false;
    sleepTimerRemaining = null;
    notifyListeners();
  }

  void _executeSleepTimer() {
    _sleepTimer?.cancel();
    _sleepTimer = null;
    _sleepTimerEnd = null;
    _sleepFinishCurrentSong = false;
    _sleepFinishCurrentSongOption = false;
    sleepTimerRemaining = null;
    notifyListeners();
    unawaited(_audioHandler.pause());
  }

  Future<void> _restoreSettings() async {
    final prefs = await SharedPreferences.getInstance();
    addListeningTimeEnabled =
        prefs.getBool(_listenTimeSettingKey) ?? addListeningTimeEnabled;
    keepScreenOnEnabled =
        prefs.getBool(_keepScreenOnSettingKey) ?? keepScreenOnEnabled;
    lyricBlurEnabled = prefs.getBool(_lyricBlurSettingKey) ?? lyricBlurEnabled;
    playerGameEnabled =
        prefs.getBool(_playerGameEnabledSettingKey) ?? playerGameEnabled;
    audioQuality = AudioQuality.fromApiValue(
      prefs.getString(_audioQualitySettingKey),
    );
    smartQualityEnabled =
        prefs.getBool(_smartQualitySettingKey) ?? smartQualityEnabled;
    autoPlayOnStartupEnabled =
        prefs.getBool(_autoPlayOnStartupSettingKey) ?? autoPlayOnStartupEnabled;
    resumeLastPlaylistOnStartupEnabled =
        prefs.getBool(_resumeLastPlaylistOnStartupSettingKey) ??
        resumeLastPlaylistOnStartupEnabled;
    equalizerEnabled =
        prefs.getBool(_equalizerEnabledSettingKey) ?? equalizerEnabled;
    equalizerPresetName =
        prefs.getString(_equalizerPresetSettingKey) ?? equalizerPresetName;
    equalizerLevels = _restoreEqualizerLevels(
      prefs.getString(_equalizerLevelsSettingKey),
    );
    equalizerConfig = EqualizerConfig.fallback(equalizerLevels);
    bassBoostEnabled =
        prefs.getBool(_bassBoostEnabledSettingKey) ?? bassBoostEnabled;
    bassBoostStrength =
        prefs.getDouble(_bassBoostStrengthSettingKey) ?? bassBoostStrength;
    audioInterruptionEnabled =
        prefs.getBool(_audioInterruptionEnabledSettingKey) ??
        audioInterruptionEnabled;
    autoResumeAfterInterruption =
        prefs.getBool(_autoResumeAfterInterruptionSettingKey) ??
        autoResumeAfterInterruption;
    autoPlayOnDeviceConnected =
        prefs.getBool(_autoPlayOnDeviceConnectedSettingKey) ??
        autoPlayOnDeviceConnected;
    autoPauseOnDeviceDisconnect =
        prefs.getBool(_autoPauseOnDeviceDisconnectSettingKey) ??
        autoPauseOnDeviceDisconnect;
    volumeNormalizationEnabled =
        prefs.getBool(_volumeNormalizationEnabledSettingKey) ??
        volumeNormalizationEnabled;
    volumeGainDb = prefs.getDouble(_volumeGainDbSettingKey) ?? volumeGainDb;
    final savedGainMode = prefs.getString(_volumeGainModeSettingKey);
    if (savedGainMode != null) {
      volumeGainMode = VolumeGainMode.values.firstWhere(
        (m) => m.name == savedGainMode,
        orElse: () => VolumeGainMode.auto,
      );
    }
    final savedLoudness = prefs.getString(_autoLoudnessLevelSettingKey);
    if (savedLoudness != null) {
      autoLoudnessLevel = AutoLoudnessLevel.values.firstWhere(
        (l) => l.name == savedLoudness,
        orElse: () => AutoLoudnessLevel.standard,
      );
    }
    // 整曲响度缓存：音源 `/song/url` 拿到的 `volume`，用于下一次开场即精确给足。
    final loudnessRaw = prefs.getString(_songLoudnessCacheKey);
    if (loudnessRaw != null && loudnessRaw.isNotEmpty) {
      try {
        final map = jsonDecode(loudnessRaw);
        if (map is Map) {
          map.forEach((key, value) {
            // 旧版本曾把哨兵值 0 写进缓存，读取时一并过滤。
            final volume = validLoudnessDb(value is num ? value.toDouble() : null);
            if (volume != null) {
              _songLoudnessCache[key.toString()] = volume;
            }
          });
        }
      } catch (_) {}
    }
    // 缓存来源表（续十一）：旧版本没有这张表，缺失一律视为音源实测
    // （那时缓存只可能来自 /song/url）。端上实测值要如实标注为 measured。
    final sourceRaw = prefs.getString(_songLoudnessSourceCacheKey);
    if (sourceRaw != null && sourceRaw.isNotEmpty) {
      try {
        final map = jsonDecode(sourceRaw);
        if (map is Map) {
          map.forEach((key, value) {
            _songLoudnessSourceCache[key.toString()] =
                SongLoudnessSource.values.firstWhere(
              (e) => e.name == value,
              orElse: () => SongLoudnessSource.url,
            );
          });
        }
      } catch (_) {}
    }
    // 口径标定样本：冷启动后立即有可用偏置，不必等重新攒够 3 首。
    final calibrationRaw = prefs.getString(_loudnessCalibrationDeltasKey);
    if (calibrationRaw != null && calibrationRaw.isNotEmpty) {
      try {
        final list = jsonDecode(calibrationRaw);
        if (list is List) {
          _calibrationDeltas.addAll(
            list.whereType<num>().map((e) => e.toDouble()),
          );
          if (_calibrationDeltas.length > _maxCalibrationSamples) {
            _calibrationDeltas.removeRange(
              0,
              _calibrationDeltas.length - _maxCalibrationSamples,
            );
          }
        }
      } catch (_) {}
    }
    bluetoothLyricsEnabled =
        prefs.getBool(_bluetoothLyricsEnabledSettingKey) ??
        bluetoothLyricsEnabled;
    final savedPendingMs = prefs.getInt(_pendingListenTimeKey) ?? 0;
    if (savedPendingMs > 0) {
      _pendingListenTime = Duration(milliseconds: savedPendingMs);
      // 这部分时长在上次运行中已经计入本地统计，水位对齐避免重复累加。
      _lastStatsFlush = _pendingListenTime;
    }
    bluetoothLyricsTitleSyncEnabled =
        prefs.getBool(_bluetoothLyricsTitleSyncSettingKey) ?? true;
    _audioHandler.setBluetoothLyricsTitleSync(
      bluetoothLyricsEnabled && bluetoothLyricsTitleSyncEnabled,
    );
    playbackSpeed = prefs.getDouble(_playbackSpeedSettingKey) ?? playbackSpeed;
    desktopLyricsEnabled =
        prefs.getBool(_desktopLyricsEnabledSettingKey) ?? desktopLyricsEnabled;
    final dlSettingsRaw = prefs.getString(_desktopLyricsSettingsKey);
    if (dlSettingsRaw != null && dlSettingsRaw.isNotEmpty) {
      try {
        final map = jsonDecode(dlSettingsRaw);
        if (map is Map<String, dynamic>) {
          desktopLyricsSettings = DesktopLyricsSettings.fromMap(map);
        }
      } catch (_) {}
    }
    unawaited(audioPlayer.setSpeed(playbackSpeed));
    if (desktopLyricsEnabled) {
      unawaited(_desktopLyrics.updateSettings(desktopLyricsSettings));
      unawaited(_syncDesktopLyricsVisibility());
    }
    _syncListeningTimeTracker();
    unawaited(_refreshEqualizerConfig());
    unawaited(_applyEqualizer());
    unawaited(_applyBassBoost());
    unawaited(_applyVolumeNormalization());
    notifyListeners();
    if (!_settingsRestored.isCompleted) {
      _settingsRestored.complete();
    }
  }

  List<int> _restoreEqualizerLevels(String? raw) {
    if (raw == null || raw.isEmpty) {
      return List<int>.of(_defaultEqualizerLevels);
    }
    try {
      final decoded = jsonDecode(raw);
      if (decoded is List) {
        final levels = decoded
            .whereType<num>()
            .map((value) => value.round())
            .toList();
        if (levels.isNotEmpty) {
          return _levelsForBandCount(levels, _defaultEqualizerLevels.length);
        }
      }
    } catch (_) {}
    return List<int>.of(_defaultEqualizerLevels);
  }

  Future<void> _persistEqualizer() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_equalizerEnabledSettingKey, equalizerEnabled);
    await prefs.setString(_equalizerPresetSettingKey, equalizerPresetName);
    await prefs.setString(
      _equalizerLevelsSettingKey,
      jsonEncode(equalizerLevels),
    );
  }

  Future<void> _refreshEqualizerConfig() async {
    if (!isAudioEffectsSupported) {
      return;
    }
    final config = await _audioEffects.equalizerConfig(
      audioSessionId:
          _androidAudioSessionId ?? audioPlayer.androidAudioSessionId,
    );
    if (config == null || config.bands.isEmpty) {
      return;
    }
    equalizerConfig = config;
    if (equalizerLevels.length != config.bands.length) {
      equalizerLevels = _levelsForBandCount(
        equalizerLevels,
        config.bands.length,
      );
      unawaited(_persistEqualizer());
    }
    notifyListeners();
  }

  Future<void> _applyEqualizer() async {
    if (!isAudioEffectsSupported) {
      return;
    }
    await _audioEffects.configureEqualizer(
      audioSessionId:
          _androidAudioSessionId ?? audioPlayer.androidAudioSessionId,
      enabled: equalizerEnabled,
      levels: equalizerLevels,
    );
  }

  Future<void> _applyBassBoost() async {
    if (!isBassBoostSupported) {
      return;
    }

    await _audioEffects.configureBassBoost(
      audioSessionId:
          _androidAudioSessionId ?? audioPlayer.androidAudioSessionId,
      enabled: bassBoostEnabled,
      strength: bassBoostStrength,
    );
  }

  /// 把所有音量均衡参数下发到原生。返回 false 仅当「需要自动适配但录音权限
  /// 被拒，已降级为固定增益」。
  ///
  /// 这里是打开开关 / 冷启动恢复 / audioSession 变化 / 增益调整等所有下发的
  /// 唯一汇合点。AGC 用 Visualizer 测量实时响度，而 Visualizer 需要
  /// RECORD_AUDIO；必须在下发 autoGain=true 之前确保权限，否则原生会直接
  /// skip，UI 却仍显示「自动适配」，形成开关已开却无实时增益的假成功。
  /// 权限已授予时缓存命中，切歌等高频调用零 platform channel 往返、不卡顿。
  Future<bool> _applyVolumeNormalization() async {
    if (!isAudioEffectsSupported) return true;

    var useAuto = volumeNormalizationEnabled &&
        volumeGainMode == VolumeGainMode.auto;
    var downgraded = false;
    if (useAuto) {
      final granted = await _ensureRecordAudioPermission();
      if (!granted) {
        // 不让 auto 假生效：内存状态、持久化、UI 与原生保持一致地回退 fixed，
        // 静态增益补偿照常工作，音效不失效。
        useAuto = false;
        downgraded = true;
        volumeGainMode = VolumeGainMode.fixed;
        agcStatus = null;
        notifyListeners();
        await _persistVolumeGainMode(VolumeGainMode.fixed);
      }
    }

    await _audioEffects.configureVolumeNormalization(
      audioSessionId:
          _androidAudioSessionId ?? audioPlayer.androidAudioSessionId,
      enabled: volumeNormalizationEnabled,
      gainDb: volumeGainDb,
      autoGain: useAuto,
      targetRmsDb: autoLoudnessLevel.targetRmsDb,
    );
    if (useAuto) {
      await refreshAgcStatus();
    } else {
      agcStatus = null;
    }
    return !downgraded;
  }

  /// 记录音源下发的整曲响度（song.hash → volume），并异步落盘。
  ///
  /// 音源 `/song/url` 的 `volume` 是整曲平均电平，实测 12/12 覆盖
  /// （-16.4 ~ -4.7dBFS），比开场短窗样本可靠得多：长低音前奏的歌
  /// （如《是否我真的一无所有》前奏 P80=-40.96、整曲 volume=-11.8）用开场
  /// 样本会误判成偏小声并打满 +15dB，用整曲响度算则应为 0dB。
  /// 缓存条目的可信度：音源官方 > 端上整曲实测 > 历史缓存 / 先验。
  static int _sourceRank(SongLoudnessSource s) => switch (s) {
        SongLoudnessSource.url => 3,
        SongLoudnessSource.measured => 2,
        SongLoudnessSource.cache => 1,
        SongLoudnessSource.fallback => 0,
      };

  void _rememberSongLoudness(
    String hash,
    double volumeDb, [
    SongLoudnessSource source = SongLoudnessSource.url,
  ]) {
    // volume=0 是「音源尚未测出响度」的哨兵值（实测全为近三天发行的新歌），
    // 绝不能写进缓存，否则下次播放仍会被当成 0 dBFS 算出超大负增益。
    if (hash.isEmpty || validLoudnessDb(volumeDb) == null) return;
    final previous = _songLoudnessCache[hash];
    final previousSource =
        _songLoudnessSourceCache[hash] ?? SongLoudnessSource.url;
    // 值相同且新来源不比现有更权威时不必写盘；值变了或来源升级（端上→音源）才写。
    if (previous != null &&
        (previous - volumeDb).abs() < 0.05 &&
        _sourceRank(source) <= _sourceRank(previousSource)) {
      return;
    }
    _songLoudnessCache[hash] = volumeDb;
    _songLoudnessSourceCache[hash] = source;
    // 控制缓存规模：长期使用后按插入顺序淘汰最早的一半，避免无限膨胀。
    if (_songLoudnessCache.length > 800) {
      for (final key in _songLoudnessCache.keys.take(400).toList()) {
        _songLoudnessCache.remove(key);
        _songLoudnessSourceCache.remove(key);
      }
    }
    unawaited(() async {
      try {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString(
          _songLoudnessCacheKey,
          jsonEncode(_songLoudnessCache),
        );
        await prefs.setString(
          _songLoudnessSourceCacheKey,
          jsonEncode(_songLoudnessSourceCache.map(
            (k, v) => MapEntry(k, v.name),
          )),
        );
      } catch (_) {
        // 缓存写失败只影响下次能否预置，不影响本次播放。
      }
    }());
  }

  /// 记一个口径标定样本：`volume`（音源官方）− 端上整曲 RMS。
  void _recordCalibrationDelta(double delta) {
    if (!delta.isFinite || delta.abs() > 20) return;
    _calibrationDeltas.add(delta);
    if (_calibrationDeltas.length > _maxCalibrationSamples) {
      _calibrationDeltas.removeAt(0);
    }
    unawaited(() async {
      try {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString(
          _loudnessCalibrationDeltasKey,
          jsonEncode(_calibrationDeltas),
        );
      } catch (_) {
        // 标定样本写失败只影响自适应偏置，不影响本次播放。
      }
    }());
  }

  /// 切歌前结算上一曲的端上整曲 RMS（续十一）。
  ///
  /// 调用时机必须是 `playSong` 最开头：此时 `currentSong` 仍是旧歌、
  /// `duration` 仍是旧曲时长，结算值才能写进正确的 hash。原生累积独立于
  /// agcActive / agcLocked，锁定后与 fixed 模式都继续采样，所以覆盖整曲。
  ///
  /// 两条用途：
  /// 1) 该曲有音源实测 `volume` → 记 `volume − rms` 做口径标定；
  /// 2) 该曲完全没有元数据 → 用 `rms + 标定偏置` 估算并写缓存，
  ///    下次播放就能按实测预置，不再依赖曲库先验。
  Future<void> _settlePreviousTrackRms() async {
    final song = currentSong;
    if (song == null || song.hash.isEmpty || !isAudioEffectsSupported) return;
    final result = await _audioEffects.consumeTrackRms();
    if (result == null || !result.hasData) return;
    final rms = result.rmsDb!;
    lastTrackRmsDb = rms;
    lastTrackRmsSamples = result.samples;

    final effectiveSec = result.effectiveSeconds;
    final durationSec = duration.inMilliseconds / 1000.0;
    // 短窗口实测不可靠（见 _trackRmsMinDurationRatio 注释）：曲长未知或没播到
    // 85% 就丢弃，绝不把半首歌的偏值写进缓存。
    if (durationSec <= 0) return;
    if (effectiveSec < _trackRmsMinSeconds) return;
    if (effectiveSec < durationSec * _trackRmsMinDurationRatio) return;

    // 只要本曲电平来自音源官方（本次实测，或缓存里标注 url 的值）就能用于标定；
    // 端上实测缓存不再重复标定，否则 delta≈0 会把中位数稀释掉。
    // 实测多数重播会走 cache 而非 url，只认本次实测会让标定长期攒不够样本。
    final known = validLoudnessDb(_currentSongVolumeDb);
    final cachedSource = _songLoudnessSourceCache[song.hash];
    final sourceIsUrl = _currentSongLoudnessSource == SongLoudnessSource.url ||
        cachedSource == SongLoudnessSource.url ||
        (cachedSource == null && known != null); // 旧数据无来源表，按 url 兼容
    if (known != null && sourceIsUrl) {
      final delta = known - rms;
      _recordCalibrationDelta(delta);
      debugPrint(
        '[九九音乐][playback] loudness calibration delta=${delta.toStringAsFixed(2)} '
        'url=${known.toStringAsFixed(1)} deviceRms=${rms.toStringAsFixed(1)} '
        'samples=${result.samples} offset=${calibrationOffsetDb.toStringAsFixed(2)}',
      );
    } else if (known == null) {
      // 没有可信标定时不要写：此时 offset=0，端上读数与音源口径差十几 dB，
      // 写进去会让下次播放被推高十几 dB，比继续用曲库先验糟得多。
      if (!calibrationReady) {
        debugPrint(
          '[九九音乐][playback] loudness calibration not ready '
          'n=${_calibrationDeltas.length} -> keep library prior',
        );
        return;
      }
      final estimate = validLoudnessDb(rms + calibrationOffsetDb);
      if (estimate != null) {
        _rememberSongLoudness(song.hash, estimate, SongLoudnessSource.measured);
        debugPrint(
          '[九九音乐][playback] loudness cached from device rms=${rms.toStringAsFixed(1)} '
          'offset=${calibrationOffsetDb.toStringAsFixed(2)} '
          'estimate=${estimate.toStringAsFixed(1)} samples=${result.samples}',
        );
      }
    }
  }

  /// 本地命中（已下载 / 已缓存）时补拉一次音源响度元数据。
  ///
  /// 这类歌曲不走 `/song/url`，拿不到 `volume`，只能用曲库均值先验（准确度
  /// 不如实测）。这里只用返回的 `volume`，**不替换播放地址**，也不在歌内改动
  /// 增益（避免中途跳变），只为让这首歌下一次播放能直接精确预置。
  Future<void> _warmLoudnessMetadata(Song song) async {
    if (song.source != SongSource.kugou || song.isCloudDrive) return;
    if (song.hash.isEmpty) return;
    // 已有音源官方实测值就无需再等；端上实测 / 无缓存的歌才需要补拉，
    // 音源补齐后会自动覆盖为 url 来源（见 _rememberSongLoudness 的来源优先级）。
    final cachedSource = _songLoudnessSourceCache[song.hash];
    if (cachedSource == SongLoudnessSource.url) return;
    // 音源对新歌是小时~天级异步批处理，同一首歌 30 分钟内不重复请求。
    final last = _loudnessWarmLastAttempt[song.hash];
    final now = DateTime.now();
    if (last != null && now.difference(last) < _loudnessWarmRetryInterval) {
      return;
    }
    _loudnessWarmLastAttempt[song.hash] = now;
    try {
      final playUrl = await _api.songUrl(
        song,
        quality: audioQuality,
        hashOnly: _isPersonalFmActive,
      );
      final volume = validLoudnessDb(playUrl.volumeDb);
      if (volume != null) {
        _rememberSongLoudness(song.hash, volume, SongLoudnessSource.url);
        debugPrint(
          '[九九音乐][playback] loudness warmed hash=${song.hash} '
          'url:${volume.toStringAsFixed(1)}',
        );
      }
    } catch (_) {
      // 补拉失败不影响播放，下次播放会再试。
    }
  }

  /// 本曲整曲响度：优先用本次音源返回，其次用历史缓存。
  double? _songVolumeDb(Song song, PlayUrl? playUrl) {
    final fresh = validLoudnessDb(playUrl?.volumeDb);
    if (fresh != null) return fresh;
    return validLoudnessDb(_songLoudnessCache[song.hash]);
  }

  /// 切歌时采集当前曲的响度元数据快照（UI 展示用，不影响增益决策）。
  ///
  /// 与 [_songVolumeDb] 用同一套优先级：本次音源实测 > 历史缓存 > 无元数据。
  /// 同时记录 `volume_peak` 与来源，让设置页能区分「实测」和「曲库均值先验」。
  /// 两处调用点：正常切歌（playSong）与冷启动恢复（prepareRestoredSong）。
  void _captureSongLoudnessMeta(Song song, PlayUrl? playUrl) {
    final fresh = validLoudnessDb(playUrl?.volumeDb);
    _currentSongVolumeDb = _songVolumeDb(song, playUrl);
    if (fresh != null) {
      _currentSongVolumePeakDb = playUrl?.volumePeakDb;
      _currentSongLoudnessSource = SongLoudnessSource.url;
    } else if (_currentSongVolumeDb != null) {
      // 缓存里只有 volume，没有 peak。区分缓存值究竟来自音源还是端上整曲实测。
      _currentSongVolumePeakDb = null;
      final cached =
          _songLoudnessSourceCache[song.hash] ?? SongLoudnessSource.url;
      _currentSongLoudnessSource = cached == SongLoudnessSource.measured
          ? SongLoudnessSource.measured
          : SongLoudnessSource.cache;
    } else {
      _currentSongVolumePeakDb = null;
      _currentSongLoudnessSource = SongLoudnessSource.fallback;
    }
    debugPrint(
      '[九九音乐][playback] loudness meta '
      'source=${_currentSongLoudnessSource.name} '
      'volume=${_currentSongVolumeDb?.toStringAsFixed(1) ?? "none"} '
      'peak=${_currentSongVolumePeakDb?.toStringAsFixed(1) ?? "none"} '
      'hash=${song.hash} title=${song.title}',
    );
  }

  /// 设置页「实时增益」读取的当前曲响度元数据快照。
  ///
  /// [presetGainDb] 与 [effectiveVolumeDb] 直接复用增益链路的计算，保证 UI
  /// 上显示的推导和真正下发给原生的值完全一致（改档后也会即时反映）。
  SongLoudnessMeta get currentSongLoudnessMeta {
    final song = currentSong;
    final volume = validLoudnessDb(_currentSongVolumeDb);
    final target = autoLoudnessLevel.targetRmsDb;
    return SongLoudnessMeta(
      songTitle: song?.title ?? '—',
      artist: song?.artist ?? '—',
      hash: song?.hash ?? '',
      songSource: song?.source ?? SongSource.kugou,
      source: _currentSongLoudnessSource,
      volumeDb: volume,
      volumePeakDb: _currentSongVolumePeakDb,
      effectiveVolumeDb: volume ?? _fallbackSongVolumeDb,
      fallbackVolumeDb: _fallbackSongVolumeDb,
      targetRmsDb: target,
      presetGainDb: _presetGainForVolume(volume),
      calibrationOffsetDb: calibrationOffsetDb,
      calibrationSamples: calibrationSamples,
      calibrationReady: calibrationReady,
    );
  }

  /// 按整曲响度算预置增益：双向归一化 [−24,+15]，上限/下限与原生一致。
  /// 偏轻的歌补上来、偏响的歌压下去，所有歌都落在同一个目标响度上。
  ///
  /// 无元数据（本地文件、缓存播放、音源返回哨兵值 0 的新歌）时改用曲库
  /// 统计先验 [_fallbackSongVolumeDb] 反推一个中性增益并直接锁定：开场
  /// 就有合理电平，且整曲不跳变。续十二已删除开场测定回退路径。
  double _presetGainForVolume(double? volumeDb) {
    final effective = validLoudnessDb(volumeDb) ?? _fallbackSongVolumeDb;
    return (autoLoudnessLevel.targetRmsDb - effective)
        .clamp(-_agcPresetMinGainDb, _agcAutoMaxGainDb);
  }

  /// 端上整曲 RMS → 音源 `volume` 口径的标定偏置（dB）。
  ///
  /// 样本少于 3 首时返回 0，避免单首异常值把偏置带偏；之后取滑动中位数，
  /// 对个别脏数据（不同母带、音源口径波动）比均值稳健。
  double get calibrationOffsetDb {
    if (_calibrationDeltas.length < 3) return 0.0;
    final sorted = List<double>.from(_calibrationDeltas)..sort();
    final mid = sorted.length ~/ 2;
    return sorted.length.isOdd
        ? sorted[mid]
        : (sorted[mid - 1] + sorted[mid]) / 2;
  }

  int get calibrationSamples => _calibrationDeltas.length;

  /// 标定偏置是否可信：至少 3 个样本，且离散度（MAD）不超过 2.5 dB。
  /// 不可信时端上实测缓存一律不写——offset 还是 0 会把估算值整体带偏十几 dB。
  bool get calibrationReady {
    if (_calibrationDeltas.length < 3) return false;
    final sorted = List<double>.from(_calibrationDeltas)..sort();
    final mid = sorted.length ~/ 2;
    final median = sorted.length.isOdd
        ? sorted[mid]
        : (sorted[mid - 1] + sorted[mid]) / 2;
    final deviations = sorted.map((d) => (d - median).abs()).toList()..sort();
    return deviations[deviations.length ~/ 2] <= 2.5;
  }

  /// 当前曲的预置增益是否来自「曲库均值先验」而非任何实测值。
  /// 设置页据此如实区分状态文案，避免把估算说成实测。
  bool get presetGainIsEstimate =>
      validLoudnessDb(_currentSongVolumeDb) == null;

  /// 用当前曲的整曲响度重新预置增益（切歌 / 改档 / 切回 auto 共用）。
  ///
  /// 无论有无元数据都下发预置增益并锁定：有元数据按实测整曲电平算，
  /// 无元数据按曲库均值先验算。
  Future<void> _applyPresetGainForCurrentSong() async {
    if (!isAudioEffectsSupported) return;
    if (!volumeNormalizationEnabled ||
        volumeGainMode != VolumeGainMode.auto) {
      return;
    }
    final volume = validLoudnessDb(_currentSongVolumeDb);
    final preset = _presetGainForVolume(volume);
    debugPrint(
      '[九九音乐][playback] loudness preset=${preset.toStringAsFixed(1)} '
      'source=${volume == null ? "fallback:${_fallbackSongVolumeDb.toStringAsFixed(1)}" : "measured:${volume.toStringAsFixed(1)}"} '
      'target=${autoLoudnessLevel.targetRmsDb.toStringAsFixed(1)}',
    );
    await _audioEffects.resetAgcForTrackChange(
      presetGainDb: preset,
      // 与预置值配套下发：原生用它反推本曲整曲响度，改档时才能算对。
      targetRmsDb: autoLoudnessLevel.targetRmsDb,
    );
  }

  void _syncListeningTimeTracker() {
    final shouldTrack =
        addListeningTimeEnabled && isPlaying && currentSong != null;
    if (shouldTrack) {
      _listenTimeStartedAt ??= DateTime.now();
      _listenTimeTimer ??= Timer.periodic(
        _listenTimeCheckInterval,
        (_) => unawaited(_onListenTimeTick()),
      );
      return;
    }

    _pauseListeningTimeTracker();
  }

  /// 每 15 秒一次：独立累计本地统计，并按 5 分钟周期向服务端上报。
  Future<void> _onListenTimeTick() async {
    // 本地统计与本地上报互不影响：先落盘，再尝试上报。
    await _flushListenStats();
    await _maybeReportListeningTime();
  }

  void _pauseListeningTimeTracker() {
    final startedAt = _listenTimeStartedAt;
    if (startedAt != null) {
      _pendingListenTime += DateTime.now().difference(startedAt);
      _listenTimeStartedAt = null;
      SharedPreferences.getInstance()
          .then((prefs) {
            prefs.setInt(
              _pendingListenTimeKey,
              _pendingListenTime.inMilliseconds,
            );
          })
          .catchError((_) {});
      // 暂停/切歌时立刻把已听时长结进本地统计，避免只听了十几秒就退出
      // 导致这段时间完全没有被记录。
      unawaited(_flushListenStats());
    }
    _listenTimeTimer?.cancel();
    _listenTimeTimer = null;
  }

  /// 把尚未计入本地统计的听歌时长增量写入统计。
  ///
  /// 与服务端上报完全解耦：接口失败、未登录、离线播放都不影响本地统计。
  Future<void> _flushListenStats() async {
    final tracked = _trackedListeningTime();
    final delta = tracked - _lastStatsFlush;
    if (delta < const Duration(seconds: 1)) return;
    _lastStatsFlush = tracked;
    try {
      await _statsService.addListenTime(delta);
    } catch (_) {
      // 写盘失败时回退基准，下次继续尝试
      _lastStatsFlush -= delta;
    }
  }

  void _resetListeningTimeTracker() {
    _listenTimeStartedAt = null;
    _pendingListenTime = Duration.zero;
    _lastStatsFlush = Duration.zero;
    _listenTimeTimer?.cancel();
    _listenTimeTimer = null;
    SharedPreferences.getInstance()
        .then((prefs) {
          prefs.remove(_pendingListenTimeKey);
        })
        .catchError((_) {});
  }

  Duration _trackedListeningTime() {
    final startedAt = _listenTimeStartedAt;
    if (startedAt == null) {
      return _pendingListenTime;
    }
    return _pendingListenTime + DateTime.now().difference(startedAt);
  }

  Future<void> _maybeReportListeningTime() async {
    if (_isReportingListenTime || !addListeningTimeEnabled) {
      return;
    }
    if (_trackedListeningTime() < _listenTimeReportInterval) {
      return;
    }

    _isReportingListenTime = true;
    try {
      // 先把服务端上报前已累积的本地时长结清，避免上报成功后水位跳变
      // 让后续增量算成负数而丢失。
      await _flushListenStats();
      await _api.addListeningTime();
      // 本地统计已由 _flushListenStats 独立累计，这里只清算上报水位。
      final stillPlaying = isPlaying && currentSong != null;
      final remainder = _trackedListeningTime() - _listenTimeReportInterval;
      _pendingListenTime = remainder > Duration.zero
          ? remainder
          : Duration.zero;
      _listenTimeStartedAt = stillPlaying ? DateTime.now() : null;
      // 上报水位与本地统计水位一起归零对齐
      _lastStatsFlush = _pendingListenTime;
      if (!stillPlaying) {
        _listenTimeTimer?.cancel();
        _listenTimeTimer = null;
      }
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(_pendingListenTimeKey);
    } catch (error) {
      // 服务端失败不影响本地统计，只记录日志
      debugPrint('[九九音乐][listen-time] report failed: $error');
    } finally {
      _isReportingListenTime = false;
    }
  }

  void _schedulePlayCache(Song song, AudioQuality quality, String networkUrl) {
    _playCacheTimer?.cancel();
    _playCacheTimer = Timer(const Duration(seconds: 15), () {
      if (currentSong?.hash != song.hash || !isPlaying) {
        return;
      }
      unawaited(
        downloadController?.cacheForPlayback(song, quality, networkUrl),
      );
    });
  }

  Song? _previousSong() {
    if (queue.isEmpty) {
      return null;
    }

    final index = currentIndex;
    if (_isPersonalFmActive) {
      if (index > 0) {
        return queue[index - 1];
      }
      return null;
    }
    if (playbackMode == PlaybackMode.shuffle) {
      final currentIdentity = currentSong == null
          ? null
          : _songIdentity(currentSong!);
      final prevIdentity = _shuffleCycle.previous(
        queue.map(_songIdentity).where((identity) => identity.isNotEmpty),
        current: currentIdentity,
      );
      if (prevIdentity == null) return null;
      return queue.firstWhere(
        (song) => _songIdentity(song) == prevIdentity,
        orElse: () => currentSong ?? queue.first,
      );
    }

    if (index > 0) {
      return queue[index - 1];
    }

    if (queue.length > 1) {
      return queue.last;
    }
    return null;
  }

  Song? _nextSong() {
    if (queue.isEmpty) {
      return currentSong;
    }

    final index = currentIndex;
    if (_isPersonalFmActive) {
      if (index >= 0 && index < queue.length - 1) {
        return queue[index + 1];
      }
      return null;
    }
    if (playbackMode == PlaybackMode.shuffle) {
      final currentIdentity = currentSong == null
          ? null
          : _songIdentity(currentSong!);
      final nextIdentity = _shuffleCycle.next(
        queue.map(_songIdentity).where((identity) => identity.isNotEmpty),
        current: currentIdentity,
      );
      if (nextIdentity == null) return currentSong;
      return queue.firstWhere(
        (song) => _songIdentity(song) == nextIdentity,
        orElse: () => currentSong ?? queue.first,
      );
    }

    if (index >= 0 && index < queue.length - 1) {
      return queue[index + 1];
    }

    // index == -1 说明当前歌曲不在队列里（重启后快照残缺、外部入口切歌等）。
    // 此时不能直接返回 queue.first：若它正好就是当前歌曲，「下一首」会永远
    // 原地重播同一首，表现为完全无法切歌。改为挑一首不同的歌。
    if (index < 0 && queue.length > 1) {
      final currentIdentity = currentSong == null
          ? ''
          : _songIdentity(currentSong!);
      final different = queue.firstWhere(
        (song) => _songIdentity(song) != currentIdentity,
        orElse: () => queue.first,
      );
      return different;
    }

    return queue.first;
  }

  bool get _isAtQueueEnd {
    final index = currentIndex;
    return index >= 0 && index == queue.length - 1;
  }

  Future<void> _loadNextPersonalFmBatch({required bool isOverplay}) async {
    final song = currentSong;
    if (!_isPersonalFmActive || song == null || !_isAtQueueEnd) {
      return;
    }
    if (_isLoadingPersonalFm) {
      return;
    }

    _isLoadingPersonalFm = true;
    final requestSerial = ++_personalFmRequestSerial;
    final intentSerial = _playbackIntentSerial;
    errorMessage = null;
    notifyListeners();
    try {
      var incoming = await _api.personalFm(
        hash: song.hash.isEmpty ? null : song.hash,
        songId: song.id.isEmpty ? null : song.id,
        playtime: smoothPosition.inSeconds,
        isOverplay: isOverplay,
      );
      if (requestSerial != _personalFmRequestSerial ||
          intentSerial != _playbackIntentSerial) {
        return;
      }
      if (!_isPersonalFmActive || currentSong != song || !_isAtQueueEnd) {
        return;
      }

      var additions = _personalFmAdditions(incoming, song);
      if (additions.isEmpty) {
        incoming = await _api.personalFm();
        if (requestSerial != _personalFmRequestSerial ||
            intentSerial != _playbackIntentSerial ||
            !_isPersonalFmActive ||
            currentSong != song ||
            !_isAtQueueEnd) {
          return;
        }
        additions = _personalFmAdditions(incoming, song);
      }
      if (additions.isEmpty) {
        errorMessage = '私人 FM 暂时没有新的推荐歌曲';
        return;
      }

      queue = [...queue, ...additions];
      await playSong(
        additions.first,
        queue: queue,
        preservePersonalFmSession: true,
        userInitiated: false,
      );
    } catch (error) {
      if (requestSerial == _personalFmRequestSerial) {
        errorMessage = error.toString();
      }
    } finally {
      if (requestSerial == _personalFmRequestSerial) {
        _isLoadingPersonalFm = false;
        notifyListeners();
      }
    }
  }

  String _songIdentity(Song song) {
    if (song.hash.isNotEmpty) {
      return 'hash:${song.hash}';
    }
    if (song.albumAudioId?.isNotEmpty == true) {
      return 'audio:${song.albumAudioId}';
    }
    return song.id.isEmpty ? '' : 'id:${song.id}';
  }

  bool _isSameQueue(List<Song> a, List<Song> b) {
    if (identical(a, b)) return true;
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (_songIdentity(a[i]) != _songIdentity(b[i])) return false;
    }
    return true;
  }

  List<Song> _personalFmAdditions(List<Song> incoming, Song current) {
    final currentIdentity = _songIdentity(current);
    return incoming
        .where((song) {
          final identity = _songIdentity(song);
          return identity.isNotEmpty && identity != currentIdentity;
        })
        .take(5)
        .toList();
  }

  @override
  void dispose() {
    // 让仍在解析播放地址或等待切换音源的请求立即失效，避免销毁后继续更新状态。
    _playRequestSerial++;
    _cancelAutomaticResume();
    _pauseListeningTimeTracker();
    _stopPositionSaving();
    _queueSaveTimer?.cancel();
    _playCacheTimer?.cancel();
    _bluetoothAnnounceTimer?.cancel();
    _autoResumeTimer?.cancel();
    _sleepTimer?.cancel();
    _positionSub.cancel();
    _durationSub.cancel();
    _stateSub.cancel();
    _processingStateSub.cancel();
    _androidAudioSessionSub.cancel();
    _interruptionSub?.cancel();
    _becomingNoisySub?.cancel();
    _devicesSub?.cancel();
    _completionFallbackTimer?.cancel();
    _stallWatchdogTimer?.cancel();
    _recoveryRetryTimer?.cancel();
    _playerErrorSub?.cancel();
    unawaited(
      _audioEffects.configureEqualizer(
        audioSessionId:
            _androidAudioSessionId ?? audioPlayer.androidAudioSessionId,
        enabled: false,
        levels: equalizerLevels,
      ),
    );
    unawaited(
      _audioEffects.configureBassBoost(
        audioSessionId:
            _androidAudioSessionId ?? audioPlayer.androidAudioSessionId,
        enabled: false,
        strength: bassBoostStrength,
      ),
    );
    unawaited(
      _audioEffects.configureVolumeNormalization(
        audioSessionId:
            _androidAudioSessionId ?? audioPlayer.androidAudioSessionId,
        enabled: false,
        gainDb: volumeGainDb,
      ),
    );
    _audioHandler.detachTransportControls();
    _desktopLyrics.setVisibilityChangedHandler(null);
    unawaited(_audioHandler.close());
    unawaited(_desktopLyrics.hide());
    positionNotifier.dispose();
    super.dispose();
  }

  void _setPositionBase(Duration value, {required bool playing}) {
    final clamped = _clampPosition(value);
    position = clamped;
    positionNotifier.value = clamped;
    _positionClock
      ..stop()
      ..reset();
    if (playing) {
      _positionClock.start();
    }
  }

  Duration _clampPosition(Duration value) {
    if (value < Duration.zero) {
      return Duration.zero;
    }
    if (duration > Duration.zero && value > duration) {
      return duration;
    }
    return value;
  }

  List<int> _levelsForBandCount(List<int> source, int count) {
    if (count <= 0) {
      return const [];
    }
    if (source.length == count) {
      return List<int>.of(source);
    }
    if (source.length == 1) {
      return List<int>.filled(count, source.first);
    }

    return [
      for (var index = 0; index < count; index++)
        source[((index / math.max(1, count - 1)) * (source.length - 1))
            .round()],
    ];
  }

  // ===== 播放队列持久化 =====

  /// 保存播放队列状态到本地存储（防抖 500ms）。
  void _saveQueueState() {
    _queueSaveTimer?.cancel();
    _queueSaveTimer = Timer(
      const Duration(milliseconds: _queueSaveDebounceMs),
      () => _persistQueueState(),
    );
  }

  /// 立即持久化播放队列、当前歌曲和播放模式。
  ///
  /// 队列、当前歌曲、播放模式必须作为**同一个 JSON 快照**写入：
  /// SharedPreferences 的多次写入不是原子的，一旦进程在中间被杀，
  /// 重启后就会出现「当前歌曲存在但队列丢了」的残缺状态，表现为
  /// 无法在歌单内切歌（下一首永远是它自己），必须重新进歌单点播。
  Future<void> _persistQueueState() async {
    final prefs = await SharedPreferences.getInstance();
    final song = currentSong;
    final snapshot = jsonEncode({
      'mode': playbackMode.name,
      'currentSong': song?.toCache(),
      'queue': queue.map((s) => s.toCache()).toList(),
      if (_queueOrigin != null) 'origin': _queueOrigin!.toJson(),
    });
    // 先写快照（单次原子写入），再更新进度。
    await prefs.setString(_stateKey, snapshot);
    // 进度单独保存：高频更新时无需重写整个队列。
    // 恢复音源尚未加载时不能用播放器的 0 位置覆盖磁盘里待恢复的进度。
    final record = _buildPositionRecord();
    if (record != null) {
      await prefs.setString(_currentPositionKey, record);
    } else if (song == null && _audioHandler.loadedSong == null) {
      // 确实没有任何歌曲时才清除记录；有当前歌曲但音源未加载时保持原值，
      // 否则退到后台会把磁盘上待恢复的进度（如 120 秒）抹成 0。
      await prefs.remove(_currentPositionKey);
    }
    // 旧版本的分散字段不再写入：歌单动辄上千首，写多份副本会显著拖慢
    // 主线程外的序列化，也更容易在写入中途被杀导致状态残缺。
  }

  /// 启动定时保存播放进度（每 5 秒）。
  void _startPositionSaving() {
    _positionSaveTimer?.cancel();
    _positionSaveTimer = Timer.periodic(_positionSaveInterval, (_) {
      _saveCurrentPosition();
    });
  }

  /// 保存当前播放进度。
  ///
  /// 进度必须与歌曲绑定（hash + 毫秒）一起存：只存毫秒的话，切歌与写盘
  /// 交错时会把这个位置错配到下一首歌上，重进软件就跳到错误位置。
  void _saveCurrentPosition() {
    final payload = _buildPositionRecord();
    if (payload == null) return;
    SharedPreferences.getInstance().then((prefs) async {
      await prefs.setString(_currentPositionKey, payload);
    });
  }

  /// 生成要落盘的进度记录；null 表示保持磁盘原值不改写。
  String? _buildPositionRecord() {
    final loaded = _audioHandler.loadedSong;
    final loadedHash = loaded?.hash.isNotEmpty == true ? loaded!.hash : null;
    return resolvePositionRecord(
      currentSongHash: currentSong?.hash,
      loadedSongHash: loadedHash,
      playerPosition: audioPlayer.position,
      pendingResume: _resumePosition,
    );
  }

  /// 读取磁盘上保存的播放进度，返回 null 表示没有可用进度。
  Duration? _readSavedPosition(SharedPreferences prefs, String? songHash) {
    return parseSavedPosition(prefs.get(_currentPositionKey), songHash);
  }

  /// 停止定时保存播放进度。
  void _stopPositionSaving() {
    _positionSaveTimer?.cancel();
    _positionSaveTimer = null;
  }

  /// 同步保存当前播放状态（用于 dispose 时紧急保存）。
  void persistCurrentStateSync() {
    _persistQueueState();
    _saveCurrentPosition();
  }

  /// 用服务端「继续播放」列表填充队列并定位到第一首（不自动播放）。
  void restoreFromServerQueue(List<Song> songs) {
    if (songs.isEmpty || currentSong != null) return;
    queue = List<Song>.of(songs);
    currentSong = songs.first;
    position = Duration.zero;
    positionNotifier.value = Duration.zero;
    // 服务端「继续播放」不提供精确到毫秒的进度，清掉本地待恢复值，
    // 避免把上一次本地记录的进度套到这首歌上。
    _resumePosition = null;
    notifyListeners();
  }

  /// 从本地存储恢复播放队列、当前歌曲和播放进度。
  Future<bool> restoreQueueState() async {
    final requestSerial = _playRequestSerial;
    final prefs = await SharedPreferences.getInstance();
    if (requestSerial != _playRequestSerial) return false;

    String? modeName;
    List<Song> restoredQueue = const [];
    Song? restoredSong;
    QueueOrigin? restoredOrigin;

    // 优先读快照：队列/当前歌曲/模式是同一次写入，不会出现残缺组合。
    final snapshotJson = prefs.getString(_stateKey);
    if (snapshotJson != null && snapshotJson.isNotEmpty) {
      try {
        final decoded = jsonDecode(snapshotJson);
        if (decoded is Map<String, dynamic>) {
          modeName = asString(decoded['mode']);
          restoredQueue = asList(decoded['queue'])
              .whereType<Map<String, dynamic>>()
              .map(Song.fromCache)
              .where((s) => s.hash.isNotEmpty)
              .toList();
          restoredOrigin = QueueOrigin.fromJson(decoded['origin']);
          final songJson = decoded['currentSong'];
          if (songJson is Map<String, dynamic>) {
            restoredSong = Song.fromCache(songJson);
          }
        }
      } catch (_) {}
    }

    // 兼容旧版本的分散字段（升级后首次启动）。
    if (modeName == null) {
      modeName = prefs.getString(_playbackModeKey);
    }
    if (restoredQueue.isEmpty) {
      final queueJson = prefs.getString(_queueKey);
      if (queueJson != null && queueJson.isNotEmpty) {
        try {
          final decoded = jsonDecode(queueJson);
          if (decoded is List) {
            restoredQueue = decoded
                .whereType<Map<String, dynamic>>()
                .map(Song.fromCache)
                .where((s) => s.hash.isNotEmpty)
                .toList();
          }
        } catch (_) {}
      }
    }
    if (restoredSong == null) {
      final songJson = prefs.getString(_currentSongKey);
      if (songJson != null && songJson.isNotEmpty) {
        try {
          final decoded = jsonDecode(songJson);
          if (decoded is Map<String, dynamic>) {
            restoredSong = Song.fromCache(decoded);
          }
        } catch (_) {}
      }
    }

    // 恢复播放模式
    if (modeName != null) {
      for (final mode in PlaybackMode.values) {
        if (mode.name == modeName) {
          playbackMode = mode;
          break;
        }
      }
    }

    // 恢复当前歌曲：队列里找不到时补进去，否则 currentIndex 为 -1，
    // 「下一首」会一直返回同一首，看起来就是无法切歌。
    if (restoredSong != null && restoredSong.hash.isNotEmpty) {
      final identity = _songIdentity(restoredSong);
      if (restoredQueue.isNotEmpty &&
          !restoredQueue.any((item) => _songIdentity(item) == identity)) {
        restoredQueue = List<Song>.of(restoredQueue)..add(restoredSong);
      }
      queue = restoredQueue.isEmpty
          ? [restoredSong]
          : List<Song>.of(restoredQueue);
      currentSong = restoredSong;
    } else if (restoredQueue.isNotEmpty) {
      queue = List<Song>.of(restoredQueue);
      currentSong = restoredQueue.first;
    }

    // 恢复播放进度：只接受与当前歌曲 hash 匹配的记录。
    // 旧版本只存毫秒值（int），升级后仍兼容读取。
    final savedPosition = _readSavedPosition(prefs, currentSong?.hash);
    if (savedPosition != null && savedPosition > Duration.zero) {
      position = savedPosition;
      positionNotifier.value = position;
      _resumePosition = savedPosition;
    } else {
      position = Duration.zero;
      positionNotifier.value = Duration.zero;
      _resumePosition = null;
    }

    _queueOrigin = restoredOrigin;
    notifyListeners();

    // 队列残缺（只剩一首或为空）时按来源补全，保证能继续切歌。
    // 放在 notifyListeners 之后：界面先用已恢复内容渲染，补全后台完成。
    if (restoredOrigin != null && queue.length <= 1) {
      unawaited(_rebuildQueueFromOrigin());
    }
    return true;
  }

  /// 按记忆的队列来源重新拉取完整歌单/专辑，并用它替换当前队列。
  ///
  /// 只在队列明显残缺时触发；当前歌曲不在新队列里就补到队尾，
  /// 避免 currentIndex 变成 -1 导致「下一首」原地不动。
  Future<void> _rebuildQueueFromOrigin() async {
    final origin = _queueOrigin;
    final song = currentSong;
    if (origin == null || song == null) return;
    final rebuildSerial = ++_queueRebuildSerial;
    // 记录发起重建时的队列快照：网络返回期间用户可能用「下一首播放」
    // 插入了新歌，不能直接用来源歌单把它们覆盖掉。
    final originalQueue = List<Song>.of(queue);
    try {
      final songs = await _fetchOriginSongs(origin);
      // 重建只跟「队列来源/当前歌曲」有关，与播放请求序号无关：
      // 恢复预加载会自增 _playRequestSerial，不能因此丢弃补全结果。
      if (rebuildSerial != _queueRebuildSerial ||
          _queueOrigin != origin ||
          currentSong != song ||
          songs.isEmpty) {
        return;
      }
      final identity = _songIdentity(song);
      final rebuilt = List<Song>.of(songs);
      if (!rebuilt.any((item) => _songIdentity(item) == identity)) {
        rebuilt.add(song);
      }
      queue = mergeRebuiltQueue(
        originalQueue: originalQueue,
        currentQueue: queue,
        rebuilt: rebuilt,
        currentSong: song,
      );
      if (playbackMode == PlaybackMode.shuffle) {
        _shuffleCycle.reset(
          queue.map(_songIdentity).where((identity) => identity.isNotEmpty),
          current: identity,
        );
      }
      await _audioHandler.setSongQueue(
        queueSongs: queue,
        queueIndex: currentIndex,
        currentSong: song,
      );
      _saveQueueState();
      notifyListeners();
    } catch (_) {
      // 重建失败保持已恢复的队列，用户重新点歌单也能恢复
    }
  }

  /// 按队列来源拉取完整歌曲列表（歌单 / 专辑 / 排行榜）。
  Future<List<Song>> _fetchOriginSongs(QueueOrigin origin) async {
    if (origin.isRank) {
      final page = await _api.rankSongs(origin.id, pageSize: 500);
      return page.songs;
    }
    return origin.isAlbum
        ? _api.albumSongs(origin.id, pageSize: 5000)
        : _api.playlistSongs(origin.id, fetchAll: true);
  }

  /// 在恢复队列后加载并准备播放当前歌曲（默认不自动播放，仅预加载）。
  ///
  /// [autoPlay] 为 true 时（“恢复上次播放列表”开机模式）加载完成后立即播放。
  Future<void> prepareRestoredSong({
    bool autoPlay = false,
    Song? expectedSong,
  }) async {
    final song = expectedSong ?? currentSong;
    if (song == null ||
        (expectedSong != null && !identical(currentSong, expectedSong))) {
      return;
    }
    final requestSerial = ++_playRequestSerial;
    final intentSerial = _playbackIntentSerial;
    if (autoPlay) _pendingPlaybackIntent = intentSerial;
    debugPrint(
      '[九九音乐][playback] restore-request=$requestSerial begin '
      'autoPlay=$autoPlay',
    );
    climax = null;
    unawaited(_loadClimax(song));

    // 在加载前保存恢复的进度（loadSong 会重置 position 为 0）。
    // 优先用磁盘上待应用的进度，其次才是内存中的 position。
    final restoredPosition = _resumePosition ?? position;

    isPreparing = true;
    notifyListeners();

    try {
      String url;
      PlayUrl? resolvedPlayUrl;
      final local = await downloadController?.localPathFor(song, audioQuality);
      if (requestSerial != _playRequestSerial) return;
      if (local != null) {
        url = local;
        unawaited(_warmLoudnessMetadata(song));
      } else if (song.source == SongSource.local) {
        url = song.id;
      } else {
        final playUrl = await _resolvePlayUrl(song);
        resolvedPlayUrl = playUrl;
        if (requestSerial != _playRequestSerial) return;
        if (playUrl.url.isEmpty) return;
        url = playUrl.url;
      }

      // 恢复音源与用户主动点歌共用同一串行队列。覆盖安装后，
      // 此预加载可能与“播放全部”同时发生；旧恢复请求一旦失效，
      // 不得再覆盖用户新选择的音源或播放状态。
      await _serializeSourceChange(() async {
        if (requestSerial != _playRequestSerial) return;
        await _audioHandler.loadSong(
          song: song,
          url: url,
          queueSongs: queue,
          queueIndex: currentIndex,
        );
        if (requestSerial != _playRequestSerial) return;

        // 跳转到保存的进度
        if (restoredPosition > Duration.zero) {
          await _audioHandler.seek(_clampPosition(restoredPosition));
          if (requestSerial != _playRequestSerial) return;
          _setPositionBase(_clampPosition(restoredPosition), playing: false);
        }
        // 进度已应用到音频引擎，清掉待恢复标记，避免后续再次跳转
        _resumePosition = null;

        unawaited(loadLyrics(song));
        _startPositionSaving();
        // 冷启动恢复时同样按整曲响度预置：此时音效服务可能刚起、
        // 或上一次 configure 已把增益归零，重新落一次才能保证开场就准确。
        _captureSongLoudnessMeta(song, resolvedPlayUrl);
        // 音源未给实测值时后台等它补齐（新歌批处理常要数小时~一天），
        // 补上后自动升级为官方值；内部有 30 分钟退避，不会每次切歌都打接口。
        unawaited(_warmLoudnessMetadata(song));
        await _applyPresetGainForCurrentSong();
        if (autoPlay) {
          await _startPlayback(expectedIntent: intentSerial);
          if (requestSerial != _playRequestSerial) return;
          // 记录播放历史与本地播放统计（后台执行，不阻塞播放）
          unawaited(_historyService.record(song));
          unawaited(_statsService.recordPlay(song));
        }
      });
    } catch (e) {
      if (requestSerial == _playRequestSerial) {
        debugPrint('[九九音乐] Failed to prepare restored song: $e');
      }
    } finally {
      if (requestSerial == _playRequestSerial) {
        if (_pendingPlaybackIntent == intentSerial) {
          _pendingPlaybackIntent = null;
        }
        isPreparing = false;
        notifyListeners();
      }
    }
  }
}
