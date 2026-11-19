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
      debugPrint('[KA Music][audio-effects] Equalizer config failed: $error');
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
      debugPrint('[KA Music][audio-effects] Equalizer failed: $error');
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
      debugPrint('[KA Music][audio-effects] BassBoost failed: $error');
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
    double targetRmsDb = -16.0,
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
      debugPrint('[KA Music][audio-effects] VolumeNormalization failed: $error');
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
      debugPrint('[KA Music][audio-effects] setAgcTarget failed: $error');
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
      debugPrint('[KA Music][audio-effects] setAgcActive failed: $error');
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// 切歌归位：在下一首真正出声之前同步 await 调用，把原生 AGC 的 postGain
  /// 立即打回基准档位并清掉上一首的收敛状态，消除「轻歌高增益被带到响歌」的爆音窗口。
  /// 原生侧只写一次 postGain + 清内存状态，不重建效果器 / Visualizer，不会卡顿。
  Future<bool> resetAgcForTrackChange({double gainDb = 3.0}) async {
    if (!isAudioEffectsSupported) {
      return false;
    }
    try {
      final result = await _channel.invokeMethod<bool>('resetAgcForTrackChange', {
        'gainDb': gainDb,
      });
      return result ?? false;
    } on PlatformException catch (error) {
      debugPrint('[KA Music][audio-effects] resetAgcForTrackChange failed: $error');
      return false;
    } on MissingPluginException {
      return false;
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
      debugPrint('[KA Music][audio-effects] getAgcStatus failed: $error');
      return null;
    } on MissingPluginException {
      return null;
    }
  }
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
      targetRmsDb: (map['targetRmsDb'] as num?)?.toDouble() ?? -16.0,
      locked: map['locked'] as bool? ?? false,
    );
  }
}
