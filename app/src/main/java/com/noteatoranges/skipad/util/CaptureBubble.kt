package com.noteatoranges.skipad.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 抓取悬浮窗（GKD 同款交互）：屏幕上常驻一个小圆钮，点一下 = 抓快照。
 * 点击后自动隐藏→回调（宿主广播抓取）→再弹回；支持拖动换位。
 * 需要 SYSTEM_ALERT_WINDOW 悬浮窗权限（设置页开关引导授权）。
 */
object CaptureBubble {

    private const val PREF = "capture_bubble"
    private const val SIZE_DP = 48

    /** 拖动后的位置（布局坐标，相对屏幕左上） */
    private const val KEY_X = "pos_x"
    private const val KEY_Y = "pos_y"

    @Volatile
    private var view: View? = null

    private var wm: WindowManager? = null

    /** 点击回调（宿主设置；通常 = 隐藏→广播抓取→延时回弹）。长按也走这里 */
    var onClick: (() -> Unit)? = null

    fun isEnabled(context: Context): Boolean =
        // 默认关闭：圆钮常驻屏幕会挡内容，由用户在「跳过规则与快照」里自己打开
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
        android.util.Log.i("DaxiaojieEngine", "bubble-show enter exists=${view != null} canDraw=${Settings.canDrawOverlays(context)}")
        if (view != null) return
        // minSdk 26：Settings.canDrawOverlays 从 API 23 起就有，版本判断是死代码
        if (!Settings.canDrawOverlays(context)) return
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.wm = wm

        val dm = appContext.resources.displayMetrics
        val density = dm.density
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

        val baseW = size + pad * 2
        val baseH = size + pad * 2

        val lp = WindowManager.LayoutParams(
            baseW, baseH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 拖动后位置要能活过服务重连：否则用户每次重启都得重新摆圆钮
            val sp = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val savedX = sp.getInt(KEY_X, -1)
            val savedY = sp.getInt(KEY_Y, -1)
            x = if (savedX >= 0) savedX else dm.widthPixels - size - pad * 2
            y = if (savedY >= 0) savedY else (300 * density).toInt()
        }

        fun clamp() {
            val maxX = (dm.widthPixels - lp.width).coerceAtLeast(0)
            val maxY = (dm.heightPixels - lp.height.coerceAtLeast(0)).coerceAtLeast(0)
            lp.x = lp.x.coerceIn(0, maxX)
            lp.y = lp.y.coerceIn(0, maxY)
        }
        // 存下来的位置可能来自另一套分辨率/横屏，先夹回屏幕内再用
        clamp()

        var downX = 0f
        var downY = 0f
        var moved = false
        container.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || dx * dx + dy * dy > 100) {
                        moved = true
                        lp.x = (lp.x + dx).toInt()
                        lp.y = (lp.y + dy).toInt()
                        downX = e.rawX; downY = e.rawY
                        clamp()
                        wm.updateViewLayout(container, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 只保留「点一下就抓」。坐标规则已经取消，长按菜单没有存在的意义，
                    // 按住超过 600ms 也按普通点击处理，少一层等待
                    if (!moved) {
                        onClick?.invoke()
                    } else {
                        appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
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
        val v = view ?: run {
            android.util.Log.i("DaxiaojieEngine", "bubble-hide skipped view=null")
            return
        }
        var removed = false
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .removeViewImmediate(v)
            removed = true
            android.util.Log.i("DaxiaojieEngine", "bubble-hide ok")
        } catch (e: Exception) {
            android.util.Log.i("DaxiaojieEngine", "bubble-hide fail ${e.javaClass.simpleName}")
        }
        // 只有真的移除成功才清引用：2026-11-04 实测，移除抛异常时如果照样把 view 置空，
        // 这个窗口就变成既不可见、又没有任何引用能再remove的「幽灵窗口」——之后
        // bubble-hide skipped view=null，开关也关不掉，只能杀进程
        if (removed) view = null
    }
}
