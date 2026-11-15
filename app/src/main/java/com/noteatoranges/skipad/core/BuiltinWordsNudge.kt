package com.noteatoranges.skipad.core

/**
 * 「内置默认词」被长期关闭时的提醒判定。
 *
 * 为什么需要这个：内置词表是文字型开屏广告的总闸（见 design.md 第 29 条）。关掉之后，
 * 页面上明明写着「跳过」也一律 no-match，日志表现和「引擎坏了」完全一样——用户自己
 * 关的开关，过几天却回来问「是不是失效了」，这件事在真机上真的发生过一次。
 * 与其让人自己想起来去翻规则页，不如首页直接把话说清楚。
 *
 * 四条同时成立才提醒，任何一条不满足都不打扰：
 * 1. 开关当前是关的；
 * 2. 起点时间有效（见过 setBuiltinWordsEnabled 的写入，或由 ensureOffSince 补过）；
 * 3. 关闭期间确实发生过扫描（说明用户还在用，不是装完就放着）；
 * 4. 关闭已超过 [MIN_OFF_MS]。
 *
 * 第 3 条是关键：没有它，装了不用的人一打开 App 就会被提示，等于给静态用户添噪音。
 */
object BuiltinWordsNudge {

    /**
     * 关闭多久之后开始提醒。
     *
     * 24 小时：一次重启、一次「想试试能不能只靠自己的词」的临时试验都不足以构成长期关闭；
     * 超过一天还关着，基本就是忘了自己关过，或者当成「开关坏了」。
     */
    const val MIN_OFF_MS = 24L * 3600_000L

    /** 是否该在首页提示 */
    fun shouldWarn(enabled: Boolean, offSince: Long, scannedWhileOff: Boolean, now: Long): Boolean {
        if (enabled) return false
        if (offSince <= 0L) return false
        if (!scannedWhileOff) return false
        return now - offSince >= MIN_OFF_MS
    }

    /**
     * 关闭时长，向上取整到小时。
     *
     * 取整方向故意选「向上」：23 小时 40 分显示 24 小时，与判定阈值对齐，
     * 不会出现「提示说已关闭 23 小时」却已经弹出的自相矛盾。
     */
    fun offHours(offSince: Long, now: Long): Int {
        if (offSince <= 0L) return 0
        val d = now - offSince
        if (d <= 0L) return 0
        return ((d + 3600_000L - 1) / 3600_000L).toInt()
    }

    /** 文案里用的人话时长：不足 48 小时报小时，超过报天 */
    fun offPhrase(offSince: Long, now: Long): String {
        val h = offHours(offSince, now)
        return if (h >= 48) "${h / 24} 天" else "$h 小时"
    }
}
