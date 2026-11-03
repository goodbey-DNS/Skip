package com.noteatoranges.skipad.core

/**
 * 把 [EngineTrace] 里的英文痕迹翻译成人话。
 *
 * 为什么要有这一层：引擎痕迹是给排查的人看的（`no-ad-evidence`、`reject=「跳过」在中央区域`），
 * 用户打开「诊断」想要的却是「刚才那个 App 为什么没跳过」。直接甩原始日志等于没给答案 ——
 * 用户不是不识字，是这些词本来就只对写它的人有意义。
 *
 * 翻译只在读的时候做，写日志的地方一行不改：痕迹本身要保留英文短码，
 * 否则 `logcat | grep verified` 这类排查手段全部失效。
 */
object Diagnosis {

    enum class Tone { OK, WARN, INFO }

    /** 事件归类。分开是因为「跳过成没成功」和「服务连没连上」不能混在一个数里 */
    enum class Kind {
        /** 一次开屏处理的结果：成功、放弃、点进落地页、没敢点 */
        SKIP,

        /** 没动手的原因：扫描拒绝、预热期放过、看了一圈没找到 */
        MISS,

        /** 快照抓取相关 */
        CAPTURE,

        /** 无障碍服务连接状态 */
        SERVICE,
    }

    /**
     * 一条看得懂的结论。
     *
     * [pkg] 只留原始包名供页面取图标用，[title]/[detail] 里的应用名已经换成用户认得的名字。
     */
    data class Event(
        val time: String,
        val tone: Tone,
        val kind: Kind,
        val pkg: String,
        val title: String,
        val detail: String,
    )

    /** 成功/放弃后的这个时间窗内，不再报「没找到」这类过程行 */
    private const val SKIP_SETTLE_SEC = 30

    /** 时间戳两种形态：内存记录 `HH:mm:ss.SSS`，落盘 `MM-dd HH:mm:ss.SSS` */
    private val TS = Regex("""^(\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(.*)$""")

    private val VERIFIED = Regex("""^verified (\S+) 「(.*)」 \(attempts=(\d+)\)$""")
    private val GIVEUP = Regex("""^giveup (\S+) 「(.*)」目标仍在\(.*?已补刀(\d+)次不记账$""")
    private val LANDING = Regex("""^landing (\S+) """)
    private val OFF_APP = Regex("""^off-app (\S+) → (\S+)，""")
    private val USERFREE = Regex("""^userfree-skip (\S+) 「(.*)」""")
    private val MISS = Regex("""^miss (\S+) (.+)$""")
    private val HOT_SKIP = Regex("""^hot-skip (\S+) warmup=(\d+)ms$""")
    private val HOT_CLOSE = Regex("""^hot-close (\S+) scans=(\d+) hits=(\d+)$""")
    private val CAPTURE_NODES = Regex("""^capture nodes=(\d+) pkg=(\S*) windows=(\d+)$""")
    private val SNAPSHOT_SAVED = Regex("""^snapshot saved \S+ nodes=(\d+) (\d+)x(\d+)$""")
    private val CAPTURE_EMPTY = Regex("""^capture-empty attempts=(\d+)""")
    private val SERVICE_CONNECTED = Regex("""^service-connected (\S+)$""")
    private val DEMOTE = Regex("""^demote-page-skip (\S+)""")

    /**
     * 扫描器给出的拒绝理由 → 用户能懂的一句话。
     *
     * 这些字符串来自 [NodeScanner]，两边靠文案对齐；对不上时退回原文，
     * 宁可露出半句术语，也不要凭空编一个原因。
     */
    private fun reasonOf(raw: String): String = when {
        raw.contains("疑似教程") -> "这段文字像教程或引导，不像关闭按钮"
        raw.contains("文本超长") -> "文字太长，不像按钮上的字"
        raw.contains("无文本且非 viewId") -> "没有文字，也不是已知的跳过控件"
        raw.contains("不可见") -> "按钮当时看不见"
        raw.contains("矩形无效") -> "按钮位置不对，没法点"
        raw.contains("已失效") -> "检查的时候按钮已经消失了"
        raw.contains("不在记录的位置") -> "和记住的位置对不上"
        raw.contains("附近没有跳过文案") -> "旁边没有「跳过」「关闭」这类字"
        raw.contains("中央区域") -> "在屏幕正中间，像正文而不是按钮"
        raw.contains("面积上限") -> "面积太大，像整块内容"
        raw.contains("no-root") -> "系统没给出这个页面的内容，读不到按钮"
        raw.contains("no-rule-in-hot") -> "这一页不是开屏页，不用处理"
        raw.contains("EXCLUDE") -> "页面里有被排除的词"
        else -> raw
    }

    /** 去掉包名换名字的函数：界面侧传 AppLabels 的包装，测试里可以传原样 */
    fun explain(lines: List<String>, labelOf: (String) -> String): List<Event> {
        val out = ArrayList<Event>(lines.size)
        // 每个 App 最近一次「有结论」的时刻（秒）。用来压掉紧随其后的过程行：
        // 跳过成功后往往还会再开一次窗口、扫几遍没命中，如实列出来就是
        // 「跳过成功」下面紧跟「看了一圈没找到」，自相矛盾
        val decidedAt = HashMap<String, Int>()
        for (line in lines) {
            val m = TS.matchEntire(line.trim()) ?: continue
            val at = m.groupValues[1]
            val msg = m.groupValues[2]
            // 内存 `HH:mm:ss.SSS`（12 字符），落盘 `MM-dd HH:mm:ss.SSS`（18 字符）
            val time = if (at.length > 12) at.substring(6, 14) else at.substring(0, 8)
            val e = interpret(time, msg, labelOf) ?: continue
            val sec = secOf(time)
            if (e.kind == Kind.SKIP) {
                decidedAt[e.pkg] = sec
            } else if (e.kind == Kind.MISS && e.pkg.isNotEmpty()) {
                val last = decidedAt[e.pkg]
                if (last != null && sec - last in 0..SKIP_SETTLE_SEC) continue
            }
            out.add(e)
        }
        return out
    }

    /** `HH:mm:ss` → 当天秒数；解析不了给 -1，调用方按「很远」处理 */
    private fun secOf(time: String): Int {
        val parts = time.split(':')
        if (parts.size != 3) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        val s = parts[2].toIntOrNull() ?: return -1
        return h * 3600 + m * 60 + s
    }

    private fun interpret(time: String, msg: String, labelOf: (String) -> String): Event? {
        VERIFIED.matchEntire(msg)?.let { r ->
            val app = labelOf(r.groupValues[1])
            val hit = r.groupValues[2]
            val attempts = r.groupValues[3].toIntOrNull() ?: 0
            val how = if (attempts == 0) "第一次点击就生效" else "点了 ${attempts + 1} 次才生效"
            return Event(time, Tone.OK, Kind.SKIP, r.groupValues[1], "$app 跳过成功", "点的是「$hit」，$how")
        }
        GIVEUP.matchEntire(msg)?.let { r ->
            val app = labelOf(r.groupValues[1])
            val hit = r.groupValues[2]
            val n = (r.groupValues[3].toIntOrNull() ?: 0) + 1
            return Event(
                time, Tone.WARN, Kind.SKIP, r.groupValues[1], "$app 没跳过去，已放弃",
                "点了「$hit」一共 $n 次，按钮还在，这次不计入跳过"
            )
        }
        LANDING.find(msg)?.let { r ->
            val app = labelOf(r.groupValues[1])
            return Event(
                time, Tone.WARN, Kind.SKIP, r.groupValues[1], "$app 点进了广告落地页",
                "新页面上出现了下载按钮，这次不计入跳过"
            )
        }
        OFF_APP.find(msg)?.let { r ->
            val app = labelOf(r.groupValues[1])
            val to = labelOf(r.groupValues[2])
            return Event(
                time, Tone.WARN, Kind.SKIP, r.groupValues[1], "$app 点完跳到了别的应用",
                "前台变成了 $to，这次不计入跳过"
            )
        }
        USERFREE.find(msg)?.let { r ->
            val app = labelOf(r.groupValues[1])
            return Event(
                time, Tone.WARN, Kind.SKIP, r.groupValues[1], "$app 看到「${r.groupValues[2]}」但没敢点",
                "这一页看着像主页，不像开屏广告"
            )
        }
        DEMOTE.find(msg)?.let { r ->
            val app = labelOf(r.groupValues[1])
            return Event(
                time, Tone.INFO, Kind.SKIP, r.groupValues[1], "$app 撤销了一次误记",
                "刚才跳过的其实是过渡页，不是广告，已从统计里删掉"
            )
        }
        MISS.matchEntire(msg)?.let { r ->
            val pkg = r.groupValues[1]
            val dbg = r.groupValues[2]
            val app = labelOf(pkg)
            val reason = reasonOf(dbg)
            val word = Regex("""「(.*?)」""").find(dbg)?.groupValues?.get(1)
            val title = if (word != null) "$app 没点「$word」" else "$app 这一页没动作"
            return Event(time, Tone.INFO, Kind.MISS, pkg, title, reason)
        }
        HOT_SKIP.matchEntire(msg)?.let { r ->
            val app = labelOf(r.groupValues[1])
            val sec = (r.groupValues[2].toLongOrNull() ?: 0L) / 1000
            return Event(
                time, Tone.INFO, Kind.MISS, r.groupValues[1], "$app 刚重连，先放过",
                "服务连上才 $sec 秒，这 20 秒内不动作；之后再遇到会正常处理"
            )
        }
        HOT_CLOSE.matchEntire(msg)?.let { r ->
            val scans = r.groupValues[2].toIntOrNull() ?: 0
            val hits = r.groupValues[3].toIntOrNull() ?: 0
            if (hits > 0 || scans == 0) return null
            val app = labelOf(r.groupValues[1])
            return Event(
                time, Tone.INFO, Kind.MISS, r.groupValues[1], "$app 看了一圈，没找到跳过按钮",
                "扫了 $scans 次。如果它当时没有开屏广告，这条是正常的"
            )
        }
        CAPTURE_NODES.matchEntire(msg)?.let { r ->
            val n = r.groupValues[1]
            val pkg = r.groupValues[2].takeIf { it.isNotBlank() && it != "null" } ?: ""
            val where = if (pkg.isEmpty()) "页面内容已读到" else "在 ${labelOf(pkg)} 里读到了页面内容"
            return Event(time, Tone.INFO, Kind.CAPTURE, pkg, "抓取快照：找到 $n 个控件", "$where，可以开始点选按钮")
        }
        SNAPSHOT_SAVED.matchEntire(msg)?.let { r ->
            return Event(
                time, Tone.OK, Kind.CAPTURE, "", "快照已保存",
                "${r.groupValues[1]} 个控件，${r.groupValues[2]}×${r.groupValues[3]}"
            )
        }
        CAPTURE_EMPTY.find(msg)?.let { r ->
            return Event(
                time, Tone.WARN, Kind.CAPTURE, "", "快照没抓到内容",
                "连试 ${r.groupValues[1]} 次，页面里没读到可点控件"
            )
        }
        if (msg.startsWith("capture-fail") || msg.startsWith("takeScreenshot failed") ||
            msg.startsWith("screenshot-decode-fail") || msg.startsWith("screenshot-result-fail")
        ) {
            return Event(time, Tone.WARN, Kind.CAPTURE, "", "截图失败", "系统没给到画面，稍后再试一次")
        }
        SERVICE_CONNECTED.matchEntire(msg)?.let { r ->
            return Event(time, Tone.OK, Kind.SERVICE, "", "无障碍服务已连接", "版本 ${r.groupValues[1]}")
        }
        if (msg == "service-unbind") {
            return Event(time, Tone.WARN, Kind.SERVICE, "", "无障碍服务被系统断开", "系统回收了服务，通常几秒内会自动重连")
        }
        return null
    }

    /** 概览数字：真实跳过结果，不含服务连接与抓取 */
    data class Tally(val ok: Int, val warn: Int)

    fun tally(events: List<Event>): Tally = Tally(
        events.count { it.kind == Kind.SKIP && it.tone == Tone.OK },
        events.count { it.kind == Kind.SKIP && it.tone == Tone.WARN },
    )

    /** 最近一次跳过成功的记录，给概览首行用 */
    fun lastSkip(events: List<Event>): Event? =
        events.lastOrNull { it.kind == Kind.SKIP && it.tone == Tone.OK }

    /** 最近一次服务状态，给概览第二行用 */
    fun lastService(events: List<Event>): Event? = events.lastOrNull { it.kind == Kind.SERVICE }
}
