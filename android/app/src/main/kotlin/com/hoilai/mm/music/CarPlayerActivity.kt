package com.hoilai.mm.music

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.view.Choreographer
import kotlin.math.max

/**
 * 全原生车机全屏播放器：封面转盘 + 原生歌词 [CarLyricView] + 进度/传输控制，
 * 全部走 HWUI/Skia，Flutter 引擎被覆盖后停帧——渲染路径与酷我一致，不再霸占 GPU。
 *
 * 播放由 Dart 的 PlayerController 负责，本 Activity 只发传输事件并接收状态快照：
 *  - open 时读 [CarPlayerBridge.snapshot] 建 UI
 *  - onResume 发 requestSync 让 Dart 推权威状态
 *  - 本地时钟(elapsedRealtime)在两次 sync 间平滑推进进度/歌词，暂停即停帧让 GPU idle
 */
class CarPlayerActivity : Activity() {

    private lateinit var lyricView: CarLyricView
    private lateinit var disc: ImageView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsedText: TextView
    private lateinit var remainText: TextView
    private lateinit var playBtn: ImageButton

    private var rotAnimator: ValueAnimator? = null

    private var anchorPosMs = 0L
    private var anchorUptime = 0L
    private var durationMs = 0L
    private var playing = false
    private var seekDragging = false
    private var coverUrl: String? = null
    private var coverToken = 0

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
        window.setStatusBarColor(Color.BLACK)
        window.navigationBarColor = Color.BLACK
        buildUi()

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
        CarPlayerBridge.sendEvent("closed")
        super.onBackPressed()
    }

    /** Dart 主动要求关闭（不回调 closed，因为是 Dart 触发的）。 */
    fun finishExternally() {
        runOnUiThread { finish() }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24f).toInt(), dp(16f).toInt(), dp(24f).toInt(), dp(12f).toInt())
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // 左：转盘 + 曲名/艺人
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, .42f)
            setPadding(0, 0, dp(12f).toInt(), 0)
        }
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
        val discSize = dp(200f).toInt()
        left.addView(disc, LinearLayout.LayoutParams(discSize, discSize))
        titleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(0, dp(16f).toInt(), 0, 0)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        artistText = TextView(this).apply {
            setTextColor(Color.argb(180, 255, 255, 255))
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        left.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        left.addView(artistText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        topRow.addView(left)

        // 右：原生歌词
        lyricView = CarLyricView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        topRow.addView(lyricView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, .58f))
        root.addView(topRow)

        // 底部：进度条 + 控制
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        elapsedText = TextView(this).apply { setTextColor(Color.argb(200,255,255,255)); textSize = 12f; text = "0:00" }
        remainText = TextView(this).apply { setTextColor(Color.argb(200,255,255,255)); textSize = 12f; text = "0:00" }
        seek = SeekBar(this).apply {
            max = 1
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
            it.marginStart = dp(10f).toInt(); it.marginEnd = dp(10f).toInt()
        })
        seekRow.addView(remainText)
        root.addView(seekRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4f).toInt(), 0, 0)
        }
        btnRow.addView(iconButton(android.R.drawable.ic_media_previous) {
            CarPlayerBridge.sendEvent("prev")
        })
        playBtn = iconButton(android.R.drawable.ic_media_pause) {
            CarPlayerBridge.sendEvent("playPause")
        }
        btnRow.addView(playBtn)
        btnRow.addView(iconButton(android.R.drawable.ic_media_next) {
            CarPlayerBridge.sendEvent("next")
        })
        btnRow.addView(iconButton(android.R.drawable.ic_menu_close_clear_cancel) {
            CarPlayerBridge.sendEvent("closed")
            finish()
        })
        root.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun iconButton(resId: Int, onTap: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(resId)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            val s = dp(52f).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).also { it.marginStart = dp(12f).toInt(); it.marginEnd = dp(12f).toInt() }
            contentDescription = null
            setOnClickListener { onTap() }
        }
    }

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

    private fun applyPlayState(isPlaying: Boolean) {
        playBtn.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
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
            addUpdateListener { disc.rotation = it.animatedValue as Float }
            start()
        }
    }

    private fun stopRotation() {
        rotAnimator?.cancel()
        rotAnimator = null
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
                    runOnUiThread { if (token == coverToken && bmp != null) disc.setImageBitmap(bmp) }
                }
                conn.disconnect()
            } catch (_: Exception) {
                // 封面失败不影响播放，留空圆盘
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
