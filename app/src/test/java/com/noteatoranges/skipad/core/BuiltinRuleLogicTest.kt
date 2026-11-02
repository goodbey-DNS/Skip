package com.noteatoranges.skipad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置规则与文本长度闸的纯逻辑测试。
 *
 * 只测两样东西：viewId 怎么判命中、文本长度上限怎么算。它们都不依赖 Android 运行时，
 * 所以能在 JVM 上直接跑，不需要 Robolectric，也不需要伪造 AccessibilityNodeInfo。
 *
 * 为什么先测这两个：
 * - viewId 判定是 0.17.33 最大一次改动的核心（SDK 前缀 + 关闭语义），改错就是点击
 *   广告上的「下载」按钮，后果最重；
 * - 文本长度闸决定正文里提到关键字会不会被点，是误点的第二道闸。
 *
 * 其余依赖节点的判定（面积、位置、可见性）不在这一层测——那需要真实节点树，
 * 在没设备的时候伪造出来只会测到假东西。
 */
class BuiltinRuleLogicTest {

    private fun rule(id: String) = BuiltinRules.defaults().first { it.id == id }

    // —— viewId 判定 ——

    @Test
    fun `SDK 前缀必须带关闭语义才算命中`() {
        val r = rule("ad-sdk-viewid")
        // 广告 CTA：含 gdt_ 但不是关闭按钮，必须拒绝
        assertFalse(r.viewIdHit("com.qq.e:id/gdt_btn_download"))
        assertFalse(r.viewIdHit("com.qq.e:id/gdt_btn_install"))
        assertFalse(r.viewIdHit("topon:layout/topon_btn_cta"))
        // 关闭钮：前缀 + close/skip，必须命中
        assertTrue(r.viewIdHit("com.qq.e:id/gdt_btn_close"))
        assertTrue(r.viewIdHit("tt_splash_skip_btn"))
        assertTrue(r.viewIdHit("ksad_skip_view"))
        assertTrue(r.viewIdHit("hiad_splash_close"))
        assertTrue(r.viewIdHit("topon_splash_dismiss"))
    }

    @Test
    fun `穿山甲广告角标不能当成关闭按钮`() {
        val r = rule("ad-sdk-viewid")
        // csj_ad_logo 曾在内置表里，点击是打开落地页，不是关闭
        assertFalse(r.viewIdHit("csj_ad_logo"))
    }

    @Test
    fun `不含任何 SDK 前缀的 id 不走这条规则`() {
        val r = rule("ad-sdk-viewid")
        assertFalse(r.viewIdHit("com.example:id/close_btn"))
        assertFalse(r.viewIdHit("mine_edit_button"))
    }

    @Test
    fun `viewId 判定忽略大小写`() {
        val r = rule("ad-sdk-viewid")
        assertTrue(r.viewIdHit("GDT_BTN_CLOSE"))
        assertFalse(r.viewIdHit("GDT_BTN_DOWNLOAD"))
    }

    @Test
    fun `skip 关键字规则只看有没有 skip`() {
        val r = rule("ad-skip-viewid")
        assertTrue(r.viewIdHit("tt_splash_skip_btn"))
        assertTrue(r.viewIdHit("skip"))
        assertFalse(r.viewIdHit("gdt_btn_download"))
    }

    // —— 文本长度闸 ——

    @Test
    fun `命中两字词只允许再加八个字`() {
        // 「跳过」+8 = 10：正常按钮文案都在这之内
        assertEquals(10, textLenLimit(maxTextLength = 24, byText = true, hitLen = 2, slack = 8))
    }

    @Test
    fun `长短语不超过规则绝对上限`() {
        assertEquals(24, textLenLimit(maxTextLength = 24, byText = true, hitLen = 20, slack = 8))
    }

    @Test
    fun `非文本命中用绝对上限`() {
        // viewId / 正则命中没有确定短语，不该被相对闸误杀
        assertEquals(24, textLenLimit(maxTextLength = 24, byText = false, hitLen = 0, slack = 8))
        assertEquals(24, textLenLimit(maxTextLength = 24, byText = true, hitLen = 0, slack = 8))
    }
}
