package com.noteatoranges.skipad.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 抓取悬浮窗（GKD 同款交互）：屏幕上常驻一个小圆钮，点一下 = 抓快照。
 * 点击后自动隐藏→回调（宿主广播抓取）→再弹回；支持拖动换位。
 * 需要 SYSTEM_ALERT_WINDOW 悬浮窗权限（设置页开关引导授权）。
 */
object CaptureBubble {

    private const val PREF = "capture_bubble"
    private const val SIZE_DP = 48

    /** 按住超过这个时长算长按：执行坐标规则，而不是抓快照 */
    private const val LONG_PRESS_MS = 600L

    @Volatile
    private var view: View? = null

    private var wm: WindowManager? = null

    /** 点击回调（宿主设置；通常 = 隐藏→广播抓取→延时回弹） */
    var onClick: (() -> Unit)? = null

    /** 长按回调：立即执行前台 App 的坐标规则（识别不到的广告手动点一下） */
    var onLongClick: (() -> Unit)? = null

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("on", false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("on", on).apply()
    }

    /** 权限已授且开关开 → 显示；否则移除 */
    fun apply(context: Context) {
        if (isEnabled(context) && Settings.canDrawOverlays(context)) {
            show(context)
        } else {
            hide(context)
        }
    }

    fun show(context: Context) {
        if (view != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.wm = wm

        val density = appContext.resources.displayMetrics.density
        val size = (SIZE_DP * density + 0.5f).toInt()
        val pad = (6 * density + 0.5f).toInt()

        val label = TextView(appContext).apply {
            text = "抓"
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            paint.isFakeBoldText = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC16A34A.toInt())
                setStroke((1.5f * density).toInt(), 0xFFFFFFFF.toInt())
            }
        }
        val container = FrameLayout(appContext).apply {
            addView(label, FrameLayout.LayoutParams(size, size))
        }

        val lp = WindowManager.LayoutParams(
            size + pad * 2, size + pad * 2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = appContext.resources.displayMetrics.widthPixels - size - pad * 2
            y = (300 * density).toInt()
        }

        var downX = 0f
        var downY = 0f
        var moved = false
        var downAt = 0L
        container.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; moved = false
                    downAt = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || dx * dx + dy * dy > 100) {
                        moved = true
                        // 上下左右都夹住：原来只有 coerceAtLeast(0)，往右/下拖出屏幕就再也找不回来
                        val maxX = (appContext.resources.displayMetrics.widthPixels - lp.width).coerceAtLeast(0)
                        val maxY = (appContext.resources.displayMetrics.heightPixels - lp.height).coerceAtLeast(0)
                        lp.x = (lp.x + dx).toInt().coerceIn(0, maxX)
                        lp.y = (lp.y + dy).toInt().coerceIn(0, maxY)
                        downX = e.rawX; downY = e.rawY
                        wm.updateViewLayout(container, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val held = System.currentTimeMillis() - downAt
                        if (held >= LONG_PRESS_MS && onLongClick != null) {
                            onLongClick?.invoke()
                        } else {
                            onClick?.invoke()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(container, lp)
            view = container
        } catch (e: Exception) {
            view = null
        }
    }

    fun hide(context: Context) {
        val v = view ?: return
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .removeViewImmediate(v)
        } catch (e: Exception) {
        }
        view = null
    }
}
