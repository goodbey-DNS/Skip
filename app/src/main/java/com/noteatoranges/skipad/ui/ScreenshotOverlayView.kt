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
    private var scale = 1f

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

    fun setContent(bitmap: Bitmap?, nodes: List<SnapshotStore.Node>) {
        this.bmp = bitmap
        this.nodes = nodes
        selected = null
        requestLayout()
        invalidate()
    }

    fun select(node: SnapshotStore.Node?) {
        selected = node
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val b = bmp
        val h = if (b != null && w > 0) (w * b.height.toFloat() / b.width).toInt() else (200 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(w, h.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bmp ?: return
        if (width <= 0) return
        scale = width.toFloat() / b.width
        canvas.drawBitmap(b, null, RectF(0f, 0f, width.toFloat(), b.height * scale), viewPaint)

        val s = scale
        for (n in nodes) {
            rf.set(n.l * s, n.t * s, n.r * s, n.b * s)
            if (n.clickable) canvas.drawRect(rf, clickStroke) else canvas.drawRect(rf, strokePaint)
        }
        selected?.let {
            rf.set(it.l * s, it.t * s, it.r * s, it.b * s)
            canvas.drawRect(rf, selFill)
            canvas.drawRect(rf, selStroke)
        }
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val s = scale
        val bx = e.x / s
        val by = e.y / s
        if (e.action == android.view.MotionEvent.ACTION_UP && bx >= 0 && by >= 0) {
            val cands = nodes.filter { bx >= it.l && bx <= it.r && by >= it.t && by <= it.b }
            val pick = cands.minByOrNull { it.w * it.h } ?: cands.firstOrNull()
            selected = pick
            invalidate()
            onNodeTapped?.invoke(pick)
            performClick()
        }
        return true
    }

    var onNodeTapped: ((SnapshotStore.Node?) -> Unit)? = null

    init {
        setOnClickListener { }
    }
}
