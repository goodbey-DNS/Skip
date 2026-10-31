package com.noteatoranges.skipad.util

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.noteatoranges.skipad.R

/**
 * 真·玻璃悬浮 Tab 栏（酷安同款）：依附子窗口 + FLAG_BLUR_BEHIND + blurBehindRadius，
 * 合成器直接模糊其后方的滚动内容（系统对话框同一条模糊管线，圆角生效）。
 * 三个 Tab 文字放在玻璃窗口内（玻璃必然盖住底下视图，文字必须在玻璃上面），
 * 窗口 FLAG_NOT_FOCUSABLE＝可点不可焦点；回调把点击交还宿主切页。
 * API < 31 或系统关闭跨窗口模糊 → available=false，宿主退回自绘底色的普通 Tab 栏。
 */
class GlassTabBar(
    private val activity: Activity,
    private val labels: List<String>,
    private val onTabClick: (Int) -> Unit,
) {

    private val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = activity.resources.displayMetrics.density

    private var root: LinearLayout? = null
    private val tabViews = mutableListOf<TextView>()
    private var lp: WindowManager.LayoutParams? = null
    private var selected = 0

    var available = false
        private set

    /** addView 真实失败（区别于"窗口未 attach 暂缓创建"） */
    var failed = false
        private set

    /** 玻璃窗口真正挂上后回调（创建可能推迟到首帧之后） */
    var onAvailable: (() -> Unit)? = null

    fun create() {
        if (Build.VERSION.SDK_INT < 31) return
        if (activity.window.decorView.windowToken == null) {
            // 窗口还没 attach（onCreate 阶段调用）：等首帧布局完再试
            activity.window.decorView.post { create() }
            return
        }
        val blurOn = wm.isCrossWindowBlurEnabled

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(CORNER_DP).toFloat()
                setColor(if (blurOn) TINT_GLASS else TINT_FALLBACK)
                setStroke(dp(1), 0x2EFFFFFF)
            }
        }
        labels.forEachIndexed { i, label ->
            val tv = TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                setPadding(dp(20), 0, dp(20), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) leftMargin = dp(4)
                }
                setOnClickListener { onTabClick(i) }
            }
            tabViews.add(tv)
            bar.addView(tv)
        }
        styleSelection()

        val params = WindowManager.LayoutParams(
            dp(BAR_WIDTH_DP), dp(BAR_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(14)
            if (blurOn) blurBehindRadius = dp(CORNER_DP)
            token = activity.window.decorView.windowToken
        }

        try {
            wm.addView(bar, params)
            root = bar
            lp = params
            available = true
            onAvailable?.invoke()
        } catch (e: Throwable) {
            android.util.Log.e("GlassTabBar", "glass window addView failed", e)
            failed = true
        }
    }

    /** 底部安全区变化时调用：离手势条留 14dp */
    fun setBottomInset(px: Int) {
        lp?.let {
            it.y = px + dp(14)
            if (available) try {
                wm.updateViewLayout(root, it)
            } catch (e: Exception) {
            }
        }
    }

    fun setSelected(index: Int) {
        selected = index
        styleSelection()
    }

    fun dismiss() {
        root?.let { try {
            wm.removeViewImmediate(it)
        } catch (e: Throwable) {
        } }
        root = null
        available = false
    }

    private fun styleSelection() {
        tabViews.forEachIndexed { i, tv ->
            tv.setBackgroundResource(if (i == selected) R.drawable.bg_tab_on else android.R.color.transparent)
            tv.setTextColor(
                activity.getColor(if (i == selected) R.color.primary else R.color.text_secondary)
            )
            tv.paint.isFakeBoldText = i == selected
        }
    }

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    private companion object {
        const val CORNER_DP = 28
        const val BAR_WIDTH_DP = 300
        const val BAR_HEIGHT_DP = 52
        const val TINT_GLASS = 0xA6FFFFFF.toInt()    // 玻璃态 65% 白
        const val TINT_FALLBACK = 0xF2FFFFFF.toInt() // 无模糊时更实
    }
}
