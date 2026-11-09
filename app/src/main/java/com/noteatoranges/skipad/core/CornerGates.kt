package com.noteatoranges.skipad.core

import java.util.Locale

/**
 * `CornerSkipFinder` 的几何判据与打分，纯函数版本。
 *
 * 这是全项目唯一一条「靠位置猜」的规则（右上角、小、可点击），所以每一条阈值
 * 都是「宁可漏，不可错」定下来的。它们原来散在 `find()` 的遍历里，只有真机
 * 页面才能触发；放到这里之后，边界（面积正好等于上限、宽高比正好 4.5、
 * 中心点正好压在 72% 上）可以逐条钉死。
 *
 * 参数只用数值和字符串，不碰 `AccessibilityNodeInfo` / `Rect`。
 */
object CornerGates {

    /** 面积下限 0.15%：小于这个基本是图标碎屑，点不中也别点 */
    const val MIN_AREA_RATIO = 0.0015f

    /** 面积上限 3.5%：超过它更像图片热区或整条横幅，不是跳过按钮 */
    const val MAX_AREA_RATIO = 0.035f

    /** 中心点必须落在屏幕宽度右侧这之后 */
    const val RIGHT_RATIO = 0.72f

    /** 中心点必须落在屏幕高度上方这之前 */
    const val TOP_RATIO = 0.22f

    /** 不许压状态栏 */
    const val STATUS_BAR_RATIO = 0.02f

    /** 宽高比范围：排掉全屏热区（太方/太大）和细长横栏 */
    const val MIN_ASPECT = 0.5f
    const val MAX_ASPECT = 4.5f

    /** 文案超过这么多字的一律不算：跳过按钮不会写一段话 */
    const val MAX_TEXT_LEN = 8

    /** 右上角经常出现的「非跳过」语义，命中即排除 */
    val NEGATIVE = listOf(
        "更多", "菜单", "搜索", "分享", "设置", "个人", "我的", "消息", "通知",
        "客服", "反馈", "帮助", "登录", "注册",
        "more", "menu", "search", "share", "setting", "profile", "account",
        "notification", "help", "feedback", "login", "sign",
    )

    /**
     * 面积是否落在 [MIN_AREA_RATIO, MAX_AREA_RATIO] 的比例区间里。
     *
     * 两端都含：`0.15%` 和 `3.5%` 本身就是从真实按钮量出来的边界值，
     * 排除边界会把正好这个大小的跳过钮漏掉。
     */
    fun areaAllowed(w: Int, h: Int, screenW: Int, screenH: Int): Boolean {
        if (w <= 0 || h <= 0 || screenW <= 0 || screenH <= 0) return false
        val area = w.toFloat() * h
        val screenArea = screenW.toFloat() * screenH
        return area >= screenArea * MIN_AREA_RATIO && area <= screenArea * MAX_AREA_RATIO
    }

    /** 宽高比是否落在 [MIN_ASPECT, MAX_ASPECT] */
    fun aspectAllowed(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        val aspect = w.toFloat() / h
        return aspect >= MIN_ASPECT && aspect <= MAX_ASPECT
    }

    /**
     * 中心点是否在右上角，且不压状态栏。
     *
     * `cx >= 右侧线` 与 `cy <= 上方线` 都是含边界的：这条规则本来就是「宁可漏」，
     * 边界值放行多一次点击机会，但面积和宽高比两道闸还在后面挡着。
     */
    fun inCorner(cx: Float, cy: Float, top: Int, screenW: Int, screenH: Int): Boolean {
        if (screenW <= 0 || screenH <= 0) return false
        if (cx < screenW * RIGHT_RATIO) return false
        if (cy > screenH * TOP_RATIO) return false
        if (top < screenH * STATUS_BAR_RATIO) return false
        return true
    }

    /**
     * 文案是否可接受：不超过 [MAX_TEXT_LEN] 个字，且不含「更多/菜单/搜索」这类语义。
     *
     * 空文案是合法的——很多跳过钮就是一张图，没有文字。
     */
    fun textAllowed(label: String): Boolean {
        val trimmed = label.trim()
        if (trimmed.length > MAX_TEXT_LEN) return false
        if (trimmed.isEmpty()) return true
        val lower = trimmed.lowercase(Locale.ROOT)
        return NEGATIVE.none { lower.contains(it) }
    }

    /**
     * 候选打分，取最高分。
     *
     * 权重是经验值：viewId 带 skip 是最强证据（那本来就是跳过按钮的命名），
     * close 次之；有文案说明是个带字的按钮；面积明显偏小的再加一分，
     * 因为开屏的跳过钮通常比整块热区小得多。
     */
    fun score(vid: String, label: String, area: Float, maxArea: Float): Int {
        val lowerVid = vid.lowercase(Locale.ROOT)
        var s = 0
        if (lowerVid.contains("skip")) s += 4
        if (lowerVid.contains("close")) s += 2
        if (label.isNotEmpty()) s += 2
        if (area < maxArea * 0.3f) s += 1
        return s
    }
}
