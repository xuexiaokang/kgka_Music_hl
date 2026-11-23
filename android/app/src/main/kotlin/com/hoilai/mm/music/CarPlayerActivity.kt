package com.hoilai.mm.music

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * 全原生车机全屏播放器（酷狗风换皮，保持横屏左右分栏）：
 *  封面取色渐变背景 + 左侧黑胶转盘(带唱针)/曲名/艺人 + 右侧原生歌词 [CarLyricView]
 *  + 底部带滑块进度条 + 控制行(循环模式 / 上一曲 / 圆形播放 / 下一曲 / 队列)。
 *  顶部左"收起"关闭、右"红心"收藏。全部走 HWUI/Skia，Flutter 引擎被覆盖后停帧。
 *
 * 播放由 Dart 的 PlayerController 负责，本 Activity 只发传输事件并接收状态快照：
 *  - open 时读 [CarPlayerBridge.snapshot] 建 UI
 *  - onResume 发 requestSync 让 Dart 推权威状态
 *  - 本地时钟(elapsedRealtime)在两次 sync 间平滑推进进度/歌词，暂停即停帧让 GPU idle
 *  - sync 额外携带 playMode/isLiked 以刷新循环模式与红心图标
 */
class CarPlayerActivity : Activity() {

    private lateinit var bgImage: ImageView
    private lateinit var bgView: View
    /** 液态玻璃动效的流体极光层（静态 Drawable，不产生持续动画帧） */
    private lateinit var orbView: View
    private lateinit var root: FrameLayout
    private lateinit var lyricView: CarLyricView
    private lateinit var discWrap: FrameLayout
    private lateinit var discGroup: FrameLayout
    private var disc: ImageView? = null
    // [CASSETTE_EXPERIMENT] 实验性磁带视图：单击盘图在「黑胶 / 磁带」间切换。
    // showCassette 必须是字段——buildDisc() 会在尺寸变化时重建视图，需据此恢复形态。
    private var cassetteView: CarCassetteView? = null
    private var showCassette = true
    // 当前曲目文字（供「更多」面板标题复用）。车机全屏顶栏与右侧不再重复显示歌名，
    // 统一由磁带下方的曲目铭牌呈现。
    private var currentTitle = ""
    private var currentArtist = ""
    // 磁带机下方「曲目铭牌」：歌名 / 歌手·专辑 / 当前音质徽章
    private lateinit var infoCard: LinearLayout
    private lateinit var infoTitle: TextView
    private lateinit var infoSub: TextView
    private lateinit var infoQualityBox: LinearLayout
    private lateinit var infoQualityBadge: TextView
    private lateinit var infoQualityLabel: TextView
    private lateinit var infoQualityArrow: ImageView
    // 音质按钮下方就地展开的三档选择（替代原底部弹窗）
    private lateinit var infoQualityOptions: LinearLayout
    private val qualityOptionViews = mutableListOf<TextView>()
    private var qualityOptionsExpanded = false
    private lateinit var seek: SeekBar
    private lateinit var seekRow: LinearLayout
    private lateinit var climaxDot: View
    private lateinit var elapsedText: TextView
    private lateinit var remainText: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var playProgress: android.widget.ProgressBar
    private lateinit var modeBtn: ImageButton
    private lateinit var likeBtn: ImageButton
    private lateinit var moreBtn: ImageButton
    private lateinit var queueOverlay: FrameLayout
    private lateinit var queueList: LinearLayout
    // 队列面板懒重建:切歌时只缓存最新队列数据,打开面板时才真正构建,
    // 避免每次 onMeta 都在 UI 线程 removeAllViews + 逐行 inflate + 逐行起下载线程导致切歌卡顿。
    private var pendingQueueItems: List<Map<*, *>>? = null
    private var queueDirty = false
    private lateinit var queueCountText: TextView
    private lateinit var queueClearBtn: TextView
    private lateinit var queueTitleText: TextView
    private lateinit var queuePanelBg: GradientDrawable
    private lateinit var queueHandleBg: GradientDrawable
    private var sheet: FrameLayout? = null

    // 旋转不走 ValueAnimator：它由 AnimationHandler 每帧驱动，与进度刷新的 tick 不同步，
    // 会让 invalidate 落在额外的一批帧上（实测叠加成 30fps）。改为在 tick 里按经过时间
    // 直接算角度，与进度/歌词共用同一次重绘。
    private var rotRunning = false
    private var rotAnchorUptimeMs = 0L
    private var rotAnchorAngle = 0f
    private var lastDiscSize = -1

    private var anchorPosMs = 0L
    private var anchorUptime = 0L
    private var durationMs = 0L
    private var playing = false
    private var buffering = false
    private var seekDragging = false
    private var coverUrl: String? = null
    private var coverToken = 0
    private var playMode = 0
    private var playModeLabel = ""
    private var wantModeToast = false
    private var liked = false
    private var canLike = true
    private var climaxStartMs = -1L

    // "更多"面板当前状态
    private var qualityIndex = 0
    private var qualityLabel = ""
    private var qualityBadge = ""
    private var speed = 1.0
    private var speedLabel = ""
    private var effectLabel = ""
    private var effectsSupported = true
    private var effectNames: List<String> = emptyList()
    private var desktopLyricsSupported = false
    private var desktopLyricsEnabled = false
    private var sleepActive = false
    private var sleepFinishCurrent = false
    private var sleepRemainingMs: Long? = null

    // 对齐上游车机播放页的扩展能力（补齐差集）
    private var canComment = false          // 当前歌曲是否支持评论（酷狗/网易云）
    private var canAddToPlaylist = false   // 仅酷狗源支持添加到自建歌单
    private var songHash = ""              // 供 Flutter 侧从队列定位当前歌曲
    private var lyricScale = 0.68f          // 歌词字号缩放档位（标准 0.68，原生 SharedPreferences 持久化）
    // Flutter 设置页两个外观开关，经 meta 下发；同时落原生 prefs 供 onCreate 首帧使用
    private var lyricBlurEnabled = false
    private var liquidGlassEnabled = true

    companion object {
        private const val PREF_NAME = "car_player_prefs"
        private const val PREF_LYRIC_SCALE = "lyric_scale"
        private const val PREF_UI_SCALE = "ui_scale"
        // [CASSETTE_EXPERIMENT] 形态持久化 key（删除实验功能时一并移除）
        private const val PREF_SHOW_CASSETTE = "show_cassette"
        private const val PREF_LYRIC_BLUR = "lyric_blur_enabled"
        private const val PREF_LIQUID_GLASS = "liquid_glass_enabled"
        // 与 CarPlayerService._styles() 保持一致的基准字号，缩放时按比例重算
        // （2026-11-22 微调：720P 车机上 48/34/20 基准偏小，整体上浮约 8%；
        //  档位组与标准 0.68 不变，用户已选档位与百分比显示完全不受影响）
        private val LYRIC_SCALES = floatArrayOf(0.58f, 0.68f, 0.78f, 0.88f, 1.02f)
        private const val LYRIC_SCALE_STD = 0.68f

        /** 已收藏心形的红色，对齐 Flutter `Colors.redAccent`。 */
        private val LIKED_RED = 0xFFFF5252.toInt()
        private const val PREF_LYRIC_SCALE_MIGRATED = "lyric_scale_v3_migrated"
    }

    // 队列底部弹窗主题色（对齐 Flutter showModalBottomSheet 的 colorScheme，默认浅色）
    private var thSurface = 0xFFFFFFFF.toInt()
    private var thPrimary = 0xFF1478FF.toInt()
    private var thOnSurface = 0xFF080B12.toInt()
    private var thOnSurfaceVariant = 0xFF6F7785.toInt()
    private var thOutlineVariant = 0xFFE7EDF7.toInt()
    private var thError = 0xFFB3261E.toInt()
    private var thSurfaceContainer = 0xFFF1F3F8.toInt()
    private var thSurfaceContainerHighest = 0xFFE3E7EF.toInt()

    private var clockStarted = false
    private var lastShownSec = -1L

    // 渲染节流（车机弱 GPU：全屏页每帧重绘会占满 SurfaceFlinger 合成预算，连累同屏悬浮视频）
    // 关键：**所有**周期性更新必须走同一个 tick。实测若进度(5Hz)/歌词(12.5Hz)/旋转(15Hz)
    // 各自用独立的定时/动画驱动，三者的 invalidate 会落在不同的帧上叠加成 ~32fps；
    // 合并到单个 15Hz tick 后同帧刷新，页面稳定 15fps。
    private val tickMs = 66L
    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tickTask = object : Runnable {
        override fun run() {
            // 自停时同步复位标志，避免 stopClock 未走到时 startClock 误判为“已在运行”
            if (!playing || isFinishing) { clockStarted = false; return }
            // 缓冲期间真实播放位置不走（Dart 侧时钟已停），锚点插值必须同步冻结，
            // 否则磁带卷盘/进度条会按墙钟虚走，缓冲恢复后回跳（与歌不同步）。
            val cur = if (buffering) anchorPosMs else currentMs()
            // 进度条/时间只到秒精度：秒未变就不碰它，避免无谓的 invalidate
            val sec = cur / 1000
            if (!seekDragging && sec != lastShownSec) {
                lastShownSec = sec
                seek.progress = cur.toInt()
                elapsedText.text = fmt(cur)
                remainText.text = fmt(durationMs)
            }
            if (!lyricView.userInteracting) lyricView.setProgress(cur)
            stepRotation()
            // [CASSETTE_EXPERIMENT] 磁带卷轴与卷径由同一 tick 的绝对进度驱动，不额外起帧
            cassetteView?.setProgress(cur, durationMs)
            if (playing) tickHandler.postDelayed(this, tickMs)
        }
    }

    private fun startClock() {
        if (clockStarted) return
        clockStarted = true
        lastShownSec = -1L
        tickHandler.post(tickTask)
    }

    private fun stopClock() {
        clockStarted = false
        tickHandler.removeCallbacks(tickTask)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density)

    // ---- 车机界面缩放：Flutter「车机界面缩放」档位（theme.car_mode_scale） ----
    // 此前该档位只作用于 Flutter 渲染的分栏页（textScaler），原生接管的全屏页
    // 全部尺寸硬编码 sp/dp，高分屏低密度下内容偏小且档位无效。
    // 做法：buildUi 前把 Activity Resources 的 density/scaledDensity 按档位放大，
    // dp()/textSize(sp) 全部随之缩放（磁带视图按 View 尺寸自适应，不受影响）。
    private var uiScale = 1.15f
    private var baseDensity = 0f
    private var baseScaledDensity = 0f
    private var baseDensityDpi = 0

    private fun applyUiScale(scale: Float) {
        uiScale = scale.coerceIn(0.85f, 1.5f)
        val dm = resources.displayMetrics
        if (baseDensity <= 0f) {
            baseDensity = dm.density
            baseScaledDensity = dm.scaledDensity
            baseDensityDpi = dm.densityDpi
        }
        // 自动密度归一：车机屏常是「高物理分辨率 + 低系统密度」（如 2560×1440@160dpi），
        // 同样的 dp 在这类屏上内容占比只有 720p 基准的一半。按「短边逻辑尺寸 / 720dp」
        // 等比归一，让任何分辨率下内容占比与基准 1280×720@1x 一致；
        // 用户档位再在其上微调。
        val shortDp = minOf(dm.widthPixels, dm.heightPixels) / baseDensity
        val auto = (shortDp / 720f).coerceIn(0.75f, 2.0f)
        val total = (uiScale * auto).coerceIn(0.6f, 3.0f)
        dm.density = baseDensity * total
        dm.scaledDensity = baseScaledDensity * total
        dm.densityDpi = (baseDensityDpi * total).toInt().coerceAtLeast(120)
    }

    /** 从 open 快照 / 上次持久化档位取缩放值并持久化（buildUi 前调用）。 */
    private fun resolveUiScale(): Float {
        val snap = ((CarPlayerBridge.snapshot?.get("meta")) as? Map<*, *>)
            ?.get("uiScale") as? Number
        val saved = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .getFloat(PREF_UI_SCALE, 1.15f)
        val scale = ((snap?.toFloat() ?: saved)).coerceIn(0.85f, 1.5f)
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
            .putFloat(PREF_UI_SCALE, scale).apply()
        return scale
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // [CASSETTE_EXPERIMENT] 恢复上次选择的盘面形态（默认磁带）
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        showCassette = prefs.getBoolean(PREF_SHOW_CASSETTE, true)
        // 两个外观开关：先用上次落盘值建 UI，随后 onMeta 用 Flutter 最新值覆盖
        lyricBlurEnabled = prefs.getBoolean(PREF_LYRIC_BLUR, false)
        liquidGlassEnabled = prefs.getBoolean(PREF_LIQUID_GLASS, true)
        // 车机界面缩放：必须在 buildUi 之前生效（dp/sp 随 density 全局缩放）
        applyUiScale(resolveUiScale())
        buildUi()
        applyLyricBlur(lyricBlurEnabled)
        applyLiquidGlass(liquidGlassEnabled)
        setDefaultBackground()

        (CarPlayerBridge.snapshot)?.let { applySnapshot(it) }
    }

    override fun onResume() {
        super.onResume()
        CarPlayerBridge.current = this
        CarPlayerBridge.sendEvent("requestSync")
        applyPlayState(playing)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 窗口获得焦点时 View 树必然已完成至少一次 layout，宽高可靠。
        // 冷启动直接进全屏时 OnLayoutChangeListener 会错过首次 layout，
        // root.post 也可能早于 measure；在此再兜一次底，保证盘面必定出现。
        if (hasFocus) root.post { ensureDiscBuilt() }
    }

    override fun onPause() {
        super.onPause()
        if (CarPlayerBridge.current === this) CarPlayerBridge.current = null
    }

    override fun onDestroy() {
        if (CarPlayerBridge.current === this) CarPlayerBridge.current = null
        stopRotation()
        stopClock()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (sheet != null) {
            closeSheet()
            return
        }
        if (queueOverlay.visibility == View.VISIBLE) {
            hideQueue()
            return
        }
        CarPlayerBridge.sendEvent("closed")
        super.onBackPressed()
        overridePendingTransition(0, 0)
    }

    /** Dart 主动要求关闭（不回调 closed，因为是 Dart 触发的）。 */
    fun finishExternally() {
        runOnUiThread {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        root = FrameLayout(this)

        // ---- 背景（对齐 Flutter _PlayerBackground）：模糊封面 + 竖向黑色渐变遮罩，兜底对角渐变
        bgImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(
            bgImage, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        bgView = View(this)
        root.addView(
            bgView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // 液态玻璃动效：叠在背景遮罩之上、内容之下的流体极光层。
        // 用 GONE 完全移除绘制与合成开销，关闭时零成本。
        orbView = View(this).apply { background = AuroraDrawable() }
        root.addView(
            orbView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(24f).toInt(), dp(10f).toInt(), dp(30f).toInt(), dp(36f).toInt())
        }

        // ---- 头部：返回(圆钮) / 标题+艺人 / 收藏(圆钮) / 更多(圆钮)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            circleIconButton(R.drawable.ic_kg_chevron_left, 34f, 44f, R.drawable.bg_circle_white12) {
                CarPlayerBridge.sendEvent("closed"); finish(); overridePendingTransition(0, 0)
            },
            lp(dp(44f).toInt(), dp(44f).toInt())
        )
        // 顶栏不再重复显示歌名/歌手（磁带下方铭牌已呈现），
        // 只保留返回 / 收藏 / 更多，用弹性占位把右侧按钮推到端头。
        header.addView(View(this), lpWeight(1f))
        likeBtn = circleIconButton(R.drawable.ic_kg_heart_border, 24f, 44f, R.drawable.bg_circle_white12) {
            CarPlayerBridge.sendEvent("like")
        }
        header.addView(likeBtn, lp(dp(44f).toInt(), dp(44f).toInt()))
        header.addView(space(dp(8f).toInt(), 0))
        moreBtn = circleIconButton(R.drawable.ic_kg_more_horiz, 24f, 44f, R.drawable.bg_circle_white12) {
            showMoreSheet()
        }
        header.addView(moreBtn, lp(dp(44f).toInt(), dp(44f).toInt()))
        // 高度钉死为按钮高度：顶栏内部只用带 weight 的空占位撑开左右两侧。
        // 若沿用 WRAP_CONTENT，空的 weight 占位会在测量中被拉伸到父容器可用高度，
        // 把 bodyRow(weight=1) 挤成 0 高，磁带、歌词、控制行会整块消失。
        content.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44f).toInt()
        ))
        content.addView(space(0, dp(10f).toInt()))

        // ---- 主体：左唱片(flex 9) + 右面板(flex 12)
        val bodyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // 左栏：磁带机保持原来的全宽全高（尺寸与旧版完全一致，黄金比例不受影响），
        // 曲目铭牌作为同层 FrameLayout 贴底叠加，不参与 discWrap 测量。
        // 不用「垂直 LinearLayout + weight」——嵌套 weight 会让 discWrap 高度测量异常，
        // 导致 addOnLayoutChangeListener 拿不到尺寸、盘面根本不构建。
        val leftFrame = FrameLayout(this)
        discWrap = FrameLayout(this)
        leftFrame.addView(discWrap, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        // 信息区（歌名铭牌 + 独立一行音质按钮）整体贴底并同步上移 48dp。
        // 不再给左右 margin：歌名框宽度由 buildDisc() 校正为盘面 sizePx，
        // 靠 CENTER_HORIZONTAL 与磁带同心对齐；窄屏下也不会因固定 margin 溢出。
        leftFrame.addView(buildTrackInfoBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).also {
            it.bottomMargin = dp(48f).toInt()
        })
        // 三档音质展开行独立叠在 leftFrame 底部，不参与信息区测量：
        // 展开/收起只切自身可见性，音质胶囊与歌名铭牌位置始终不动。
        leftFrame.addView(infoQualityOptions, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).also { it.bottomMargin = dp(6f).toInt() })
        // 上移只改「子 View 的 margin」，绝不缩小左栏容器高度：
        // 盘面按 min(宽,高)·0.786 计算，16:9 下宽度是短边没问题，
        // 但超宽车机屏（1920×720 等）经密度归一后左栏高度会成为短边，
        // 一旦缩小容器高度就会连带把磁带算小。见 buildDisc 里的 bottomMargin。
        bodyRow.addView(leftFrame, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 9f
        ))
        bodyRow.addView(space(dp(34f).toInt(), 0))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        // 歌名/歌手统一由磁带下方铭牌显示，右侧不再重复；仅留少量顶部间距给歌词。
        right.addView(space(0, dp(8f).toInt()))

        lyricView = CarLyricView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            onLineSeek = { ms ->
                anchorPosMs = ms; anchorUptime = android.os.SystemClock.elapsedRealtime()
                CarPlayerBridge.sendEvent("seek", mapOf("ms" to ms))
            }
        }
        right.addView(lyricView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        right.addView(space(0, dp(6f).toInt()))

        // 进度：白色滑块 + 高潮圆点，下方 已播 / 总时长
        val seekWrap = FrameLayout(this)
        seek = SeekBar(this).apply {
            max = 1
            maxHeight = dp(3f).toInt()
            progressDrawable = resources.getDrawable(R.drawable.kg_seek_track, null)
            thumb = resources.getDrawable(R.drawable.kg_seek_thumb, null)
            // 滑块 drawable 实际是 24dp 光晕域（8dp 圆核心 + 8dp inset）：
            // offset/padding = 域半径 12dp，0%/100% 时圆心恰对轨道端点，
            // 光晕与圆都不会越出视图被裁（此前 offset 5dp > padding 4dp 左端仍裁 1dp）。
            thumbOffset = dp(12f).toInt()
            splitTrack = false
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) elapsedText.text = fmt(p.toLong())
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { seekDragging = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seekDragging = false
                    val ms = (sb?.progress ?: 0).toLong()
                    anchorPosMs = ms; anchorUptime = android.os.SystemClock.elapsedRealtime()
                    CarPlayerBridge.sendEvent("seek", mapOf("ms" to ms))
                }
            })
        }
        seekWrap.addView(seek, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        climaxDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
                setStroke(dp(1f).toInt(), 0x66000000)
            }
            visibility = View.GONE
            isClickable = false
        }
        seekWrap.addView(climaxDot, FrameLayout.LayoutParams(dp(7f).toInt(), dp(7f).toInt()).also {
            it.gravity = Gravity.CENTER_VERTICAL
        })
        right.addView(seekWrap, lpMatchWrap())
        seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f).toInt(), 0, dp(2f).toInt(), 0)
        }
        elapsedText = TextView(this).apply { setTextColor(0xA3FFFFFF.toInt()); textSize = 12f; text = "0:00" }
        remainText = TextView(this).apply { setTextColor(0xA3FFFFFF.toInt()); textSize = 12f; text = "0:00" }
        seekRow.addView(elapsedText)
        seekRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        seekRow.addView(remainText)
        right.addView(seekRow, lpMatchWrap().also { it.topMargin = dp(2f).toInt() })
        right.addView(space(0, dp(4f).toInt()))

        // 控制行：循环 / 上一曲 / 播放(半透白圆) / 下一曲 / 队列
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        modeBtn = circleIconButton(R.drawable.ic_kg_mode_loop, 28f, 48f, 0) {
            wantModeToast = true; CarPlayerBridge.sendEvent("playMode")
        }
        btnRow.addView(modeBtn, lp(dp(48f).toInt(), dp(48f).toInt()))
        btnRow.addView(space(dp(20f).toInt(), 0))
        btnRow.addView(
            circleIconButton(R.drawable.ic_kg_prev, 44f, 60f, 0) { CarPlayerBridge.sendEvent("prev") },
            lp(dp(60f).toInt(), dp(60f).toInt())
        )
        btnRow.addView(space(dp(20f).toInt(), 0))
        val playWrap = FrameLayout(this)
        playBtn = ImageButton(this).apply {
            setBackgroundResource(R.drawable.bg_circle_white18)
            setImageResource(R.drawable.ic_kg_play)
            setColorFilter(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(10f).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { CarPlayerBridge.sendEvent("playPause") }
        }
        playProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        playWrap.addView(playBtn, FrameLayout.LayoutParams(dp(80f).toInt(), dp(80f).toInt()))
        playWrap.addView(
            playProgress,
            FrameLayout.LayoutParams(dp(36f).toInt(), dp(36f).toInt(), Gravity.CENTER)
        )
        btnRow.addView(playWrap, lp(dp(80f).toInt(), dp(80f).toInt()))
        btnRow.addView(space(dp(20f).toInt(), 0))
        btnRow.addView(
            circleIconButton(R.drawable.ic_kg_next, 44f, 60f, 0) { CarPlayerBridge.sendEvent("next") },
            lp(dp(60f).toInt(), dp(60f).toInt())
        )
        btnRow.addView(space(dp(20f).toInt(), 0))
        btnRow.addView(
            circleIconButton(R.drawable.ic_kg_queue, 28f, 48f, 0) { showQueue() },
            lp(dp(48f).toInt(), dp(48f).toInt())
        )
        right.addView(btnRow, lpMatchWrap().also { it.topMargin = dp(4f).toInt() })

        bodyRow.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 12f))
        content.addView(bodyRow)

        root.addView(content)
        root.addView(buildQueueOverlay())

        setContentView(root)

        // 盘面在首帧按左栏实际尺寸构建。
        // 黄金尺寸：盘面面积 : 周围留白面积 = φ : 1（面积域黄金分割）
        //   ⇒ S = min(w,h)·√(φ/(1+φ)) = √0.618 ≈ 0.786（原 0.72 偏小，+9%）
        // 上限 352→400dp 同步放宽，避免横屏大屏下被钳制吃掉黄金比例
        // 关键：回调发生在布局遍历「内部」，此时若直接 addView，新子 View 不会被
        // 本次遍历 measure/layout，尺寸停在 0 且永不绘制。一律 post 到遍历之后再构建。
        discWrap.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            discWrap.post { ensureDiscBuilt() }
        }
        // 监听器在 setContentView 之后注册，只会在「后续」布局变化时回调；若首次
        // layout 已结束且此后无 relayout（冷启动直接进全屏时常见），盘面会一直空白。
        // 用 post 在首帧主动兜底一次，保证任何进入路径都能出盘面。
        root.post { ensureDiscBuilt() }
    }

    /** 按左栏当前尺寸构建/重建盘面；尺寸未变则跳过，避免重复 removeAllViews。 */
    private fun ensureDiscBuilt() {
        val w = discWrap.width; val h = discWrap.height
        if (w <= 0 || h <= 0) return
        val sizePx = (minOf(w, h) * 0.786f).coerceIn(dp(120f), dp(400f)).toInt()
        if (sizePx != lastDiscSize) { lastDiscSize = sizePx; buildDisc(sizePx) }
    }

    private fun circleIconButton(
        resId: Int, iconSizeDp: Float, sizeDp: Float, bgRes: Int, onTap: () -> Unit
    ): ImageButton {
        val s = dp(sizeDp).toInt()
        val icon = dp(iconSizeDp).toInt()
        return ImageButton(this).apply {
            if (bgRes != 0) setBackgroundResource(bgRes) else setBackgroundColor(Color.TRANSPARENT)
            setImageResource(resId)
            setColorFilter(0xFFFFFFFF.toInt())
            // FIT_CENTER 才会把矢量按 padding 后的盒放大绘制；CENTER_INSIDE 只按 intrinsic(24dp) 显示、不会放大
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = ((s - icon) / 2).coerceAtLeast(0)
            setPadding(pad, pad, pad, pad)
            contentDescription = null
            setOnClickListener { onTap() }
        }
    }

    /**
     * 磁带机下方的信息区：歌名 / 歌手文字独占一行 + 音质选择按钮单独放在最下面一行。
     * 不画边框也不铺底色，文字直接浮在背景上；infoCard 宽度在 buildDisc() 里同步为
     * 盘面 sizePx，配合 CENTER 让文字块与磁带同心对齐。
     * 长歌名此前被同行右侧的音质徽章挤占宽度而截断，分行后可用整框宽度跑马灯。
     */
    private fun buildTrackInfoBar(): View {
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 两个子 View 都按水平中心对齐：歌名文字与磁带同心，音质按钮单独居中一行。
            gravity = Gravity.CENTER_HORIZONTAL
        }

        infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 无边框、无底色：只保留内边距，文字靠 WRAP_CONTENT + CENTER 居中于磁带正下方。
            gravity = Gravity.CENTER
            val h = dp(14f).toInt()
            val v = dp(10f).toInt()
            setPadding(h, v, h, v)
        }
        infoTitle = marqueeText(21f, 0xF2FFFFFF.toInt(), true)
        infoSub = TextView(this).apply {
            setTextColor(0x9AFFFFFF.toInt())
            textSize = 12f
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // WRAP_CONTENT：短歌名收缩后由父容器居中；超长歌名撑满父宽继续跑马灯，
        // 两种情况下文字块中心都与磁带一致。
        infoCard.addView(infoTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        infoCard.addView(infoSub, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(3f).toInt() })
        // 初始占满左栏；首帧 buildDisc() 按盘面真实尺寸改成等宽居中。
        stack.addView(infoCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        infoQualityBox = LinearLayout(this).apply {
            // 单行横向胶囊：规格 + 中文名 + 下拉箭头全在一行，绝不换行。
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val h = dp(12f).toInt()
            val v = dp(6f).toInt()
            setPadding(h, v, h, v)
            // 音质徽章本身就是按钮：点击在其正下方就地展开三档，不再另弹面板。
            isClickable = true
            isFocusable = true
            contentDescription = "切换音质"
            setOnClickListener { toggleQualityOptions() }
        }
        infoQualityBadge = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            isSingleLine = true
            includeFontPadding = false
        }
        infoQualityLabel = TextView(this).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            isSingleLine = true
            includeFontPadding = false
        }
        // 下拉箭头放最右：左 chevron 旋转 -90° 成向下，明确提示「可展开选择」。
        infoQualityArrow = ImageView(this).apply {
            setImageResource(R.drawable.ic_kg_chevron_left)
            rotation = -90f
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.8f
        }
        infoQualityBox.addView(
            infoQualityBadge,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        infoQualityBox.addView(
            infoQualityLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = dp(6f).toInt() }
        )
        infoQualityBox.addView(
            infoQualityArrow,
            LinearLayout.LayoutParams(dp(11f).toInt(), dp(11f).toInt()).also {
                it.marginStart = dp(4f).toInt()
            }
        )
        // 音质按钮独立占最下面一行、居中；不再与歌名同行抢宽度。
        stack.addView(
            infoQualityBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(10f).toInt() }
        )
        // 三档音质就地列在音质按钮正下方：点击胶囊展开/收起，点某档即切换。
        // 无外框、无底色：只保留三枚独立档位胶囊（各自配色见 applyQualityOptions）。
        // 该行在 buildBody 里单独叠加到 leftFrame，不参与本 stack 测量，故展开不推动胶囊。
        infoQualityOptions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        listOf("标准 128K" to 0, "高品 320K" to 1, "无损 FLAC" to 2).forEach { (label, idx) ->
            val btn = TextView(this).apply {
                text = label
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                val hp = dp(9f).toInt()
                val vp = dp(6f).toInt()
                setPadding(hp, vp, hp, vp)
                isClickable = true
                contentDescription = label
                setOnClickListener { selectQuality(idx) }
            }
            qualityOptionViews.add(btn)
            infoQualityOptions.addView(
                btn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { if (idx > 0) it.marginStart = dp(4f).toInt() }
            )
        }
        applyTrackQuality()
        return stack
    }

    /** 音质胶囊：规格 + 中文名 + 箭头同一行，档位配色（标准灰白 / 高品蓝 / 无损金）。 */
    private fun applyTrackQuality() {
        val bgColor: Int
        val badgeColor: Int
        val labelColor: Int
        when (qualityIndex) {
            2 -> {
                bgColor = 0x2FE3B052.toInt(); badgeColor = 0xFFF5D79B.toInt()
                labelColor = 0xCCF5D79B.toInt()
            }
            1 -> {
                bgColor = 0x2F5C9BFF.toInt(); badgeColor = 0xFFB6D2FF.toInt()
                labelColor = 0xCCB6D2FF.toInt()
            }
            else -> {
                bgColor = 0x26FFFFFF.toInt(); badgeColor = 0xEBFFFFFF.toInt()
                labelColor = 0x99FFFFFF.toInt()
            }
        }
        // 可点击按钮：圆角底 + 提亮描边 + 按压 ripple（mask 把水波纹限在圆角内）。
        val base = GradientDrawable().apply {
            cornerRadius = dp(20f)
            setColor(bgColor)
            setStroke(
                dp(1f).toInt().coerceAtLeast(1),
                (badgeColor and 0x00FFFFFF) or (0x5A shl 24)
            )
        }
        val mask = GradientDrawable().apply {
            cornerRadius = dp(20f)
            setColor(0xFFFFFFFF.toInt())
        }
        infoQualityBox.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF.toInt()), base, mask
        )
        infoQualityBadge.setTextColor(badgeColor)
        infoQualityLabel.setTextColor(labelColor)
        infoQualityArrow.setColorFilter(badgeColor)
        infoQualityBadge.text = qualityBadge.ifEmpty { "—" }
        infoQualityLabel.text = qualityLabel.ifEmpty { "音质" }
        applyQualityOptions()
    }

    /** 点击音质胶囊：在其正下方就地展开/收起三档按钮。 */
    private fun toggleQualityOptions(expand: Boolean? = null) {
        if (!::infoQualityOptions.isInitialized) return
        val toExpand = expand ?: !qualityOptionsExpanded
        qualityOptionsExpanded = toExpand
        infoQualityOptions.visibility = if (toExpand) View.VISIBLE else View.GONE
        infoQualityArrow.rotation = if (toExpand) 90f else -90f
    }

    /** 三档按钮：标准灰白 / 高品蓝 / 无损金，各自配色；选中档更亮，未选中保留色相但弱化。 */
    private fun applyQualityOptions() {
        if (!::infoQualityOptions.isInitialized) return
        qualityOptionViews.forEachIndexed { idx, btn ->
            val selected = idx == qualityIndex
            val tone = when (idx) {
                2 -> 0xFFF5D79B.toInt() to 0x2FE3B052.toInt()   // 无损：金
                1 -> 0xFFB6D2FF.toInt() to 0x2F5C9BFF.toInt()   // 高品：蓝
                else -> 0xEBFFFFFF.toInt() to 0x26FFFFFF.toInt() // 标准：灰白
            }
            val fg = (tone.first and 0x00FFFFFF) or
                ((if (selected) 0xFF else 0xB3) shl 24)
            val bg = (tone.second and 0x00FFFFFF) or
                ((if (selected) 0xFF else 0x70) shl 24)
            val stroke = (tone.first and 0x00FFFFFF) or
                ((if (selected) 0x99 else 0x3D) shl 24)
            btn.background = GradientDrawable().apply {
                cornerRadius = dp(8f)
                setColor(bg)
                setStroke(dp(1f).toInt().coerceAtLeast(1), stroke)
            }
            btn.setTextColor(fg)
        }
    }

    /** 就地选中音质：先本地刷新徽章与选中态，发事件由 Flutter 重载后回推 meta 校正。 */
    private fun selectQuality(idx: Int) {
        qualityIndex = idx
        qualityBadge = when (idx) { 2 -> "FLAC"; 1 -> "320K"; else -> "128K" }
        qualityLabel = when (idx) { 2 -> "无损音质"; 1 -> "高品音质"; else -> "标准音质" }
        applyTrackQuality()
        toggleQualityOptions(false)
        CarPlayerBridge.sendEvent("setQuality", mapOf("qualityIndex" to idx))
    }

    private fun marqueeText(sizeSp: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            setTextColor(color)
            textSize = sizeSp
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            isSingleLine = true
            isSelected = true
        }

    private fun space(w: Int, h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(w, h)
    }

    private fun lp(w: Int, h: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h)

    private fun lpMatchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun lpWeight(weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)

    /** 网易云风黑胶：细密沟槽盘体 + 中心封面(0.585R) + 静态方向光感/边缘亮弧；盘体随 discGroup 旋转。 */
    private fun buildDisc(sizePx: Int) {
        // 歌名铭牌与盘面等宽、同心：盘面按 min(w,h)·0.786 算出 sizePx，
        // 同一数值直接给铭牌，任何屏幕比例下两者左右边界都严格对齐。
        // 只在宽度变化时赋值，避免触发无意义的 relayout 循环。
        val cardLp = infoCard.layoutParams as? LinearLayout.LayoutParams
        if (cardLp != null && cardLp.width != sizePx) {
            cardLp.width = sizePx
            infoCard.layoutParams = cardLp
        }
        discWrap.removeAllViews()

        // [CASSETTE_EXPERIMENT] BEGIN —— 黑胶 / 磁带二选一，删除时移除本 if 分支的磁带侧即可
        if (showCassette) {
            // 仍初始化 discGroup 占位：startRotation/stepRotation 的 ::discGroup 守卫依赖它，
            // 磁带形态下不 addView、不设硬件层，转动完全由 setProgress 从绝对进度驱动。
            discGroup = FrameLayout(this)
            disc = null
            val cassette = CarCassetteView(this)
            cassetteView = cassette
            discWrap.addView(cassette, FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER).also {
                // 与铭牌同步上移：FrameLayout 的 CENTER_VERTICAL 居中计算会减去
                // bottomMargin，盘面整体上移该值（不是 margin/2），与铭牌同量，
                // 两者间距保持不变。用子 View margin 而非缩小容器，
                // 保证任何屏幕比例下盘面尺寸都不变。
                it.bottomMargin = dp(48f).toInt()
            })
        } else {
            cassetteView = null
            // 黑胶是「满圆」(半径 = size/2)，磁带本体却只占 View 宽的 96%、上下留白；
            // 两者共用同一 sizePx 时，黑胶底缘会顶到甚至压住下方歌名(1280×720 实测 0 间距)。
            // 这里外层旋转组与铭牌宽度仍用 sizePx(对齐关系 / margin 不变)，
            // 只把圆盘实际绘制尺寸收 16% 并在容器内居中 → 底缘上移 (1-k)·sizePx/2，
            // 配合下方 16dp 额外上移，实机 720P / 超宽屏分别留出 39px / 22px 间距；
            // 歌名框宽度与盘心不变。
            val vinylSize = (sizePx * 0.84f).toInt()
            // 超宽车机屏下 sizePx 由左栏高度决定、比 720P 大约 25%，盘心不变时
            // 半径变大会把底缘重新压到歌名上；故黑胶在共用 48dp 上移之外再抬 16dp，
            // 让任意屏幕比例下都有呼吸空间（磁带分支仍保持 48dp 不变）。
            val vinylBottomMargin = dp(48f).toInt() + dp(16f).toInt()
            // 旋转组：黑胶盘体(细密纹路位图) + 中心圆形封面。沟槽是径向对称的，
            // 所以旋转时只有封面看得出在转，符合网易云观感。
            discGroup = FrameLayout(this)

            val vinyl = ImageView(this).apply {
                setImageBitmap(makeVinylBitmap(vinylSize))
                scaleType = ImageView.ScaleType.FIT_XY
            }
            discGroup.addView(vinyl, FrameLayout.LayoutParams(vinylSize, vinylSize, Gravity.CENTER))

            val cover = ImageView(this).apply {
                // 圆形封面在位图里预抗锯齿(circleize)，故不再用 clipToOutline 硬裁(硬裁边缘有锯齿)
                // 不再单独设硬件层：旋转组整体已设层，组内再套层会多一次离屏合成
                scaleType = ImageView.ScaleType.FIT_XY
            }
            disc = cover
            val coverSize = (vinylSize * 0.585f).toInt()
            discGroup.addView(cover, FrameLayout.LayoutParams(coverSize, coverSize, Gravity.CENTER))

            discWrap.addView(discGroup, FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER).also {
                it.bottomMargin = vinylBottomMargin
            })

            // 静态光泽层(不随盘旋转):大尺度方向光感 + 外缘斜面受光亮弧。
            // 与圆盘同用 vinylSize；FrameLayout CENTER 的中心与 childHeight 无关，
            // 同样的 bottomMargin 下亮弧仍精确贴合缩小后的盘缘。
            val conicSheen = ImageView(this).apply {
                setImageBitmap(makeSheenBitmap(vinylSize))
                scaleType = ImageView.ScaleType.FIT_XY
            }
            discWrap.addView(conicSheen, FrameLayout.LayoutParams(vinylSize, vinylSize, Gravity.CENTER).also {
                it.bottomMargin = vinylBottomMargin
            })
        }
        // [CASSETTE_EXPERIMENT] END

        // 左右滑动切歌（对齐 Flutter _LandscapeArtworkShowcase.onHorizontalDragEnd）
        val discGesture = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent) = true
                // [CASSETTE_EXPERIMENT] 长按盘图切换黑胶 / 磁带（单击不再切换，避免误触）；
                // 左右滑动切歌保持不变
                override fun onLongPress(e: android.view.MotionEvent) {
                    showCassette = !showCassette
                    // [CASSETTE_EXPERIMENT] 持久化形态，下次进入车机全屏保持选择
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                        .putBoolean(PREF_SHOW_CASSETTE, showCassette).apply()
                    stopRotation()
                    if (lastDiscSize > 0) buildDisc(lastDiscSize)
                }
                override fun onFling(
                    e1: android.view.MotionEvent?, e2: android.view.MotionEvent,
                    vx: Float, vy: Float
                ): Boolean {
                    if (kotlin.math.abs(vx) > 200f && kotlin.math.abs(vx) > kotlin.math.abs(vy)) {
                        if (vx < 0) CarPlayerBridge.sendEvent("next") else CarPlayerBridge.sendEvent("prev")
                        return true
                    }
                    return false
                }
            })
        discWrap.isClickable = true
        discWrap.setOnTouchListener { _, ev -> discGesture.onTouchEvent(ev) }

        // 换歌后封面图重挂到新的 disc；恢复旋转动画与当前封面
        coverUrl?.let { loadCover(it) }
        // [CASSETTE_EXPERIMENT] 磁带形态下黑胶旋转不启动；进度/封面由 tick 与 loadCover 喂给磁带 View
        if (playing && !showCassette) startRotation()
        cassetteView?.setProgress(currentMs(), durationMs)
    }

    /** 生成网易云风黑胶盘体位图(2x 超采样防摩尔纹;只画径向对称元素——底色/沟槽/暗侧面,随盘旋转)。 */
    private fun makeVinylBitmap(size: Int): Bitmap {
        // 2x 超采样:细沟槽按 1x 直接画会与像素网格共振产生摩尔纹/水波纹,先画大图再缩小
        val ss = (size * 2).coerceAtMost(2048)
        val big = Bitmap.createBitmap(ss, ss, Bitmap.Config.ARGB_8888)
        val c = Canvas(big)
        val cx = ss / 2f
        val cy = ss / 2f
        val r = ss / 2f

        // 深黑盘体(v5):真黑胶盘面是近纯黑,原 1B/12/0A 偏灰被实测否决
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, r,
                intArrayOf(0xFF0E0E11.toInt(), 0xFF0A0A0C.toInt(), 0xFF060608.toInt()),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
            )
        }
        c.drawCircle(cx, cy, r, base)

        // 同心沟槽:网易云贴图是细密、隐约可见的纹理(55 条,单条极细,低对比);
        // "亮区里沟槽更明显"的效果由静态 sheen 层的方向光完成,这里保持径向对称
        val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (ss * 0.0016f).coerceAtLeast(1f)
        }
        val inner = r * 0.645f
        val outer = r * 0.955f
        val rings = 55
        val step = (outer - inner) / rings
        var rr = inner
        var i = 0
        while (i <= rings) {
            groove.color = if (i % 2 == 0) 0x0EFFFFFF else 0x14000000
            c.drawCircle(cx, cy, rr, groove)
            rr += step
            i++
        }

        // 最外暗侧面(v6b 极简边):收窄到 0.99R~R 且强度减半,
        // 原宽暗带被实测判"外圈大边不和谐"
        val sideBand = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            addCircle(cx, cy, r * 0.99f, Path.Direction.CW)
            fillType = Path.FillType.EVEN_ODD
        }
        c.save()
        c.clipPath(sideBand)
        c.drawColor(0x4D000000.toInt())
        c.restore()

        // 标签孔外的细暗环:把中心封面与黑胶盘体分出一圈边界
        val labelDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (ss * 0.01f).coerceAtLeast(1f)
            color = 0x66000000
        }
        c.drawCircle(cx, cy, r * 0.60f, labelDark)

        // 最外硬收边
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (ss * 0.004f).coerceAtLeast(1f)
            color = 0xFF060607.toInt()
        }
        c.drawCircle(cx, cy, r - rim.strokeWidth / 2f, rim)

        // 缩回目标尺寸(FILTER_BITMAP 双线性,顺带抗锯齿)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawBitmap(
            big, null,
            android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        big.recycle()
        return bmp
    }

    /** 生成静态光泽层(透明底,不随盘旋转):大尺度方向光感 + 外缘斜面受光亮弧。 */
    private fun makeSheenBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f

        // 1) 窄叶方向光感(v5):原宽叶覆盖大半圆导致整面发灰;现收窄到 ±55°,
        //    半宽处平滑归零,其余角度全透明——大部分盘面保持纯黑
        val glowSweep = SweepGradient(
            cx, cy,
            intArrayOf(
                0x26FFFFFF, 0x13FFFFFF, 0x00FFFFFF,
                0x00FFFFFF, 0x13FFFFFF, 0x26FFFFFF
            ),
            floatArrayOf(0f, 0.0765f, 0.153f, 0.847f, 0.9235f, 1f)
        )
        glowSweep.setLocalMatrix(Matrix().apply { setRotate(-125f, cx, cy) })
        c.save()
        c.clipPath(
            Path().apply {
                addCircle(cx, cy, r, Path.Direction.CW)
                addCircle(cx, cy, r * 0.60f, Path.Direction.CW)
                fillType = Path.FillType.EVEN_ODD
            }
        )
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glowSweep })
        c.restore()

        // 2) 外缘斜面受光亮弧(v6b 极简边):峰值 0x30→0x16,带收窄到
        //    0.955R~0.985R,仅受光侧 ±75°;上一版宽亮带被判不和谐
        val bevelSweep = SweepGradient(
            cx, cy,
            intArrayOf(
                0x16FFFFFF, 0x0BFFFFFF, 0x00FFFFFF,
                0x00FFFFFF, 0x0BFFFFFF, 0x16FFFFFF
            ),
            floatArrayOf(0f, 0.104f, 0.208f, 0.792f, 0.896f, 1f)
        )
        bevelSweep.setLocalMatrix(Matrix().apply { setRotate(-125f, cx, cy) })
        c.save()
        c.clipPath(
            Path().apply {
                addCircle(cx, cy, r * 0.985f, Path.Direction.CW)
                addCircle(cx, cy, r * 0.955f, Path.Direction.CW)
                fillType = Path.FillType.EVEN_ODD
            }
        )
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bevelSweep })
        c.restore()

        return bmp
    }

    // --------------------------------------------------------- 队列面板

    /**
     * 弹层宽度：对齐 Flutter `showModalBottomSheet` 的默认约束
     * `BoxConstraints(maxWidth: 640)`（M3 默认），窄屏时退化为满宽。
     *
     * 原生此前三个弹层都用 MATCH_PARENT，在 2560dp 宽的车机屏上会铺满整屏，
     * 宽度占比和普通横屏的底部弹窗（屏宽 25%）完全对不上。
     */
    private fun sheetWidth(): Int =
        min(resources.displayMetrics.widthPixels, dp(640f).toInt())

    /** 弹层高度上限：对齐 Flutter `showModalBottomSheet` 默认的 9/16 屏高。 */
    private fun sheetMaxHeight(): Int =
        (resources.displayMetrics.heightPixels * 9 / 16)

    private fun buildQueueOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            isClickable = true
            setOnClickListener { hideQueue() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        // 底部弹窗面板：surface 底色 + 顶部 28dp 圆角（对齐 Flutter showModalBottomSheet）
        queuePanelBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(thSurface)
            // 左上/右上圆角 28dp，底部直角
            val rad = dp(28f)
            cornerRadii = floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = queuePanelBg
            val padX = dp(16f).toInt()
            setPadding(padX, 0, padX, dp(18f).toInt())
            // 宽高对齐普通横屏的底部弹窗（showModalBottomSheet 默认约束）：
            // 宽 = min(屏宽, 640dp) 水平居中贴底；高 = 9/16 屏高（Flutter 侧 ListView
            // 会撑满这个上限，高度恒定，与曲目多少无关）。
            layoutParams = FrameLayout.LayoutParams(
                sheetWidth(), sheetMaxHeight()
            ).also { it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            isClickable = true
        }
        // 拖拽把手胶囊 32x4dp（对齐 showDragHandle）
        queueHandleBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(thOutlineVariant)
            cornerRadius = dp(2f)
        }
        panel.addView(View(this).apply {
            background = queueHandleBg
            layoutParams = LinearLayout.LayoutParams(dp(32f).toInt(), dp(4f).toInt()).also {
                it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(10f).toInt(); it.bottomMargin = dp(10f).toInt()
            }
        })
        // 头部行：播放队列 + N 首 + 清空
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(10f).toInt() }
        }
        header.addView(TextView(this).apply {
            text = "播放队列"
            setTextColor(thOnSurface); textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }.also { queueTitleText = it })
        header.addView(TextView(this).apply {
            text = ""; setTextColor(thOnSurfaceVariant); textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = dp(8f).toInt() }
        }.also { queueCountText = it })
        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        header.addView(TextView(this).apply {
            text = "清空"
            setTextColor(thError); textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10f).toInt(), dp(6f).toInt(), dp(2f).toInt(), dp(6f).toInt())
            isClickable = true
            setOnClickListener {
                if (queueCountText.tag == true) {
                    CarPlayerBridge.sendEvent("clearQueue", emptyMap<String, Any>())
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }.also { queueClearBtn = it })
        panel.addView(header)
        // 列表
        queueList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(queueList, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        overlay.addView(panel)
        queueOverlay = overlay
        return overlay
    }

    private fun rebuildQueue(items: List<Map<*, *>>) {
        queueList.removeAllViews()
        val canClear = items.size > 1
        queueCountText.text = "${items.size} 首"
        queueCountText.tag = canClear
        queueClearBtn.alpha = if (canClear) 1f else 0.4f
        items.forEachIndexed { i, m ->
            val active = m["active"] == true
            val title = (m["title"] as? String) ?: ""
            val artist = (m["artist"] as? String) ?: ""
            val coverUrl = (m["coverUrl"] as? String)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9f).toInt(), 0, dp(9f).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            // 封面缩略图 44dp，圆角 8（对齐 Flutter SongArtwork(size: 44)；预渲染，
            // 显示真实封面，不做着色）
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
            }
            row.addView(thumb, LinearLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt()).also {
                it.marginEnd = dp(14f).toInt()
            })
            loadThumb(thumb, coverUrl)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = title; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (active) thPrimary else thOnSurface); textSize = 15f
                if (active) setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            col.addView(TextView(this).apply {
                text = artist; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (active) thPrimary else thOnSurfaceVariant); textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(2f).toInt() }
            })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            // 当前曲尾部图标：播放中=均衡器，暂停=暂停键（对齐 Flutter）
            if (active) {
                row.addView(ImageView(this).apply {
                    setImageResource(if (playing) R.drawable.ic_kg_equalizer else R.drawable.ic_kg_pause)
                    setColorFilter(thPrimary)
                }, LinearLayout.LayoutParams(dp(24f).toInt(), dp(24f).toInt()).also {
                    it.marginStart = dp(8f).toInt()
                })
            }
            // 行尾收藏按钮：对齐普通横屏队列行的 FavoriteIconButton（同一份 liked 状态、
            // 同一套"仅酷狗音源可收藏"规则）。仅酷狗音源的行才显示。
            if (m["canLike"] == true) {
                val likedItem = m["liked"] == true
                val heart = ImageButton(this).apply {
                    setImageResource(
                        if (likedItem) R.drawable.ic_kg_heart_fill else R.drawable.ic_kg_heart_border
                    )
                    setColorFilter(if (likedItem) LIKED_RED else thOnSurfaceVariant)
                    background = null
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
                    contentDescription = if (likedItem) "取消收藏" else "收藏"
                    setOnClickListener {
                        CarPlayerBridge.sendEvent("likeQueueIndex", mapOf("index" to i))
                    }
                }
                row.addView(heart, LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()).also {
                    it.marginStart = dp(6f).toInt()
                })
            }
            row.setOnClickListener {
                CarPlayerBridge.sendEvent("playQueueIndex", mapOf("index" to i))
                hideQueue()
            }
            queueList.addView(row)
        }
    }

    /** 中心裁剪成正方形 + 圆角(边长 20%)抗锯齿，供队列缩略图使用。 */
    private fun roundedSquare(src: Bitmap): Bitmap {
        val side = min(src.width, src.height)
        if (side <= 0) return src
        val sx = (src.width - side) / 2f
        val sy = (src.height - side) / 2f
        val n = (side * 2).coerceIn(120, 400) // 2x 超采样取 AA 边缘
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = n.toFloat() / side
        val m = Matrix()
        m.setTranslate(-sx * scale, -sy * scale)
        m.postScale(scale, scale)
        shader.setLocalMatrix(m)
        paint.shader = shader
        val r = n * 0.2f // 对齐 Flutter 圆角 8 / 尺寸 40
        canvas.drawRoundRect(0f, 0f, n.toFloat(), n.toFloat(), r, r, paint)
        return out
    }

    /** 异步加载队列缩略图（复用 HttpURLConnection+BitmapFactory，解码小图后圆角化）。 */
    private fun loadThumb(iv: ImageView, url: String?) {
        if (url.isNullOrBlank()) return
        Thread {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.inputStream.use { ins ->
                    val bytes = ins.readBytes()
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    var sample = 1
                    val target = 160
                    while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= target) sample *= 2
                    val real = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
                    if (bmp != null && !isFinishing) {
                        val rounded = roundedSquare(bmp)
                        runOnUiThread { if (!isFinishing) iv.setImageBitmap(rounded) }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                // 缩略图失败留空即可，不影响列表
            }
        }.start()
    }

    private fun showQueue() {
        // 打开面板时才构建队列(切歌期间只缓存了数据),把重建开销挪出切歌关键路径
        if (queueDirty) {
            pendingQueueItems?.let { rebuildQueue(it) }
            queueDirty = false
        }
        // 主题快照在 buildUi 之后才到，显示前按当前 th* 重刷静态装饰色
        queuePanelBg.setColor(thSurface)
        queueHandleBg.setColor(thOutlineVariant)
        queueTitleText.setTextColor(thOnSurface)
        queueCountText.setTextColor(thOnSurfaceVariant)
        queueClearBtn.setTextColor(thError)
        queueOverlay.visibility = View.VISIBLE
    }
    private fun hideQueue() { queueOverlay.visibility = View.GONE }

    // --------------------------------------------------------- 更多面板

    /** 一行：可选图标 + 主标题 + 可选副标题 + 选中态；点击触发 onClick。 */
    private class Row(
        val label: String,
        val subtitle: String? = null,
        val icon: Int = 0,
        val selected: Boolean = false,
        val onClick: () -> Unit
    )

    /**
     * 通用原生底部弹层，1:1 复刻 Flutter showModalBottomSheet：拖拽把手(32x4) +
     * 标题(22sp w900) + 可选副标题(14sp) + 圆角 16 surfaceContainer 卡片（行=图标/标题/
     * 副标题/尾部勾选，行间 Divider indent 58）。不新建 Activity/task，避免车机出现第二窗口 /
     * Flutter 闪屏。行点击先关面板再执行 onClick。
     */
    private fun openSheet(title: String, rows: List<Row>, subtitle: String? = null) {
        closeSheet()
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            setOnClickListener { closeSheet() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        // 面板：底部对齐，顶部圆角 28dp，底色 surface
        val rad = dp(28f)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(thSurface)
                cornerRadii = floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f)
            }
            layoutParams = FrameLayout.LayoutParams(
                sheetWidth(), FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            isClickable = true
        }
        // 拖拽把手：32x4，水平居中，色 outlineVariant
        panel.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(thOutlineVariant)
                cornerRadius = dp(2f)
            }
        }, LinearLayout.LayoutParams(dp(32f).toInt(), dp(4f).toInt()).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
            it.topMargin = dp(10f).toInt(); it.bottomMargin = dp(6f).toInt()
        })
        // 内容区：Padding(16,0,16,18)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), dp(18f).toInt())
        }
        content.addView(TextView(this).apply {
            text = title; setTextColor(thOnSurface); textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (subtitle != null) {
            content.addView(TextView(this).apply {
                text = subtitle; setTextColor(thOnSurfaceVariant); textSize = 14f
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(4f).toInt() })
        }
        content.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(12f).toInt()))
        // 圆角 16 surfaceContainer 卡片承载列表，内部可滚动
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(thSurfaceContainer)
                cornerRadius = dp(16f)
            }
        }
        rows.forEachIndexed { idx, r ->
            card.addView(sheetRow(r))
            if (idx < rows.size - 1) card.addView(sheetDivider())
        }
        scroll.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scrim.addView(panel)
        root.addView(scrim)
        sheet = scrim
        // 横屏车机高度有限：列表过长时限高到屏高 80%，让内部 ScrollView 生效；短列表不填充。
        val maxH = (resources.displayMetrics.heightPixels * 0.80f).toInt()
        panel.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                panel.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (panel.height > maxH) {
                    val overflow = panel.height - maxH
                    (scroll.layoutParams as LinearLayout.LayoutParams).let {
                        it.height = (scroll.height - overflow).coerceAtLeast(dp(120f).toInt())
                        scroll.layoutParams = it
                    }
                    (panel.layoutParams as FrameLayout.LayoutParams).let {
                        it.height = maxH
                        panel.layoutParams = it
                    }
                }
            }
        })
    }

    /** 卡片内一行：[图标 24 tint onSurface] + [标题 16 / 副标题 14 onSurfaceVariant] + [尾部勾选 24 tint primary]。 */
    private fun sheetRow(r: Row): LinearLayout {
        val h = dp(16f).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(h, dp(14f).toInt(), h, dp(14f).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true
        }
        if (r.icon != 0) {
            row.addView(ImageView(this).apply {
                setImageResource(r.icon); setColorFilter(thOnSurface)
            }, LinearLayout.LayoutParams(dp(24f).toInt(), dp(24f).toInt()).also {
                it.marginEnd = dp(16f).toInt()
            })
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = r.label; setTextColor(thOnSurface); textSize = 16f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (r.subtitle != null) {
            textCol.addView(TextView(this).apply {
                text = r.subtitle; setTextColor(thOnSurfaceVariant); textSize = 14f
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(2f).toInt() })
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (r.selected) {
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_ms_check); setColorFilter(thPrimary)
            }, LinearLayout.LayoutParams(dp(24f).toInt(), dp(24f).toInt()).also {
                it.marginStart = dp(12f).toInt()
            })
        }
        row.setOnClickListener { closeSheet(); r.onClick() }
        return row
    }

    /** Flutter Divider(height:1, indent:58)：左缩进 58dp 对齐标题列。 */
    private fun sheetDivider(): View = View(this).apply {
        setBackgroundColor(thOutlineVariant)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).also { it.marginStart = dp(58f).toInt() }
    }

    private fun closeSheet() {
        sheet?.let { root.removeView(it) }
        sheet = null
    }

    /** 更多面板的一枚网格项/磁贴：图标 + 标题 + 可选副标题。 */
    private class Tile(val icon: Int, val title: String, val subtitle: String?, val onClick: () -> Unit)

    /**
     * 「更多」面板：**车机专属的左侧滑入弹窗**，1:1 对齐 Flutter `showSongActionSheet` 的车机分支
     * （`_buildCarActionDialogContent`）：
     *   · 320dp 宽卡片贴左、垂直居中，外边距 24dp，圆角 16（AppRadius.lg）+ 圆角投影
     *   · 头部：关闭 X（surfaceContainerHighest @50% 圆形底）+ 曲名（粗体）/艺人
     *   · 2 列网格磁贴（间距 10dp、childAspectRatio 1.35），每枚为 surfaceContainer 圆角 16 卡片
     *   · 从左滑入（280ms easeOutCubic，对齐 SlideTransition 的 Curves.easeOutCubic），遮罩 black54
     *
     * 左侧滑入是车机全屏的专属交互；普通横屏仍走 Flutter 的标准底部弹窗（`showModalBottomSheet`），
     * 两者互不混用。功能项与上一版一致（只增不减）。
     */
    private fun showMoreSheet() {
        closeSheet()
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x80000000.toInt())
            isClickable = true
            setOnClickListener { closeSheet() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val margin = dp(24f).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(thSurface)
                cornerRadius = dp(16f)   // AppRadius.lg
            }
            elevation = dp(12f)          // 近似 BoxShadow(black .2 / blur 15)：圆角投影
            val pad = dp(16f).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                dp(320f).toInt(), FrameLayout.LayoutParams.MATCH_PARENT
            ).also {
                it.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                it.marginStart = margin
                it.topMargin = margin
                it.bottomMargin = margin
            }
            isClickable = true
        }
        // 头部：关闭 X + 曲名/艺人
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val closeColor = (thSurfaceContainerHighest and 0x00FFFFFF) or (0x80 shl 24)
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_ms_close)
            setColorFilter(thOnSurface)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(closeColor)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9f).toInt(), dp(9f).toInt(), dp(9f).toInt(), dp(9f).toInt())
            setOnClickListener { closeSheet() }
        }, LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@CarPlayerActivity).apply {
                text = currentTitle; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(thOnSurface); textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@CarPlayerActivity).apply {
                text = currentArtist; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(thOnSurfaceVariant); textSize = 12f
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(2f).toInt() })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = dp(12f).toInt()
        })
        card.addView(header)
        card.addView(View(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(20f).toInt()))

        // 磁贴列表（2 列网格，行内等宽 + 10dp 间距）
        val tiles = ArrayList<Tile>()
        tiles.add(Tile(R.drawable.ic_ms_speed, "倍速播放", speedLabel) {
            openSheet("倍速播放", listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0).map { v ->
                Row(speedText(v), icon = R.drawable.ic_ms_speed,
                    selected = kotlin.math.abs(v - speed) < 0.001) {
                    CarPlayerBridge.sendEvent("setSpeed", mapOf("speed" to v))
                }
            }, subtitle = "调整音乐播放速度")
        })
        tiles.add(Tile(R.drawable.ic_ms_high_quality, "音质：$qualityLabel", "在音质按钮下直接切换") {
            toggleQualityOptions(true)
        })
        tiles.add(Tile(R.drawable.ic_ms_auto_awesome, "试听高潮", "播放歌曲高潮片段") {
            CarPlayerBridge.sendEvent("climax")
        })
        if (effectsSupported && effectNames.isNotEmpty()) {
            tiles.add(Tile(R.drawable.ic_ms_graphic_eq, "音效", effectLabel) {
                val rows = ArrayList<Row>()
                rows.add(Row("关闭", icon = R.drawable.ic_ms_power, selected = effectLabel == "关闭") {
                    CarPlayerBridge.sendEvent("setEffect", mapOf("off" to true))
                })
                effectNames.forEach { name ->
                    rows.add(Row(name, icon = R.drawable.ic_ms_tune,
                        selected = effectLabel.contains(name)) {
                        CarPlayerBridge.sendEvent("setEffect", mapOf("name" to name))
                    })
                }
                openSheet("音效", rows, subtitle = "选择音效预设")
            })
        }
        tiles.add(Tile(R.drawable.ic_ms_playlist_add, "下一首播放", "添加到播放队列") {
            CarPlayerBridge.sendEvent("playNext")
        })
        tiles.add(Tile(R.drawable.ic_ms_bedtime, "定时播放", sleepSubtitle()) {
            openSheet("定时播放", listOf(
                Row("不开启", icon = R.drawable.ic_ms_power,
                    selected = !sleepActive && !sleepFinishCurrent) {
                    CarPlayerBridge.sendEvent("sleepTimer", mapOf("minutes" to 0))
                },
                Row("播完当前单曲", icon = R.drawable.ic_ms_bedtime, selected = sleepFinishCurrent) {
                    CarPlayerBridge.sendEvent("sleepTimer", mapOf("finishCurrent" to true))
                },
                *listOf(15, 30, 45, 60, 90).map { m ->
                    Row("$m 分钟", icon = R.drawable.ic_ms_timer) {
                        CarPlayerBridge.sendEvent("sleepTimer", mapOf("minutes" to m))
                    }
                }.toTypedArray()
            ), subtitle = "定时结束后自动暂停播放")
        })
        if (desktopLyricsSupported) {
            tiles.add(Tile(R.drawable.ic_ms_lyrics, "桌面歌词",
                if (desktopLyricsEnabled) "已开启" else "已关闭") {
                CarPlayerBridge.sendEvent("toggleDesktopLyrics")
            })
            if (desktopLyricsEnabled) {
                tiles.add(Tile(R.drawable.ic_ms_tune, "歌词设置", "桌面歌词外观") {
                    CarPlayerBridge.sendEvent("openPage", mapOf("page" to "lyricsSettings"))
                })
            }
        }
        tiles.add(Tile(R.drawable.ic_ms_text_size, "歌词字号",
            "${(lyricScale / LYRIC_SCALE_STD * 100).toInt()}%") {
            showLyricScaleSheet()
        })
        tiles.add(Tile(R.drawable.ic_ms_info, "详细信息", "专辑、音源、时长") {
            CarPlayerBridge.sendEvent("openPage", mapOf(
                "page" to "songDetail", "songHash" to songHash))
        })
        if (canComment) {
            tiles.add(Tile(R.drawable.ic_ms_comment, "查看评论", "热门 / 最新") {
                CarPlayerBridge.sendEvent("openPage", mapOf(
                    "page" to "comments", "songHash" to songHash))
            })
        }
        if (canAddToPlaylist) {
            tiles.add(Tile(R.drawable.ic_ms_playlist_add, "添加到歌单", "选择自建歌单") {
                CarPlayerBridge.sendEvent("openPage", mapOf(
                    "page" to "addToPlaylist", "songHash" to songHash))
            })
        }
        // 原底部弹窗头部的分享入口在车机设计中不存在，改为磁贴保留，避免功能回退
        tiles.add(Tile(R.drawable.ic_ms_share, "分享", "复制歌曲分享信息") {
            CarPlayerBridge.sendEvent("shareSong", emptyMap<String, Any>())
        })
        // 卡片内容宽 = 320 - 2×16 = 288dp，列间距 10dp → 单枚 139dp；高 = 139 / 1.35 ≈ 103dp
        val tileH = dp(103f).toInt()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var i = 0
        while (i < tiles.size) {
            val rowLl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowLl.addView(buildMoreTile(tiles[i]), LinearLayout.LayoutParams(0, tileH, 1f))
            if (i + 1 < tiles.size) {
                rowLl.addView(buildMoreTile(tiles[i + 1]),
                    LinearLayout.LayoutParams(0, tileH, 1f).also {
                        it.marginStart = dp(10f).toInt()
                    })
            } else {
                rowLl.addView(View(this), LinearLayout.LayoutParams(0, tileH, 1f).also {
                    it.marginStart = dp(10f).toInt()
                })
            }
            grid.addView(rowLl, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(10f).toInt() })
            i += 2
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        scrim.addView(card)
        root.addView(scrim)
        sheet = scrim

        // 从左滑入：280ms easeOutCubic（对齐 Flutter SlideTransition 的 Curves.easeOutCubic）
        card.translationX = -dp(320f)
        card.animate()
            .translationX(0f)
            .setDuration(280L)
            .setInterpolator(
                android.view.animation.PathInterpolator(0.215f, 0.61f, 0.355f, 1f))
            .start()
    }

    /** 单枚磁贴：圆角 surfaceContainer 底 + 居中(图标 22dp / 标题 12sp 粗体 / 副标题 9sp)。 */
    private fun buildMoreTile(t: Tile): LinearLayout {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(thSurfaceContainer)
                cornerRadius = dp(16f)
            }
            setPadding(dp(8f).toInt(), dp(6f).toInt(), dp(8f).toInt(), dp(6f).toInt())
            isClickable = true
            setOnClickListener { closeSheet(); t.onClick() }
        }
        v.addView(ImageView(this).apply {
            setImageResource(t.icon); setColorFilter(thOnSurface)
        }, LinearLayout.LayoutParams(dp(22f).toInt(), dp(22f).toInt()))
        v.addView(TextView(this).apply {
            text = t.title; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER; setTextColor(thOnSurface); textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(6f).toInt() })
        if (t.subtitle != null) {
            v.addView(TextView(this).apply {
                text = t.subtitle; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER; setTextColor(thOnSurfaceVariant); textSize = 9f
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(2f).toInt() })
        }
        return v
    }

    private fun speedText(v: Double): String =
        if (v % 1.0 == 0.0) "${v.toInt()}.0x" else "${v}x"

    private fun sleepSubtitle(): String {
        if (sleepFinishCurrent) return "播完当前单曲"
        if (!sleepActive) return "不开启"
        val rem = sleepRemainingMs ?: 0L
        val total = (rem / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(total / 60, total % 60)
    }

    // --------------------------------------------------------- 快照/事件

    /**
     * 歌词字号缩放（对齐上游 `_LandscapeLyricPanel` 的 `settings.lyric_scale`）：
     * 基准字号由 Dart 侧 `_styles()` 下发，这里只按比例重算 3 个字号与行距，
     * 通过 CarLyricView.setStyles 覆盖。档位存原生 SharedPreferences，
     * 每次 open/meta 重推 styles 后都会再叠加一次，避免被基准值冲掉。
     */
    private fun readLyricScale(): Float {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        // 全新安装（从未写过档位）直接落标准档；老用户档位值一次性 ×0.85 迁移
        var saved = if (prefs.contains(PREF_LYRIC_SCALE))
            prefs.getFloat(PREF_LYRIC_SCALE, LYRIC_SCALE_STD) else LYRIC_SCALE_STD
        if (!prefs.getBoolean(PREF_LYRIC_SCALE_MIGRATED, false)) {
            if (prefs.contains(PREF_LYRIC_SCALE)) {
                // 一次性迁移：v2 档位整体 ×0.85，旧选择意图按比例保留
                saved *= 0.85f
            }
            prefs.edit().putBoolean(PREF_LYRIC_SCALE_MIGRATED, true).apply()
        }
        val snapped = LYRIC_SCALES.minByOrNull { kotlin.math.abs(it - saved) } ?: LYRIC_SCALE_STD
        // 迁移结果落盘，避免下次重复吸附偏差
        if (kotlin.math.abs(prefs.getFloat(PREF_LYRIC_SCALE, -1f) - snapped) > 0.001f) {
            prefs.edit().putFloat(PREF_LYRIC_SCALE, snapped).apply()
        }
        return snapped
    }

    private fun applyLyricScale(scale: Float) {
        lyricScale = scale
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
            .putFloat(PREF_LYRIC_SCALE, scale).apply()
        val s = mapOf<String, Any>(
            "activeSizeSp" to 52.0 * scale,
            "inactiveSizeSp" to 37.0 * scale,
            "translationSizeSp" to 22.0 * scale,
            "lineGapDp" to 21.0 * scale,
        )
        lyricView.setStyles(s)
    }

    /** 「歌词非高亮行高斯模糊」：交给 [CarLyricView] 按行距当前行的距离递增模糊。 */
    private fun applyLyricBlur(enabled: Boolean) {
        lyricBlurEnabled = enabled
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_LYRIC_BLUR, enabled).apply()
        if (::lyricView.isInitialized) lyricView.setLyricBlurEnabled(enabled)
    }

    /** 「液态玻璃动效」：控制全屏流体极光层的显隐（静态渐变，不引入持续动画帧）。 */
    private fun applyLiquidGlass(enabled: Boolean) {
        liquidGlassEnabled = enabled
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_LIQUID_GLASS, enabled).apply()
        if (::orbView.isInitialized) {
            orbView.visibility = if (enabled) View.VISIBLE else View.GONE
        }
    }

    /**
     * 流体极光光晕：两层低透明度径向渐变（顶部主题蓝 + 右下副色紫），
     * 对齐 Flutter [LiquidGlassBackground] 的 showOrbs 视觉（dark: primary .18 / secondary .12）。
     * 纯静态 Drawable —— 仅在尺寸/开关变化时绘制一次，不占 vsync，弱车机 GPU 无持续负担。
     */
    private class AuroraDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            // 顶部偏左的主色极光（中心上移出屏，避免硬边）
            paint.shader = RadialGradient(
                w * 0.30f, -h * 0.10f, w * 0.78f,
                intArrayOf(0x2E4A90FF.toInt(), 0x004A90FF.toInt()),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            // 右下副色微光
            paint.shader = RadialGradient(
                w * 0.96f, h * 1.02f, w * 0.52f,
                intArrayOf(0x1E8A63FF.toInt(), 0x008A63FF.toInt()),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun showLyricScaleSheet() {
        val rows = LYRIC_SCALES.map { sc ->
            val label = when (sc) {
                0.58f -> "小"
                LYRIC_SCALE_STD -> "标准"
                0.78f -> "较大"
                0.88f -> "大"
                else -> "超大"
            }
            // 显示百分比相对新标准（标准 = 100%）
            Row("$label  ${(sc / LYRIC_SCALE_STD * 100).toInt()}%", icon = R.drawable.ic_ms_text_size,
                selected = kotlin.math.abs(sc - lyricScale) < 0.001f) {
                applyLyricScale(sc)
                showToast("歌词字号已设为$label")
            }
        }
        openSheet("歌词字号", rows, subtitle = "仅调整车机全屏歌词显示大小")
    }

    private fun applySnapshot(m: Map<*, *>) {
        (m["theme"] as? Map<*, *>)?.let { t ->
            (t["surface"] as? Number)?.let { thSurface = it.toInt() }
            (t["primary"] as? Number)?.let { thPrimary = it.toInt() }
            (t["onSurface"] as? Number)?.let { thOnSurface = it.toInt() }
            (t["onSurfaceVariant"] as? Number)?.let { thOnSurfaceVariant = it.toInt() }
            (t["outlineVariant"] as? Number)?.let { thOutlineVariant = it.toInt() }
            (t["error"] as? Number)?.let { thError = it.toInt() }
            (t["surfaceContainer"] as? Number)?.let { thSurfaceContainer = it.toInt() }
            (t["surfaceContainerHighest"] as? Number)?.let { thSurfaceContainerHighest = it.toInt() }
        }
        (m["styles"] as? Map<*, *>)?.let { lyricView.setStyles(it) }
        // 基准 styles 每次 open 都会重推，这里再叠加用户保存的字号档位
        // （新标准为 0.80，无论何档都要叠加，不能用 1.0 作跳过判据）
        lyricScale = readLyricScale()
        applyLyricScale(lyricScale)
        (m["meta"] as? Map<*, *>)?.let { onMeta(it) }
        (m["lyrics"] as? Map<*, *>)?.let { onLyrics(it) }
        (m["transport"] as? Map<*, *>)?.let { onSync(it) }
    }

    fun onMeta(m: Map<*, *>?) {
        if (m == null) return
        // 车机界面缩放档位：即时持久化，下次进入全屏（重建 Activity）生效；
        // 档位只能在 Flutter 设置页修改，那时原生页已 dismiss，实际总会重建。
        (m["uiScale"] as? Number)?.let {
            val s = it.toFloat().coerceIn(0.85f, 1.5f)
            if (kotlin.math.abs(s - uiScale) > 0.001f) {
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .putFloat(PREF_UI_SCALE, s).apply()
            }
        }
        // Flutter 设置页两个外观开关：车机全屏是独立原生 Activity，必须在此显式接收并应用，
        // 否则设置只在 Flutter 侧生效，原生页完全无变化。
        (m["lyricBlurEnabled"] as? Boolean)?.let { applyLyricBlur(it) }
        (m["liquidGlassEnabled"] as? Boolean)?.let { applyLiquidGlass(it) }
        val t = (m["title"] as? String) ?: ""
        val a = (m["artist"] as? String) ?: ""
        val album = (m["album"] as? String) ?: ""
        currentTitle = t
        currentArtist = a
        // 磁带机下方曲目铭牌（专辑名与歌名相同时不重复显示）
        infoTitle.text = t
        val sub = listOf(a, album)
            .filter { it.isNotEmpty() && it != t }
            .joinToString(" · ")
        infoSub.text = sub
        infoSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        val url = m["coverUrl"] as? String
        if (url != coverUrl) { coverUrl = url; loadCover(url) }
        (m["playMode"] as? Number)?.let { setPlayMode(it.toInt()) }
        playModeLabel = (m["playModeLabel"] as? String) ?: playModeLabel
        (m["isLiked"] as? Boolean)?.let { setLiked(it) }
        canLike = (m["canLike"] as? Boolean) ?: true
        applyLikeEnabled()
        (m["climaxStartMs"] as? Number)?.let { climaxStartMs = it.toLong() }
            ?: run { climaxStartMs = -1L }
        positionClimaxDot()
        // "更多"面板状态
        qualityIndex = (m["audioQualityIndex"] as? Number)?.toInt() ?: qualityIndex
        qualityLabel = (m["audioQualityLabel"] as? String) ?: qualityLabel
        qualityBadge = (m["audioQualityBadge"] as? String) ?: qualityBadge
        applyTrackQuality()
        speed = (m["playbackSpeed"] as? Number)?.toDouble() ?: speed
        speedLabel = (m["playbackSpeedLabel"] as? String) ?: speedLabel
        effectLabel = (m["audioEffectLabel"] as? String) ?: effectLabel
        effectsSupported = (m["effectsSupported"] as? Boolean) ?: effectsSupported
        @Suppress("UNCHECKED_CAST")
        (m["effectNames"] as? List<String>)?.let { effectNames = it }
        desktopLyricsSupported = (m["desktopLyricsSupported"] as? Boolean) ?: desktopLyricsSupported
        desktopLyricsEnabled = (m["desktopLyricsEnabled"] as? Boolean) ?: desktopLyricsEnabled
        canComment = (m["canComment"] as? Boolean) ?: false
        canAddToPlaylist = (m["canAddToPlaylist"] as? Boolean) ?: false
        songHash = (m["songHash"] as? String) ?: ""
        sleepActive = (m["sleepActive"] as? Boolean) ?: sleepActive
        sleepFinishCurrent = (m["sleepFinishCurrent"] as? Boolean) ?: sleepFinishCurrent
        sleepRemainingMs = (m["sleepRemainingMs"] as? Number)?.toLong()
        @Suppress("UNCHECKED_CAST")
        (m["queue"] as? List<Map<*, *>>)?.let { q ->
            pendingQueueItems = q
            queueDirty = true
            // 面板正显示时才即时刷新;否则留到 showQueue() 打开时再构建,避免切歌卡顿
            if (::queueOverlay.isInitialized && queueOverlay.visibility == View.VISIBLE) {
                rebuildQueue(q)
            }
        }
    }

    fun onLyrics(m: Map<*, *>?) {
        if (m == null) return
        @Suppress("UNCHECKED_CAST")
        val lines = m["lines"] as? List<Any?> ?: return
        lyricView.setLyrics(lines)
    }

    fun onSync(m: Map<*, *>?) {
        if (m == null) return
        var pos = (m["positionMs"] as? Number)?.toLong() ?: anchorPosMs
        durationMs = (m["durationMs"] as? Number)?.toLong() ?: durationMs
        // 歌已自然播完：位置推到时长终点。播放器 completed 时上报的位置可能
        // 略小于 durationMs（末帧/预卷差），不推终点磁带卷盘会停在差一点的位置。
        val completed = (m["isCompleted"] as? Boolean) ?: false
        if (completed && durationMs > 0) pos = durationMs
        playing = (m["isPlaying"] as? Boolean) ?: playing
        buffering = (m["isBuffering"] as? Boolean) ?: buffering
        (m["playMode"] as? Number)?.let { setPlayMode(it.toInt()) }
        (m["isLiked"] as? Boolean)?.let { setLiked(it) }
        anchorPosMs = pos
        anchorUptime = android.os.SystemClock.elapsedRealtime()
        seek.max = max(1, durationMs.toInt())
        applyPlayState(playing)
        applyBuffering(buffering)
        positionClimaxDot()
        if (!playing && !seekDragging) {
            val cur = currentMs()
            seek.progress = cur.toInt()
            elapsedText.text = fmt(cur)
            remainText.text = fmt(durationMs)
            if (!lyricView.userInteracting) lyricView.setProgress(cur)
        }
    }

    private fun setPlayMode(mode: Int) {
        playMode = mode
        if (modeBtn.tag as? Int == mode) return
        val res = when (mode) {
            1 -> R.drawable.ic_kg_mode_shuffle
            2 -> R.drawable.ic_kg_mode_single
            else -> R.drawable.ic_kg_mode_loop
        }
        modeBtn.setImageResource(res)
        modeBtn.tag = mode
        if (wantModeToast) {
            wantModeToast = false
            showToast("已切换到${playModeLabel}")
        }
    }

    private fun setLiked(v: Boolean) {
        liked = v
        if (v && canLike) {
            likeBtn.setImageResource(R.drawable.ic_kg_heart_fill)
            likeBtn.setColorFilter(0xFFFF4A6B.toInt())
        } else {
            likeBtn.setImageResource(R.drawable.ic_kg_heart_border)
            likeBtn.setColorFilter(0xFFEEFFFFFF.toInt())
        }
    }

    /** 非酷狗来源不可收藏：红心置灰且禁用点击（对齐 Flutter 横屏 header 的 gating）。 */
    private fun applyLikeEnabled() {
        likeBtn.isEnabled = canLike
        likeBtn.alpha = if (canLike) 1f else 0.35f
    }

    /** 缓冲时把播放图标淡出、显示环形进度；否则反之（对齐 Flutter 播放按钮的 loading 态）。 */
    private fun applyBuffering(b: Boolean) {
        if (!::playProgress.isInitialized) return
        playProgress.visibility = if (b) View.VISIBLE else View.GONE
        playBtn.alpha = if (b) 0f else 1f
        if (b) {
            playBtn.isEnabled = false
            if (!playProgress.isShown) playProgress.bringToFront()
        } else {
            playBtn.isEnabled = true
        }
    }

    /** 在进度条上按高潮起始时间比例摆放小圆点（duration 未知时隐藏，等待下次 sync）。 */
    private fun positionClimaxDot() {
        if (!::climaxDot.isInitialized) return
        if (climaxStartMs < 0L || durationMs <= 0L) {
            climaxDot.visibility = View.GONE
            return
        }
        climaxDot.visibility = View.VISIBLE
        val place = Runnable {
            val w = seek.width
            if (w <= 0) { seek.post { positionClimaxDot() }; return@Runnable }
            val pad = seek.paddingLeft.toFloat()
            val frac = (climaxStartMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val x = (pad + frac * (w - 2 * pad) - dp(4f)).toInt().coerceIn(0, w - climaxDot.width)
            (climaxDot.layoutParams as FrameLayout.LayoutParams).leftMargin = x
            climaxDot.requestLayout()
        }
        if (seek.width > 0) place.run() else seek.post(place)
    }

    internal fun showToast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun applyPlayState(isPlaying: Boolean) {
        playBtn.setImageResource(if (isPlaying) R.drawable.ic_kg_pause else R.drawable.ic_kg_play)
        if (isPlaying) {
            startRotation()
            startClock()
        } else {
            stopRotation()
            stopClock()
        }
    }

    private fun currentMs(): Long {
        if (!playing) return anchorPosMs
        val d = android.os.SystemClock.elapsedRealtime() - anchorUptime
        // 夹在时长内：同步间隔期间墙钟插值可能超出时长（歌已到尾、下一首 sync 未到），
        // 不夹会让卷盘 p 提前到 1 又无所谓，但时间文本会越界跳动。
        val v = anchorPosMs + d
        return if (durationMs > 0 && v > durationMs) durationMs else v
    }

    private fun startRotation() {
        // discGroup 由 buildDisc() 在布局完成后创建；onCreate 期间的同步调用（applyPlayState）
        // 会早于该时机，此时直接跳过——buildDisc() 末尾会在 playing 时重新触发旋转。
        if (!::discGroup.isInitialized) return
        // 旋转组整体缓存为硬件层:此后每帧只是矩阵变换,不再重采样盘面位图与中心封面。
        // (原先组无层、组内 cover 反而有层,等于每帧多一次离屏合成,是车机上的主要 GPU 开销)
        // 必须放在早退之前:buildDisc() 重建后会换一个新的 discGroup,新对象需要重新挂层。
        discGroup.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        if (rotRunning) return
        rotRunning = true
        rotAnchorUptimeMs = android.os.SystemClock.uptimeMillis()
        rotAnchorAngle = discGroup.rotation
    }

    /** 按经过时间直接算角度(32s/圈),与 tick 同频——一次 tick 只产生一次重绘。 */
    private fun stepRotation() {
        if (!rotRunning || !::discGroup.isInitialized) return
        val dt = android.os.SystemClock.uptimeMillis() - rotAnchorUptimeMs
        discGroup.rotation = (rotAnchorAngle + (dt / 32000f) * 360f) % 360f
    }

    private fun stopRotation() {
        rotRunning = false
    }

    private fun setDefaultBackground() {
        bgImage.setImageDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF153D35.toInt(), 0xFF061219.toInt(), 0xFF2C1320.toInt())
            )
        )
        bgView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            // 竖向 scrim [.32,.56,.82] 叠加 Flutter 的整屏 flat black .12（按 alpha 合成 a+.12*(1-a)）
            intArrayOf(0x67000000, 0x9D000000.toInt(), 0xD7000000.toInt())
        )
    }

    /**
     * 近似 Flutter 的模糊封面背景（minSdk26 无 RenderEffect）：
     *  先把长边缩到 ~72px 制造大尺度柔化，再做 3 遍真·盒式模糊(滑窗均值)消除相邻色块间的硬棱，
     *  最后双线性放大回原尺寸。之前只做缩放(无均值)会留下 32px 网格的马赛克拼块感，均值模糊才是柔和磨砂。
     */
    private fun blurCover(src: Bitmap): Bitmap {
        val maxSide = 72
        val scale = maxSide.toFloat() / max(src.width, src.height)
        val w = (src.width * scale).toInt().coerceIn(8, maxSide)
        val h = (src.height * scale).toInt().coerceIn(8, maxSide)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        boxBlur(px, w, h, 4)
        boxBlur(px, w, h, 4)
        boxBlur(px, w, h, 4)
        small.setPixels(px, 0, w, 0, 0, w, h)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    /** 可分离盒式模糊：横向滑窗均值到 tmp，再纵向滑窗均值写回 px。alpha 强制不透明。 */
    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var rs = 0; var gs = 0; var bs = 0
            for (i in -r..r) {
                val c = px[row + i.coerceIn(0, w - 1)]
                rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF
            }
            for (x in 0 until w) {
                tmp[row + x] = 0xFF000000.toInt() or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
                val a = px[row + (x + r + 1).coerceIn(0, w - 1)]
                val s = px[row + (x - r).coerceIn(0, w - 1)]
                rs += ((a shr 16) and 0xFF) - ((s shr 16) and 0xFF)
                gs += ((a shr 8) and 0xFF) - ((s shr 8) and 0xFF)
                bs += (a and 0xFF) - (s and 0xFF)
            }
        }
        for (x in 0 until w) {
            var rs = 0; var gs = 0; var bs = 0
            for (i in -r..r) {
                val c = tmp[i.coerceIn(0, h - 1) * w + x]
                rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF
            }
            for (y in 0 until h) {
                px[y * w + x] = 0xFF000000.toInt() or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
                val a = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                val s = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                rs += ((a shr 16) and 0xFF) - ((s shr 16) and 0xFF)
                gs += ((a shr 8) and 0xFF) - ((s shr 8) and 0xFF)
                bs += (a and 0xFF) - (s and 0xFF)
            }
        }
    }

    /**
     * 把封面裁成正圆并预抗锯齿：用 BitmapShader 填充一个带 AA+双线性采样的圆，
     * 高倍超采样 + 圆半径内缩(留出 AA 余量)后交 ImageView 缩放，
     * 使圆边不被画布边界裁成硬边、旋转唱片时边缘依旧平滑(替代 clipToOutline 的硬边锯齿)。
     */
    private fun circleize(src: Bitmap): Bitmap {
        val side = min(src.width, src.height)
        // 超采样按缩小后的圆盘显示尺寸取 ~2x 即可（1024 上限省一半纹理内存/GPU 采样）
        val n = (side * 2).coerceIn(360, 1024)
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val m = Matrix()
        // 关键：圆半径内缩 pad，给抗锯齿过渡带留出画布余量，
        // 否则圆与画布四边相切处 AA 被裁掉→上下左右呈硬边/锯齿。
        val pad = 3f
        val r = n / 2f - pad
        // 方形封面自带浅色/白色印刷边：若圆按短边内切(直径=短边)，圆周正好压在方形四边中点上
        // →上下左右漏出白边。让圆只采样原图中心约 89% 区域(把图放大 zoom 倍溢出圆外)，
        // 将封面自带的亮/白边裁到可见圆之外。
        val zoom = 1.12f
        val s = (r * 2f) / side * zoom
        m.setScale(s, s)
        m.postTranslate((n - src.width * s) / 2f, (n - src.height * s) / 2f)
        shader.setLocalMatrix(m)
        paint.shader = shader
        c.drawCircle(n / 2f, n / 2f, r, paint)
        // 边缘压一圈极细暗色描边：消除抗锯齿亮边、并把封面边界自然融进黑色黑胶，杜绝残余白边。
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = n * 0.014f
            color = 0x99000000.toInt()
        }
        c.drawCircle(n / 2f, n / 2f, r - rim.strokeWidth / 2f, rim)
        return out
    }

    private fun loadCover(url: String?) {
        val token = ++coverToken
        if (url.isNullOrBlank()) {
            runOnUiThread {
                if (token == coverToken) {
                    disc?.setImageDrawable(null)
                    // [CASSETTE_EXPERIMENT] 磁带标签清空封面
                    cassetteView?.setAlbumArt(null)
                }
            }
            return
        }
        Thread {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.inputStream.use { ins ->
                    val bytes = ins.readBytes()
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    var sample = 1
                    val target = 512
                    while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= target) sample *= 2
                    val real = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
                    if (bmp != null && token == coverToken) {
                        val blurred = blurCover(bmp)
                        val circle = circleize(bmp)
                        runOnUiThread {
                            if (token == coverToken) {
                                disc?.setImageBitmap(circle)
                                bgImage.setImageBitmap(blurred)
                                // [CASSETTE_EXPERIMENT] 标签用未圆化的原图，恰好填充方形纸标
                                cassetteView?.setAlbumArt(bmp)
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                // 封面失败不影响播放，保留兜底对角渐变
            }
        }.start()
    }

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }
}
