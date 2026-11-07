package com.noteatoranges.skipad.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 真·磨砂玻璃 Tab 栏（快照自模糊，酷安观感）：
 * 每帧把 [setup] 指定的内容层缩微截屏（1/8）→ CPU 盒式模糊×3 → 放大垫在文字底下，
 * 叠 70% 白 + 描边 + 圆角裁剪。不依赖 ROM 的跨窗口模糊开关，任何机器效果一致。
 * 原理同 BlurView 库：挂在内容层 OnPreDraw 上跟随滚动逐帧重绘；
 * target 必须是本 View 的兄弟容器（不含自己），否则自递归。
 * 继承 LinearLayout：三个 Tab 文字用 weight 平分，onDraw 先画玻璃再画文字。
 */
class GlassBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private var target: View? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        postInvalidateOnAnimation()
        true
    }

    private val cornerPx = dp(28).toFloat()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint().apply { color = 0xB3FFFFFF.toInt() } // 70% 白
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
        color = 0x30FFFFFF
    }
    private val clipPath = Path()
    private val rect = Rect()

    /** onDraw 每帧都要用：提成字段，避免每次分配（Lint DrawAllocation） */
    private val barLoc = IntArray(2)
    private val rootLoc = IntArray(2)
    private val frameRect = RectF()

    /** 模糊用的缩微位图缓存：每帧重建会持续制造 GC 压力，这里按尺寸复用 */
    private var snap: Bitmap? = null
    private var lastSnapAt = 0L

    /** 指定要模糊的内容层（本 View 的兄弟节点） */
    fun setup(contentRoot: View) {
        target = contentRoot
        contentRoot.viewTreeObserver.addOnPreDrawListener(preDraw)
        setWillNotDraw(false)
    }

    override fun onDetachedFromWindow() {
        target?.viewTreeObserver?.removeOnPreDrawListener(preDraw)
        snap?.recycle()
        snap = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val t = target
        val w = width
        val h = height
        if (t == null || w <= 0 || h <= 0) {
            // 无内容层可模糊：纯白底兜底，文字保可读
            canvas.drawColor(Color.WHITE)
            super.onDraw(canvas)
            return
        }

        getLocationInWindow(barLoc)
        t.getLocationInWindow(rootLoc)
        val dx = (barLoc[0] - rootLoc[0]).toFloat()
        val dy = (barLoc[1] - rootLoc[1]).toFloat()

        // 1/8 缩微截屏 + 复用位图 + 60ms 节流：原实现每帧 createBitmap、
        // 重绘整个内容层再做三轮模糊，滚动时 CPU 白烧
        val ss = DOWNSCALE
        val bw = maxOf(1, (w / ss).toInt())
        val bh = maxOf(1, (h / ss).toInt())
        val cache: Bitmap = snap?.takeIf { it.width == bw && it.height == bh } ?: run {
            snap?.recycle()
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                snap = it
                lastSnapAt = 0L
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastSnapAt >= MIN_SNAP_INTERVAL_MS) {
            lastSnapAt = now
            val c = Canvas(cache)
            c.drawColor(0, PorterDuff.Mode.CLEAR)
            c.scale(1 / ss, 1 / ss)
            c.translate(-dx, -dy)
            t.draw(c)
            boxBlur(cache, BLUR_RADIUS_PX, 3)
        }

        // 圆角裁剪内：模糊快照 + 白 tint + 描边；再画文字子View
        clipPath.reset()
        frameRect.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.addRoundRect(frameRect, cornerPx, cornerPx, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        rect.set(0, 0, w, h)
        canvas.drawBitmap(cache, null, rect, bitmapPaint)
        canvas.drawPaint(tintPaint)
        canvas.restore()
        canvas.drawPath(clipPath, borderPaint)

        super.onDraw(canvas)
    }

    /** 盒式模糊（水平+垂直为一轮，跑 3 轮近似高斯）；尺寸只有百余像素，代价可忽略 */
    private fun boxBlur(bmp: Bitmap, radius: Int, rounds: Int) {
        val w = bmp.width
        val h = bmp.height
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)
        val out = IntArray(pix.size)
        repeat(rounds) {
            boxPass(pix, out, w, h, radius, horizontal = true)
            boxPass(pix, out, w, h, radius, horizontal = false)
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h)
    }

    private fun boxPass(pix: IntArray, out: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        for (line in 0 until lines) {
            for (i in 0 until len) {
                var a = 0L
                var rr = 0L
                var gg = 0L
                var bb = 0L
                var n = 0
                for (k in -r..r) {
                    val idx = (i + k).coerceIn(0, len - 1)
                    val p = if (horizontal) pix[line * w + idx] else pix[idx * w + line]
                    a += (p ushr 24) and 0xFF
                    rr += (p shr 16) and 0xFF
                    gg += (p shr 8) and 0xFF
                    bb += p and 0xFF
                    n++
                }
                val v = ((a / n).toInt() shl 24) or ((rr / n).toInt() shl 16) or
                    ((gg / n).toInt() shl 8) or (bb / n).toInt()
                if (horizontal) out[line * w + i] = v else out[i * w + line] = v
            }
        }
        System.arraycopy(out, 0, pix, 0, pix.size)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val DOWNSCALE = 8f
        const val BLUR_RADIUS_PX = 6
        const val MIN_SNAP_INTERVAL_MS = 60L
    }
}
