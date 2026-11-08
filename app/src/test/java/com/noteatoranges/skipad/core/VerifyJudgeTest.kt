package com.noteatoranges.skipad.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 核验的包名判定（`VerifyJudge`）。
 *
 * 为什么单独测这一块：它决定「点完之后算不算跳过成功」，判错的方向不对称——
 * 把真跳过判成误点，会给这个 App 打十分钟静默（下一次开屏直接不动作）；
 * 把点进广告判成跳过，统计虚高、落地页不进黑名单（0.17.33「我的」页那次）。
 *
 * 这段逻辑原来埋在 `verifySkipOnce()` 里，要覆盖它只能上真机、靠广告整好在
 * 800ms 内换页面。判定本身不碰 `AccessibilityNodeInfo`、不看时间，放 JVM 上
 * 可以把每条边界钉死。
 */
class VerifyJudgeTest {

    private val container = "com.miui.systemAdSolution"
    private val gates = setOf(container)

    // —— classify：同 App ——

    @Test
    fun `前台仍是同一个包就是真跳过`() {
        assertEquals(
            VerifyOutcome.SAME_APP,
            VerifyJudge.classify("com.qihoo.cleandroid_cn", "com.qihoo.cleandroid_cn", gates),
        )
    }

    @Test
    fun `前台读不到按真跳过处理`() {
        // 800ms 核验时广告窗口可能正在销毁，rootInActiveWindow 会短暂返回 null。
        // 这时判成「点进广告」会把真跳过记成误点，还会静默十分钟。
        assertEquals(
            VerifyOutcome.SAME_APP,
            VerifyJudge.classify("com.qihoo.cleandroid_cn", null, gates),
        )
    }

    @Test
    fun `前台是空串也按读不到处理`() {
        assertEquals(
            VerifyOutcome.SAME_APP,
            VerifyJudge.classify("com.qihoo.cleandroid_cn", "", gates),
        )
        assertEquals(
            VerifyOutcome.SAME_APP,
            VerifyJudge.classify("com.qihoo.cleandroid_cn", "   ", gates),
        )
    }

    // —— classify：跨包 ——

    @Test
    fun `跳到应用商店判为点进广告`() {
        assertEquals(
            VerifyOutcome.OFF_APP,
            VerifyJudge.classify("com.qihoo.cleandroid_cn", "com.xiaomi.market", gates),
        )
    }

    @Test
    fun `跳到浏览器判为点进广告`() {
        assertEquals(
            VerifyOutcome.OFF_APP,
            VerifyJudge.classify("com.sina.weibo", "com.android.browser", gates),
        )
    }

    @Test
    fun `不在门禁集合里的包跨包一律判点进广告`() {
        // 容器集合为空时，任何跨包都是误点——门禁只认显式登记过的容器。
        assertEquals(
            VerifyOutcome.OFF_APP,
            VerifyJudge.classify("pkg.a", "pkg.b", emptySet()),
        )
    }

    @Test
    fun `门禁只按包名精确匹配`() {
        // 大小写不同、多一个空格都不算容器：包名是精确标识，不做规范化，
        // 否则「看起来像」的名字会把未登记的容器放进来。
        assertEquals(
            VerifyOutcome.OFF_APP,
            VerifyJudge.classify(container.uppercase(), "pkg.host", gates),
        )
        assertEquals(
            VerifyOutcome.OFF_APP,
            VerifyJudge.classify("$container ", "pkg.host", gates),
        )
    }

    // —— classify：系统广告容器 ——

    @Test
    fun `容器点掉后回到宿主算成功`() {
        // 这是 0.17.48 要救的那类：开屏渲染在容器包名里，点掉之后前台是宿主 App。
        // 跨包在这里正是成功的样子。
        assertEquals(
            VerifyOutcome.GATEWAY,
            VerifyJudge.classify(container, "com.xiaomi.market", gates),
        )
    }

    @Test
    fun `容器自身没换包按普通成功处理`() {
        // 容器页还在（例如倒计时没走完又渲染了一帧）：不跨包就没有「点进广告」的问题。
        assertEquals(
            VerifyOutcome.SAME_APP,
            VerifyJudge.classify(container, container, gates),
        )
    }

    @Test
    fun `容器但前台读不到时不判成功也不判误点`() {
        assertEquals(
            VerifyOutcome.SAME_APP,
            VerifyJudge.classify(container, null, gates),
        )
    }

    // —— statsPackage：统计记到谁名下 ——

    @Test
    fun `容器命中记宿主名下`() {
        // 统计里不能出现 com.miui.systemAdSolution —— 用户看不懂，而真正被跳过的
        // 是宿主 App 的开屏。
        assertEquals(
            "com.xiaomi.market",
            VerifyJudge.statsPackage(container, "com.xiaomi.market", VerifyOutcome.GATEWAY),
        )
    }

    @Test
    fun `容器命中但宿主读不到时退回容器自身`() {
        // 宁可名字难看，也不能丢这一条。
        assertEquals(
            container,
            VerifyJudge.statsPackage(container, null, VerifyOutcome.GATEWAY),
        )
        assertEquals(
            container,
            VerifyJudge.statsPackage(container, "  ", VerifyOutcome.GATEWAY),
        )
    }

    @Test
    fun `普通命中记被跳过的那个包`() {
        assertEquals(
            "com.qihoo.cleandroid_cn",
            VerifyJudge.statsPackage("com.qihoo.cleandroid_cn", "com.qihoo.cleandroid_cn", VerifyOutcome.SAME_APP),
        )
    }

    @Test
    fun `误点不改变记账包名`() {
        // 误点分支根本不会插入记录，这里只保证函数本身不会把包名换成商店。
        assertEquals(
            "com.qihoo.cleandroid_cn",
            VerifyJudge.statsPackage("com.qihoo.cleandroid_cn", "com.xiaomi.market", VerifyOutcome.OFF_APP),
        )
    }

    // —— 组合：一条真实链路 ——

    @Test
    fun `小米商店开屏的完整判定`() {
        val skipPkg = container
        val fg = "com.xiaomi.market"
        val outcome = VerifyJudge.classify(skipPkg, fg, gates)
        assertEquals(VerifyOutcome.GATEWAY, outcome)
        assertEquals(fg, VerifyJudge.statsPackage(skipPkg, fg, outcome))
    }
}
