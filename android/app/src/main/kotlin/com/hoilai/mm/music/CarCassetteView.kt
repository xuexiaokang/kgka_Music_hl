package com.hoilai.mm.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * [CASSETTE_EXPERIMENT] 实验性「卡式磁带」视觉，用于替代车机全屏的黑胶唱片盘图。
 *
 * 素材全部取自用户提供的写实磁带照片（960x622 正视高清图），坚持「原汁原味」：
 *  1. cassette6_body —— 原图外壳 / 螺丝 / 红条 / 黑条 / 边框 / **全部标签文字**
 *     （B、HD、POSITION NORMAL、90、Normal Bias 120μs EQ、A|60）100% 原图像素保留；
 *     照片纯白背景整圈抠掉（并做颜色外扩），塑料外侧不再有白边 / 白毛边；
 *     米色纸底挖空（让封面透出）；窗口内开口换成长条带基底色（磁带由动态层重绘）；
 *  2. 专辑封面只填进米色标签纸区，位于 body 之下，因此文字天然覆盖在封面之上；
 *  3. 卷轴 = 以各自齿轮轴心为圆心的**圆形带包**（满卷 R188 取自原图；
 *     空卷 R70 ≈ 真实磁带接近卷芯的半径，小于窗缘到轴心的距离 84–109，
 *     磁带用完后卷盘**缩离窗缘**露出黑底，而不是棕色贴边残留），
 *     半径按磁带面积守恒随进度联动：R_l²+R_r²=188²+70²，左卷收、右卷涨，
 *     即「棕色磁带从左往右输送」；可见部分是窗口玻璃里的圆弧段；
 *     贴图为原图卷面像素极坐标镜像铺环，采样前已把窗口内白色成分
 *     （月牙/刻度）修复成棕色（gen_assets8.py），**卷面纯棕黑无白边**，随齿轮同角度旋转；
 *  4. cassette6_marks —— 窗口中央 6 条刻度小竖条，**固定在塑料上**：
 *     画在卷盘之上、frame 之下，任何进度纹丝不动；
 *  5. cassette6_ring_* —— **白色月牙环**（卷盘塑料法兰环，实测与卷盘同心：
 *     左环带 r92-104 / 右 r81-100，白芯 94-102 / 83-98）。它是透明上法兰里的
 *     白色加强环——**固定半径、随齿轮同角旋转**的齿轮塑料，不随带包缩放，
 *     带包缩离窗缘后依然贴在窗缘（空卷露白法兰，符合真实磁带）；
 *     贴图用逐半径镜角平铺从原图月牙采样（gen_ring.py），rotation=0 时
 *     窗缘所见即原照片月牙（中间最亮、上下收尖）；
 *  6. cassette6_hub_* —— 原图齿轮只抠「可见孔」半径 58（窗口黑框不参与旋转），
 *     同向转动，角度由绝对进度推导（与卷轴共用同一角度，严格同步）；
 *  7. cassette6_frame —— 窗口黑框 + 窗内一圈边缘，**画在最上层**，
 *     保证中间窗口永远固定不动（齿轮与卷盘都盖不住它），动的只有窗口里的磁带。
 *
 * 全套素材 Lanczos 2x 超采样（坐标基准 960 x2 = 1920）：车机宽屏上磁带会被
 * 放大 >1x，2x 素材让绘制回落到 ≤1:1 采样，保持高清。
 *
 * 动效由绝对进度推导（不持有定时器 / 动画），seek / 暂停 / 重建自动对齐。
 *
 * 删除：删本文件 + res/drawable-nodpi/cassette6_*.png + CarPlayerActivity 中
 * 所有 [CASSETTE_EXPERIMENT] 标记代码块，无通道 / Dart 残留。
 */
class CarCassetteView(context: Context) : View(context) {

    private var albumArt: Bitmap? = null
    private var positionMs = 0L
    private var durationMs = 0L

    private var body: Bitmap? = null
    private var frame: Bitmap? = null
    private var hubLeft: Bitmap? = null
    private var hubRight: Bitmap? = null
    private var reelLeft: Bitmap? = null
    private var reelRight: Bitmap? = null
    private var ringLeft: Bitmap? = null
    private var ringRight: Bitmap? = null
    private var marks: Bitmap? = null

    private val pBitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4D000000
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val pEdgeInner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pEdgeOuter = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pWash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x50FFF8EF.toInt()
        style = Paint.Style.FILL
    }
    private val labelPath = Path()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val texRect = RectF()
    private val tmpRect = RectF()
    private val winRect = RectF()
    private val clipRect = RectF()
    private val circlePath = Path()
    private val ringPath = Path()
    private var assetTier = 0

    companion object {
        // 素材坐标基于 960x622 照片；全套 2x 超采样，坐标统一 x2
        private const val HI = 2
        private const val IMG_W = 960f * HI
        private const val IMG_H = 622f * HI
        private const val IMG_CX = 480f * HI
        private const val IMG_CY = 311f * HI

        // 米色标签纸区（封面填充范围，圆角 6 -> 12）
        private const val LABEL_L = 66f * HI
        private const val LABEL_T = 90f * HI
        private const val LABEL_R = 900f * HI
        private const val LABEL_B = 440f * HI

        // 窗口内开口（磁带可见区）：磁带只在 rect 内动，绝不溢出到黑框
        private const val WIN_L = 368f * HI
        private const val WIN_T = 221f * HI
        private const val WIN_R = 596f * HI
        private const val WIN_B = 352f * HI
        // frame 小图（窗口黑框层）贴图原点；marks（刻度固定层）贴图原点
        private const val FRAME_L = 188f * HI
        private const val FRAME_T = 193f * HI
        private const val MARK_L = 368f * HI
        private const val MARK_T = 221f * HI
        // frame / marks 设计尺寸（2x 坐标系）：阶梯档位按目标矩形拉伸绘制
        private const val FRAME_W = 579f * HI
        private const val FRAME_H = 189f * HI
        private const val MARK_W = 228f * HI
        private const val MARK_H = 131f * HI

        // 白色月牙环（卷盘塑料法兰环）：与卷盘同心、固定半径、随齿轮同角旋转，
        // 不随带包缩放；带包缩离窗缘后依然贴在窗缘（实测左环带 r92-104 / 右 r81-100）
        private const val RING_L_IN = 92f * HI
        private const val RING_L_OUT = 104f * HI
        private const val RING_R_IN = 81f * HI
        private const val RING_R_OUT = 100f * HI
        // 环裁剪比贴图羽化带宽 4px：边缘由贴图 alpha 收口，裁剪线落在透明区不露硬边
        private const val RING_CLIP_PAD = 4f * HI

        // 左右卷轴半径（满卷 188 取自原图棕带外缘）
        private const val R_FULL = 188f * HI
        // 带包可见下限 = 白月牙环外缘 + 一线残留（真实磁带到结尾卷芯也留着一线磁带）。
        // 此前下限取隐藏的卷芯 r70，导致棕色爬出环外缘(>100)要 p>17%（4 分钟歌约 42 秒）
        // 才开始可见、左盘棕色外带 p>81% 即缩进环内——"开头迟迟不出现 / 结尾提前消失"。
        private const val REEL_FLOOR_L = RING_L_OUT + 8f * HI
        private const val REEL_FLOOR_R = RING_R_OUT + 8f * HI
        // 棕色可见带包量（环外缘到带缘的面积）随播放时间线性增减：
        // R_l² = FLOOR_L² + (R_FULL²−FLOOR_L²)(1−p)、R_r² = FLOOR_R² + (R_FULL²−FLOOR_R²)p
        private const val TAPE_DAREA_L = R_FULL * R_FULL - REEL_FLOOR_L * REEL_FLOOR_L
        private const val TAPE_DAREA_R = R_FULL * R_FULL - REEL_FLOOR_R * REEL_FLOOR_R
        // 卷轴贴图 760x760（2x），圆心在位图 (380,380)
        private const val TEX_HALF = 190f * HI
        // 投影：下偏移量 ∝ 卷盘半径（光从上方来的俯仰角系数）
        private const val SHADOW_DROP = 0.12f

        // 原图齿轮中心（可见孔半径约 54，抠图半径 58）
        private const val HUB_L = 284f * HI
        private const val HUB_R = 677f * HI
        private const val HUB_Y_L = 288f * HI
        private const val HUB_Y_R = 287f * HI
        private const val GEAR_HALF = 58f * HI

        // 清晰度阶梯：全套贴图按主档设计宽的 1/8、1/4、1/2、3/4 预生成 t1..t4 档，
        // 运行时按「实际绘制宽度」选最小能覆盖的档位，保证双线性降采样 ≤2 倍
        // （此前只有 2x 主档，车机屏 ~320px 绘制宽时被 6-12 倍压采样而发糊）；
        // 1.0 档沿用无后缀主档（body 1920×1244 ≥ 1080P，大屏专用）
        private val TIERS = floatArrayOf(0.125f, 0.25f, 0.5f, 0.75f, 1f)
    }

    fun setAlbumArt(bmp: Bitmap?) {
        albumArt = bmp
        invalidate()
    }

    /** 由 Activity 现有 tick 调用；卷盘宽度与齿轮角度都由绝对进度推导。 */
    fun setProgress(positionMs: Long, durationMs: Long) {
        if (this.positionMs == positionMs && this.durationMs == durationMs) return
        this.positionMs = positionMs
        this.durationMs = durationMs
        invalidate()
    }

    private fun ensureAssets() {
        if (labelPath.isEmpty) {
            labelPath.reset()
            labelPath.addRoundRect(
                RectF(LABEL_L, LABEL_T, LABEL_R, LABEL_B), 12f, 12f, Path.Direction.CW
            )
            winRect.set(WIN_L, WIN_T, WIN_R, WIN_B)
            clipRect.set(winRect)
        }
        val tier = assetTierIndex()
        if (body != null && tier == assetTier) return
        assetTier = tier
        // 换档（仅在 View 尺寸变化时发生）：回收旧档位位图后按新档重新解码
        listOfNotNull(
            body, frame, hubLeft, hubRight, reelLeft, reelRight, ringLeft, ringRight, marks
        ).forEach { it.recycle() }
        body = decodeTier("cassette6_body", tier)
        frame = decodeTier("cassette6_frame", tier)
        marks = decodeTier("cassette6_marks", tier)
        reelLeft = decodeTier("cassette6_reel_l", tier)
        reelRight = decodeTier("cassette6_reel_r", tier)
        ringLeft = decodeTier("cassette6_ring_l", tier)
        ringRight = decodeTier("cassette6_ring_r", tier)
        hubLeft = decodeTier("cassette6_hub_l", tier)
        hubRight = decodeTier("cassette6_hub_r", tier)
    }

    /** 按当前绘制尺寸选清晰度档位索引：f·主档宽 ≥ 实际绘制宽（双线性降采样 ≤2 倍）。 */
    private fun assetTierIndex(): Int {
        if (width <= 0 || height <= 0) return TIERS.size - 1
        val scale = minOf(width, height).toFloat() * 0.96f / IMG_W
        for (i in TIERS.indices) if (TIERS[i] >= scale - 1e-3f) return i
        return TIERS.size - 1
    }

    /** 解码指定档位贴图；t1..t4 为阶梯小图，末档回落无后缀主档（body ≥1080P）。 */
    private fun decodeTier(base: String, tier: Int): Bitmap? {
        val res = resources
        val pkg = context.packageName
        var id = 0
        if (tier < TIERS.size - 1) {
            id = res.getIdentifier("${base}_t${tier + 1}", "drawable", pkg)
        }
        if (id == 0) id = res.getIdentifier(base, "drawable", pkg)
        return if (id != 0) BitmapFactory.decodeResource(res, id) else null
    }

    /** 播放进度 0..1；无时长时按 0 处理，避免除零。 */
    private fun progress(): Float {
        if (durationMs <= 0L) return 0f
        val p = positionMs.toFloat() / durationMs.toFloat()
        return if (p < 0f) 0f else if (p > 1f) 1f else p
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        ensureAssets()
        val shell = body ?: return

        // 等比映射：磁带本体占 View 宽度 96%
        val scale = s * 0.96f / IMG_W
        canvas.save()
        canvas.translate(
            width / 2f - IMG_CX * scale,
            height / 2f - IMG_CY * scale
        )
        canvas.scale(scale, scale)

        // 1) 专辑封面填进米色标签纸区（等比裁切填满，轻微提亮让黑字可读）
        val art = albumArt
        if (art != null && art.width > 0 && art.height > 0) {
            canvas.save()
            canvas.clipPath(labelPath)
            val ar = art.width.toFloat() / art.height
            val lr = (LABEL_R - LABEL_L) / (LABEL_B - LABEL_T)
            if (ar > lr) {
                val sw = (art.height * lr).toInt()
                srcRect.set((art.width - sw) / 2, 0, (art.width + sw) / 2, art.height)
            } else {
                val sh = (art.width / lr).toInt()
                srcRect.set(0, (art.height - sh) / 2, art.width, (art.height + sh) / 2)
            }
            dstRect.set(LABEL_L, LABEL_T, LABEL_R, LABEL_B)
            canvas.drawBitmap(art, srcRect, dstRect, pBitmap)
            // 提亮 + 偏米色，保持原图黑字可读（不压暗）
            canvas.drawRect(dstRect, pWash)
            canvas.restore()
        }

        // 2) 外壳底图：文字 / 红黑条 / 边框原样，米色底透明、窗内为带基底色
        dstRect.set(0f, 0f, IMG_W, IMG_H)
        canvas.drawBitmap(shell, null, dstRect, pBitmap)

        // 旋转角：卷轴贴图与齿轮共用同一角度（同向同步转动）。
        // 真实磁带走带沿窗口底部从左卷到右卷，两轴带面在底部均随磁带向右移动，
        // 故两轴同为逆时针（Canvas 正角度=屏幕顺时针，取负即逆时针）。
        val angle = -(positionMs / 1000f) * 45f

        // 3) 两个圆形卷轴：半径随进度联动、纹理随齿轮同步旋转（严格裁剪在窗口内）
        canvas.save()
        canvas.clipRect(clipRect)
        drawReels(canvas, progress(), angle)
        // 3.5) 白色月牙环：固定半径塑料法兰，随同一角度旋转（不随带包缩放）
        drawRings(canvas, angle)
        canvas.restore()

        // 4) 两个原图齿轮同向旋转（只转可见孔，窗口黑框不参与）
        drawHub(canvas, hubLeft, HUB_L, HUB_Y_L, angle)
        drawHub(canvas, hubRight, HUB_R, HUB_Y_R, angle)

        // 5) 刻度层：窗口中央 6 条刻度小竖条（月牙归入旋转环层）。
        //    原图中刻度印在窗面前侧塑料上、叠在磁带之上（照片里两条刻度
        //    直接落在带包上），故画在卷盘上层；body 底图窗内的残留刻度
        //    痕迹已清除，全窗只保留这一套，位置按原图实测。
        marks?.let {
            dstRect.set(MARK_L, MARK_T, MARK_L + MARK_W, MARK_T + MARK_H)
            canvas.drawBitmap(it, null, dstRect, pBitmap)
        }

        // 6) 窗口黑框 + 窗内边缘：静态压在最上层，保证窗口纹丝不动
        frame?.let {
            dstRect.set(FRAME_L, FRAME_T, FRAME_L + FRAME_W, FRAME_T + FRAME_H)
            canvas.drawBitmap(it, null, dstRect, pBitmap)
        }

        canvas.restore()
    }

    /** 从原图抠出的圆形齿轮，按角度同向旋转。 */
    private fun drawHub(canvas: Canvas, hub: Bitmap?, cx: Float, cy: Float, angle: Float) {
        if (hub == null) return
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angle)
        texRect.set(-GEAR_HALF, -GEAR_HALF, GEAR_HALF, GEAR_HALF)
        canvas.drawBitmap(hub, null, texRect, pBitmap)
        canvas.restore()
    }

    /**
     * 左右卷轴：以齿轮轴心为圆心的圆形带包。
     * 真实磁带带基线速度恒定 ⇒ 可见棕色带包面积（环外缘→带缘）与播放时间成正比，
     * 半径按平方根规律变化；带缘下限贴住白月牙环外缘+一线残留，
     * 保证棕色从第 0 秒起就可见、结尾也不会整卷消失（卷芯残留一线磁带）；
     * 白色月牙环是卷轴齿轮塑料，固定半径不参与时间计量；
     * 圆形裁剪出可见弧段，贴图按齿轮同角度旋转（同向同步）；
     * 暗边环软化裁剪锯齿，模仿照片里的卷缘阴影。
     * 投影：光从上方来，卷盘向带基一侧投下软阴影——
     * 投影盘中心向下偏移 off∝R，被卷盘本体盖住后只露出
     * 卷缘外侧的下弯月牙，盘越大月牙越厚（真实光影随卷径变化）。
     */
    private fun drawReels(canvas: Canvas, p: Float, angle: Float) {
        val rl = kotlin.math.sqrt(REEL_FLOOR_L * REEL_FLOOR_L + TAPE_DAREA_L * (1f - p))
        val rr = kotlin.math.sqrt(REEL_FLOOR_R * REEL_FLOOR_R + TAPE_DAREA_R * p)
        drawReelShadow(canvas, HUB_L, HUB_Y_L, rl)
        drawReelShadow(canvas, HUB_R, HUB_Y_R, rr)
        drawReel(canvas, reelLeft, HUB_L, HUB_Y_L, rl, angle)
        drawReel(canvas, reelRight, HUB_R, HUB_Y_R, rr, angle)
    }

    /**
     * 白色月牙环层：卷盘的塑料法兰环（齿轮塑料的一部分）。
     * 与卷盘同心、固定半径——不随带包缩放，贴图随同一 angle 旋转，
     * 带包缩离窗缘后依然贴在窗缘（空卷露白法兰，符合真实磁带）。
     * 裁剪外扩 RING_CLIP_PAD，边缘由贴图自身 2.5px alpha 羽化收口。
     */
    private fun drawRings(canvas: Canvas, angle: Float) {
        drawRing(canvas, ringLeft, HUB_L, HUB_Y_L, RING_L_IN, RING_L_OUT, angle)
        drawRing(canvas, ringRight, HUB_R, HUB_Y_R, RING_R_IN, RING_R_OUT, angle)
    }

    private fun drawRing(
        canvas: Canvas, tex: Bitmap?, cx: Float, cy: Float, rIn: Float, rOut: Float, angle: Float
    ) {
        if (tex == null) return
        canvas.save()
        canvas.translate(cx, cy)
        ringPath.reset()
        ringPath.addCircle(0f, 0f, rOut + RING_CLIP_PAD, Path.Direction.CW)
        ringPath.addCircle(0f, 0f, rIn - RING_CLIP_PAD, Path.Direction.CW)
        ringPath.fillType = Path.FillType.EVEN_ODD
        canvas.clipPath(ringPath)
        canvas.rotate(angle)
        texRect.set(-TEX_HALF, -TEX_HALF, TEX_HALF, TEX_HALF)
        canvas.drawBitmap(tex, null, texRect, pBitmap)
        canvas.restore()
    }

    /** 卷盘投影：向下偏移的软圆盘，露出卷缘外的月牙即投影，随半径缩放。 */
    private fun drawReelShadow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (radius <= 0f) return
        val off = radius * SHADOW_DROP
        pShadow.shader = RadialGradient(
            cx, cy + off, radius,
            intArrayOf(0x00000000, 0x00000000, 0x4D000000, 0x33000000),
            floatArrayOf(0f, 0.80f, 0.94f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + off, radius, pShadow)
    }

    private fun drawReel(
        canvas: Canvas, tex: Bitmap?, cx: Float, cy: Float, radius: Float, angle: Float
    ) {
        if (tex == null || radius <= 0f) return
        canvas.save()
        canvas.translate(cx, cy)
        circlePath.reset()
        circlePath.addCircle(0f, 0f, radius, Path.Direction.CW)
        canvas.clipPath(circlePath)
        canvas.rotate(angle)
        texRect.set(-TEX_HALF, -TEX_HALF, TEX_HALF, TEX_HALF)
        canvas.drawBitmap(tex, null, texRect, pBitmap)
        canvas.restore()
        // 卷缘内侧渐变：贴图边缘向内轻微压暗，柔化棕→缘过渡
        pEdgeInner.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(0x00000000, 0x00000000, 0x33000000),
            floatArrayOf(0f, 0.965f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, pEdgeInner)
        // 卷缘细暗线（在旋转系外画，圆与角度无关）
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawCircle(0f, 0f, radius, pEdge)
        canvas.restore()
        // 卷缘外侧长渐变坡：从卷缘缓慢融入背景，避免生硬分割
        pEdgeOuter.shader = RadialGradient(
            cx, cy, radius + 24f,
            intArrayOf(0x00000000, 0x00000000, 0x38000000, 0x00000000),
            floatArrayOf(0f, 0.90f, 0.94f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius + 24f, pEdgeOuter)
    }
}
