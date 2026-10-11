package com.hoilai.mm.music

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 车机全屏歌词视图 —— 严格复刻酷我原生 [cn.kuwo.base.uilib.DrawLyricView] 的渲染纪律：
 * 纯 [android.view.View] + HWUI/Skia，卡拉OK 高亮 = `clipRect` + `drawText`（零 `saveLayer`、
 * 零离屏混合、零 dstIn），逐字推进只 `invalidate` 当前行那一小条脏矩形，让 HWUI 做
 * damage-based 局部栅格化；非播放时不申请 vsync、GPU 可 idle。
 *
 * 之所以用它替换 flutter_lyric：Flutter 只要该层脏就重栅格【整块歌词层】并把整面 buffer
 * 交给系统合成器，弱车机 GPU 上这会持续满载、饿死并发的第三方浮窗视频；原生 HWUI 只刷
 * 一小条带，与酷我同路。
 *
 * 数据来源：Dart 侧经 per-view MethodChannel 传 `setLyrics`（行 + 逐字时间）与逐帧 `setProgress`（ms）。
 */
class CarLyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- 画笔（全部纯色直绘，无 Layer / 无 Xfermode）----
    private val basePaint = antialiasPaint()       // 非当前行（暗色，小字号）
    private val activeBasePaint = antialiasPaint() // 当前行未唱部分（暗色，大字号，与高亮同尺寸对齐）
    private val activePaint = antialiasPaint()     // 当前行已唱部分（高亮，大字号）
    private val transBasePaint = antialiasPaint()
    private val transActivePaint = antialiasPaint()

    private fun antialiasPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    // ---- 样式（sp/dp 由 Dart 设定，默认对齐现车机横屏样式）----
    private var activeSizeSp = 34f
    private var inactiveSizeSp = 24f
    private var translationSizeSp = 15f
    private var lineGapDp = 16f
    private var paddingHorizontalDp = 24f
    private var paddingVerticalDp = 40f
    private var baseColor = Color.argb(87, 255, 255, 255)     // 白 alpha .34
    private var activeColor = Color.WHITE
    private var transBaseColor = Color.argb(61, 255, 255, 255)
    private var showTranslation = true

    private var density = resources.displayMetrics.density
    private var spScale = resources.displayMetrics.scaledDensity

    private val scroller = OverScroller(context)
    private val dirty = Rect()

    // ---- 歌词数据 ----
    private var texts: Array<String> = emptyArray()
    private var translations: Array<String?> = emptyArray()
    private var startsMs: LongArray = LongArray(0)
    private var endsMs: LongArray = LongArray(0)
    private var wordsText: Array<Array<String>> = emptyArray()
    private var wordsStart: Array<LongArray> = emptyArray()
    private var wordsEnd: Array<LongArray> = emptyArray()

    // ---- 运行时布局缓存（按当前激活行字号会变，故脏标记重排）----
    private var activeIndex = -1
    private var currentMs = 0L
    private var lineTop: IntArray = IntArray(0)   // 每行内容顶部相对内容起点
    private var lineHeight: IntArray = IntArray(0)
    private var contentHeight = 0
    private var lastScrolledActive = -2

    private var offsetY = 0f
    private var anchorFraction = 0.32f

    // ---- 触摸交互：点击歌词行 seek + 手动上下拖动滚动 ----
    /** 点击某行时回调其起始时间（ms），由宿主 Activity 转发给 Dart 执行 seek。 */
    var onLineSeek: ((Long) -> Unit)? = null
    /** 用户正在拖动/刚交互过，宿主可据此暂缓自动跟随滚动。 */
    var userInteracting = false
        private set

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        applyPaints()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (texts.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; lastY = event.y
                downTime = System.currentTimeMillis()
                dragging = false
                userInteracting = true
                scroller.abortAnimation()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastY
                lastY = event.y
                if (!dragging && abs(event.y - downY) > touchSlop) dragging = true
                if (dragging) {
                    // 手指向下 → 内容向下（offsetY 减小）；限制在内容范围内
                    val maxScroll = (contentHeight - height + paddingVerticalPx() * 2).coerceAtLeast(0f)
                    offsetY = (offsetY - dy).coerceIn(0f, maxScroll)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val quick = System.currentTimeMillis() - downTime < 300
                val moved = abs(event.y - downY) > touchSlop || abs(event.x - downX) > touchSlop
                if (event.actionMasked == MotionEvent.ACTION_UP && quick && !moved) {
                    val idx = lineIndexAtY(event.y)
                    if (idx in texts.indices) {
                        val start = startsMs[idx]
                        currentMs = start
                        activeIndex = idx
                        invalidateLayout()
                        scrollToActive(smooth = false)
                        invalidate()
                        onLineSeek?.invoke(start)
                    }
                }
                dragging = false
                // 交互结束后延时交回自动跟随：下一次换行会自然滚回当前行
                postDelayed({ userInteracting = false }, 2500)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 命中测试：给定屏幕 y 找出对应歌词行（与 onDraw 的坐标换算一致）。 */
    private fun lineIndexAtY(y: Float): Int {
        val padY = paddingVerticalPx()
        val n = texts.size
        for (i in 0 until n) {
            val top = padY + lineTop[i] - offsetY
            val bottom = top + lineHeight[i]
            if (y >= top && y <= bottom) return i
        }
        return -1
    }

    private fun applyPaints() {
        basePaint.apply {
            color = baseColor
            textSize = inactiveSizeSp * spScale
            isFakeBoldText = true
        }
        activeBasePaint.apply {
            color = baseColor
            textSize = activeSizeSp * spScale
            isFakeBoldText = true
        }
        activePaint.apply {
            color = activeColor
            textSize = activeSizeSp * spScale
            isFakeBoldText = true
        }
        transBasePaint.apply {
            color = transBaseColor
            textSize = translationSizeSp * spScale
            isFakeBoldText = false
        }
        transActivePaint.apply {
            color = activeColor
            textSize = translationSizeSp * spScale
            isFakeBoldText = false
        }
    }

    // ------------------------------------------------------------------
    //  Dart -> Native 配置入口
    // ------------------------------------------------------------------

    fun setStyles(map: Map<*, *>) {
        activeSizeSp = (map["activeSizeSp"] as? Number)?.toFloat() ?: activeSizeSp
        inactiveSizeSp = (map["inactiveSizeSp"] as? Number)?.toFloat() ?: inactiveSizeSp
        translationSizeSp = (map["translationSizeSp"] as? Number)?.toFloat() ?: translationSizeSp
        lineGapDp = (map["lineGapDp"] as? Number)?.toFloat() ?: lineGapDp
        paddingHorizontalDp = (map["paddingHorizontalDp"] as? Number)?.toFloat() ?: paddingHorizontalDp
        paddingVerticalDp = (map["paddingVerticalDp"] as? Number)?.toFloat() ?: paddingVerticalDp
        (map["baseColor"] as? Number)?.let { baseColor = it.toInt() }
        (map["activeColor"] as? Number)?.let { activeColor = it.toInt() }
        (map["transColor"] as? Number)?.let { transBaseColor = it.toInt() }
        (map["showTranslation"] as? Boolean)?.let { showTranslation = it }
        applyPaints()
        invalidateLayout()
        invalidate()
    }

    fun setLyrics(lines: List<*>) {
        val n = lines.size
        texts = Array(n) { "" }
        translations = arrayOfNulls(n)
        startsMs = LongArray(n)
        endsMs = LongArray(n)
        wordsText = Array(n) { emptyArray<String>() }
        wordsStart = Array(n) { LongArray(0) }
        wordsEnd = Array(n) { LongArray(0) }

        for (i in 0 until n) {
            val m = lines[i] as? Map<*, *> ?: continue
            texts[i] = (m["text"] as? String) ?: ""
            translations[i] = (m["translation"] as? String)?.takeIf { it.isNotEmpty() }
            startsMs[i] = (m["startMs"] as? Number)?.toLong() ?: 0L
            endsMs[i] = (m["endMs"] as? Number)?.toLong() ?: startsMs[i]
            val ws = m["words"] as? List<*>
            if (ws != null && ws.isNotEmpty()) {
                wordsText[i] = Array(ws.size) { "" }
                wordsStart[i] = LongArray(ws.size)
                wordsEnd[i] = LongArray(ws.size)
                for (j in ws.indices) {
                    val wm = ws[j] as? Map<*, *> ?: continue
                    wordsText[i][j] = (wm["text"] as? String) ?: ""
                    wordsStart[i][j] = (wm["startMs"] as? Number)?.toLong() ?: 0L
                    wordsEnd[i][j] = (wm["endMs"] as? Number)?.toLong() ?: wordsStart[i][j]
                }
            }
        }
        activeIndex = -1
        lastScrolledActive = -2
        invalidateLayout()
        invalidate()
    }

    fun setProgress(ms: Long) {
        if (texts.isEmpty()) return
        currentMs = ms
        val idx = findActiveIndex(ms)
        if (idx != activeIndex) {
            activeIndex = idx
            invalidateLayout()
            scrollToActive(smooth = lastScrolledActive in 0 until texts.size)
            lastScrolledActive = idx
        }
        // 只刷当前行那一小条脏矩形（HWUI 局部栅格化）
        val band = bandRect(idx)
        if (band != null) invalidate(band) else invalidate()
    }

    // ------------------------------------------------------------------
    //  布局 / 滚动
    // ------------------------------------------------------------------

    private fun invalidateLayout() {
        val n = texts.size
        if (n == 0) {
            lineTop = IntArray(0); lineHeight = IntArray(0); contentHeight = 0
            return
        }
        lineTop = IntArray(n)
        lineHeight = IntArray(n)
        val gap = (lineGapDp * density).roundToInt()
        val transHeight = if (showTranslation) translationSizeSp * spScale else 0f
        var y = 0
        for (i in 0 until n) {
            val paint = if (i == activeIndex) activePaint else basePaint
            val fm = paint.fontMetrics
            var h = (fm.descent - fm.ascent).roundToInt()
            if (showTranslation && !translations[i].isNullOrEmpty()) {
                h += (transHeight + 6 * density).roundToInt()
            }
            lineHeight[i] = h
            lineTop[i] = y
            y += h + gap
        }
        contentHeight = y
    }

    private fun scrollToActive(smooth: Boolean) {
        if (activeIndex !in texts.indices) return
        val anchor = lineTop[activeIndex] - height * anchorFraction
        val maxScroll = (contentHeight - height + paddingVerticalPx() * 2).coerceAtLeast(0f)
        val target = anchor.coerceIn(0f, maxScroll).roundToInt()
        val cur = offsetY.roundToInt()
        if (!smooth || abs(target - cur) > height) {
            offsetY = target.toFloat()
            scroller.abortAnimation()
            return
        }
        val dy = target - cur
        if (dy != 0) {
            scroller.startScroll(0, cur, 0, dy, 380)
            postInvalidateOnAnimation()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offsetY = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        }
    }

    private fun paddingVerticalPx() = paddingVerticalDp * density
    private fun paddingHorizontalPx() = paddingHorizontalDp * density

    private fun bandRect(idx: Int): Rect? {
        if (idx !in texts.indices) return null
        val top = (paddingVerticalPx() + lineTop[idx] - offsetY).roundToInt()
        val bottom = top + lineHeight[idx]
        if (bottom < 0 || top > height) return null
        val pad = (4 * density).roundToInt()
        return Rect(0, top - pad, width, (bottom + pad).coerceAtMost(height + pad))
    }

    // ------------------------------------------------------------------
    //  绘制
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = texts.size
        if (n == 0) return
        if (lineTop.size != n) invalidateLayout()

        val padX = paddingHorizontalPx()
        val padY = paddingVerticalPx()

        for (i in 0 until n) {
            val top = padY + lineTop[i] - offsetY
            val bottom = top + lineHeight[i]
            if (bottom < 0 || top > height) continue // 视口外，跳过
            val line = texts[i]
            if (line.isEmpty()) continue

            if (i == activeIndex) {
                val hl = highlightX(line, i)
                // 底：整行按当前行字号（大）暗色，保证与高亮部分逐字对齐
                canvas.drawText(line, padX, baselineIn(activePaint, top, lineHeight[i]), activeBasePaint)
                // 高亮：clip 到已唱宽度后重画一次亮色（酷我 clipRect+drawText，无 saveLayer）
                if (hl > 0f) {
                    val save = canvas.save()
                    canvas.clipRect(padX, top, padX + hl, bottom)
                    canvas.drawText(line, padX, baselineIn(activePaint, top, lineHeight[i]), activePaint)
                    canvas.restoreToCount(save)
                }
                if (showTranslation) drawTranslation(canvas, translations[i], padX, top, lineHeight[i], hl, activePaint)
            } else {
                canvas.drawText(line, padX, baselineIn(basePaint, top, lineHeight[i]), basePaint)
                if (showTranslation) drawTranslation(canvas, translations[i], padX, top, lineHeight[i], -1f, basePaint)
            }
        }
    }

    private fun drawTranslation(
        canvas: Canvas, text: String?, padX: Float, lineTopPx: Float, blockHeight: Int, hl: Float, mainPaint: Paint
    ) {
        if (text.isNullOrEmpty()) return
        val mainFm = mainPaint.fontMetrics
        val mainH = (mainFm.descent - mainFm.ascent)
        val ty = lineTopPx + mainH + transActivePaint.fontMetrics.descent + 4 * density
        canvas.drawText(text, padX, ty, transBasePaint)
        if (hl > 0f) {
            val save = canvas.save()
            // 垂直覆盖整行块（含主行+译文）区域，只做水平"已唱宽度"裁剪
            canvas.clipRect(padX, lineTopPx, padX + hl, lineTopPx + blockHeight)
            canvas.drawText(text, padX, ty, transActivePaint)
            canvas.restoreToCount(save)
        }
    }

    /** 让文字在该行块内垂直居中后的基线 y。 */
    private fun baselineIn(paint: Paint, top: Float, blockHeight: Int): Float {
        val fm = paint.fontMetrics
        val textH = fm.descent - fm.ascent
        val centerY = top + blockHeight / 2f
        return centerY - textH / 2f - fm.ascent
    }

    /** 已唱到的像素宽度（相对文本起点），逐字优先，无逐字则整行线性填充。按当前行字号测量。 */
    private fun highlightX(line: String, idx: Int): Float {
        val ws = wordsText.getOrNull(idx)
        val totalWidth = activePaint.measureText(line)
        if (ws == null || ws.isEmpty()) {
            val s = startsMs[idx]
            val e = endsMs[idx]
            if (e <= s) return if (currentMs >= s) totalWidth else 0f
            val p = (currentMs - s).toFloat() / (e - s)
            return (totalWidth * p.coerceIn(0f, 1f))
        }
        val wStart = wordsStart[idx]
        val wEnd = wordsEnd[idx]
        var x = 0f
        for (j in ws.indices) {
            val w = ws[j]
            val wWidth = activePaint.measureText(w)
            val s = wStart[j]
            val e = wEnd[j].let { if (it <= s) s + 1 else it }
            if (currentMs >= e) {
                x += wWidth
            } else if (currentMs <= s) {
                return x
            } else {
                val p = (currentMs - s).toFloat() / (e - s)
                return x + wWidth * p.coerceIn(0f, 1f)
            }
        }
        return x.coerceAtMost(totalWidth)
    }

    private fun findActiveIndex(ms: Long): Int {
        val n = texts.size
        if (n == 0) return -1
        var idx = -1
        var lo = 0
        var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (ms >= startsMs[mid]) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return idx
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        density = resources.displayMetrics.density
        spScale = resources.displayMetrics.scaledDensity
        if (texts.isNotEmpty()) {
            invalidateLayout()
            scrollToActive(smooth = false)
        }
    }
}
