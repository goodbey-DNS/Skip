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

    /** 按住超过这个时长算长按：弹出「抓快照 / 点坐标」两项菜单 */
    private const val LONG_PRESS_MS = 600L

    /** 菜单宽度与每行高度（dp） */
    private const val PANEL_W_DP = 96
    private const val ROW_H_DP = 40

    /** 菜单自动收起时长：选就选，不选别一直挡着 */
    private const val PANEL_TIMEOUT_MS = 5000L

    @Volatile
    private var view: View? = null

    private var wm: WindowManager? = null

    /** 点击回调（宿主设置；通常 = 隐藏→广播抓取→延时回弹） */
    var onClick: (() -> Unit)? = null

    /** 长按回调：立即执行前台 App 的坐标规则（识别不到的广告手动点一下） */
    var onLongClick: (() -> Unit)? = null

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
        android.util.Log.i("DaxiaojieEngine", "bubble-show enter exists=${view != null} canDraw=${Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)}")
        if (view != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.wm = wm

        val dm = appContext.resources.displayMetrics
        val density = dm.density
        val size = (SIZE_DP * density + 0.5f).toInt()
        val pad = (6 * density + 0.5f).toInt()
        val panelW = (PANEL_W_DP * density + 0.5f).toInt()
        val rowH = (ROW_H_DP * density + 0.5f).toInt()
        val rowGap = (6 * density).toInt()

        fun roundBg(fill: Int): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(fill)
        }

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

        fun menuRow(text: String): TextView = TextView(appContext).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            background = roundBg(0xE616A34A.toInt())
        }

        val rowShot = menuRow("抓快照")
        val rowPoint = menuRow("点坐标")
        val panel = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(rowShot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH))
            addView(
                rowPoint,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH).apply {
                    topMargin = rowGap
                },
            )
        }
        // 没接长按回调时（理论上不会）不摆出「点坐标」，免得点了没反应
        if (onLongClick == null) rowPoint.visibility = View.GONE

        val container = FrameLayout(appContext).apply {
            addView(label, FrameLayout.LayoutParams(size, size))
            addView(panel, FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        val baseW = size + pad * 2
        val baseH = size + pad * 2
        val openH = rowH * 2 + rowGap

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

        var panelOpen = false
        val collapse = Runnable {
            if (panelOpen) {
                panelOpen = false
                panel.visibility = View.GONE
                label.visibility = View.VISIBLE
                lp.width = baseW
                lp.height = baseH
                clamp()
                runCatching { wm.updateViewLayout(container, lp) }
            }
        }

        fun closePanel() {
            container.removeCallbacks(collapse)
            collapse.run()
        }

        fun openPanel() {
            if (panelOpen) return
            panelOpen = true
            label.visibility = View.GONE
            panel.visibility = View.VISIBLE
            lp.width = panelW
            lp.height = openH
            clamp()
            runCatching { wm.updateViewLayout(container, lp) }
            container.removeCallbacks(collapse)
            container.postDelayed(collapse, PANEL_TIMEOUT_MS)
        }

        rowShot.setOnClickListener {
            closePanel()
            onClick?.invoke()
        }
        rowPoint.setOnClickListener {
            closePanel()
            onLongClick?.invoke()
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
                    // 面板展开时不跟着拖，否则手指一滑就把两个选项带跑
                    if (!panelOpen) {
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
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 长按不再直接执行坐标点击：先弹两项菜单，选错也还有救。
                    // 以前慢一点的点按就会在别人界面上按坐标点一下，不可撤销
                    if (!panelOpen && !moved) {
                        val held = System.currentTimeMillis() - downAt
                        if (held >= LONG_PRESS_MS) openPanel() else onClick?.invoke()
                    } else if (moved) {
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
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .removeViewImmediate(v)
            android.util.Log.i("DaxiaojieEngine", "bubble-hide ok")
        } catch (e: Exception) {
            android.util.Log.i("DaxiaojieEngine", "bubble-hide fail ${e.javaClass.simpleName}")
        }
        view = null
    }
}
