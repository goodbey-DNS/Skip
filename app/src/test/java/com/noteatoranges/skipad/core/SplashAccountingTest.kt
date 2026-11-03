package com.noteatoranges.skipad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.17.60 两块记账逻辑的边界测试。
 *
 * 为什么放在这一层：这两条判据都是「时间序 + 计数」问题，在真机上要靠广告整好
 * 在同一秒内出现两段才能撞到，受控 App 里也受窗口事件时序影响（第二段晚 200ms
 * 结论就变）。放到 JVM 上，边界能一条条钉死，不依赖任何设备状态。
 */
class SplashAccountingTest {

    // —— 过渡页回撤 ——

    @Test
    fun `没有上一条时不撤`() {
        val d = TransitionDemoter(2000L)
        assertEquals(0L, d.demoteTarget("pkg.a", 10_000L))
    }

    @Test
    fun `弱证据且在两秒内才撤`() {
        val d = TransitionDemoter(2000L)
        d.record("pkg.a", id = 7L, at = 1_000L, weak = true)
        assertEquals(7L, d.demoteTarget("pkg.a", 3_000L))   // 正好 2 秒，仍撤
        assertEquals(7L, d.demoteTarget("pkg.a", 1_500L))   // 更近，当然撤
        assertEquals(0L, d.demoteTarget("pkg.a", 3_001L))   // 超窗，不撤
    }

    @Test
    fun `强证据一律不撤`() {
        val d = TransitionDemoter(2000L)
        d.record("pkg.a", id = 7L, at = 1_000L, weak = false)
        assertEquals(0L, d.demoteTarget("pkg.a", 1_200L))
    }

    @Test
    fun `不同包互不影响`() {
        val d = TransitionDemoter(2000L)
        d.record("pkg.a", id = 7L, at = 1_000L, weak = true)
        assertEquals(0L, d.demoteTarget("pkg.b", 1_200L))
        assertEquals(7L, d.demoteTarget("pkg.a", 1_200L))
    }

    @Test
    fun `时钟回拨时不撤`() {
        val d = TransitionDemoter(2000L)
        d.record("pkg.a", id = 7L, at = 5_000L, weak = true)
        assertEquals(0L, d.demoteTarget("pkg.a", 4_000L))
    }

    @Test
    fun `clear 之后不再撤`() {
        val d = TransitionDemoter(2000L)
        d.record("pkg.a", id = 7L, at = 1_000L, weak = true)
        d.clear("pkg.a")
        assertEquals(0L, d.demoteTarget("pkg.a", 1_200L))
    }

    // —— 空窗页学习 ——

    @Test
    fun `类名不可用时不学`() {
        val t = PlainActTracker(threshold = 2)
        // 窗口事件只给控件类名：拿它当键会把整个 App 的页面串成一个
        assertFalse(t.learn("pkg.a", "android.widget.FrameLayout"))
        assertFalse(t.learn("pkg.a", "android.widget.FrameLayout"))
        assertFalse(t.isPlain("pkg.a", "android.widget.FrameLayout"))
        assertEquals(0, t.countOf("pkg.a", "android.widget.FrameLayout"))

        assertFalse(t.learn("pkg.a", ""))
        assertFalse(t.learn("pkg.a", "Main"))
    }

    @Test
    fun `连续两次空窗才学`() {
        val t = PlainActTracker(threshold = 2)
        assertFalse(t.learn("pkg.a", "com.a.MainActivity"))
        assertFalse(t.isPlain("pkg.a", "com.a.MainActivity"))
        assertEquals(1, t.countOf("pkg.a", "com.a.MainActivity"))

        assertTrue(t.learn("pkg.a", "com.a.MainActivity"))   // 第二次，达阈值
        assertTrue(t.isPlain("pkg.a", "com.a.MainActivity"))
        assertEquals(2, t.countOf("pkg.a", "com.a.MainActivity"))
        // 已经是空窗页，再学一次不算「刚达到」
        assertFalse(t.learn("pkg.a", "com.a.MainActivity"))
    }

    @Test
    fun `命中一次就忘记`() {
        val t = PlainActTracker(threshold = 2)
        t.learn("pkg.a", "com.a.MainActivity")
        t.forget("pkg.a", "com.a.MainActivity")
        assertEquals(0, t.countOf("pkg.a", "com.a.MainActivity"))

        // 忘记之后要重新攒两次，不能靠旧账
        assertFalse(t.learn("pkg.a", "com.a.MainActivity"))
        assertFalse(t.isPlain("pkg.a", "com.a.MainActivity"))
    }

    @Test
    fun `学过之后命中也能推翻`() {
        val t = PlainActTracker(threshold = 2)
        t.learn("pkg.a", "com.a.MainActivity")
        t.learn("pkg.a", "com.a.MainActivity")
        assertTrue(t.isPlain("pkg.a", "com.a.MainActivity"))

        t.forget("pkg.a", "com.a.MainActivity")
        assertFalse(t.isPlain("pkg.a", "com.a.MainActivity"))
    }

    @Test
    fun `不同包不同页面互不影响`() {
        val t = PlainActTracker(threshold = 2)
        t.learn("pkg.a", "com.a.MainActivity")
        t.learn("pkg.a", "com.a.MainActivity")
        assertTrue(t.isPlain("pkg.a", "com.a.MainActivity"))
        assertFalse(t.isPlain("pkg.b", "com.a.MainActivity"))
        assertFalse(t.isPlain("pkg.a", "com.a.OtherActivity"))
    }

    // —— 类名判定 ——

    @Test
    fun `Activity 与控件类名的区分`() {
        assertTrue(usableAct("com.noteatoranges.skiptest.MainOnlyActivity"))
        assertTrue(usableAct("com.qihoo.cleandroid_cn.SplashActivity"))
        assertFalse(usableAct("android.widget.FrameLayout"))
        assertFalse(usableAct("java.lang.Object"))
        assertFalse(usableAct(""))
        assertFalse(usableAct("Main"))
    }
}
