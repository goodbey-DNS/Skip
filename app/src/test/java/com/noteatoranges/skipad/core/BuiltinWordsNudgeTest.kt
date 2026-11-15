package com.noteatoranges.skipad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「内置词表长期关闭」提醒的判定（`BuiltinWordsNudge`）。
 *
 * 为什么单独测：这个提示只说一句话，但说错一次就是打扰——用户在规则页主动关掉开关
 * 是很明确的操作，App 不该在他刚关完就把「你是不是搞错了」贴在首页。
 * 四条条件（关着 / 有起点 / 扫过 / 满 24 小时）里任何一条缺失都必须静默，
 * 这类「不该出现却出现」的边界只能靠单测钉死，真机上要复现得等一整天。
 */
class BuiltinWordsNudgeTest {

    private val hour = 3600_000L
    private val day = 24 * hour
    private val now = 1_790_000_000_000L
    private val since = now - 30 * hour

    @Test
    fun `开关开着永远不提示`() {
        assertFalse(BuiltinWordsNudge.shouldWarn(true, since, true, now))
    }

    @Test
    fun `没有起点时间不提示`() {
        assertFalse(BuiltinWordsNudge.shouldWarn(false, 0L, true, now))
        assertFalse(BuiltinWordsNudge.shouldWarn(false, -1L, true, now))
    }

    @Test
    fun `关闭期间没扫过页面不提示`() {
        // 装完就放着不用的用户不该被打扰：开关状态和提醒无关
        assertFalse(BuiltinWordsNudge.shouldWarn(false, since, false, now))
    }

    @Test
    fun `差一分钟满一天也不提示`() {
        assertFalse(BuiltinWordsNudge.shouldWarn(false, now - day + 60_000L, true, now))
    }

    @Test
    fun `正好满一天开始提示`() {
        assertTrue(BuiltinWordsNudge.shouldWarn(false, now - day, true, now))
    }

    @Test
    fun `四条都满足才提示`() {
        assertTrue(BuiltinWordsNudge.shouldWarn(false, since, true, now))
    }

    // —— 文案时长 ——

    @Test
    fun `时长向上取整到小时`() {
        assertEquals(24, BuiltinWordsNudge.offHours(now - 23 * hour - 60_000L, now))
        assertEquals(1, BuiltinWordsNudge.offHours(now - 1L, now))
        assertEquals(0, BuiltinWordsNudge.offHours(0L, now))
    }

    @Test
    fun `未来时间戳不倒挂成负数`() {
        assertEquals(0, BuiltinWordsNudge.offHours(now + hour, now))
    }

    @Test
    fun `不足两天报小时超过报天`() {
        assertEquals("30 小时", BuiltinWordsNudge.offPhrase(since, now))
        assertEquals("3 天", BuiltinWordsNudge.offPhrase(now - 3 * day - 5 * hour, now))
    }
}
