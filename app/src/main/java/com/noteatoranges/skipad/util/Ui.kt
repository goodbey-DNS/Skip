package com.noteatoranges.skipad.util

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 沉浸式：状态栏/导航栏透明（在主题里设），系统栏+挖孔内边距落到两个专用垫片 View 上。
 * 垫片高度即 inset 高度，避免 padding 叠加问题；[onBottomInset] 供"内容延伸到手势条底下"的页面自行布局。
 */
object Ui {

    fun edgeToEdge(
        activity: Activity,
        topSpacer: View,
        bottomSpacer: View? = null,
        onBottomInset: ((Int) -> Unit)? = null,
    ) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        ViewCompat.setOnApplyWindowInsetsListener(topSpacer) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topSpacer.layoutParams.height = bars.top
            topSpacer.requestLayout()
            bottomSpacer?.let {
                it.layoutParams.height = bars.bottom
                it.requestLayout()
            }
            onBottomInset?.invoke(bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
