package com.noteatoranges.skipad.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面匹配的纯逻辑测试。
 *
 * 这行的后果比看起来重：`nodeRulesAsScannerRules` 先用它筛规则，
 * 一旦判错，用户从快照里点选的规则会在引擎里「静默不存在」——
 * 界面上规则还在、开关也是开的，就是从来不生效。没有任何日志会提示这件事。
 *
 * 不依赖 Context、不依赖节点树，所以直接在 JVM 上跑。
 */
class RuleStoreMatchTest {

    @Test
    fun `规则没写页面就对该应用全部页面生效`() {
        assertTrue(RuleStore.activityMatches(null, "com.x.SplashActivity"))
        assertTrue(RuleStore.activityMatches("", "com.x.MainActivity"))
        // 连当前页面为空也算生效：部分 ROM 的窗口事件拿不到类名，
        // 这时如果判 false，用户规则会整片失效
        assertTrue(RuleStore.activityMatches(null, null))
        assertTrue(RuleStore.activityMatches("  ", null))
    }

    @Test
    fun `规则写了页面 但当前页面未知时不能生效`() {
        // 与上一条相反：规则明确绑定页面，就没有理由在不知道页面时动手
        assertFalse(RuleStore.activityMatches("SplashActivity", null))
        assertFalse(RuleStore.activityMatches("SplashActivity", ""))
    }

    @Test
    fun `完整类名精确相等`() {
        assertTrue(
            RuleStore.activityMatches(
                "com.sina.weibo.SplashActivity",
                "com.sina.weibo.SplashActivity",
            )
        )
    }

    @Test
    fun `简写页面名按后缀匹配`() {
        // 快照里存的是完整类名，手输规则时通常只写后两段
        assertTrue(RuleStore.activityMatches("SplashActivity", "com.sina.weibo.SplashActivity"))
        assertTrue(RuleStore.activityMatches(".SplashActivity", "com.sina.weibo.SplashActivity"))
        assertTrue(RuleStore.activityMatches("weibo.SplashActivity", "com.sina.weibo.SplashActivity"))
    }

    @Test
    fun `不同页面不匹配`() {
        assertFalse(RuleStore.activityMatches("SplashActivity", "com.sina.weibo.MainActivity"))
        assertFalse(
            RuleStore.activityMatches("com.sina.weibo.MainActivity", "com.sina.weibo.SplashActivity")
        )
    }

    @Test
    fun `后缀匹配是宽松的 记录在案`() {
        // 这是实现现状，不是期望：endsWith 不带分隔符约束，
        // 所以写 MainActivity 也会命中 SomeMainActivity。
        // 规则实际由快照生成为完整类名，日常碰不到；这里把它钉住，
        // 将来若收紧成「.分隔」，这条测试会提醒有人误改了行为。
        assertTrue(RuleStore.activityMatches("MainActivity", "com.x.SomeMainActivity"))
    }
}
