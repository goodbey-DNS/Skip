package com.noteatoranges.skipad.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.noteatoranges.skipad.db.SnapshotStore

/**
 * 快照可视化点选层：底图 = 广告截图；可点击节点画绿框、其余画灰框；
 * 点按任意位置选中"包含该点且面积最小"的节点（最具体的元素），红框高亮。
 */
class ScreenshotOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var bmp: Bitmap? = null
    private var nodes: List<SnapshotStore.Node> = emptyList()
    private var selected: SnapshotStore.Node? = null

    /**
     * 截图与节点坐标所在的原始像素空间（= 抓取时的屏幕分辨率）。
     *
     * 底图为了省内存按 inSampleSize=2 解码，位图只有原始的一半大；
     * 而节点矩形是全屏像素。用位图宽度算缩放会让框整体偏 2 倍——
     * 点到的控件和画出来的框对不上，长按取点的 dp 也跟着算错。
     * 所以缩放基准必须是原始像素，不能是位图尺寸。
     */
    private var srcW = 0
    private var srcH = 0
    private var longFired = false
    private var pickX = -1f
    private var pickY = -1f

    private val gesture = android.view.GestureDetector(
        context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: android.view.MotionEvent) {
                val s = drawScale
                if (s <= 0f) return
                longFired = true
                // 减去绘制偏移：预览图被缩到 55% 高度后，内容是居中画的
                pickX = (e.x - drawLeft) / s
                pickY = (e.y - drawTop) / s
                invalidate()
                onLongTapped?.invoke(pickX.toInt(), pickY.toInt())
            }
        },
    )

    /** 长按截图：回传图片像素坐标（换算成 dp 交给规则层） */
    var onLongTapped: ((x: Int, y: Int) -> Unit)? = null

    private val viewPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = 0x66000000
    }
    private val clickStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0xCC16A34A.toInt()
    }
    private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        color = 0xFFE11D48.toInt()
    }
    private val selFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30E11D48
    }
    private val rf = RectF()

    /** 原始像素 → 视图像素的缩放比（不依赖 onDraw 是否已跑过） */
    fun setContent(
        bitmap: Bitmap?,
        nodes: List<SnapshotStore.Node>,
        srcWidth: Int = 0,
        srcHeight: Int = 0,
    ) {
        this.bmp = bitmap
        // 节点坐标就是屏幕像素，取它与 meta 的较大值：旧快照的 meta 里 screenH 记的是
        // 「应用可用高度」（1220x2453，不含导航栏），比真实截图（1220x2712）矮，
        // 单独用它会把框越往下压得越偏。
        val nodeMaxR = nodes.maxOfOrNull { it.r } ?: 0
        val nodeMaxB = nodes.maxOfOrNull { it.b } ?: 0
        this.srcW = maxOf(srcWidth, nodeMaxR).takeIf { it > 0 } ?: bitmap?.width ?: 0
        this.srcH = maxOf(srcHeight, nodeMaxB).takeIf { it > 0 } ?: bitmap?.height ?: 0
        this.nodes = nodes
        selected = null
        // 换一份快照时清掉上一次的长按十字标记，否则新图上会留一个旧落点
        pickX = -1f
        pickY = -1f
        requestLayout()
        invalidate()
    }

    fun select(node: SnapshotStore.Node?) {
        selected = node
        invalidate()
    }

    /** 截图与节点共用的原始像素尺寸：长按取点的 dp 换算要用它，才会与画框一致 */
    val contentW: Int get() = srcW
    val contentH: Int get() = srcH

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val full = if (srcW > 0 && srcH > 0 && w > 0) {
            (w * srcH.toFloat() / srcW).toInt()
        } else {
            (200 * resources.displayMetrics.density).toInt()
        }
        // 整屏比例的截图铺满宽度就是两三屏高，节点列表被顶到最下面，
        // 得滚很久才够得到「生成跳过规则」。限到屏幕高度的 55%：
        // 整张图一眼看全，下面同时露出列表
        val maxH = (resources.displayMetrics.heightPixels * 0.55f).toInt().coerceAtLeast(1)
        setMeasuredDimension(w, full.coerceIn(1, maxH))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bmp ?: return
        if (width <= 0 || height <= 0) return
        // 高度被上限截断后，宽高比不再等于原图，必须按「较短边」等比缩放再居中，
        // 直接铺满会把画面压扁，节点框也跟着错
        val sw = if (srcW > 0) srcW else b.width
        val sh = if (srcH > 0) srcH else b.height
        val s = if (sw > 0 && sh > 0) {
            minOf(width.toFloat() / sw, height.toFloat() / sh)
        } else 1f
        drawScale = s
        drawLeft = (width - sw * s) / 2f
        drawTop = (height - sh * s) / 2f

        // 复用 rf：底图画完这一帧就不再用它，随后循环里会重新 set 成节点框，
        // 不会互相污染。每帧 new RectF 属于 onDraw 里的多余分配
        rf.set(drawLeft, drawTop, drawLeft + sw * s, drawTop + sh * s)
        canvas.drawBitmap(b, null, rf, viewPaint)

        for (n in nodes) {
            // 只画可点击节点与当前选中：淘宝这类页面节点上百个，全画出来就是一层黑网格，
            // 页面内容全被盖住，反而没法判断该点哪个。
            // 点选逻辑不受影响——点在空白处仍会选中「包含该点且面积最小」的节点
            if (!n.clickable && n != selected) continue
            rf.set(
                drawLeft + n.l * s, drawTop + n.t * s,
                drawLeft + n.r * s, drawTop + n.b * s,
            )
            canvas.drawRect(rf, if (n.clickable) clickStroke else strokePaint)
        }
        selected?.let {
            rf.set(
                drawLeft + it.l * s, drawTop + it.t * s,
                drawLeft + it.r * s, drawTop + it.b * s,
            )
            canvas.drawRect(rf, selFill)
            canvas.drawRect(rf, selStroke)
        }
        // 长按取点标记：十字星，让用户确认落点
        if (pickX >= 0f) {
            val px = drawLeft + pickX * s
            val py = drawTop + pickY * s
            val arm = 12f * resources.displayMetrics.density
            canvas.drawCircle(px, py, 9f * resources.displayMetrics.density, selStroke)
            canvas.drawLine(px - arm, py, px + arm, py, selStroke)
            canvas.drawLine(px, py - arm, px, py + arm, selStroke)
        }
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                longFired = false
                dragging = false
                downX = e.x
                downY = e.y
                // 先按「点击」处理，把滚动留给下面判断
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = Math.abs(e.x - downX)
                    val dy = Math.abs(e.y - downY)
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    // 竖向滑动超过阈值就交还父 ScrollView：
                    // 截图是整屏比例，节点列表和「生成跳过规则」按钮一定在它下方，
                    // 以前这里无条件消费所有事件，用户根本滚不到下面去
                    if (dy > slop && dy > dx) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        gesture.onTouchEvent(e)
        val s = drawScale
        // 视图坐标 → 截图像素坐标：先减居中偏移，再除缩放
        val bx = if (s > 0f) (e.x - drawLeft) / s else -1f
        val by = if (s > 0f) (e.y - drawTop) / s else -1f
        if (e.action == android.view.MotionEvent.ACTION_UP) {
            if (longFired) {
                longFired = false
                return true
            }
            if (bx >= 0 && by >= 0) {
                val cands = nodes.filter { bx >= it.l && bx <= it.r && by >= it.t && by <= it.b }
                val pick = cands.minByOrNull { it.w * it.h } ?: cands.firstOrNull()
                selected = pick
                invalidate()
                onNodeTapped?.invoke(pick)
                performClick()
            }
        }
        return true
    }

    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    /** 当前绘制用的缩放与偏移（高度被上限截断后，内容按比例居中） */
    private var drawScale = 1f
    private var drawLeft = 0f
    private var drawTop = 0f

    var onNodeTapped: ((SnapshotStore.Node?) -> Unit)? = null

    init {
        setOnClickListener { }
    }
}
