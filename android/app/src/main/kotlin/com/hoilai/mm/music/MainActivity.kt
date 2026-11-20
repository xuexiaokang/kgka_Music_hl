package com.hoilai.mm.music

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Visualizer
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.ryanheise.audioservice.AudioServiceActivity
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import java.io.File
import kotlin.math.abs

class MainActivity : AudioServiceActivity() {
    private val updateDownloads = mutableMapOf<Long, String>()
    private var downloadReceiverRegistered = false
    private var lyricsStateReceiverRegistered = false
    private var desktopLyricsChannel: MethodChannel? = null
    private var superLyricChannel: MethodChannel? = null
    private var superLyricRegistered = false
    private var bassBoost: BassBoost? = null
    private var bassBoostSessionId: Int? = null
    private var equalizer: Equalizer? = null
    private var equalizerSessionId: Int? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var dynamicsProcessingSessionId: Int? = null

    // 音量均衡 AGC：开环前馈 + 整曲响度预置 + 整曲锁定（续十二已删除开场测定）。
    // 关键纪律：
    // 0) 增益唯一来源是 Dart 下发的预置值：按音源 `/song/url` 的 `volume`
    //    （整曲平均电平，实测 12/12 覆盖 -16.4~-4.7dBFS）或曲库均值先验算出
    //    gain = target - volume，经 resetAgcForTrackChange(presetGainDb) 在出声前
    //    同步下发，原生直接写 postGain 并锁定，整曲不再改变。
    //    开场 4.8 秒 P80 测定（续一~续五）自续六起被整曲元数据预置取代，续十二
    //    彻底删除：长低音前奏会因开场样本误判打满 +15dB（《是否我真的一无所有》
    //    开场 P80=-40.96dBFS、整曲 volume=-11.8dBFS，两者差 15dB）。
    // 1) 双向归一化：gain 可以为正（补轻歌）也可以为负（压响歌），范围
    //    [-24,+15]dB，所有歌落在同一目标响度。负增益只靠 MBC 透明增益级
    //    postGain 实现，无压缩、无音染；+15 上限留峰值余量，由 limiter(-1dBFS) 兜住。
    // 2) 开环前馈：实测 Visualizer 读数不包含 postGain，闭环「gain += error」
    //    积分律不成立，增益不跟随瞬时电平。
    // 3) Visualizer 常驻但只做两件事：UI 瞬时电平展示 + 整曲 RMS 累积
    //    （续十一，切歌结算后用于无元数据歌曲的响度缓存与口径标定）。
    //    累积独立于 agcActive / agcLocked，fixed 模式与锁定后都继续采样。
    // 4) configure / 音频会话重建（蓝牙/USB 切换）时保留已锁定的增益，
    //    不重新测定，否则增益会瞬间归零，用户听到明显音量骤降。
    // 5) 改档 / 切策略后由上层立即重新下发预置值；在此之前增益保持中性
    //    0dB，不再有「开场测定」兜底，避免误判。
    private var loudnessVisualizer: Visualizer? = null
    // agcEnabled = Visualizer 是否在跑（音效开启期间常驻）；
    // agcActive = 当前是否把 AGC 结果写进 postGain（auto=true / fixed=false）。
    // 模式切换只翻 agcActive，不碰 Visualizer，避免 binder 重建造成卡顿。
    private var agcEnabled = false
    private var agcActive = false
    private var agcCurrentGainDb = DEFAULT_VOLUME_GAIN_DB // 当前实际写入 postGain 的增益
    private var agcDesiredGainDb = 0.0f // 平滑逼近的目标增益（预置时与 current 相等）
    private var agcTargetRmsDb = DEFAULT_TARGET_RMS_DB
    // 冻结增益时依据的「本曲整曲响度」：由 target - preset 反推，改档时用
    // (新target - decision) 重算 desired。
    private var agcDecisionDb = 0.0f
    // 锁定 = 本曲增益已冻结，唯一来源是 applyPresetGain。
    private var agcLocked = false
    // 用户主动改目标响度档位后置位：临时放开下降步长，让改档快速生效
    private var agcTargetAdjusting = false
    private var lastMeasuredRmsDb = 0.0f // 瞬时电平，仅用于 UI 展示
    // ---- 整曲响度累积（续十一）----
    // 与增益决策完全独立：增益锁定后、fixed 模式下仍继续采样，直到切歌结算。
    // Visualizer 读数不包含 postGain（见 1247 注释），所以累积值就是原始素材电平，
    // 无需反算增益。线性功率域累加 Σ10^(dB/10)，避免对 dB 取算术平均偏低 1~3 dB。
    private var trackRmsPowerSum = 0.0
    private var trackRmsSamples = 0
    // 官方 PEAK_RMS 测量：后台线程轮询 getMeasurementPeakRms，避免主线程 binder 调用。
    private var agcMeasureThread: HandlerThread? = null
    private var agcMeasureHandler: Handler? = null
    private val agcMeasurement = Visualizer.MeasurementPeakRms()
    private var pendingPermissionResult: MethodChannel.Result? = null
    private var deepLinkChannel: MethodChannel? = null
    private var initialDeepLink: String? = null

    companion object {
        private const val REQUEST_READ_AUDIO = 1001
        private const val TAG_SUPER_LYRIC = "SuperLyricPublisher"
        private const val TAG_BLUETOOTH_LYRICS = "BluetoothLyrics"
        private const val TAG_VOLUME_NORMALIZATION = "VolumeNormalization"
        private const val DEFAULT_VOLUME_GAIN_DB = 3.0f

        // AGC 目标 RMS（dBFS）。续九整体 +4dB：默认标准 -12dBFS 按实车听感
        // 对齐网易云（≈ -10 LUFS），比 Spotify/YouTube 的 -14 LUFS 响 4dB。
        // 峰值余量相应收紧，由 limiter(-1dBFS) 兜住越过 0dBFS 的峰值。
        private const val DEFAULT_TARGET_RMS_DB = -12.0f
        // 响度目标（dBFS）的合法范围，与增益范围是两回事。
        // 之前误用增益范围 [-8,18] clamp 目标，把当时的三档全抬到 -8，档位失效。
        // （续九后的新三档为 -16/-12/-8，仍在 [-40,-3] 内，不会被此处截断。）
        private const val AGC_MIN_TARGET_DBFS = -40.0f
        private const val AGC_MAX_TARGET_DBFS = -3.0f
        // 预置增益（双向归一化）的下限：volume 比目标响度高多少就衰减多少。
        // -24dB 覆盖实测最响的歌（volume=-4.7dBFS 配轻柔档 -16 → -11.3dB）并留余量。
        // 负增益靠 postGain 直接衰减，不引入压缩，也不会削波。
        private const val AGC_PRESET_MIN_GAIN_DB = -24.0f

        // 单 band MBC 的最高处理频率取设备输出采样率的 Nyquist：既保证全频通过，
        // 也确保落在 native 层允许的合法范围内（传远超 Nyquist 的固定大值可能被拒绝，
        // 导致整个音量均衡初始化失败）。只算一次并缓存。
        private val mbcFullBandCutoffHz: Float by lazy {
            val sampleRate = try {
                AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
            } catch (e: Exception) {
                48000
            }
            (if (sampleRate > 0) sampleRate else 48000) / 2.0f
        }
        // RMS 轮询周期 400ms；下降每步最多 0.6dB（慢），上升/改档每步最多 2.0dB（快）。
        private const val AGC_SAMPLE_INTERVAL_MS = 400L
        private const val AGC_MAX_STEP_DB = 0.6f
        private const val AGC_FAST_RISE_DB = 2.0f
        // 自动模式增益上限：由峰值余量定，避免推过 0dBFS 让 limiter 持续限幅
        // （音质劣化，用户明确要求避免）。
        private const val AGC_AUTO_MAX_GAIN_DB = 15.0f
        // 低于此 RMS 视为静音/弱段落，不计入整曲响度累积（否则底噪被当作歌曲响度）。
        private const val AGC_SILENCE_RMS_DB = -55.0f
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val fileName = updateDownloads.remove(downloadId) ?: return
            if (isDownloadSuccessful(downloadId)) {
                installDownloadedApk(fileName)
            }
        }
    }

    private val lyricsStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LyricsOverlayService.ACTION_VISIBILITY_CHANGED) {
                return
            }
            desktopLyricsChannel?.invokeMethod(
                "onVisibilityChanged",
                mapOf(
                    "visible" to intent.getBooleanExtra(
                        LyricsOverlayService.EXTRA_VISIBLE,
                        false
                    ),
                    "userClosed" to intent.getBooleanExtra(
                        LyricsOverlayService.EXTRA_USER_CLOSED,
                        false
                    )
                )
            )
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // 车机全屏歌词：原生 PlatformView（酷我式 clipRect+drawText、HWUI 局部栅格），
        // 替换 flutter_lyric 的整层重栅格，让出 GPU 给并发浮窗视频。
        flutterEngine.platformViewsController?.registry?.registerViewFactory(
            CarLyricPlatformViewFactory.VIEW_TYPE,
            CarLyricPlatformViewFactory(flutterEngine.dartExecutor.binaryMessenger)
        )

        // 全原生车机播放器桥接：双向 channel，Dart 调 open 拉起 CarPlayerActivity，
        // 传输事件经 ka.car_player/flutter 回传 Dart。
        CarPlayerBridge.init(this, flutterEngine.dartExecutor.binaryMessenger)

        deepLinkChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "kgka_music_hl/deep_link"
        ).apply {
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "getInitialLink" -> {
                        result.success(initialDeepLink)
                        initialDeepLink = null
                    }
                    else -> result.notImplemented()
                }
            }
        }
        intent?.dataString?.let {
            if (it.startsWith("kgka://")) {
                initialDeepLink = it
            }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "kgka_music_hl/screen")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "setKeepScreenOn" -> {
                        val enabled = call.arguments as? Boolean ?: false
                        if (enabled) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        // 车机检测：isAutomotive 判别车机。
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "kgka_music_hl/device")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "isAutomotive" -> result.success(isAutomotiveDevice())
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "kgka_music_hl/update")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "downloadAndInstallApk" -> {
                        val url = call.argument<String>("url")
                        val fileName = call.argument<String>("fileName") ?: "ka_music_update.apk"
                        if (url.isNullOrBlank()) {
                            result.error("invalid_url", "APK download url is empty", null)
                            return@setMethodCallHandler
                        }

                        runCatching {
                            enqueueApkDownload(url, fileName)
                        }.onSuccess {
                            result.success(null)
                        }.onFailure { error ->
                            result.error("download_failed", error.message, null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "kgka_music_hl/audio_effects")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getEqualizerConfig" -> {
                        val audioSessionId = call.argument<Int>("audioSessionId")
                        runCatching {
                            equalizerConfig(audioSessionId)
                        }.onSuccess { config ->
                            result.success(config)
                        }.onFailure { error ->
                            result.error("equalizer_config_failed", error.message, null)
                        }
                    }
                    "configureEqualizer" -> {
                        val audioSessionId = call.argument<Int>("audioSessionId")
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        val levels = call.argument<List<Int>>("levels") ?: emptyList()

                        runCatching {
                            configureEqualizer(audioSessionId, enabled, levels)
                        }.onSuccess { supported ->
                            result.success(supported)
                        }.onFailure { error ->
                            releaseEqualizer()
                            result.error("equalizer_failed", error.message, null)
                        }
                    }
                    "configureBassBoost" -> {
                        val audioSessionId = call.argument<Int>("audioSessionId")
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        val strength = call.argument<Int>("strength") ?: 0

                        runCatching {
                            configureBassBoost(audioSessionId, enabled, strength)
                        }.onSuccess { supported ->
                            result.success(supported)
                        }.onFailure { error ->
                            releaseBassBoost()
                            result.error("bass_boost_failed", error.message, null)
                        }
                    }
                    "configureVolumeNormalization" -> {
                        val audioSessionId = call.argument<Int>("audioSessionId")
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        val gainDb =
                            (call.argument<Double>("gainDb") ?: DEFAULT_VOLUME_GAIN_DB.toDouble())
                                .toFloat()
                        val autoGain = call.argument<Boolean>("autoGain") ?: false
                        val targetRmsDb =
                            (call.argument<Double>("targetRmsDb")
                                ?: DEFAULT_TARGET_RMS_DB.toDouble()).toFloat()

                        runCatching {
                            configureVolumeNormalization(
                                audioSessionId, enabled, gainDb, autoGain, targetRmsDb
                            )
                        }.onSuccess { supported ->
                            result.success(supported)
                        }.onFailure { error ->
                            stopLoudnessAgc()
                            releaseDynamicsProcessing()
                            result.error("volume_normalization_failed", error.message, null)
                        }
                    }
                    "getAgcStatus" -> {
                        runCatching {
                            mapOf<String, Any>(
                                "active" to agcActive,
                                "gainDb" to agcCurrentGainDb.toDouble(),
                                "measuredRmsDb" to lastMeasuredRmsDb.toDouble(),
                                "targetRmsDb" to agcTargetRmsDb.toDouble(),
                                "locked" to agcLocked,
                                "trackRmsSamples" to trackRmsSamples,
                            )
                        }.onSuccess { status ->
                            result.success(status)
                        }.onFailure { error ->
                            result.error("agc_status_failed", error.message, null)
                        }
                    }
                    // 只改 AGC 目标响度：不重建效果器、不碰 Visualizer，避免主线程
                    // binder 同步调用阻塞导致设置菜单假死。AGC 会在下一采样周期自然收敛。
                    "setAgcTarget" -> {
                        val targetRmsDb =
                            (call.argument<Double>("targetRmsDb")
                                ?: DEFAULT_TARGET_RMS_DB.toDouble()).toFloat()
                        val applied = setAgcTarget(targetRmsDb)
                        result.success(applied)
                    }
                    // 切换 auto/fixed 策略：只翻 agcActive 开关 + 需要时写一次 postGain，
                    // 不 stop/release/new Visualizer（全是主线程 binder 同步调用，会卡顿）。
                    // Visualizer 在音效开启期间常驻，fixed 模式下回调早退不做调整。
                    "setAgcActive" -> {
                        val active = call.argument<Boolean>("active") ?: false
                        val gainDb =
                            (call.argument<Double>("gainDb")
                                ?: DEFAULT_VOLUME_GAIN_DB.toDouble()).toFloat()
                        val applied = setAgcActive(active, gainDb)
                        result.success(applied)
                    }
                    // 切歌归位：在新歌出声前同步调用，直接按整曲响度预置并锁定增益。
                    // 不重建效果器 / Visualizer，同步且零卡顿。
                    // targetRmsDb 一并带下来：applyPresetGain 要用它反推整曲响度
                    // （decision = target - gain），保证改档重算基准正确。
                    "resetAgcForTrackChange" -> {
                        val presetGainDb =
                            (call.argument<Double>("presetGainDb") ?: 0.0).toFloat()
                        val targetRmsDb = call.argument<Double>("targetRmsDb")?.toFloat()
                        result.success(
                            resetAgcForTrackChange(presetGainDb, targetRmsDb)
                        )
                    }
                    // 续十一：切歌前结算上一曲整曲 RMS，写进 Dart 侧响度缓存。
                    "consumeTrackRms" -> result.success(consumeTrackRms())
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "kgka_music_hl/local_music")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "hasPermission" -> {
                        result.success(hasReadAudioPermission())
                    }
                    "requestPermission" -> {
                        if (hasReadAudioPermission()) {
                            result.success(true)
                        } else {
                            pendingPermissionResult = result
                            requestAudioPermission()
                        }
                    }
                    "getLocalSongs" -> {
                        if (!hasReadAudioPermission()) {
                            result.error("no_permission", "READ_MEDIA_AUDIO permission not granted", null)
                            return@setMethodCallHandler
                        }
                        runCatching {
                            queryLocalSongs()
                        }.onSuccess { songs ->
                            result.success(songs)
                        }.onFailure { error ->
                            result.error("query_failed", error.message, null)
                        }
                    }
                    "getAlbumArt" -> {
                        val albumId = call.argument<Number>("albumId")?.toLong()
                        if (albumId == null || albumId <= 0) {
                            result.success(null)
                            return@setMethodCallHandler
                        }
                        runCatching {
                            getAlbumArtBytes(albumId)
                        }.onSuccess { bytes ->
                            result.success(bytes)
                        }.onFailure { error ->
                            result.error("album_art_failed", error.message, null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }

        desktopLyricsChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "kgka_music_hl/desktop_lyrics"
        )
        registerLyricsStateReceiver()
        desktopLyricsChannel?.setMethodCallHandler { call, result ->
                when (call.method) {
                    "checkPermission" -> {
                        result.success(Settings.canDrawOverlays(this))
                    }
                    "requestPermission" -> {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        result.success(null)
                    }
                    "show" -> {
                        if (!Settings.canDrawOverlays(this)) {
                            result.error("no_permission", "No overlay permission", null)
                            return@setMethodCallHandler
                        }
                        val title = call.argument<String>("title") ?: ""
                        val artist = call.argument<String>("artist") ?: ""
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_UPDATE_LYRICS
                            putExtra(LyricsOverlayService.EXTRA_TITLE, title)
                            putExtra(LyricsOverlayService.EXTRA_ARTIST, artist)
                            putExtra(LyricsOverlayService.EXTRA_CURRENT_LYRIC, "")
                            putExtra(LyricsOverlayService.EXTRA_NEXT_LYRIC, "")
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "hide" -> {
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_HIDE
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "updateLyrics" -> {
                        val current = call.argument<String>("current") ?: ""
                        val next = call.argument<String>("next") ?: ""
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_UPDATE_LYRICS
                            putExtra(LyricsOverlayService.EXTRA_CURRENT_LYRIC, current)
                            putExtra(LyricsOverlayService.EXTRA_NEXT_LYRIC, next)
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "updatePlayState" -> {
                        val isPlaying = call.argument<Boolean>("isPlaying") ?: false
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_UPDATE_PLAY_STATE
                            putExtra(LyricsOverlayService.EXTRA_IS_PLAYING, isPlaying)
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "isVisible" -> {
                        result.success(LyricsOverlayService.isRunning(this))
                    }
                    "updateKaraokeProgress" -> {
                        val progress = call.argument<Double>("progress")?.toFloat() ?: 0f
                        val lineDurationMs = call.argument<Int>("lineDurationMs") ?: 0
                        val isPlaying = call.argument<Boolean>("isPlaying") ?: false
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_UPDATE_KARAOKE
                            putExtra(LyricsOverlayService.EXTRA_PROGRESS, progress)
                            putExtra(LyricsOverlayService.EXTRA_LINE_DURATION_MS, lineDurationMs)
                            putExtra(LyricsOverlayService.EXTRA_IS_PLAYING, isPlaying)
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "updateSettings" -> {
                        val opacity = call.argument<Double>("opacity")?.toFloat() ?: 0.8f
                        val locked = call.argument<Boolean>("locked") ?: false
                        val passthrough = call.argument<Boolean>("passthrough") ?: false
                        // 颜色值小于 2^31 时经 MethodChannel 到达为 Integer，需经 Number 转换
                        val textColorLong =
                            (call.argument<Number>("textColor"))?.toLong() ?: 0xFFFFFFFFL
                        val backgroundColorLong =
                            (call.argument<Number>("backgroundColor"))?.toLong() ?: 0xFF1A1A2EL
                        val fontSize = call.argument<Double>("fontSize")?.toFloat() ?: 16f
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_UPDATE_SETTINGS
                            putExtra(LyricsOverlayService.EXTRA_OPACITY, opacity)
                            putExtra(LyricsOverlayService.EXTRA_LOCKED, locked)
                            putExtra(LyricsOverlayService.EXTRA_PASSTHROUGH, passthrough)
                            putExtra(LyricsOverlayService.EXTRA_TEXT_COLOR, textColorLong.toInt())
                            putExtra(LyricsOverlayService.EXTRA_BACKGROUND_COLOR, backgroundColorLong.toInt())
                            putExtra(LyricsOverlayService.EXTRA_FONT_SIZE, fontSize)
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "setAppForeground" -> {
                        val isForeground = call.argument<Boolean>("isForeground") ?: false
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_SET_APP_FOREGROUND
                            putExtra(LyricsOverlayService.EXTRA_IS_FOREGROUND, isForeground)
                        }
                        startService(intent)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        // SuperLyricApi 歌词发布到系统服务
        superLyricChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "kgka_music_hl/super_lyric"
        )
        superLyricChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "isAvailable" -> {
                    runCatching {
                        SuperLyricHelper.isAvailable()
                    }.onSuccess { available ->
                        result.success(available)
                    }.onFailure { error ->
                        Log.w(TAG_SUPER_LYRIC, "isAvailable failed: ${error.message}")
                        result.success(false)
                    }
                }
                "registerPublisher" -> {
                    runCatching {
                        if (!superLyricRegistered) {
                            SuperLyricHelper.registerPublisher()
                            superLyricRegistered = true
                        }
                        // 以系统服务里的真实注册状态为准（本地标志位可能过期）
                        val registered = runCatching {
                            SuperLyricHelper.isPublisherRegistered()
                        }.getOrNull() ?: superLyricRegistered
                        Log.d(TAG_SUPER_LYRIC, "registerPublisher: registered=$registered")
                        result.success(registered)
                    }.onFailure { error ->
                        Log.w(TAG_SUPER_LYRIC, "registerPublisher failed: ${error.message}")
                        result.success(false)
                    }
                }
                "unregisterPublisher" -> {
                    runCatching {
                        if (superLyricRegistered) {
                            SuperLyricHelper.unregisterPublisher()
                            superLyricRegistered = false
                        }
                        result.success(null)
                    }.onFailure {
                        result.success(null)
                    }
                }
                "sendLyric" -> {
                    runCatching {
                        // 自愈：启动时注册可能因系统服务未就绪而失败，
                        // 这里在首次发送前补注册一次（幂等，服务端按 UID 去重）
                        if (!superLyricRegistered) {
                            SuperLyricHelper.registerPublisher()
                            superLyricRegistered = true
                        }
                        val title = call.argument<String>("title") ?: ""
                        val artist = call.argument<String>("artist") ?: ""
                        val album = call.argument<String>("album") ?: ""
                        val lyricText = call.argument<String>("lyricText") ?: ""
                        // ⚠️ Dart int 经 MethodChannel 传输后，能放进 32 位时是 Integer，
                        // 直接 cast Long 会抛 ClassCastException，必须经 Number.toLong() 转换
                        val lyricStartTime =
                            (call.argument<Number>("lyricStartTime"))?.toLong() ?: 0L
                        val lyricEndTime =
                            (call.argument<Number>("lyricEndTime"))?.toLong() ?: 0L
                        val secondaryText = call.argument<String>("secondaryText")
                        val translationText = call.argument<String>("translationText")
                        val words = call.argument<List<Map<String, Any>>>("words")

                        val lyricData = SuperLyricData()
                            .setTitle(title)
                            .setArtist(artist)
                            .setAlbum(album)

                        // 主歌词行（含逐字）
                        val lyricWords: Array<SuperLyricWord>? = words?.mapNotNull { w ->
                            val wordText = w["word"] as? String
                            val wordStart = (w["startTime"] as? Number)?.toLong()
                            val wordEnd = (w["endTime"] as? Number)?.toLong()
                            if (wordText != null && wordStart != null && wordEnd != null) {
                                SuperLyricWord(wordText, wordStart, wordEnd)
                            } else null
                        }?.takeIf { it.isNotEmpty() }?.toTypedArray()

                        val mainLyric = if (lyricWords != null) {
                            SuperLyricLine(lyricText, lyricWords, lyricStartTime, lyricEndTime)
                        } else {
                            SuperLyricLine(lyricText, lyricStartTime, lyricEndTime)
                        }
                        lyricData.setLyric(mainLyric)

                        // 副歌词行（音译/罗马音）
                        if (!secondaryText.isNullOrBlank()) {
                            lyricData.setSecondary(
                                SuperLyricLine(secondaryText, lyricStartTime, lyricEndTime)
                            )
                        }
                        // 翻译行
                        if (!translationText.isNullOrBlank()) {
                            lyricData.setTranslation(
                                SuperLyricLine(translationText, lyricStartTime, lyricEndTime)
                            )
                        }
                        SuperLyricHelper.sendLyric(lyricData)
                        Log.d(
                            TAG_SUPER_LYRIC,
                            "sendLyric ok: \"$lyricText\" ($lyricStartTime-$lyricEndTime ms), " +
                                "words=${lyricWords?.size ?: 0}, " +
                                "secondary=${!secondaryText.isNullOrBlank()}, " +
                                "translation=${!translationText.isNullOrBlank()}"
                        )
                        result.success(true)
                    }.onFailure { error ->
                        Log.w(TAG_SUPER_LYRIC, "sendLyric failed: ${error.message}")
                        result.error("send_lyric_failed", error.message, null)
                    }
                }
                "sendStop" -> {
                    runCatching {
                        if (!superLyricRegistered) {
                            SuperLyricHelper.registerPublisher()
                            superLyricRegistered = true
                        }
                        SuperLyricHelper.sendStop(SuperLyricData())
                        Log.d(TAG_SUPER_LYRIC, "sendStop ok")
                        result.success(true)
                    }.onFailure { error ->
                        Log.w(TAG_SUPER_LYRIC, "sendStop failed: ${error.message}")
                        result.error("send_stop_failed", error.message, null)
                    }
                }
                "debugState" -> {
                    result.success(
                        mapOf(
                            "serviceAvailable" to
                                (runCatching { SuperLyricHelper.isAvailable() }.getOrNull() ?: false),
                            "localRegisteredFlag" to superLyricRegistered,
                            "publisherRegistered" to
                                (runCatching { SuperLyricHelper.isPublisherRegistered() }
                                    .getOrNull() ?: false),
                        )
                    )
                }
                else -> result.notImplemented()
            }
        }

        // 车载蓝牙歌词广播
        val btLyricChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "kgka_music_hl/bluetooth_lyrics"
        )
        btLyricChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "broadcastMetaChanged" -> {
                    runCatching {
                        val title = call.argument<String>("title") ?: ""
                        val artist = call.argument<String>("artist") ?: ""
                        val album = call.argument<String>("album") ?: ""
                        val lyric = call.argument<String>("lyric") ?: ""
                        val positionMs = (call.argument<Number>("positionMs") ?: 0).toLong()
                        val durationMs = (call.argument<Number>("durationMs") ?: 0).toLong()
                        val playing = call.argument<Boolean>("playing") ?: false
                        val track = (call.argument<Number>("track") ?: 0).toInt()
                        val listSize = (call.argument<Number>("listSize") ?: 0).toInt()

                        val extras = Bundle().apply {
                            putString("track", title)
                            putString("artist", artist)
                            putString("album", album)
                            putString("id", "")
                            putLong("position", positionMs)
                            putLong("duration", durationMs)
                            putBoolean("playing", playing)
                            putInt("ListSize", listSize)
                            putInt("trackPos", track)
                            putString("lyric", lyric)
                            // 网易云/部分车机额外字段
                            putString("currentLyric", lyric)
                            putString("DISPLAY_NAME", title)
                        }

                        // 标准 Android 音乐元数据变化广播
                        sendOrderedBroadcast(Intent("com.android.music.metachanged").apply {
                            putExtras(extras)
                            setPackage(null)
                        }, null)

                        // 注意：per-line 元数据广播只发 metachanged 系。
                        // 严禁在此发送 playbackcomplete / queuechanged /
                        // playstatechanged——这些是"播放完成/队列变化/状态切换"
                        // 语义信号，随歌词逐句误发会导致车机、仪表盘误判切歌
                        // 或清空状态；真实状态变化由 broadcastPlayStateChanged
                        // 专用通道发送。

                        // QQMusic / Netease 自定义广播（很多车机 App 监听）
                        sendBroadcast(Intent("com.netease.cloudmusic.metachanged").apply {
                            putExtras(Bundle(extras))
                            setPackage(null)
                        })
                        sendBroadcast(Intent("com.tencent.qqmusic.metachanged").apply {
                            putExtras(Bundle(extras))
                            setPackage(null)
                        })
                        // KuGou/KuWo 广播
                        sendBroadcast(Intent("com.kugou.android.metachanged").apply {
                            putExtras(Bundle(extras))
                            setPackage(null)
                        })
                        sendBroadcast(Intent("cn.kuwo.player.metachanged").apply {
                            putExtras(Bundle(extras))
                            setPackage(null)
                        })

                        Log.d(
                            TAG_BLUETOOTH_LYRICS,
                            "metaChanged ok: \"$title\"/\"$artist\", lyric=\"${lyric.take(24)}\", " +
                                "pos=${positionMs}ms, playing=$playing, track=$track/$listSize, 5 actions sent"
                        )
                        result.success(true)
                    }.onFailure { error ->
                        Log.w(TAG_BLUETOOTH_LYRICS, "broadcastMetaChanged failed: ${error.message}")
                        result.success(false)
                    }
                }
                "broadcastPlayStateChanged" -> {
                    runCatching {
                        val title = call.argument<String>("title") ?: ""
                        val artist = call.argument<String>("artist") ?: ""
                        val album = call.argument<String>("album") ?: ""
                        val positionMs = (call.argument<Number>("positionMs") ?: 0).toLong()
                        val durationMs = (call.argument<Number>("durationMs") ?: 0).toLong()
                        val playing = call.argument<Boolean>("playing") ?: false

                        val extras = Bundle().apply {
                            putString("track", title)
                            putString("artist", artist)
                            putString("album", album)
                            putLong("position", positionMs)
                            putLong("duration", durationMs)
                            putBoolean("playing", playing)
                        }
                        sendBroadcast(Intent("com.android.music.playstatechanged").apply {
                            putExtras(extras)
                            setPackage(null)
                        })
                        sendBroadcast(Intent("com.netease.cloudmusic.playstatechanged").apply {
                            putExtras(Bundle(extras))
                            setPackage(null)
                        })
                        sendBroadcast(Intent("com.tencent.qqmusic.playstatechanged").apply {
                            putExtras(extras)
                            setPackage(null)
                        })
                        Log.d(
                            TAG_BLUETOOTH_LYRICS,
                            "playStateChanged ok: \"$title\", playing=$playing, pos=${positionMs}ms"
                        )
                        result.success(true)
                    }.onFailure { error ->
                        Log.w(TAG_BLUETOOTH_LYRICS, "broadcastPlayStateChanged failed: ${error.message}")
                        result.success(false)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun readAudioPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasReadAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, readAudioPermission()) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(readAudioPermission()),
            REQUEST_READ_AUDIO
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_READ_AUDIO) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingPermissionResult?.success(granted)
            pendingPermissionResult = null
        }
    }

    private fun queryLocalSongs(): List<Map<String, Any?>> {
        val songs = mutableListOf<Map<String, Any?>>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.IS_MUSIC,
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                collection,
                projection,
                selection,
                null,
                sortOrder
            )

            cursor?.let {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val albumIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val title = it.getString(titleColumn) ?: "未知歌曲"
                    val artist = it.getString(artistColumn) ?: "未知艺人"
                    val album = it.getString(albumColumn) ?: ""
                    val duration = it.getLong(durationColumn)
                    val filePath = it.getString(dataColumn) ?: ""
                    val albumId = it.getLong(albumIdColumn)

                    // 构建专辑封面 URI
                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    )

                    if (filePath.isNotEmpty()) {
                        songs.add(
                            mapOf(
                                "id" to filePath,
                                "title" to title,
                                "artist" to artist,
                                "album" to album,
                                "duration" to duration,
                                "filePath" to filePath,
                                "albumArtUri" to albumArtUri.toString(),
                            )
                        )
                    }
                }
            }
        } finally {
            cursor?.close()
        }

        return songs
    }

    private fun registerLyricsStateReceiver() {
        if (lyricsStateReceiverRegistered) {
            return
        }
        val filter = IntentFilter(LyricsOverlayService.ACTION_VISIBILITY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(lyricsStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(lyricsStateReceiver, filter)
        }
        lyricsStateReceiverRegistered = true
    }

    private fun equalizerConfig(audioSessionId: Int?): Map<String, Any>? {
        if (audioSessionId == null || audioSessionId <= 0) {
            return null
        }
        val effect = ensureEqualizer(audioSessionId)
        val range = effect.bandLevelRange
        val bands = (0 until effect.numberOfBands).map { index ->
            val band = index.toShort()
            mapOf(
                "centerHz" to effect.getCenterFreq(band) / 1000,
                "level" to effect.getBandLevel(band).toInt()
            )
        }
        return mapOf(
            "range" to listOf(range[0].toInt(), range[1].toInt()),
            "bands" to bands
        )
    }

    private fun configureEqualizer(
        audioSessionId: Int?,
        enabled: Boolean,
        levels: List<Int>
    ): Boolean {
        if (!enabled) {
            releaseEqualizer()
            return true
        }
        if (audioSessionId == null || audioSessionId <= 0) {
            return false
        }

        val effect = ensureEqualizer(audioSessionId)
        val range = effect.bandLevelRange
        val bandCount = minOf(effect.numberOfBands.toInt(), levels.size)
        for (index in 0 until bandCount) {
            val level = levels[index].coerceIn(range[0].toInt(), range[1].toInt())
            effect.setBandLevel(index.toShort(), level.toShort())
        }
        effect.enabled = true
        return true
    }

    private fun ensureEqualizer(audioSessionId: Int): Equalizer {
        if (equalizerSessionId == audioSessionId && equalizer != null) {
            return equalizer!!
        }
        releaseEqualizer()
        return Equalizer(0, audioSessionId).also {
            equalizer = it
            equalizerSessionId = audioSessionId
        }
    }

    private fun releaseEqualizer() {
        equalizer?.runCatching {
            enabled = false
            release()
        }
        equalizer = null
        equalizerSessionId = null
    }

    private fun configureBassBoost(
        audioSessionId: Int?,
        enabled: Boolean,
        strength: Int
    ): Boolean {
        if (!enabled) {
            releaseBassBoost()
            return true
        }
        if (audioSessionId == null || audioSessionId <= 0) {
            return false
        }

        val effect = if (bassBoostSessionId == audioSessionId && bassBoost != null) {
            bassBoost!!
        } else {
            releaseBassBoost()
            BassBoost(0, audioSessionId).also {
                bassBoost = it
                bassBoostSessionId = audioSessionId
            }
        }

        val clampedStrength = strength.coerceIn(0, 1000).toShort()
        if (effect.strengthSupported) {
            effect.setStrength(clampedStrength)
        } else {
            effect.setStrength(if (clampedStrength > 0) 1000 else 0)
        }
        effect.enabled = true
        return true
    }

    private fun releaseBassBoost() {
        bassBoost?.runCatching {
            enabled = false
            release()
        }
        bassBoost = null
        bassBoostSessionId = null
    }

    private fun configureVolumeNormalization(
        audioSessionId: Int?,
        enabled: Boolean,
        gainDb: Float = DEFAULT_VOLUME_GAIN_DB,
        autoGain: Boolean = false,
        targetRmsDb: Float = DEFAULT_TARGET_RMS_DB
    ): Boolean {
        if (!enabled) {
            stopLoudnessAgc()
            releaseDynamicsProcessing()
            return true
        }
        if (audioSessionId == null || audioSessionId <= 0) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }

        try {
            Log.d(
                TAG_VOLUME_NORMALIZATION,
                "configure: enabled=$enabled autoGain=$autoGain gainDb=$gainDb " +
                    "targetRmsDb=$targetRmsDb session=$audioSessionId"
            )
            // 已锁定时，configure 不得把当前增益归零：
            // - 音频会话重建（车机蓝牙/USB 切换）：否则增益瞬间掉到 0，用户听到
            //   明显音量骤降；
            // - 切歌预置：resetAgcForTrackChange 已按整曲响度锁定，configure 若
            //   清掉就会让本曲在重新预置前保持中性 0dB。
            // 需要重置的场景（开关/策略变更）都由调用方先清掉 agcLocked，
            // 因此这里的判断不需要再看 session 是否变化。
            val preserveLockedGain = autoGain && agcLocked
            val preservedGain = if (preserveLockedGain) agcCurrentGainDb else 0f
            val preservedDecisionDb = if (preserveLockedGain) agcDecisionDb else 0f

            val effect = ensureDynamicsProcessing(audioSessionId)

            // 静态模式：postGain 固定为设置页下发的 gainDb（旧行为，权限被拒时的兜底）。
            // 自动模式：postGain 由 Dart 预置值维护，起点固定为中性 0dB——不能用固定增益档
            // （可能是 +12dB）做起点，否则响歌一上来被放大、切歌开头爆音。
            agcTargetRmsDb = targetRmsDb.coerceIn(AGC_MIN_TARGET_DBFS, AGC_MAX_TARGET_DBFS)
            val initialGain = if (autoGain) preservedGain else gainDb
            agcCurrentGainDb = initialGain
            applyMbcAndLimiter(effect, initialGain)
            effect.enabled = true

            // 模式只由 agcActive 决定，不再 stop/new Visualizer。
            // 两种模式共用常驻 Visualizer：fixed 时 onAgcMeasuredRms 早退不调整，
            // auto 时正常收敛。这样 auto↔fixed 切换零 binder 重建、不卡顿。
            agcActive = autoGain
            if (autoGain && !preserveLockedGain) {
                // configure 总是把 postGain 写回 0dB 起点，增益状态一并归零，
                // 等上层随后下发预置值；不做任何测量或推测。
                resetAgcGainState()
            }
            // 只要音效开启且有权限，就保持 Visualizer 常驻（幂等，已启动直接返回）。
            // fixed 模式也启动，是为了切到 auto 时零延迟——代价仅是一次轻量采样回调。
            startLoudnessAgc(audioSessionId)
            if (preserveLockedGain) {
                // ensureDynamicsProcessing → releaseDynamicsProcessing → stopLoudnessAgc
                // 都会重置增益状态，故在最后统一恢复冻结状态；并显式写回 postGain，
                // 抵消 startLoudnessAgc 首帧可能写下的 0.6dB 慢降。
                // 改档只用 agcDecisionDb，无需恢复任何测量窗口。
                agcCurrentGainDb = preservedGain
                agcDesiredGainDb = preservedGain
                agcDecisionDb = preservedDecisionDb
                agcLocked = true
                agcTargetAdjusting = false
                applyMbcAndLimiter(effect, preservedGain)
                Log.d(
                    TAG_VOLUME_NORMALIZATION,
                    "AGC preserved locked gain across configure: gain=$preservedGain"
                )
            }
            return true
        } catch (e: Exception) {
            stopLoudnessAgc()
            releaseDynamicsProcessing()
            return false
        }
    }

    private fun ensureDynamicsProcessing(audioSessionId: Int): DynamicsProcessing {
        if (dynamicsProcessingSessionId == audioSessionId && dynamicsProcessing != null) {
            return dynamicsProcessing!!
        }
        releaseDynamicsProcessing()
        val config = DynamicsProcessing.Config.Builder(
            // 必须用频域变体(0)。曾误改为时域变体 VARIANT_FAVOR_TIME_RESOLUTION(1)，
            // 但设备 native 层普遍未实现该引擎架构，实测 MuMu 直接报
            // 「variant 1 not available for creation」+「DP_PARAM_ENGINE_ARCHITECTURE
            // error setting variant 1」，整个 DynamicsProcessing 对象为 null：
            // postGain 完全写不进去，固定增益与自动 AGC 双双静默失效。
            // 更隐蔽的是 native 创建失败时 Java 构造函数不抛异常，configure 仍返回
            // true，UI 与日志都看不出异常，只能靠听感发现——所以这个参数禁止再改。
            // 高频丢失的根因是此前的 2:1 全频压缩器（已由下方 MBC 透明增益级移除），
            // 与变体无关；单 band + 全 EQ 关闭时频域变体不会带来可闻的高频损失。
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            // 框架会用 effect 引擎的实际通道数重建配置（AOSP：new Config(mChannelCount, cfg)
            // 且「replicate channels if more」），故这里写 2 不会导致声道被静音或丢失。
            2, // channels
            false, // preEq
            0,
            true, // mbc
            1, // 1 band：仅作为 limiter 之前的增益级，不做动态压缩
            false, // postEq
            0,
            true // limiter
        ).build()
        return DynamicsProcessing(0, audioSessionId, config).also {
            dynamicsProcessing = it
            dynamicsProcessingSessionId = audioSessionId
        }
    }

    /// 写入「纯增益级 + 峰值限幅」。postGain 由调用方决定：静态=固定档位，自动=AGC 当前增益。
    ///
    /// 音量均衡只需要补增益 + 防削波，不需要动态压缩。早期把 MBC 配成单 band 全频
    /// 压缩器（threshold=-16dBFS / ratio=2:1 / release=300ms），而现代流媒体 RMS 普遍
    /// -14~-9dBFS，压缩器几乎整首歌都在做 3~5dB 增益衰减；压缩量由全频总能量（中低频
    /// 主导）驱动，会把电平很低的高频泛音/齿音/镲片一并压低且长时间抬不回来，听感就是
    /// 「打开音量均衡后高频丢失、声音发闷」，还会反过来抵消 AGC 刚补上的增益。
    /// 现把 MBC 退化为透明增益级，只留 postGain；preGain 恒为 0（推高压缩器输入曾重现
    /// 上游 #153「首次播放变电流声」）。
    /// 保留 MBC 而非把增益挪到 limiter.postGain：MBC 在 limiter 之前，补完增益后仍能
    /// 被限幅兜住；limiter.postGain 在限幅之后，+18dB 会直接削波。
    private fun applyMbcAndLimiter(effect: DynamicsProcessing, postGainDb: Float) {
        val mbcBand = DynamicsProcessing.MbcBand(
            true, // enabled
            // cutoffFrequency 是「该 band 处理的最高频率」。单 band 下原值 20000Hz 会让
            // 20kHz 以上完全不覆盖，且分频滤波器在 ~16kHz 就开始滚降。改为设备实际
            // 输出采样率的 Nyquist：全频通过，且一定落在 native 层允许的合法范围内。
            mbcFullBandCutoffHz,
            1.0f, // attackTime（不压缩时无实际作用）
            1.0f, // releaseTime
            1.0f, // ratio: 1:1，不做动态压缩
            0.0f, // threshold: 0dBFS —— 官方明确「threshold=0 表示不发生压缩」
            0.0f, // kneeWidth: 0，无软拐点
            -60.0f, // noiseGateThreshold
            1.0f, // expanderRatio: 1:1，噪声门不工作
            0.0f, // preGain: 0dB
            postGainDb // 唯一有效项：静态档位或 AGC 动态增益
        )

        // limiter 只兜住 0dBFS 附近的峰值，不做常态压缩。缩短 attack 以抓准高频瞬态，
        // 缩短 release 以免峰值过后继续压低整段信号造成发闷。
        val limiter = DynamicsProcessing.Limiter(
            true, // inUse
            true, // enabled
            0, // linkGroup
            0.3f, // attackTime: 原 1.0ms，高频瞬态更快被兜住
            50.0f, // releaseTime: 原 100ms，减少峰值后持续压低带来的闷感
            10.0f, // ratio
            -1.0f, // threshold
            0.0f // postGain
        )

        for (c in 0 until 2) {
            effect.setMbcBandByChannelIndex(c, 0, mbcBand)
            effect.setLimiterByChannelIndex(c, limiter)
        }
    }

    // ---------- 实时响度 AGC ----------
    // Visualizer 与 DynamicsProcessing 挂在同一 audioSessionId，但 Android 不保证 effect 链
    // 顺序：实测 Visualizer 读到的电平并不包含 postGain 的变化，闭环假设不成立。
    // 因此只用开环前馈：增益完全由 Dart 按整曲响度预置，锁后整曲不变。
    // Visualizer 只负责 UI 瞬时电平与整曲 RMS 累积（续十一），不驱动增益。

    /// 重置本曲增益状态。切歌、开关/策略变更、Visualizer 启停时调用。
    /// 续十二：开场测定窗口删除后不再有「本曲响度估计」，这里只把增益归零
    /// 并解除锁定；真正的增益由 Dart 随后下发的 applyPresetGain 建立。
    /// 中间态是中性 0dB，不做任何测量或推测，避免长前奏误判。
    /// 只清内存状态，不碰 DynamicsProcessing / Visualizer 的创建与 release（binder 同步
    /// 调用会卡主线程）；postGain 由各调用点自行写回起点。
    private fun resetAgcGainState() {
        agcDecisionDb = 0.0f
        agcDesiredGainDb = 0.0f
        agcLocked = false
        agcTargetAdjusting = false
    }

    /// 轻量更新目标响度：只改字段，不重建效果器 / Visualizer。
    /// （当前上层改档走 resetAgcForTrackChange 整体重算，此通道保留作轻量备选。）
    /// 用户主动改档是明确意图，置 agcTargetAdjusting 临时放开下降步长，让改档快速生效。
    private fun setAgcTarget(targetRmsDb: Float): Boolean {
        val clamped = targetRmsDb.coerceIn(AGC_MIN_TARGET_DBFS, AGC_MAX_TARGET_DBFS)
        agcTargetRmsDb = clamped
        agcTargetAdjusting = true
        if (agcLocked) {
            // 本曲增益已冻结：用新目标立即重算（用户主动改档，允许升/降），
            // 不解除锁定，避免歌内重新出现增益跳变。
            agcDesiredGainDb = (clamped - agcDecisionDb)
                .coerceIn(AGC_PRESET_MIN_GAIN_DB, AGC_AUTO_MAX_GAIN_DB)
        }
        // 未锁定时无需处理：等上层下发预置值时会带上新 target。
        Log.d(
            TAG_VOLUME_NORMALIZATION,
            "setAgcTarget: target=${clamped}dB desired=$agcDesiredGainDb"
        )
        return true
    }

    /// 轻量切换 auto/fixed 策略：只翻开关，不碰 Visualizer / 效果器创建。
    /// Visualizer 在音效开启期间常驻，两种模式共用，避免切换时的主线程 binder 重建卡顿。
    private fun setAgcActive(active: Boolean, gainDb: Float): Boolean {
        agcActive = active
        // 策略变了，本曲增益状态作废，等上层重新下发预置值。
        resetAgcGainState()
        if (active) {
            // 切到 auto：必须把 postGain 与 agcCurrentGainDb 一并归零。轻量通道不经过
            // configure，若沿用 fixed 档位（默认 +3，最高 +18）做起点，偏响歌会以
            // 0.6dB/步慢降十几秒仍被放大并触发 limiter（复审 P1-1）。
            // 通常 Visualizer 已常驻；仅当权限刚授予等边缘情况才需幂等启动。
            agcCurrentGainDb = 0f
            dynamicsProcessing?.let { applyMbcAndLimiter(it, 0f) }
            dynamicsProcessingSessionId?.let { startLoudnessAgc(it) }
            Log.d(
                TAG_VOLUME_NORMALIZATION,
                "setAgcActive: AUTO gain=$agcCurrentGainDb visualizer=$agcEnabled"
            )
        } else {
            // 切到 fixed：把 postGain 恢复到用户设定的固定档位，AGC 停止写增益。
            dynamicsProcessing?.let { applyMbcAndLimiter(it, gainDb) }
            agcCurrentGainDb = gainDb
            Log.d(TAG_VOLUME_NORMALIZATION, "setAgcActive: FIXED gain=$gainDb")
        }
        return true
    }

    /// 切歌归位：在下一首真正出声之前由 Flutter 同步 await 调用。
    /// 直接按整曲响度预置并锁定增益，完全跳过测量；即使上一首是轻歌被推到
    /// +15dB，切到响歌也不会残留高增益造成开头爆音。
    /// 关键：只写一次 postGain + 重置内存状态，不碰 DynamicsProcessing / Visualizer
    /// 的创建与 release（那些都是 binder 同步调用），保证同步调用也不卡顿。
    ///
    /// [presetGainDb] 由上层按整曲响度算好（有元数据用 `/song/url` 的 `volume`
    /// 实测值；无元数据用曲库均值先验）。[targetRmsDb] 非空时先更新目标响度，
    /// 保证 decision = target - gain 的反推基准正确。
    private fun resetAgcForTrackChange(
        presetGainDb: Float,
        targetRmsDb: Float? = null
    ): Boolean {
        if (targetRmsDb != null && targetRmsDb.isFinite()) {
            agcTargetRmsDb = targetRmsDb.coerceIn(AGC_MIN_TARGET_DBFS, AGC_MAX_TARGET_DBFS)
        }
        val applied = applyPresetGain(presetGainDb)
        Log.d(
            TAG_VOLUME_NORMALIZATION,
            "resetAgcForTrackChange: preset=$applied target=$agcTargetRmsDb agcActive=$agcActive"
        )
        return true
    }

    /// 按整曲响度直接预置增益并锁定（续六起取代开场测定，续十二删除测定）。
    ///
    /// 双向归一化：gain = target - volume，可以为正（补轻歌）也可以为负（压响歌），
    /// 下限 AGC_PRESET_MIN_GAIN_DB=-24。这样每首歌的输出响度都等于目标响度，
    /// 切歌不再出现「这首突然大/小一截」——与网易云的听感一致。
    private fun applyPresetGain(presetGainDb: Float): Float {
        val preset = presetGainDb.coerceIn(AGC_PRESET_MIN_GAIN_DB, AGC_AUTO_MAX_GAIN_DB)
        resetAgcGainState()
        agcCurrentGainDb = preset
        agcDesiredGainDb = preset
        // agcDecisionDb 的语义是「本曲整体响度」，不是增益。由 target - gain 反推，
        // 这样用户改档时 setAgcTarget 用 (新target - decision) 重算才是对的。
        // （旧代码把它写成 preset 本身，改档后会把增益当响度算，偏轻歌改到响亮反而变 0dB。）
        agcDecisionDb = agcTargetRmsDb - preset
        agcLocked = true
        agcTargetAdjusting = false
        lastMeasuredRmsDb = 0.0f
        dynamicsProcessing?.let { applyMbcAndLimiter(it, preset) }
        return preset
    }

    private fun startLoudnessAgc(audioSessionId: Int) {
        // 幂等：同一 session 且 AGC 已在跑时不重建（重建 Visualizer 是 binder 同步调用，
        // 切歌外的高频 configure 会因此卡住主线程）。
        if (agcEnabled && loudnessVisualizer != null) return
        stopLoudnessAgc()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG_VOLUME_NORMALIZATION, "AGC skipped: RECORD_AUDIO permission not granted")
            return
        }
        try {
            val visualizer = Visualizer(audioSessionId)
            // 用官方 PEAK_RMS 测量（系统级 16bit 精度、绝对 mB 电平），不再从 8bit 波形手算 RMS。
            // SCALING_MODE_NORMALIZED(0) 会把波形按内容放大到满幅，测不到真实响度；
            // 虽然 RMS 测量本身不受 scalingMode 影响，仍设为 AS_PLAYED(1) 保持一致语义。
            // 注意：SDK 中并无 SCALING_MODE_NONE 常量，0 就是 NORMALIZED（上一版本的隐藏 bug）。
            visualizer.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            visualizer.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS)
            visualizer.enabled = true
            loudnessVisualizer = visualizer
            agcEnabled = true
            // Visualizer 常驻；整曲 RMS 累积由 consumeTrackRms 在切歌时结算，
            // 不因 Visualizer 启停而清增益状态。

            // 后台线程每 400ms 轮询一次 RMS：getMeasurementPeakRms 是 binder 同步调用，
            // 放主线程会在音频繁忙时阻塞 UI（历史上多次卡顿的根因）。
            val thread = HandlerThread("KA-AgcRms").apply { start() }
            val handler = Handler(thread.looper)
            agcMeasureThread = thread
            agcMeasureHandler = handler
            val poll = object : Runnable {
                override fun run() {
                    if (!agcEnabled) return
                    val v = loudnessVisualizer
                    if (v != null) {
                        // 与 stopLoudnessAgc 存在竞态：visualizer 可能刚被 release，
                        // binder 调用抛异常时安静跳过，下一周期自然恢复。
                        runCatching {
                            val rc = v.getMeasurementPeakRms(agcMeasurement)
                            if (rc == Visualizer.SUCCESS) {
                                // mRms 单位 mB（0=满刻度，-9600=16bit 静音下限），转 dBFS。
                                val measuredDb = agcMeasurement.mRms / 100.0f
                                onAgcMeasuredRms(measuredDb)
                            }
                        }
                    }
                    handler.postDelayed(this, AGC_SAMPLE_INTERVAL_MS)
                }
            }
            handler.post(poll)
            Log.d(TAG_VOLUME_NORMALIZATION, "AGC started: session=$audioSessionId mode=PEAK_RMS")
        } catch (e: Exception) {
            Log.w(TAG_VOLUME_NORMALIZATION, "AGC start failed: ${e.message}")
            stopLoudnessAgc()
        }
    }

    /// 取走并清空「上一曲」的整曲 RMS 累积（续十一）。
    /// 由 Flutter 在切歌最早期调用：此时 currentSong 仍是旧歌，结算值可以写进它的
    /// 响度缓存，让无元数据歌曲第二次播放就按实测值预置增益。
    /// 返回 {rmsDb: Double?, samples: Int}，rmsDb 是线性功率域平均后转回的 dBFS。
    private fun consumeTrackRms(): Map<String, Any?> {
        val samples = trackRmsSamples
        val rmsDb = if (samples > 0) {
            10.0 * Math.log10(trackRmsPowerSum / samples)
        } else {
            null
        }
        val snapshot = mapOf<String, Any?>(
            "rmsDb" to rmsDb,
            "samples" to samples,
        )
        trackRmsPowerSum = 0.0
        trackRmsSamples = 0
        Log.d(
            TAG_VOLUME_NORMALIZATION,
            "consumeTrackRms: rmsDb=$rmsDb samples=$samples"
        )
        return snapshot
    }

    private fun onAgcMeasuredRms(measuredDb: Float) {
        if (!agcEnabled) return
        // measuredDb 来自官方 getMeasurementPeakRms 的 mRms（mB→dBFS），是短窗瞬时电平。
        // 它只用于 UI 展示与累积响度样本，绝不直接换算成增益。
        lastMeasuredRmsDb = measuredDb

        // 整曲响度累积：独立于 agcActive（fixed 也要采）与 agcLocked（锁定后继续采）。
        // 静音/极弱段落不计入（否则底噪被当作歌曲响度），沿用 -55dBFS 门限。
        if (measuredDb >= AGC_SILENCE_RMS_DB) {
            trackRmsPowerSum += Math.pow(10.0, measuredDb.toDouble() / 10.0)
            trackRmsSamples++
        }

        // fixed 模式下 Visualizer 常驻但不做任何增益调整，早退。
        if (!agcActive) return
        if (measuredDb < AGC_SILENCE_RMS_DB) return

        // desired 只由 applyPresetGain / setAgcTarget 设定，此处统一平滑逼近。
        // 上升快、下降平时慢（防听感突降），用户主动改档时临时放开下降步长。
        val gap = agcDesiredGainDb - agcCurrentGainDb
        if (abs(gap) < 0.01f) {
            agcTargetAdjusting = false
            return
        }
        val step = if (gap > 0f) {
            minOf(gap, AGC_FAST_RISE_DB)
        } else {
            val downStep = if (agcTargetAdjusting) AGC_FAST_RISE_DB else AGC_MAX_STEP_DB
            maxOf(gap, -downStep)
        }
        agcCurrentGainDb = (agcCurrentGainDb + step)
            .coerceIn(AGC_PRESET_MIN_GAIN_DB, AGC_AUTO_MAX_GAIN_DB)
        if (abs(agcDesiredGainDb - agcCurrentGainDb) < 0.01f) agcTargetAdjusting = false
        dynamicsProcessing?.let { applyMbcAndLimiter(it, agcCurrentGainDb) }
        Log.d(
            TAG_VOLUME_NORMALIZATION,
            "AGC pulse: gain=${agcCurrentGainDb}dB desired=${agcDesiredGainDb}dB " +
                "measured=${measuredDb}dB target=${agcTargetRmsDb}dB locked=$agcLocked"
        )
    }

    private fun stopLoudnessAgc() {
        // 停掉后台轮询线程，避免 handler 持有已 release 的 visualizer。
        agcMeasureHandler?.removeCallbacksAndMessages(null)
        agcMeasureHandler = null
        // 必须用 quit() 而非 quitSafely()：后者会保留 400ms 延迟消息直到到期，
        // 到那时 visualizer 已 release，poll 会拿着失效句柄调用 binder。
        agcMeasureThread?.quit()
        agcMeasureThread = null
        loudnessVisualizer?.runCatching {
            enabled = false
            setMeasurementMode(Visualizer.MEASUREMENT_MODE_NONE)
            release()
        }
        loudnessVisualizer = null
        agcEnabled = false
        resetAgcGainState()
    }

    private fun releaseDynamicsProcessing() {
        stopLoudnessAgc()
        dynamicsProcessing?.runCatching {
            enabled = false
            release()
        }
        dynamicsProcessing = null
        dynamicsProcessingSessionId = null
    }

    private fun enqueueApkDownload(url: String, fileName: String) {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("九九音乐 更新包")
            .setDescription("正在下载新版本")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)

        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        updateDownloads[downloadId] = fileName
        registerDownloadReceiver()
    }

    private fun registerDownloadReceiver() {
        if (downloadReceiverRegistered) {
            return
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, filter)
        }
        downloadReceiverRegistered = true
    }

    private fun isDownloadSuccessful(downloadId: Long): Boolean {
        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        var cursor: Cursor? = null
        return try {
            cursor = downloadManager.query(query)
            cursor != null &&
                cursor.moveToFirst() &&
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                DownloadManager.STATUS_SUCCESSFUL
        } finally {
            cursor?.close()
        }
    }

    private fun installDownloadedApk(fileName: String) {
        val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (!apkFile.exists()) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        val apkUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(installIntent)
    }

    /// 是否为 Android Automotive 车机设备。
    /// 仅依赖官方 FEATURE_AUTOMOTIVE 标记：国产定制 AOSP 车机通常未声明，
    /// 会判为 false，需用户在设置→个性化手动开启车机模式。
    private fun isAutomotiveDevice(): Boolean {
        return packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    }

    private fun getAlbumArtBytes(albumId: Long): ByteArray? {
        val uri = ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId
        )
        return try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { uri ->
            if (uri.startsWith("kgka://")) {
                deepLinkChannel?.invokeMethod("onDeepLink", uri)
            }
        }
    }

    override fun onDestroy() {
        releaseEqualizer()
        releaseBassBoost()
        if (superLyricRegistered) {
            runCatching { SuperLyricHelper.unregisterPublisher() }
            superLyricRegistered = false
        }
        if (downloadReceiverRegistered) {
            unregisterReceiver(downloadReceiver)
            downloadReceiverRegistered = false
        }
        if (lyricsStateReceiverRegistered) {
            unregisterReceiver(lyricsStateReceiver)
            lyricsStateReceiverRegistered = false
        }
        super.onDestroy()
    }
}
