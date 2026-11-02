package com.noteatoranges.skipad.core

import android.graphics.Rect
import android.graphics.RectF
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
    /**
     * 用户排除词：命中即整条拒绝。优先级高于命中词——用户写「跳过新手引导」
     * 就是为了把这类命中挡掉，绝不能先看命中词再考虑排除词
     */
    private val excludes: List<String> = emptyList(),
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

    private val compiled: List<Compiled> = rules.filter { it.enabled }.mapNotNull { r ->
        // 正则写错不该让整组规则失效：Regex() 在构造时抛 PatternSyntaxException，
        // 以前这条异常会在每次事件里抛一遍，扫描器永远建不起来，用户只看到「规则不生效」
        val regexes = if (r.regex) {
            r.patterns.mapNotNull { p -> runCatching { Regex(p) }.getOrNull() }
        } else {
            emptyList()
        }
        if (r.regex && regexes.isEmpty()) return@mapNotNull null
        Compiled(
            rule = r,
            regexes = regexes,
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

            val text = runCatching { f.label }.getOrNull()
            if (text != null && samples.size < 20) samples.add(text.take(16))
            val vid = runCatching { f.vid }.getOrNull()?.lowercase(Locale.ROOT)
            if (vid != null && samples.size < 20) samples.add("vid=$vid")

            val matched = firstMatch(node, text, vid)
            if (matched != null) {
                val byViewId = vid != null && matched.node.rule.viewIdHit(vid)
                val limit = allowedLen(matched)
                val tooLong = text != null && text.length > limit
                // 排除词命中 = 整屏放弃。界面上写的就是「排除词命中就整屏放弃」，
                // 而以前只拒这一个节点、接着扫下一个，等于承诺没兑现，
                // 用户写了排除词却还是被点了
                if (text != null && excludes.any { text.contains(it, ignoreCase = true) }) {
                    lastDebug = "EXCLUDE「${text.take(12)}」整屏放弃"
                    return null
                }
                // 负向闸：教程 / 引导 / 新手 这类「跳过」不是广告按钮。
                // 0.17.33 起同时查 viewId：新手引导页的按钮文本常写「跳过」，而资源名是
                // skip_intro / skip_guide 这类——只查文本等于只挡了一半，而引导页被点掉
                // 用户看到的是功能全丢，比漏跳一个广告严重得多。
                val negative = listOfNotNull(text, vid).any { src ->
                    NEGATIVE_HINTS.any { src.contains(it, ignoreCase = true) }
                }
                if (negative) {
                    reject = "「${(text ?: vid)?.take(12)}」疑似教程/引导"
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
                    val cand = runCatching { clickableAncestor(node) }.getOrNull() ?: node
                    // 节点可能在遍历中途失效（页面已跳走），属性读取会抛。这里一律当「本节点
                    // 不可用」处理而不是让整次扫描崩掉——0.17.12 在淘宝页就是这个形态
                    val candFacts = runCatching { factsOf(cand) }.getOrNull()
                    val visible = candFacts != null && runCatching { candFacts.visible }.getOrDefault(false)
                    if (candFacts == null || !visible) {
                        reject = "「${(text ?: vid)?.take(12)}」不可见"
                    } else {
                        val rect = runCatching { candFacts.rect }.getOrNull()
                        if (rect == null || rect.width() <= 0 || rect.height() <= 0) {
                            reject = "「${(text ?: vid)?.take(12)}」矩形无效"
                            continue
                        }
                        val area = rect.width().toFloat() * rect.height()
                        val pct = (area / screenArea * 100).toInt()
                        // 位置闸：排除屏幕中央主体区，那里出现关键字基本是正文而不是按钮。
                        // viewId 命中的是按钮本体，免此闸。
                        val cx = rect.exactCenterX()
                        val cy = rect.exactCenterY()
                        val inCenter = cx > screenW * 0.3f && cx < screenW * 0.7f &&
                            cy > screenH * 0.2f && cy < screenH * 0.8f
                        // 位置校验：规则记了用户点选时的位置，就只认那块地方；
                        // 没记（旧规则）才退回「屏幕正中算正文」的粗闸。
                        // 比的是命中节点本身（带文字那个）的矩形，不是可点击祖先——
                        // 旧布局里跳过按钮常是一个全宽横栏里的两个字，用祖先矩形比就会被判在外面
                        val zone = matched.node.rule.zone
                        val hitRect = runCatching { f.rect }.getOrNull()
                        if (hitRect == null) {
                            reject = "「${text?.take(12)}」命中节点已失效"
                            continue
                        }
                        val zoneOk = zone == null || zoneHit(zone, hitRect, screenW, screenH)
                        if (!zoneOk) {
                            reject = "「${text?.take(12)}」不在记录的位置"
                        } else if (!neighborHasHint(node, matched.node.rule.neighborHints)) {
                            // 倒计时数字这类「旁边必须有跳过文案」的规则：孤立数字不点
                            reject = "「${text?.take(12)}」附近没有跳过文案"
                        } else if (zone == null && !byViewId && !matched.node.rule.allowCenter && inCenter) {
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

            // childCount / getChild 在节点失效时不一致会抛 ArrayIndexOutOfBoundsException，
            // 系统内部 LongArray.get 越界直接抛，外层 catch 只能救进程、救不回这次扫描
            val count = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                runCatching { node.getChild(i) }.getOrNull()?.let(queue::add)
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
                c.keywords.firstOrNull { kwHit(c, lower!!, it) }
                    ?.let { return Match(c, it.length, true) }
                // 归一化后再试一次：「跳 过」「[跳过]」「跳·过」
                if (norm != null && norm.isNotEmpty() && norm != lower) {
                    c.keywords.firstOrNull { kwHit(c, norm, it) }
                        ?.let { return Match(c, it.length, true) }
                }
            }
            if (vid != null && c.rule.viewIdHit(vid)) return Match(c, 0, false)
        }
        return null
    }

    /**
     * 关键字是否命中。
     *
     * 用户词：包含匹配即可——他写「放弃福利」就是明确要这一段出现在控件里。
     * 内置通用词：还要求关键字落在首尾（见 [SkipRule.strictText]）。否则「广告跳过项目」
     * 这类会话标题、列表正文只要含「跳过」两个字就会被当成开屏按钮点掉。
     */
    private fun kwHit(c: Compiled, s: String, kw: String): Boolean {
        if (!s.contains(kw)) return false
        if (!c.rule.strictText) return true
        val t = stripEdge(s)
        return t.startsWith(kw) || t.endsWith(kw)
    }

    /**
     * 去掉首尾的标点与符号，只给首尾判定用。
     *
     * 「跳过。」「[跳过]」「跳过广告，」这类收尾带标点的写法，不该因为一个逗号
     * 就被判成「关键字不在结尾」。
     */
    private fun stripEdge(s: String): String = s.replace(EDGE_TRIM, "")

    /**
     * 命中节点是否落在「用户点选时那个控件」的位置附近。
     *
     * 容差横 8%、纵 12%：纵向偏大是因为规则存的是抓取时的全屏比例，回放时比的是
     * 不含导航栏的可用高度，两者相差约 9%，容差不留余量会把整条规则判死。
     * 判据用「相交面积 ≥ 较小者的 25%」而不是「中心点落在范围内」：全宽横栏里的
     * 两个字中心靠近右侧，用中心点判会误杀，用相交判只看它们是否在同一块地方。
     */
    private fun zoneHit(zone: RectF, hit: Rect, screenW: Int, screenH: Int): Boolean {
        val l = (zone.left - ZONE_TOL_X) * screenW
        val t = (zone.top - ZONE_TOL_Y) * screenH
        val r = (zone.right + ZONE_TOL_X) * screenW
        val b = (zone.bottom + ZONE_TOL_Y) * screenH
        val inter = RectF()
        if (!inter.setIntersect(RectF(l, t, r, b), RectF(hit))) return false
        val hitArea = hit.width().toFloat() * hit.height()
        val zoneArea = (r - l) * (b - t)
        val small = minOf(hitArea, zoneArea)
        return small <= 0f || inter.width() * inter.height() >= small * ZONE_MIN_OVERLAP
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < ANCESTOR_HOPS) {
            if (runCatching { cur!!.isClickable }.getOrDefault(false)) return cur
            cur = runCatching { cur!!.parent }.getOrNull()
            hops++
        }
        return null
    }

    /**
     * 命中节点附近是否出现指定文案（自身 / 父节点 / 兄弟节点）。
     *
     * 只向上看两层：开屏页的倒计时与跳过文案通常是同一容器里的两个子节点
     * （父 → 数字 + 文案），或者文案在外层横栏里。再往上就是整张广告图，
     * 那里的文字和这个数字没有判别关系，看得越远误判越多。
     *
     * hints 为空 = 不校验，直接放行（用户规则与普通内置词都走这条，
     * 只有倒计时数字那条规则带了 hints）。
     */
    private fun neighborHasHint(node: AccessibilityNodeInfo, hints: List<String>): Boolean {
        if (hints.isEmpty()) return true
        fun hit(t: String?): Boolean =
            t != null && hints.any { t.contains(it, ignoreCase = true) }

        var cur: AccessibilityNodeInfo = node
        var hops = 0
        while (hops < NEIGHBOR_HOPS) {
            val parent: AccessibilityNodeInfo = runCatching { cur.parent }.getOrNull() ?: return false
            if (hit(runCatching { factsOf(parent).label }.getOrNull())) return true
            val count = runCatching { parent.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                val sib = runCatching { parent.getChild(i) }.getOrNull() ?: continue
                if (sib == cur) continue
                if (hit(runCatching { factsOf(sib).label }.getOrNull())) return true
            }
            cur = parent
            hops++
        }
        return false
    }

    private companion object {
        const val MAX_NODES = 800
        const val TRAVERSE_TIMEOUT_MS = 400L
        const val ANCESTOR_HOPS = 6

        /** 邻近文案校验向上看几层（见 neighborHasHint） */
        const val NEIGHBOR_HOPS = 2

        /**
         * 相对长度闸的余量。命中「跳过」时允许节点文字最多 10 字。
         *
         * 不是照搬参考实现的 6：按钮常见「跳过 5s」「跳过广告，进入APP」（9 字）
         * 这类带倒计时或引导语的写法，6 字会误杀；而 24（原绝对上限）对两字词又等于没闸。
         */
        const val TEXT_SLACK = 8

        /** 位置校验的容差（屏幕比例）：横向 8%、纵向 12% */
        const val ZONE_TOL_X = 0.08f
        const val ZONE_TOL_Y = 0.12f

        /** 命中节点与记录位置的最小相交比例（相对较小的一方） */
        const val ZONE_MIN_OVERLAP = 0.25f

        /**
         * 分隔符正则：空白、零宽字符、全角空格、中点、常见括号与竖线。
         * 按钮文字常写成「跳 过」「[跳过]」「跳·过」，原样 contains 匹配不到。
         */
        val SEPARATOR_RE =
            Regex("[\\s\\u200B-\\u200D\\uFEFF\\p{Z}·\\-_|/•\\[\\]()（）【】]+")

        /**
         * 首尾标点/符号裁剪，只给内置词的「关键字在首尾」判定用。
         * 不动中间内容，所以不会把「广告跳过项目」误裁成以「跳过」结尾。
         */
        val EDGE_TRIM = Regex("^[\\p{P}\\p{S}\\s]+|[\\p{P}\\p{S}\\s]+\$")

        /** 负向关键词：出现即判定不是要点的按钮（宁可少点，不能误点引导页） */
        val NEGATIVE_HINTS = listOf(
            "教程", "引导", "新手", "跳过此步", "跳过本",
            "tutorial", "guide", "walkthrough", "intro", "onboard",
        )
    }
}
