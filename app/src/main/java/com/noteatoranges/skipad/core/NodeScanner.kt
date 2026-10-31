package com.noteatoranges.skipad.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * 在节点树中定位"跳过"目标：BFS 遍历 → 规则匹配 → 安全校验 → 返回可点击节点。
 *
 * 匹配三层（GKD 路数）：
 * - 文本/描述 关键字与正则（需过右上角位置闸防页面正文误触）；
 * - **viewId 结构匹配**：viewIdResourceName 含 "skip" 之类即命中，无文本也认——
 *   国产广告 SDK 的跳过按钮本体（tt_splash_skip_btn / xxx:id/skip_btn），免位置闸；
 * - 命中节点不可点击时沿父链向上找可点击祖先。
 *
 * 注：AccessibilityNodeInfo.recycle() 在 API 33+ 已废弃（系统接管回收）；
 * 低版本上不主动回收只造成轻微内存压力，换来规避 recycle 误用型崩溃，有意不回收。
 */
class NodeScanner(rules: List<SkipRule>) {

    private data class Compiled(
        val rule: SkipRule,
        val regexes: List<Regex>,
        val keywords: List<String>,
        val viewIds: List<String>,
    )

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
                lastDebug = "ABORT nodes=$visited texts=${samples.joinToString("|").take(180)}"
                return null
            }
            val node = queue.removeFirst()

            val text = nodeText(node)
            if (text != null && samples.size < 20) samples.add(text.take(16))
            val vid = node.viewIdResourceName?.lowercase(Locale.ROOT)
            if (vid != null && vid.contains("skip") && samples.size < 20) samples.add("vid=$vid")

            val matched = firstMatch(node, text, vid)
            if (matched != null) {
                val byViewId = vid != null && matched.viewIds.any { vid.contains(it) }
                val tooLong = text != null && text.length > matched.rule.maxTextLength
                // 负向闸：教程 / 引导 / 新手 这类"跳过"不是广告按钮（"跳过教程""Skip tutorial"）
                if (text != null && NEGATIVE_HINTS.any { text.contains(it, ignoreCase = true) }) {
                    reject = "「${text.take(12)}」疑似教程/引导，非广告"
                } else if (tooLong && text != null) {
                    // 文本长度闸对 viewId 命中同样生效：原先 viewId 命中完全绕过此闸，
                    // 长文本控件（正文里提到 skip）也会被点。无文本的 viewId 命中仍放行，
                    // 那是穿山甲等广告 SDK 跳过按钮的正常形态（只有 id 没有 text）。
                    reject = "「${text.take(12)}」文本超长(${text.length})"
                } else if (!byViewId && text == null) {
                    reject = "「${vid?.take(12)}」无文本且非 viewId 命中"
                } else {
                    // 找不到可点击祖先时退回节点本身：ClickPerformer 会先试 performAction，
                    // 失败再按节点中心坐标点。微博开屏的"跳过"是纯 TextView（clickable=false、
                    // 祖先也不可点），只有这条路能点掉
                    val cand = clickableAncestor(node) ?: node
                    if (!cand.isVisibleToUser) {
                        reject = "「${(text ?: vid)?.take(12)}」不可见"
                    } else {
                        val rect = Rect()
                        cand.getBoundsInScreen(rect)
                        val area = rect.width().toFloat() * rect.height()
                        val pct = (area / screenArea * 100).toInt()
                        // 位置闸：广告跳过按钮可以出现在任意角落（微博在右下角 95%），
                        // 只排除屏幕中央主体区，那里出现"跳过"基本是正文而不是按钮。
                        // viewId 命中的是按钮本体，免此闸。
                        val cx = rect.exactCenterX()
                        val cy = rect.exactCenterY()
                        val inCenter = cx > screenW * 0.3f && cx < screenW * 0.7f &&
                            cy > screenH * 0.2f && cy < screenH * 0.8f
                        if (!byViewId && inCenter) {
                            reject = "「${text?.take(12)}」在中央区域(cx=${cx.toInt()},cy=${cy.toInt()})"
                        } else if (area in 1f..screenArea * matched.rule.maxAreaRatio) {
                            lastDebug = "HIT「${(text ?: vid)?.take(20)}」面积${pct}% 规则=${matched.rule.id}" +
                                if (byViewId) "(viewId)" else ""
                            return cand to matched.rule
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
        lastDebug = "no-match nodes=$visited texts=[${samples.joinToString("|").take(180)}]" +
            (reject?.let { " reject=$it" } ?: "")
        return null
    }

    private fun firstMatch(
        node: AccessibilityNodeInfo,
        text: String?,
        vid: String?,
    ): Compiled? {
        val lower = text?.lowercase(Locale.ROOT)
        for (c in compiled) {
            if (text != null) {
                if (c.regexes.any { it.containsMatchIn(text) }) return c
                if (c.keywords.any { lower!!.contains(it) }) return c
            }
            if (vid != null && c.viewIds.any { vid.contains(it) }) return c
        }
        return null
    }

    /** text 优先，无 text 用 contentDescription（图片型"跳过"按钮常只有 desc） */
    private fun nodeText(node: AccessibilityNodeInfo): String? {
        val t = node.text?.toString()
        if (!t.isNullOrBlank()) return t
        val d = node.contentDescription?.toString()
        if (!d.isNullOrBlank()) return d
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

        /** 负向关键词：出现即判定不是广告跳过按钮（宁可少点，不能误点引导页） */
        val NEGATIVE_HINTS = listOf(
            "教程", "引导", "新手", "跳过此步", "跳过本",
            "tutorial", "guide", "walkthrough",
        )
    }
}
