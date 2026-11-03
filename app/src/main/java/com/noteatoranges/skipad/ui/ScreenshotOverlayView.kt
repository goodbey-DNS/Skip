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
                val s = scaleToView()
                if (s <= 0f) return
                longFired = true
                pickX = e.x / s
                pickY = e.y / s
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
    private fun scaleToView(): Float {
        val sw = if (srcW > 0) srcW else bmp?.width ?: 0
        val w = width
        return if (w > 0 && sw > 0) w.toFloat() / sw else 1f
    }

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
        val h = if (srcW > 0 && srcH > 0 && w > 0) {
            (w * srcH.toFloat() / srcW).toInt()
        } else {
            (200 * resources.displayMetrics.density).toInt()
        }
        setMeasuredDimension(w, h.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bmp ?: return
        if (width <= 0) return
        val s = scaleToView()
        canvas.drawBitmap(b, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), viewPaint)

        for (n in nodes) {
            rf.set(n.l * s, n.t * s, n.r * s, n.b * s)
            if (n.clickable) canvas.drawRect(rf, clickStroke) else canvas.drawRect(rf, strokePaint)
        }
        selected?.let {
            rf.set(it.l * s, it.t * s, it.r * s, it.b * s)
            canvas.drawRect(rf, selFill)
            canvas.drawRect(rf, selStroke)
        }
        // 长按取点标记：十字星，让用户确认落点
        if (pickX >= 0f) {
            val px = pickX * s
            val py = pickY * s
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
        val s = scaleToView()
        val bx = e.x / s
        val by = e.y / s
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

    var onNodeTapped: ((SnapshotStore.Node?) -> Unit)? = null

    init {
        setOnClickListener { }
    }
}
