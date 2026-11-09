package com.noteatoranges.skipad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右上角启发式的几何判据与打分。
 *
 * 这是全项目唯一一条「靠位置猜」的规则，误点的代价是点掉别人的功能按钮
 * （0.17.60 在高德地图上点过「图层入口」）。阈值都是量出来的边界，
 * 所以每条都在边界上正反各测一次。
 */
class CornerGatesTest {

    private val w = 1220
    private val h = 2712

    // —— 面积 ——

    @Test
    fun `面积下限附近`() {
        // 1220×2712 的屏，0.15% ≈ 4963 平方像素
        assertFalse(CornerGates.areaAllowed(70, 70, w, h))   // 4900，略小
        assertTrue(CornerGates.areaAllowed(71, 70, w, h))    // 4970，刚够
    }

    @Test
    fun `面积上限附近`() {
        // 3.5% ≈ 115802
        assertTrue(CornerGates.areaAllowed(340, 340, w, h))  // 115600
        assertFalse(CornerGates.areaAllowed(341, 341, w, h)) // 116281
    }

    @Test
    fun `零尺寸与非法屏幕一律拒绝`() {
        assertFalse(CornerGates.areaAllowed(0, 100, w, h))
        assertFalse(CornerGates.areaAllowed(100, 0, w, h))
        assertFalse(CornerGates.areaAllowed(100, 100, 0, h))
    }

    // —— 宽高比 ——

    @Test
    fun `宽高比两端都含边界`() {
        assertTrue(CornerGates.aspectAllowed(100, 200))  // 0.5
        assertFalse(CornerGates.aspectAllowed(99, 200))  // 0.495
        assertTrue(CornerGates.aspectAllowed(450, 100))  // 4.5
        assertFalse(CornerGates.aspectAllowed(451, 100)) // 4.51
    }

    @Test
    fun `方按钮与常见跳过钮都过`() {
        assertTrue(CornerGates.aspectAllowed(120, 120))
        assertTrue(CornerGates.aspectAllowed(180, 84))   // 360 清理实测尺寸
    }

    // —— 位置 ——

    @Test
    fun `中心点必须越过右侧线`() {
        // 0.72 × 1220 = 878.4
        assertFalse(CornerGates.inCorner(870f, 300f, 60, w, h))
        assertTrue(CornerGates.inCorner(879f, 300f, 60, w, h))
    }

    @Test
    fun `中心点必须在屏幕上方`() {
        // 0.22 × 2712 ≈ 596.6
        assertTrue(CornerGates.inCorner(1000f, 596f, 60, w, h))
        assertFalse(CornerGates.inCorner(1000f, 600f, 60, w, h))
    }

    @Test
    fun `压到状态栏的不算`() {
        // 0.02 × 2712 ≈ 54.2
        assertFalse(CornerGates.inCorner(1000f, 40f, 50, w, h))
        assertTrue(CornerGates.inCorner(1000f, 60f, 60, w, h))
    }

    @Test
    fun `左下角的按钮不算右上角`() {
        assertFalse(CornerGates.inCorner(60f, 2600f, 2600, w, h))
    }

    // —— 文案 ——

    @Test
    fun `八个字放行九个字拒绝`() {
        assertTrue(CornerGates.textAllowed("跳过跳过跳过跳过"))   // 8 个字
        assertFalse(CornerGates.textAllowed("跳过跳过跳过跳过跳")) // 9 个字
    }

    @Test
    fun `空文案合法`() {
        // 很多跳过钮就是一张图，没有文字
        assertTrue(CornerGates.textAllowed(""))
        assertTrue(CornerGates.textAllowed("   "))
    }

    @Test
    fun `非跳过语义一律排除`() {
        assertFalse(CornerGates.textAllowed("更多"))
        assertFalse(CornerGates.textAllowed("消息"))
        assertFalse(CornerGates.textAllowed("Menu"))
        assertFalse(CornerGates.textAllowed("search"))
    }

    @Test
    fun `跳过类文案放行`() {
        assertTrue(CornerGates.textAllowed("跳过"))
        assertTrue(CornerGates.textAllowed("Skip"))
        assertTrue(CornerGates.textAllowed("3"))
    }

    // —— 打分 ——

    @Test
    fun `什么都没有是零分`() {
        assertEquals(0, CornerGates.score("", "", 50_000f, 100_000f))
    }

    @Test
    fun `viewId 带 skip 加四分`() {
        assertEquals(4, CornerGates.score("com.x:id/skip_btn", "", 50_000f, 100_000f))
    }

    @Test
    fun `close 加两分且可与文案叠义`() {
        assertEquals(4, CornerGates.score("com.x:id/close", "跳过", 50_000f, 100_000f))
    }

    @Test
    fun `面积明显偏小再加一分`() {
        // 阈值是「小于上限的 30%」。不用正好 30000 做边界：0.3f 在 Float 上是
        // 0.30000001，100000×0.3f 得到 30000.001，拿 30000 测会误判成「偏小」。
        assertEquals(1, CornerGates.score("", "", 29_999f, 100_000f))
        assertEquals(0, CornerGates.score("", "", 30_001f, 100_000f))
    }

    @Test
    fun `四项全中的最高分`() {
        assertEquals(9, CornerGates.score("com.x:id/skip_close", "跳过", 1000f, 100_000f))
    }
}
