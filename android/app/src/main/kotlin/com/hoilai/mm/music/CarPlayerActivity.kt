package com.hoilai.mm.music

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
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
    private lateinit var root: FrameLayout
    private lateinit var lyricView: CarLyricView
    private lateinit var discWrap: FrameLayout
    private lateinit var discGroup: FrameLayout
    private var disc: ImageView? = null
    private lateinit var headerTitle: TextView
    private lateinit var headerArtist: TextView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
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
    private lateinit var queueCountText: TextView
    private lateinit var queueClearBtn: TextView
    private lateinit var queueTitleText: TextView
    private lateinit var queuePanelBg: GradientDrawable
    private lateinit var queueHandleBg: GradientDrawable
    private var sheet: FrameLayout? = null

    private var rotAnimator: ValueAnimator? = null
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
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing || seekDragging || isFinishing) {
                clockStarted = false
                return
            }
            val cur = currentMs()
            seek.progress = cur.toInt()
            elapsedText.text = fmt(cur)
            remainText.text = fmt(durationMs)
            if (!lyricView.userInteracting) lyricView.setProgress(cur)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        buildUi()
        setDefaultBackground()

        (CarPlayerBridge.snapshot)?.let { applySnapshot(it) }
    }

    override fun onResume() {
        super.onResume()
        CarPlayerBridge.current = this
        CarPlayerBridge.sendEvent("requestSync")
        applyPlayState(playing)
    }

    override fun onPause() {
        super.onPause()
        if (CarPlayerBridge.current === this) CarPlayerBridge.current = null
    }

    override fun onDestroy() {
        if (CarPlayerBridge.current === this) CarPlayerBridge.current = null
        rotAnimator?.cancel()
        Choreographer.getInstance().removeFrameCallback(frameCb)
        clockStarted = false
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
        header.addView(space(dp(18f).toInt(), 0))
        val hcol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerTitle = marqueeText(16f, 0xEBFFFFFF.toInt(), true)
        headerArtist = marqueeText(12f, 0xB3FFFFFF.toInt(), true)
        hcol.addView(headerTitle, lpMatchWrap())
        hcol.addView(headerArtist, lpMatchWrap())
        header.addView(hcol, lpWeight(1f))
        likeBtn = circleIconButton(R.drawable.ic_kg_heart_border, 24f, 44f, R.drawable.bg_circle_white12) {
            CarPlayerBridge.sendEvent("like")
        }
        header.addView(likeBtn, lp(dp(44f).toInt(), dp(44f).toInt()))
        header.addView(space(dp(8f).toInt(), 0))
        moreBtn = circleIconButton(R.drawable.ic_kg_more_horiz, 24f, 44f, R.drawable.bg_circle_white12) {
            showMoreSheet()
        }
        header.addView(moreBtn, lp(dp(44f).toInt(), dp(44f).toInt()))
        content.addView(header, lpMatchWrap())
        content.addView(space(0, dp(10f).toInt()))

        // ---- 主体：左唱片(flex 9) + 右面板(flex 12)
        val bodyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        discWrap = FrameLayout(this)
        bodyRow.addView(discWrap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 9f))
        bodyRow.addView(space(dp(34f).toInt(), 0))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleText = marqueeText(22f, 0xEBFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER }
        artistText = marqueeText(14f, 0x99FFFFFF.toInt(), false).apply { gravity = Gravity.CENTER }
        // 曲名/歌手已在左上角顶栏显示，右侧不再重复；此处仅留少量顶部间距给歌词。
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
            thumbOffset = dp(5f).toInt()
            splitTrack = false
            setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
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
        modeBtn = circleIconButton(R.drawable.ic_kg_mode_loop, 34f, 56f, 0) {
            wantModeToast = true; CarPlayerBridge.sendEvent("playMode")
        }
        btnRow.addView(modeBtn, lp(dp(56f).toInt(), dp(56f).toInt()))
        btnRow.addView(space(dp(24f).toInt(), 0))
        btnRow.addView(
            circleIconButton(R.drawable.ic_kg_prev, 54f, 72f, 0) { CarPlayerBridge.sendEvent("prev") },
            lp(dp(72f).toInt(), dp(72f).toInt())
        )
        btnRow.addView(space(dp(24f).toInt(), 0))
        val playWrap = FrameLayout(this)
        playBtn = ImageButton(this).apply {
            setBackgroundResource(R.drawable.bg_circle_white18)
            setImageResource(R.drawable.ic_kg_play)
            setColorFilter(0xFFFFFFFF.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(12f).toInt()
            setPadding(pad, pad, pad, pad)
            setOnClickListener { CarPlayerBridge.sendEvent("playPause") }
        }
        playProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        playWrap.addView(playBtn, FrameLayout.LayoutParams(dp(96f).toInt(), dp(96f).toInt()))
        playWrap.addView(
            playProgress,
            FrameLayout.LayoutParams(dp(42f).toInt(), dp(42f).toInt(), Gravity.CENTER)
        )
        btnRow.addView(playWrap, lp(dp(96f).toInt(), dp(96f).toInt()))
        btnRow.addView(space(dp(24f).toInt(), 0))
        btnRow.addView(
            circleIconButton(R.drawable.ic_kg_next, 54f, 72f, 0) { CarPlayerBridge.sendEvent("next") },
            lp(dp(72f).toInt(), dp(72f).toInt())
        )
        btnRow.addView(space(dp(24f).toInt(), 0))
        btnRow.addView(
            circleIconButton(R.drawable.ic_kg_queue, 34f, 56f, 0) { showQueue() },
            lp(dp(56f).toInt(), dp(56f).toInt())
        )
        right.addView(btnRow, lpMatchWrap().also { it.topMargin = dp(4f).toInt() })

        bodyRow.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 12f))
        content.addView(bodyRow)

        root.addView(content)
        root.addView(buildQueueOverlay())

        setContentView(root)

        // 唱片在首帧按左栏实际尺寸构建（用户要求整体更大：上限从 Flutter 的 330dp 提到 440dp）
        discWrap.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width; val h = v.height
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            val density = resources.displayMetrics.density
            val sizePx = (minOf(w, h) * 0.9f).coerceIn(dp(150f), dp(440f)).toInt()
            if (sizePx != lastDiscSize) { lastDiscSize = sizePx; buildDisc(sizePx) }
        }
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

    /** 构建 Flutter 风深色黑胶唱片：径向渐变盘体 + 同心环 + 中心圆形封面，整体随 discGroup 旋转。 */
    private fun buildDisc(sizePx: Int) {
        discWrap.removeAllViews()
        discGroup = FrameLayout(this)

        val glow = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                setGradientCenter(0.5f, 0.5f)
                gradientRadius = sizePx / 2f
                // 深色哑光黑胶：中心略亮、外缘渐暗，去除原先的白色发光高光
                setColors(intArrayOf(0xFF2A2A30.toInt(), 0xFF1A1A1F.toInt(), 0xFF0E0E11.toInt()))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            // 仅裁剪成圆形：去掉 elevation 投影，消除高亮光晕。
            clipToOutline = true
        }
        discGroup.addView(glow, FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER))

        for (ratio in floatArrayOf(.36f, .52f, .68f, .82f)) {
            val ring = View(this).apply {
                setBackgroundResource(R.drawable.bg_disc_ring)
                isClickable = false
            }
            val d = (sizePx * ratio).toInt()
            discGroup.addView(ring, FrameLayout.LayoutParams(d, d, Gravity.CENTER))
        }

        val cover = ImageView(this).apply {
            // 圆形封面在位图里预抗锯齿(circleize)，故不再用 clipToOutline 硬裁(硬裁边缘有锯齿)
            scaleType = ImageView.ScaleType.FIT_XY
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        disc = cover
        val coverSize = (sizePx * 0.70f).toInt()
        discGroup.addView(cover, FrameLayout.LayoutParams(coverSize, coverSize, Gravity.CENTER))

        discWrap.addView(discGroup, FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER))

        // 左右滑动切歌（对齐 Flutter _LandscapeArtworkShowcase.onHorizontalDragEnd）
        val discGesture = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent) = true
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
        if (playing) startRotation()
    }

    // --------------------------------------------------------- 队列面板

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
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.62f).toInt()
            ).also { it.gravity = Gravity.BOTTOM }
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
            // 封面缩略图 40dp，圆角 8（预渲染，无索引号；显示真实封面，不做着色）
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
            }
            row.addView(thumb, LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()).also {
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
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.BOTTOM }
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

    /** 更多面板的一枚网格磁贴。 */
    private class Tile(val icon: Int, val title: String, val subtitle: String?, val onClick: () -> Unit)

    /** 更多面板：左侧滑入圆角卡片 + 关闭X + 曲名头 + 2 列网格磁贴（对齐 Flutter 车机版）。 */
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
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(thSurface)
                cornerRadius = dp(16f)
            }
            val pad = dp(16f).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                dp(320f).toInt(), FrameLayout.LayoutParams.MATCH_PARENT
            ).also {
                it.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                it.marginStart = dp(24f).toInt(); it.topMargin = dp(24f).toInt(); it.bottomMargin = dp(24f).toInt()
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
            setImageResource(R.drawable.ic_kg_close)
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
                text = headerTitle.text; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(thOnSurface); textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@CarPlayerActivity).apply {
                text = headerArtist.text; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
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
        // 磁贴列表（2 列网格，行内等宽 + 10dp 间距，磁贴高≈宽/1.35）
        val tiles = ArrayList<Tile>()
        tiles.add(Tile(R.drawable.ic_ms_speed, "倍速播放", speedLabel) {
            openSheet("倍速播放", listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0).map { v ->
                Row(speedText(v), icon = R.drawable.ic_ms_speed,
                    selected = kotlin.math.abs(v - speed) < 0.001) {
                    CarPlayerBridge.sendEvent("setSpeed", mapOf("speed" to v))
                }
            }, subtitle = "调整音乐播放速度")
        })
        tiles.add(Tile(R.drawable.ic_ms_high_quality, "音质：$qualityLabel", "切换当前播放音质") {
            openSheet("切换音质", listOf(
                Row("标准音质", "128K", R.drawable.ic_ms_music_note, selected = qualityIndex == 0) {
                    CarPlayerBridge.sendEvent("setQuality", mapOf("qualityIndex" to 0))
                },
                Row("高品音质", "320K", R.drawable.ic_ms_high_quality, selected = qualityIndex == 1) {
                    CarPlayerBridge.sendEvent("setQuality", mapOf("qualityIndex" to 1))
                },
                Row("无损音质", "FLAC", R.drawable.ic_ms_graphic_eq, selected = qualityIndex == 2) {
                    CarPlayerBridge.sendEvent("setQuality", mapOf("qualityIndex" to 2))
                }
            ), subtitle = "会重新加载当前歌曲并尽量保持播放进度")
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
        }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var i = 0
        while (i < tiles.size) {
            val rowLl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowLl.addView(buildMoreTile(tiles[i]), LinearLayout.LayoutParams(0, dp(100f).toInt(), 1f))
            if (i + 1 < tiles.size) {
                rowLl.addView(buildMoreTile(tiles[i + 1]), LinearLayout.LayoutParams(0, dp(100f).toInt(), 1f).also {
                    it.marginStart = dp(10f).toInt()
                })
            } else {
                rowLl.addView(View(this), LinearLayout.LayoutParams(0, dp(100f).toInt(), 1f).also {
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
    }

    /** 单枚磁贴：圆角 surfaceContainer 底 + 居中(图标/标题/副标题)。 */
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
        }, LinearLayout.LayoutParams(dp(24f).toInt(), dp(24f).toInt()))
        v.addView(TextView(this).apply {
            text = t.title; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER; setTextColor(thOnSurface); textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(6f).toInt() })
        if (t.subtitle != null) {
            v.addView(TextView(this).apply {
                text = t.subtitle; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER; setTextColor(thOnSurfaceVariant); textSize = 10f
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
        (m["meta"] as? Map<*, *>)?.let { onMeta(it) }
        (m["lyrics"] as? Map<*, *>)?.let { onLyrics(it) }
        (m["transport"] as? Map<*, *>)?.let { onSync(it) }
    }

    fun onMeta(m: Map<*, *>?) {
        if (m == null) return
        val t = (m["title"] as? String) ?: ""
        val a = (m["artist"] as? String) ?: ""
        titleText.text = t
        artistText.text = a
        headerTitle.text = t
        headerArtist.text = a
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
        speed = (m["playbackSpeed"] as? Number)?.toDouble() ?: speed
        speedLabel = (m["playbackSpeedLabel"] as? String) ?: speedLabel
        effectLabel = (m["audioEffectLabel"] as? String) ?: effectLabel
        effectsSupported = (m["effectsSupported"] as? Boolean) ?: effectsSupported
        @Suppress("UNCHECKED_CAST")
        (m["effectNames"] as? List<String>)?.let { effectNames = it }
        desktopLyricsSupported = (m["desktopLyricsSupported"] as? Boolean) ?: desktopLyricsSupported
        desktopLyricsEnabled = (m["desktopLyricsEnabled"] as? Boolean) ?: desktopLyricsEnabled
        sleepActive = (m["sleepActive"] as? Boolean) ?: sleepActive
        sleepFinishCurrent = (m["sleepFinishCurrent"] as? Boolean) ?: sleepFinishCurrent
        sleepRemainingMs = (m["sleepRemainingMs"] as? Number)?.toLong()
        @Suppress("UNCHECKED_CAST")
        (m["queue"] as? List<Map<*, *>>)?.let { rebuildQueue(it) }
    }

    fun onLyrics(m: Map<*, *>?) {
        if (m == null) return
        @Suppress("UNCHECKED_CAST")
        val lines = m["lines"] as? List<Any?> ?: return
        lyricView.setLyrics(lines)
    }

    fun onSync(m: Map<*, *>?) {
        if (m == null) return
        val pos = (m["positionMs"] as? Number)?.toLong() ?: anchorPosMs
        durationMs = (m["durationMs"] as? Number)?.toLong() ?: durationMs
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
            val pad = seek.thumbOffset.toFloat()
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
            if (!clockStarted) {
                clockStarted = true
                Choreographer.getInstance().postFrameCallback(frameCb)
            }
        } else {
            stopRotation()
            clockStarted = false
            Choreographer.getInstance().removeFrameCallback(frameCb)
        }
    }

    private fun currentMs(): Long {
        if (!playing) return anchorPosMs
        val d = android.os.SystemClock.elapsedRealtime() - anchorUptime
        return anchorPosMs + d
    }

    private fun startRotation() {
        // discGroup 由 buildDisc() 在布局完成后创建；onCreate 期间的同步调用（applyPlayState）
        // 会早于该时机，此时直接跳过——buildDisc() 末尾会在 playing 时重新触发旋转。
        if (!::discGroup.isInitialized) return
        if (rotAnimator?.isRunning == true) return
        rotAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 32000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { discGroup.rotation = it.animatedValue as Float }
            start()
        }
    }

    private fun stopRotation() {
        rotAnimator?.cancel()
        rotAnimator = null
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
        // 足够高的超采样让圆边在高分屏/旋转时依旧细腻
        val n = (side * 3).coerceIn(480, 1440)
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
            runOnUiThread { if (token == coverToken) disc?.setImageDrawable(null) }
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
