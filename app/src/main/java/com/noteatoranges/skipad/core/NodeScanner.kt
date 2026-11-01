package com.noteatoranges.skipad.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * 在节点树中定位规则指定的目标：BFS 遍历 → 规则匹配 → 安全校验 → 返回可点击节点。
 *
 * 0.17.0 起这里**只**执行用户从快照点选生成的规则（[com.noteatoranges.skipad.db.RuleStore]
 * 里 kind=node 的那些）。内置的关键字 / 倒计时 / skip viewId 规则与应用内弹窗关闭
 * 那套启发式全部撤掉——它们在聊天页、输入法窗口这类正常界面会命中短文本和小控件。
 *
 * 匹配两层：
 * - 文本包含匹配（需过位置闸与相对长度闸，防页面正文误触）；
 * - viewId 包含匹配（快照点选时记下的资源名，免位置闸）。
 *
 * 注：AccessibilityNodeInfo.recycle() 在 API 33+ 已废弃（系统接管回收）；
 * 低版本上不主动回收只造成轻微内存压力，换来规避 recycle 误用型崩溃，有意不回收。
 */
class NodeScanner(
    rules: List<SkipRule>,
    /** 共享的节点属性缓存：同一次事件里多次扫描复用同一份读取结果 */
    private val facts: NodeFactsCache? = null,
) {

    private fun factsOf(node: AccessibilityNodeInfo): NodeFacts =
        facts?.of(node) ?: NodeFacts(node)

    private data class Compiled(
        val rule: SkipRule,
        val regexes: List<Regex>,
        val keywords: List<String>,
        val viewIds: List<String>,
    )

    /**
     * 命中详情。
     *
     * hitLen 是命中短语的长度，用来做「相对长度闸」：命中词越短，允许的额外字数越少。
     * viewId / 正则命中没有确定短语，hitLen=0、byText=false，仍用规则的绝对上限。
     */
    private data class Match(val node: Compiled, val hitLen: Int, val byText: Boolean)

    private val compiled: List<Compiled> = rules.filter { it.enabled }.map { r ->
        Compiled(
            rule = r,
            regexes = if (r.regex) r.patterns.map { Regex(it) } else emptyList(),
            keywords = if (!r.regex) r.patterns.map { it.lowercase(Locale.ROOT) } else emptyList(),
            viewIds = r.viewIds.map { it.lowercase(Locale.ROOT) },
        )
    }

    /** 最近一次扫描的诊断信息（节点数/采样文本与 viewId/匹配与拒绝原因），供真机排查 */
    var lastDebug: String? = null
        private set

    /**
     * 诊断字符串开关。拼接采样文本是每次扫描都要做的，信息流里一秒扫十几次，
     * 不输出日志时这部分是纯浪费，默认关掉
     */
    var verbose = false

    /** 返回 (可点击目标节点, 命中的规则)；找不到返回 null */
    fun findSkipTarget(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
    ): Pair<AccessibilityNodeInfo, SkipRule>? {
        if (screenW <= 0 || screenH <= 0) return null
        val screenArea = screenW.toFloat() * screenH
        val deadline = System.currentTimeMillis() + TRAVERSE_TIMEOUT_MS

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val samples = LinkedHashSet<String>()
        var reject: String? = null

        while (queue.isNotEmpty()) {
            if (visited++ > MAX_NODES || System.currentTimeMillis() > deadline) {
                lastDebug = if (verbose) {
                    "ABORT nodes=$visited texts=${samples.joinToString("|").take(180)}"
                } else {
                    "ABORT nodes=$visited"
                }
                return null
            }
            val node = queue.removeFirst()
            val f = factsOf(node)

            val text = f.label
            if (text != null && samples.size < 20) samples.add(text.take(16))
            val vid = f.vid?.lowercase(Locale.ROOT)
            if (vid != null && samples.size < 20) samples.add("vid=$vid")

            val matched = firstMatch(node, text, vid)
            if (matched != null) {
                val byViewId = vid != null && matched.node.viewIds.any { vid.contains(it) }
                val limit = allowedLen(matched)
                val tooLong = text != null && text.length > limit
                // 负向闸：教程 / 引导 / 新手 这类"跳过"不是广告按钮（"跳过教程""Skip tutorial"）
                if (text != null && NEGATIVE_HINTS.any { text.contains(it, ignoreCase = true) }) {
                    reject = "「${text.take(12)}」疑似教程/引导"
                } else if (tooLong && text != null) {
                    // 文本长度闸对 viewId 命中同样生效：长文本控件（正文里提到关键字）
                    // 不该被点。无文本的 viewId 命中仍放行，那是按钮本体只有 id 的形态
                    reject = "「${text.take(12)}」文本超长(${text.length}>$limit)"
                } else if (!byViewId && text == null) {
                    reject = "「${vid?.take(12)}」无文本且非 viewId 命中"
                } else {
                    // 找不到可点击祖先时退回节点本身：ClickPerformer 会先试 performAction，
                    // 失败再按节点中心坐标点。有些跳过按钮是纯 TextView（clickable=false、
                    // 祖先也不可点），只有这条路能点掉
                    val cand = clickableAncestor(node) ?: node
                    val candFacts = factsOf(cand)
                    if (!candFacts.visible) {
                        reject = "「${(text ?: vid)?.take(12)}」不可见"
                    } else {
                        val rect = candFacts.rect
                        val area = rect.width().toFloat() * rect.height()
                        val pct = (area / screenArea * 100).toInt()
                        // 位置闸：排除屏幕中央主体区，那里出现关键字基本是正文而不是按钮。
                        // viewId 命中的是按钮本体，免此闸。
                        val cx = rect.exactCenterX()
                        val cy = rect.exactCenterY()
                        val inCenter = cx > screenW * 0.3f && cx < screenW * 0.7f &&
                            cy > screenH * 0.2f && cy < screenH * 0.8f
                        if (!byViewId && inCenter) {
                            reject = "「${text?.take(12)}」在中央区域(cx=${cx.toInt()},cy=${cy.toInt()})"
                        } else if (area in 1f..screenArea * matched.node.rule.maxAreaRatio) {
                            lastDebug = "HIT「${(text ?: vid)?.take(20)}」面积${pct}% 规则=${matched.node.rule.id}" +
                                if (byViewId) "(viewId)" else ""
                            return cand to matched.node.rule
                        } else {
                            reject = "「${text?.take(12)}」区域面积${pct}%超限"
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }
        lastDebug = if (verbose) {
            "no-match nodes=$visited texts=[${samples.joinToString("|").take(180)}]" +
                (reject?.let { " reject=$it" } ?: "")
        } else {
            "no-match nodes=$visited"
        }
        return null
    }

    /**
     * 分隔符归一化再匹配。
     *
     * 按钮文字常写成「跳 过」「[跳过]」「跳·过」「跳\n过」，原样 contains 一个都
     * 命中不了。归一化只去掉分隔符，不改变字序，所以不会把两段不相干文字拼成命中。
     */
    private fun normalizeForMatch(s: String): String = s.replace(SEPARATOR_RE, "")

    /**
     * 文本长度上限（相对闸）。
     *
     * 固定 24 字对「跳过」这种两个字的词等于没闸——正文里提到关键字的一整句都能过。
     * 改成「命中短语长度 + [TEXT_SLACK]」，同时不超过规则的绝对上限。
     */
    private fun allowedLen(m: Match): Int {
        val abs = m.node.rule.maxTextLength
        if (!m.byText || m.hitLen <= 0) return abs
        return minOf(abs, m.hitLen + TEXT_SLACK)
    }

    private fun firstMatch(
        node: AccessibilityNodeInfo,
        text: String?,
        vid: String?,
    ): Match? {
        val lower = text?.lowercase(Locale.ROOT)
        val norm = lower?.let { normalizeForMatch(it) }
        for (c in compiled) {
            if (text != null) {
                if (c.regexes.any { it.containsMatchIn(text) }) return Match(c, 0, true)
                c.keywords.firstOrNull { lower!!.contains(it) }
                    ?.let { return Match(c, it.length, true) }
                // 归一化后再试一次：「跳 过」「[跳过]」「跳·过」
                if (norm != null && norm.isNotEmpty() && norm != lower) {
                    c.keywords.firstOrNull { norm.contains(it) }
                        ?.let { return Match(c, it.length, true) }
                }
            }
            if (vid != null && c.viewIds.any { vid.contains(it) }) return Match(c, 0, false)
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < ANCESTOR_HOPS) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops++
        }
        return null
    }

    private companion object {
        const val MAX_NODES = 800
        const val TRAVERSE_TIMEOUT_MS = 400L
        const val ANCESTOR_HOPS = 6

        /**
         * 相对长度闸的余量。命中「跳过」时允许节点文字最多 10 字。
         *
         * 不是照搬参考实现的 6：按钮常见「跳过 5s」「跳过广告，进入APP」（9 字）
         * 这类带倒计时或引导语的写法，6 字会误杀；而 24（原绝对上限）对两字词又等于没闸。
         */
        const val TEXT_SLACK = 8

        /**
         * 分隔符正则：空白、零宽字符、全角空格、中点、常见括号与竖线。
         * 按钮文字常写成「跳 过」「[跳过]」「跳·过」，原样 contains 匹配不到。
         */
        val SEPARATOR_RE =
            Regex("[\\s\\u200B-\\u200D\\uFEFF\\p{Z}·\\-_|/•\\[\\]()（）【】]+")

        /** 负向关键词：出现即判定不是要点的按钮（宁可少点，不能误点引导页） */
        val NEGATIVE_HINTS = listOf(
            "教程", "引导", "新手", "跳过此步", "跳过本",
            "tutorial", "guide", "walkthrough",
        )
    }
}
