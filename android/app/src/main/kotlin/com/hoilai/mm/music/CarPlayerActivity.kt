package com.hoilai.mm.music

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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

    private lateinit var bgView: View
    private lateinit var lyricView: CarLyricView
    private lateinit var discWrap: FrameLayout
    private lateinit var discGroup: FrameLayout
    private lateinit var disc: ImageView
    private lateinit var tonearm: ImageView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsedText: TextView
    private lateinit var remainText: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var modeBtn: ImageButton
    private lateinit var likeBtn: ImageButton
    private lateinit var queueOverlay: FrameLayout
    private lateinit var queueList: LinearLayout
    private lateinit var queueHeader: TextView

    private var rotAnimator: ValueAnimator? = null

    private var anchorPosMs = 0L
    private var anchorUptime = 0L
    private var durationMs = 0L
    private var playing = false
    private var seekDragging = false
    private var coverUrl: String? = null
    private var coverToken = 0
    private var playMode = 0
    private var liked = false

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
            remainText.text = "-" + fmt(max(0L, durationMs - cur))
            lyricView.setProgress(cur)
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
        val root = FrameLayout(this)

        bgView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(bgView)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(28f).toInt(), dp(18f).toInt(), dp(28f).toInt(), dp(16f).toInt())
        }

        // 顶栏：左收起(关闭) / 右红心(收藏)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(iconButton(R.drawable.ic_kg_close, dp(24f).toInt(), 0xFFEEFFFFFF.toInt()) {
            CarPlayerBridge.sendEvent("closed")
            finish()
            overridePendingTransition(0, 0)
        })
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        likeBtn = iconButton(R.drawable.ic_kg_heart_border, dp(26f).toInt(), 0xFFEEFFFFFF.toInt()) {
            CarPlayerBridge.sendEvent("like")
        }
        topBar.addView(likeBtn)
        content.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 中部：左转盘+曲名 / 右歌词
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, .42f)
            setPadding(0, 0, dp(12f).toInt(), 0)
        }

        val outer = dp(230f).toInt()
        discGroup = FrameLayout(this)
        val vinyl = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF141416.toInt())
                setStroke(dp(1f).toInt(), 0xFF2C2C30.toInt())
            }
        }
        discGroup.addView(vinyl, FrameLayout.LayoutParams(outer, outer))
        disc = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
        val inner = dp(150f).toInt()
        discGroup.addView(disc, FrameLayout.LayoutParams(inner, inner, Gravity.CENTER))
        val hole = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0A0A0B.toInt())
                setStroke(dp(1f).toInt(), 0xFF3A3A40.toInt())
            }
        }
        discGroup.addView(hole, FrameLayout.LayoutParams(dp(24f).toInt(), dp(24f).toInt(), Gravity.CENTER))

        tonearm = ImageView(this).apply {
            setImageResource(R.drawable.ic_kg_tonearm)
            setColorFilter(0xFFD8D8DC.toInt())
            alpha = 0.95f
            pivotX = dp(56f)
            pivotY = dp(8f)
            rotation = -14f
        }
        discWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(outer + dp(30f).toInt(), outer + dp(30f).toInt())
        }
        discWrap.addView(discGroup, FrameLayout.LayoutParams(outer, outer, Gravity.CENTER))
        discWrap.addView(
            tonearm,
            FrameLayout.LayoutParams(dp(72f).toInt(), dp(72f).toInt()).also {
                it.gravity = Gravity.TOP or Gravity.END
                it.topMargin = dp(6f).toInt(); it.marginEnd = dp(2f).toInt()
            }
        )
        left.addView(discWrap)

        titleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(18f).toInt(), 0, 0)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        artistText = TextView(this).apply {
            setTextColor(0xFFB8B8BE.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        left.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        left.addView(artistText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        topRow.addView(left)

        lyricView = CarLyricView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        topRow.addView(lyricView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, .58f))
        content.addView(topRow)

        // 进度行
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f).toInt(), 0, dp(2f).toInt())
        }
        elapsedText = TextView(this).apply { setTextColor(0xFFCCFFFFFF.toInt()); textSize = 12f; text = "0:00" }
        remainText = TextView(this).apply { setTextColor(0xFFCCFFFFFF.toInt()); textSize = 12f; text = "0:00" }
        seek = SeekBar(this).apply {
            max = 1
            progressDrawable = resources.getDrawable(R.drawable.kg_seek_track, null)
            thumb = resources.getDrawable(R.drawable.kg_seek_thumb, null)
            thumbOffset = dp(6f).toInt()
            splitTrack = false
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
        seekRow.addView(elapsedText)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = dp(12f).toInt(); it.marginEnd = dp(12f).toInt()
        })
        seekRow.addView(remainText)
        content.addView(seekRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 控制行：循环 / 上一曲 / 播放(大圆) / 下一曲 / 队列
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10f).toInt(), 0, dp(4f).toInt())
        }
        modeBtn = iconButton(R.drawable.ic_kg_mode_loop, dp(30f).toInt(), 0xFFEAEAEF.toInt()) {
            CarPlayerBridge.sendEvent("playMode")
        }
        btnRow.addView(modeBtn)
        btnRow.addView(iconButton(R.drawable.ic_kg_prev, dp(38f).toInt(), 0xFFF2F2F5.toInt()) {
            CarPlayerBridge.sendEvent("prev")
        })
        playBtn = bigPlayButton()
        btnRow.addView(playBtn)
        btnRow.addView(iconButton(R.drawable.ic_kg_next, dp(38f).toInt(), 0xFFF2F2F5.toInt()) {
            CarPlayerBridge.sendEvent("next")
        })
        btnRow.addView(iconButton(R.drawable.ic_kg_queue, dp(30f).toInt(), 0xFFEAEAEF.toInt()) {
            showQueue()
        })
        content.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(content)
        root.addView(buildQueueOverlay())

        setContentView(root)
    }

    private fun bigPlayButton(): ImageButton {
        val size = dp(64f).toInt()
        return ImageButton(this).apply {
            setBackgroundResource(R.drawable.kg_play_circle)
            setImageResource(R.drawable.ic_kg_play)
            setColorFilter(0xFF15151A.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(16f).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(size, size).also {
                it.marginStart = dp(22f).toInt(); it.marginEnd = dp(22f).toInt()
            }
            setOnClickListener { CarPlayerBridge.sendEvent("playPause") }
        }
    }

    private fun iconButton(resId: Int, iconSizePx: Int, tint: Int, onTap: () -> Unit): ImageButton {
        val s = dp(50f).toInt()
        return ImageButton(this).apply {
            setImageResource(resId)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = ((s - iconSizePx) / 2).coerceAtLeast(0)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(s, s).also {
                it.marginStart = dp(6f).toInt(); it.marginEnd = dp(6f).toInt()
            }
            contentDescription = null
            setOnClickListener { onTap() }
        }
    }

    // --------------------------------------------------------- 队列面板

    private fun buildQueueOverlay(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x99000000)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { hideQueue() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.kg_queue_panel_bg, null)
            setPadding(dp(20f).toInt(), dp(14f).toInt(), dp(20f).toInt(), dp(8f).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(320f).toInt()
            ).also { it.gravity = Gravity.BOTTOM }
            isClickable = true
        }
        queueHeader = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8f).toInt())
        }
        panel.addView(queueHeader)
        queueList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
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
        queueHeader.text = "播放列表 · ${items.size} 首"
        items.forEachIndexed { i, m ->
            val active = m["active"] == true
            val title = (m["title"] as? String) ?: ""
            val artist = (m["artist"] as? String) ?: ""
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4f).toInt(), dp(12f).toInt(), dp(4f).toInt(), dp(12f).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val idx = TextView(this).apply {
                text = (i + 1).toString()
                setTextColor(if (active) ACCENT else 0xFF8A8A90.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
            }
            row.addView(idx, LinearLayout.LayoutParams(dp(30f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = title; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (active) ACCENT else Color.WHITE); textSize = 15f
            })
            col.addView(TextView(this).apply {
                text = artist; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFF9A9AA0.toInt()); textSize = 12f
            })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                CarPlayerBridge.sendEvent("playQueueIndex", mapOf("index" to i))
                hideQueue()
            }
            queueList.addView(row)
        }
    }

    private fun showQueue() { queueOverlay.visibility = View.VISIBLE }
    private fun hideQueue() { queueOverlay.visibility = View.GONE }

    // --------------------------------------------------------- 快照/事件

    private fun applySnapshot(m: Map<*, *>) {
        (m["styles"] as? Map<*, *>)?.let { lyricView.setStyles(it) }
        (m["meta"] as? Map<*, *>)?.let { onMeta(it) }
        (m["lyrics"] as? Map<*, *>)?.let { onLyrics(it) }
        (m["transport"] as? Map<*, *>)?.let { onSync(it) }
    }

    fun onMeta(m: Map<*, *>?) {
        if (m == null) return
        titleText.text = (m["title"] as? String) ?: ""
        artistText.text = (m["artist"] as? String) ?: ""
        val url = m["coverUrl"] as? String
        if (url != coverUrl) { coverUrl = url; loadCover(url) }
        (m["playMode"] as? Number)?.let { setPlayMode(it.toInt()) }
        (m["isLiked"] as? Boolean)?.let { setLiked(it) }
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
        (m["playMode"] as? Number)?.let { setPlayMode(it.toInt()) }
        (m["isLiked"] as? Boolean)?.let { setLiked(it) }
        anchorPosMs = pos
        anchorUptime = android.os.SystemClock.elapsedRealtime()
        seek.max = max(1, durationMs.toInt())
        applyPlayState(playing)
        if (!playing && !seekDragging) {
            val cur = currentMs()
            seek.progress = cur.toInt()
            elapsedText.text = fmt(cur)
            remainText.text = "-" + fmt(max(0L, durationMs - cur))
            lyricView.setProgress(cur)
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
    }

    private fun setLiked(v: Boolean) {
        liked = v
        if (v) {
            likeBtn.setImageResource(R.drawable.ic_kg_heart_fill)
            likeBtn.setColorFilter(0xFFFF4A6B.toInt())
        } else {
            likeBtn.setImageResource(R.drawable.ic_kg_heart_border)
            likeBtn.setColorFilter(0xFFEEFFFFFF.toInt())
        }
    }

    private fun applyPlayState(isPlaying: Boolean) {
        playBtn.setImageResource(if (isPlaying) R.drawable.ic_kg_pause else R.drawable.ic_kg_play)
        tonearm.animate().rotation(if (isPlaying) 26f else -14f).setDuration(500).start()
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
        if (rotAnimator?.isRunning == true) return
        rotAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 20000L
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
        bgView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF1C2230.toInt(), 0xFF0E1016.toInt(), 0xFF000000.toInt())
        )
    }

    private fun loadCover(url: String?) {
        val token = ++coverToken
        if (url.isNullOrBlank()) {
            runOnUiThread { if (token == coverToken) disc.setImageDrawable(null) }
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
                        val grad = gradientFromCover(bmp)
                        runOnUiThread {
                            if (token == coverToken) {
                                disc.setImageBitmap(bmp)
                                bgView.background = grad
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                // 封面失败不影响播放，留默认深色渐变
            }
        }.start()
    }

    /** 从封面取色：上/下分区平均后压暗，生成自上而下的深色渐变（近似酷狗模糊封面背景）。 */
    private fun gradientFromCover(bmp: Bitmap): GradientDrawable {
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return defaultGradient()
        val top = avgColor(bmp, 0, (h * 0.33f).toInt().coerceAtLeast(1))
        val mid = avgColor(bmp, (h * 0.33f).toInt(), (h * 0.66f).toInt().coerceAtLeast(2))
        val bot = avgColor(bmp, (h * 0.66f).toInt(), h)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(darken(top, 0.42f), darken(mid, 0.30f), darken(bot, 0.16f))
        )
    }

    private fun defaultGradient(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF1C2230.toInt(), 0xFF0E1016.toInt(), 0xFF000000.toInt())
    )

    private fun avgColor(bmp: Bitmap, y0: Int, y1: Int): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        val stepX = (bmp.width / 12).coerceAtLeast(1)
        val stepY = ((y1 - y0) / 12).coerceAtLeast(1)
        var y = y0
        while (y < y1) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0L) return 0xFF101014.toInt()
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    private fun darken(c: Int, f: Float): Int {
        val a = 0xFF shl 24
        val r = (((c shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * f).toInt().coerceIn(0, 255)
        return a or (r shl 16) or (g shl 8) or b
    }

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    private val ACCENT = 0xFF3FB9F0.toInt()
}
