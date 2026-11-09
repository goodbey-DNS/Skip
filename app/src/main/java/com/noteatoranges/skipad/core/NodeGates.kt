package com.noteatoranges.skipad.core

/**
 * `NodeScanner` 里三道「负向闸」的纯函数版本。
 *
 * 抽出来的原因：这三道闸决定「命中的这个东西到底能不能点」，判错方向不对称——
 * 面积/位置闸太松，正文里出现「跳过」两个字就会被点；太紧，真按钮又跳不过。
 * 它们原先写成 `findSkipTarget()` 内联表达式，只能靠真机页面才能覆盖，
 * 而构造一个「中心区域恰好 30.1% / 面积恰好等于上限」的页面在设备上做不到。
 *
 * 参数里不出现 `Rect` / `AccessibilityNodeInfo`：单元测试跑在 JVM 上，
 * android.graphics 的桩类一碰就抛。
 */
object NodeGates {

    /** 中央主体区的横向边界（比例，左闭右开不重要，判断本身用严格不等号） */
    const val CENTER_LEFT = 0.3f
    const val CENTER_RIGHT = 0.7f

    /** 中央主体区的纵向边界 */
    const val CENTER_TOP = 0.2f
    const val CENTER_BOTTOM = 0.8f

    /**
     * 命中点是否落在屏幕中央主体区。
     *
     * 那里的「跳过」基本是正文里提到的词，不是按钮。边界用严格不等号：
     * 正好压在 30% / 70% 上算外面——宁可放过一条正文（漏跳），
     * 也不要把紧贴边界的真按钮判成正文（多点一下是不可逆的）。
     */
    fun inCenterRegion(cx: Float, cy: Float, screenW: Int, screenH: Int): Boolean =
        cx > screenW * CENTER_LEFT && cx < screenW * CENTER_RIGHT &&
            cy > screenH * CENTER_TOP && cy < screenH * CENTER_BOTTOM

    /**
     * 面积闸：至少 1 平方像素，且不超过「屏幕面积 × maxAreaRatio」。
     *
     * 下限 1 像素是历史遗留的极松值（见 design.md 第 17 条），保留不动——
     * 收紧需要真实误点的尺寸证据，不能凭一行可疑日志就改。
     */
    fun areaAllowed(
        w: Int,
        h: Int,
        screenW: Int,
        screenH: Int,
        maxAreaRatio: Float,
    ): Boolean {
        if (w <= 0 || h <= 0 || screenW <= 0 || screenH <= 0) return false
        val area = w.toFloat() * h
        val screenArea = screenW.toFloat() * screenH
        return area >= 1f && area <= screenArea * maxAreaRatio
    }

    /**
     * 文本长度闸：命中词越短允许的额外字数越少，超了就当成正文。
     *
     * 相等放行：`limit` 是「最多允许多少字」，不是「必须少于」。
     */
    fun textLengthAllowed(length: Int, limit: Int): Boolean = length <= limit
}
