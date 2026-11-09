package com.noteatoranges.skipad.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NodeScanner` 的三道负向闸（中央区域 / 面积 / 文本长度）。
 *
 * 边界值都是「差 1 像素」级别：这些闸决定正文里的「跳过」会不会被点，
 * 在真机上构造一个「中心点恰好压在 70% 上」的页面做不到，只能在这里钉。
 */
class NodeGatesTest {

    private val w = 1220
    private val h = 2712

    // —— 中央主体区 ——

    @Test
    fun `屏幕正中央算中央区`() {
        assertTrue(NodeGates.inCenterRegion(w * 0.5f, h * 0.5f, w, h))
    }

    @Test
    fun `横向边界是开区间`() {
        assertFalse("正好压 30% 算外面", NodeGates.inCenterRegion(w * 0.3f, h * 0.5f, w, h))
        assertTrue(NodeGates.inCenterRegion(w * 0.3f + 1f, h * 0.5f, w, h))
        assertTrue(NodeGates.inCenterRegion(w * 0.7f - 1f, h * 0.5f, w, h))
        assertFalse("正好压 70% 算外面", NodeGates.inCenterRegion(w * 0.7f, h * 0.5f, w, h))
    }

    @Test
    fun `纵向边界是开区间`() {
        assertFalse(NodeGates.inCenterRegion(w * 0.5f, h * 0.2f, w, h))
        assertTrue(NodeGates.inCenterRegion(w * 0.5f, h * 0.2f + 1f, w, h))
        assertTrue(NodeGates.inCenterRegion(w * 0.5f, h * 0.8f - 1f, w, h))
        assertFalse(NodeGates.inCenterRegion(w * 0.5f, h * 0.8f, w, h))
    }

    @Test
    fun `右上角的按钮不算中央区`() {
        // 开屏跳过按钮的典型位置：右上角，两个轴都在区外
        assertFalse(NodeGates.inCenterRegion(w * 0.9f, h * 0.08f, w, h))
    }

    @Test
    fun `左上角的按钮不算中央区`() {
        // 左下/右下同理：只要有一个轴在外面就不是中央
        assertFalse(NodeGates.inCenterRegion(w * 0.05f, h * 0.95f, w, h))
    }

    // —— 面积闸 ——

    @Test
    fun `一平方像素是下限`() {
        // 历史遗留的极松下限（design.md 第 17 条），这里只是钉住现状，不代表它是好值
        assertTrue(NodeGates.areaAllowed(1, 1, 100, 100, 0.01f))
        assertFalse(NodeGates.areaAllowed(0, 1, 100, 100, 0.01f))
        assertFalse(NodeGates.areaAllowed(1, 0, 100, 100, 0.01f))
    }

    @Test
    fun `面积上限是含等号的`() {
        // 100×100 的屏、上限 1% = 100 平方像素
        assertTrue(NodeGates.areaAllowed(10, 10, 100, 100, 0.01f))
        assertFalse(NodeGates.areaAllowed(11, 10, 100, 100, 0.01f))
    }

    @Test
    fun `屏幕尺寸非法时一律拒绝`() {
        assertFalse(NodeGates.areaAllowed(10, 10, 0, 100, 0.5f))
        assertFalse(NodeGates.areaAllowed(10, 10, 100, -1, 0.5f))
    }

    @Test
    fun `真实屏幕上的跳过按钮能过闸`() {
        // 360 清理的「跳过」实测 180×84，上限比例按规则默认 0.02
        assertTrue(NodeGates.areaAllowed(180, 84, w, h, 0.02f))
    }

    @Test
    fun `整屏容器过不了闸`() {
        assertFalse(NodeGates.areaAllowed(w, h, w, h, 0.02f))
    }

    // —— 文本长度闸 ——

    @Test
    fun `长度正好等于上限放行`() {
        assertTrue(NodeGates.textLengthAllowed(6, 6))
        assertFalse(NodeGates.textLengthAllowed(7, 6))
    }

    @Test
    fun `空文本不算超长`() {
        assertTrue(NodeGates.textLengthAllowed(0, 0))
    }
}
