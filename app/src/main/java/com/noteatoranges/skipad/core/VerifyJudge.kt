package com.noteatoranges.skipad.core

/**
 * 核验时刻（点击后 800ms）目标已经消失，这次到底算不算「跳过成功」。
 *
 * 三选一，判据只有包名——附件里的节点还在不在由调用方先判完，这里只回答
 * 「前台换包了，是成功了还是点进广告了」。
 */
enum class VerifyOutcome {
    /** 前台还是同一个 App：目标消失就是真跳过 */
    SAME_APP,

    /**
     * 系统广告容器（`SYSTEM_AD_GATEWAYS`）：开屏渲染在容器自己的包名里，
     * 点掉之后前台回到宿主 App。**这里的跨包是成功的样子**，不是点进广告；
     * 按 OFF_APP 处理会把统计记成疑似误点，还会把容器页拉黑十分钟。
     */
    GATEWAY,

    /** 前台换成了别的包：疑似点进广告落地页、应用商店或浏览器，不记账 */
    OFF_APP,
}

/**
 * 核验的包名判定。
 *
 * 抽成纯函数的原因：这段逻辑原来散在 `verifySkipOnce()` 里（约 2500 行的服务类），
 * 要覆盖它只能上真机、靠广告整好在 800ms 内换页面才能撞到。判定本身不碰
 * `AccessibilityNodeInfo`、不看时间、不读配置，放在 JVM 上可以把每条边界钉死。
 */
object VerifyJudge {

    /**
     * @param skipPkg 这次动作是替哪个包做的（容器命中时是容器自己）
     * @param foregroundPkg 核验时读到的前台包名，读不到为 null
     * @param gateways 系统广告容器集合
     */
    fun classify(
        skipPkg: String,
        foregroundPkg: String?,
        gateways: Set<String>,
    ): VerifyOutcome = when {
        // 读不到前台不能当成「换包了」：核验本来就只隔 800ms，广告页的窗口
        // 可能正在销毁，rootInActiveWindow 会短暂返回 null。按 OFF_APP 处理
        // 会把真跳过记成误点，并且给这个 App 打上十分钟静默——代价远大于漏记。
        // 空串同理：`packageName?.toString()` 在某些实现上会给空，不能当成包名比较。
        foregroundPkg.isNullOrBlank() -> VerifyOutcome.SAME_APP
        foregroundPkg == skipPkg -> VerifyOutcome.SAME_APP
        skipPkg in gateways -> VerifyOutcome.GATEWAY
        else -> VerifyOutcome.OFF_APP
    }

    /**
     * 统计记到哪个包名下。
     *
     * 容器命中记宿主：否则「最近记录」里会出现 `com.miui.systemAdSolution`
     * 这个用户完全看不懂的名字，而真正被跳过的是宿主 App 的开屏。
     * 容器命中但前台读不到时退回容器自身——宁可名字难看，也不能丢这一条。
     */
    fun statsPackage(
        skipPkg: String,
        foregroundPkg: String?,
        outcome: VerifyOutcome,
    ): String = when (outcome) {
        VerifyOutcome.GATEWAY ->
            if (foregroundPkg.isNullOrBlank()) skipPkg else foregroundPkg
        VerifyOutcome.SAME_APP, VerifyOutcome.OFF_APP -> skipPkg
    }
}
