import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class EqualizerBand {
  const EqualizerBand({required this.centerHz, required this.levelMillibels});

  final int centerHz;
  final int levelMillibels;

  factory EqualizerBand.fromMap(Map<Object?, Object?> map) {
    return EqualizerBand(
      centerHz: (map['centerHz'] as num?)?.round() ?? 0,
      levelMillibels: (map['level'] as num?)?.round() ?? 0,
    );
  }
}

class EqualizerConfig {
  const EqualizerConfig({
    required this.minMillibels,
    required this.maxMillibels,
    required this.bands,
  });

  final int minMillibels;
  final int maxMillibels;
  final List<EqualizerBand> bands;

  factory EqualizerConfig.fallback(List<int> levels) {
    const centers = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
    return EqualizerConfig(
      minMillibels: -1200,
      maxMillibels: 1200,
      bands: [
        for (var index = 0; index < centers.length; index++)
          EqualizerBand(
            centerHz: centers[index],
            levelMillibels: index < levels.length ? levels[index] : 0,
          ),
      ],
    );
  }

  factory EqualizerConfig.fromMap(Map<Object?, Object?> map) {
    final range = (map['range'] as List<Object?>?) ?? const [-1200, 1200];
    final rawBands = (map['bands'] as List<Object?>?) ?? const [];
    return EqualizerConfig(
      minMillibels: range.isNotEmpty
          ? ((range[0] as num?)?.round() ?? -1200)
          : -1200,
      maxMillibels: range.length > 1
          ? ((range[1] as num?)?.round() ?? 1200)
          : 1200,
      bands: rawBands
          .whereType<Map<Object?, Object?>>()
          .map(EqualizerBand.fromMap)
          .toList(growable: false),
    );
  }
}

class AudioEffectsService {
  static const _channel = MethodChannel('kgka_music_hl/audio_effects');

  bool get isAudioEffectsSupported {
    return !kIsWeb && defaultTargetPlatform == TargetPlatform.android;
  }

  bool get isBassBoostSupported => isAudioEffectsSupported;

  Future<EqualizerConfig?> equalizerConfig({
    required int? audioSessionId,
  }) async {
    if (!isAudioEffectsSupported) {
      return null;
    }

    try {
      final result = await _channel.invokeMethod<Map<Object?, Object?>>(
        'getEqualizerConfig',
        {'audioSessionId': audioSessionId},
      );
      if (result == null) {
        return null;
      }
      return EqualizerConfig.fromMap(result);
    } on PlatformException catch (error) {
      debugPrint('[九九音乐][audio-effects] Equalizer config failed: $error');
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  Future<bool> configureEqualizer({
    required int? audioSessionId,
    required bool enabled,
    required List<int> levels,
  }) async {
    if (!isAudioEffectsSupported) {
      return false;
    }

    try {
      final result = await _channel.invokeMethod<bool>('configureEqualizer', {
        'audioSessionId': audioSessionId,
        'enabled': enabled,
        'levels': levels,
      });
      return result ?? false;
    } on PlatformException catch (error) {
      debugPrint('[九九音乐][audio-effects] Equalizer failed: $error');
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  Future<bool> configureBassBoost({
    required int? audioSessionId,
    required bool enabled,
    required double strength,
  }) async {
    if (!isBassBoostSupported) {
      return false;
    }

    final normalizedStrength = strength.clamp(0.0, 1.0);
    try {
      final result = await _channel.invokeMethod<bool>('configureBassBoost', {
        'audioSessionId': audioSessionId,
        'enabled': enabled,
        'strength': (normalizedStrength * 1000).round(),
      });
      return result ?? false;
    } on PlatformException catch (error) {
      debugPrint('[九九音乐][audio-effects] BassBoost failed: $error');
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  Future<bool> configureVolumeNormalization({
    required int? audioSessionId,
    required bool enabled,
    double gainDb = 3.0,
    bool autoGain = false,
    double targetRmsDb = -12.0,
  }) async {
    if (!isAudioEffectsSupported) {
      return false;
    }

    try {
      final result = await _channel.invokeMethod<bool>('configureVolumeNormalization', {
        'audioSessionId': audioSessionId,
        'enabled': enabled,
        'gainDb': gainDb,
        'autoGain': autoGain,
        'targetRmsDb': targetRmsDb,
      });
      return result ?? false;
    } on PlatformException catch (error) {
      debugPrint('[九九音乐][audio-effects] VolumeNormalization failed: $error');
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// 只更新 AGC 目标响度，不重建效果器 / Visualizer。
  /// 设置页切换响度档位走这里，避免重建音频效果器阻塞主线程造成菜单假死。
  Future<bool> setAgcTarget(double targetRmsDb) async {
    if (!isAudioEffectsSupported) {
      return false;
    }
    try {
      final result = await _channel.invokeMethod<bool>('setAgcTarget', {
        'targetRmsDb': targetRmsDb,
      });
      return result ?? false;
    } on PlatformException catch (error) {
      debugPrint('[九九音乐][audio-effects] setAgcTarget failed: $error');
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// 轻量切换 auto/fixed 策略：原生只翻 agcActive 开关，不重建效果器 / Visualizer。
  /// 用于设置页切换「音量均衡策略」，避免 binder 同步重建造成卡顿。
  Future<bool> setAgcActive({
    required bool active,
    double gainDb = 3.0,
  }) async {
    if (!isAudioEffectsSupported) {
      return false;
    }
    try {
      final result = await _channel.invokeMethod<bool>('setAgcActive', {
        'active': active,
        'gainDb': gainDb,
      });
      return result ?? false;
    } on PlatformException catch (error) {
      debugPrint('[九九音乐][audio-effects] setAgcActive failed: $error');
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// 切歌归位：在下一首真正出声之前同步 await 调用，把原生 AGC 的 postGain
  /// 立即写成本曲预置增益并锁定，消除「轻歌高增益被带到响歌」的爆音窗口。
  /// 原生侧只写一次 postGain + 重置内存状态，不重建效果器 / Visualizer，不会卡顿。
  ///
  /// [presetGainDb] 由上层按整曲响度算好：有音源实测 `volume` 时按实测值，
  /// 无元数据（新歌 `volume=0` / 本地文件 / 缓存播放）时按曲库均值先验。
  /// 续十二已删除开场 4.8 秒快测回退路径，此值必填。
  /// 可以为负：偏响的歌靠负增益压到目标响度，实现「所有歌一样响」。
  ///
  /// [targetRmsDb] 与 [presetGainDb] 配套下发：原生要用 target - gain 反推整曲
  /// 响度作为改档重算基准，缺了它冷启动/改档后会按默认档位算错。
  Future<bool> resetAgcForTrackChange({
    required double presetGainDb,
    double? targetRmsDb,
  }) async {
    if (!isAudioEffectsSupported) {
      return false;
    }
    try {
      final result = await _channel.invokeMethod<bool>('resetAgcForTrackChange', {
        'presetGainDb': presetGainDb,
        'targetRmsDb': targetRmsDb,
      });
      return result ?? false;
    } on PlatformException catch (error) {
      debugPrint('[九九音乐][audio-effects] resetAgcForTrackChange failed: $error');
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// 取走并清空「上一曲」的端上整曲 RMS 累积（续十一）。
  ///
  /// 由 [PlayerController] 在切歌最早期调用：此时 currentSong 仍是旧歌，结算值
  /// 可以写进它的响度缓存。原生读数不含 postGain，所以 rmsDb 就是原始素材电平；
  /// 上层还要按音源 `volume` 做一次口径标定偏置修正再落盘。
  Future<TrackRmsResult?> consumeTrackRms() async {
    if (!isAudioEffectsSupported) {
      return null;
    }
    try {
      final result =
          await _channel.invokeMethod<Map<Object?, Object?>>('consumeTrackRms');
      if (result == null) {
        return null;
      }
      return TrackRmsResult(
        rmsDb: (result['rmsDb'] as num?)?.toDouble(),
        samples: (result['samples'] as num?)?.toInt() ?? 0,
      );
    } on PlatformException catch (error) {
      debugPrint('[九九音乐][audio-effects] consumeTrackRms failed: $error');
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  /// 查询 AGC 实时状态（是否激活、当前增益、测得响度、是否锁定）。
  /// 仅 Android 且音量均衡开启时有效；不支持时返回 null。
  Future<AgcStatus?> getAgcStatus() async {
    if (!isAudioEffectsSupported) {
      return null;
    }
    try {
      final result = await _channel.invokeMethod<Map<Object?, Object?>>('getAgcStatus');
      if (result == null) {
        return null;
      }
      return AgcStatus.fromMap(result);
    } on PlatformException catch (error) {
      debugPrint('[九九音乐][audio-effects] getAgcStatus failed: $error');
      return null;
    } on MissingPluginException {
      return null;
    }
  }
}

/// 原生端「上一曲」整曲 RMS 累积的结算结果（续十一）。
///
/// [rmsDb] 是线性功率域平均后转回的 dBFS；[samples] 是非静音 400ms 采样数，
/// 用于判断这首歌是否播够了足够长的代表性片段，避免半首歌的偏值写进缓存。
class TrackRmsResult {
  const TrackRmsResult({required this.rmsDb, required this.samples});

  final double? rmsDb;
  final int samples;

  /// 与原生 AGC_SAMPLE_INTERVAL_MS = 400ms 对齐。
  double get effectiveSeconds => samples * 0.4;

  bool get hasData => rmsDb != null && rmsDb!.isFinite && samples > 0;
}

/// 实时响度 AGC 的快照，用于设置页展示「当前增益 / 测得响度」。
class AgcStatus {
  const AgcStatus({
    required this.active,
    required this.gainDb,
    required this.measuredRmsDb,
    required this.targetRmsDb,
    required this.locked,
  });

  final bool active;
  final double gainDb;
  final double measuredRmsDb;
  final double targetRmsDb;
  final bool locked;

  /// 模式切换时同步更新快照用（如立即把 active 置 true），避免 UI 显示旧状态。
  AgcStatus copyWith({
    bool? active,
    double? gainDb,
    double? measuredRmsDb,
    double? targetRmsDb,
    bool? locked,
  }) {
    return AgcStatus(
      active: active ?? this.active,
      gainDb: gainDb ?? this.gainDb,
      measuredRmsDb: measuredRmsDb ?? this.measuredRmsDb,
      targetRmsDb: targetRmsDb ?? this.targetRmsDb,
      locked: locked ?? this.locked,
    );
  }

  factory AgcStatus.fromMap(Map<Object?, Object?> map) {
    return AgcStatus(
      active: map['active'] as bool? ?? false,
      gainDb: (map['gainDb'] as num?)?.toDouble() ?? 0.0,
      measuredRmsDb: (map['measuredRmsDb'] as num?)?.toDouble() ?? 0.0,
      targetRmsDb: (map['targetRmsDb'] as num?)?.toDouble() ?? -12.0,
      locked: map['locked'] as bool? ?? false,
    );
  }
}
