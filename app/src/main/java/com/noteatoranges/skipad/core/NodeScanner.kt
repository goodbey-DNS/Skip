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
class NodeScanner(
    rules: List<SkipRule>,
    /** 共享的节点属性缓存：开屏与弹窗两个通道在同一次事件里复用同一份读取结果 */
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

    /** 最近一次弹窗扫描的诊断信息，供真机排查 */
    var lastPopupDebug: String? = null
        private set

    /** 弹窗关闭按钮命中结果：node 为待点节点，why 为命中依据（落库展示用） */
    data class PopupHit(val node: AccessibilityNodeInfo, val why: String)

    /** 返回 (可点击目标节点, 命中的规则)；找不到返回 null
     *
     * @param hasAdHint 该窗口里已看到广告标识（"广告"文本 / 广告 SDK viewId），
     *                  命中时免位置闸——广告页里的"跳过"无论在哪都是按钮
     */
    fun findSkipTarget(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
        hasAdHint: Boolean = false,
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
                    val candFacts = factsOf(cand)
                    if (!candFacts.visible) {
                        reject = "「${(text ?: vid)?.take(12)}」不可见"
                    } else {
                        val rect = candFacts.rect
                        val area = rect.width().toFloat() * rect.height()
                        val pct = (area / screenArea * 100).toInt()
                        // 位置闸：广告跳过按钮可以出现在任意角落（微博在右下角 95%），
                        // 只排除屏幕中央主体区，那里出现"跳过"基本是正文而不是按钮。
                        // viewId 命中的是按钮本体，免此闸。
                        val cx = rect.exactCenterX()
                        val cy = rect.exactCenterY()
                        val inCenter = cx > screenW * 0.3f && cx < screenW * 0.7f &&
                            cy > screenH * 0.2f && cy < screenH * 0.8f
                        if (!byViewId && !hasAdHint && inCenter) {
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
        lastDebug = if (verbose) {
            "no-match nodes=$visited texts=[${samples.joinToString("|").take(180)}]" +
                (reject?.let { " reject=$it" } ?: "")
        } else {
            "no-match nodes=$visited"
        }
        return null
    }

    /**
     * 兜底：开屏窗口里右上 / 右下角的"小可点击控件"。
     * 用于纯图片、视频类开屏广告——跳过按钮是 ImageView，既没有文本也没有 skip 类 viewId，
     * 无障碍层只能靠"位置 + 尺寸"认。调用方只在 Activity 名含 splash / welcome / ad 时启用，
     * 避免在正常页面误点右上角的搜索、更多。
     */
    fun findCornerButton(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
    ): AccessibilityNodeInfo? {
        if (screenW <= 0 || screenH <= 0) return null
        val w = screenW.toFloat()
        val h = screenH.toFloat()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var n = 0
        var best: AccessibilityNodeInfo? = null
        var bestArea = Float.MAX_VALUE
        while (queue.isNotEmpty() && n < CORNER_SCAN_NODES) {
            val node = queue.removeFirst()
            n++
            val f = factsOf(node)
            if (f.clickable && f.visible) {
                val r = f.rect
                val cw = r.width().toFloat()
                val ch = r.height().toFloat()
                val cx = r.exactCenterX()
                val cy = r.exactCenterY()
                val inCorner = cx > w * 0.62f && (cy < h * 0.22f || cy > h * 0.78f)
                val sizeOk = cw in (w * 0.04f)..(w * 0.30f) && ch in (h * 0.015f)..(h * 0.09f)
                if (inCorner && sizeOk) {
                    val area = cw * ch
                    if (area < bestArea) {
                        best = node
                        bestArea = area
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return best
    }

    /**
     * 应用内弹窗（不是开屏）的关闭按钮定位。
     *
     * 开屏是整个窗口一张广告，跳过按钮在角落；弹窗盖在正常页面上，关闭钮落在屏幕
     * 中央偏下或卡片右上角，正好被 [findSkipTarget] 的"排除中央区"闸拒掉。
     *
     * 三条证据任一成立才返回，宁可漏掉不误点：
     * - E1 居中卡片（短文本包围盒）边缘 ±12% 内的小可点控件，且卡片内有营销文案；
     * - E2 节点稀疏（≤60）的窗口里，纵向 60% 以下的小可点图片控件（独立弹窗 Activity）；
     * - E3 控件文字本身就是"关闭 / close / ×"，且落在屏幕中央偏下——正常页面的
     *   关闭钮都在角落，中央偏下基本只有弹窗会有。
     *
     * 业务型弹窗（登录、支付、授权、隐私）一律不点：点掉会打断用户正在做的事。
     */
    fun findPopupCloseTarget(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
    ): PopupHit? {
        if (screenW <= 0 || screenH <= 0) return null
        val snaps = collectSnaps(root, screenW, screenH)
        if (snaps.isEmpty()) {
            lastPopupDebug = "popup-empty"
            return null
        }

        // 业务弹窗排除：登录 / 支付 / 授权 / 隐私类弹窗点掉会打断正常操作。
        // 只看屏幕中央区域内的文本——外侧的“钱包银行卡支付优惠”这类入口文案不算
        for (s in snaps) {
            val t = s.text ?: continue
            val cx = s.rect.exactCenterX()
            val cy = s.rect.exactCenterY()
            if (cx > screenW * 0.30f && cx < screenW * 0.70f &&
                cy > screenH * 0.18f && cy < screenH * 0.88f &&
                POPUP_BUSINESS_WORDS.any { t.contains(it, ignoreCase = true) }
            ) {
                lastPopupDebug = "popup-block 业务弹窗「${t.take(12)}」"
                return null
            }
        }

        // 卡片 = 营销文案的包围盒。没有营销文案的窗口不是营销弹窗，宁可漏掉
        val texts = snaps.filter { s ->
            val t = s.text ?: return@filter false
            val cy = s.rect.exactCenterY()
            t.length in 2..20 && cy > screenH * 0.10f && cy < screenH * 0.89f &&
                !NUMBER_ONLY.matches(t)
        }
        val marketingTexts = texts.filter { s ->
            val t = s.text ?: return@filter false
            MARKETING_WORDS.any { t.contains(it, ignoreCase = true) }
        }
        val card = if (marketingTexts.size >= 2) unionRect(marketingTexts.map { it.rect }) else null
        val cardOk = card != null &&
            card.width().toFloat() in (screenW * 0.25f)..(screenW * 0.92f) &&
            card.height().toFloat() in (screenH * 0.12f)..(screenH * 0.62f) &&
            card.exactCenterX() in (screenW * 0.35f)..(screenW * 0.65f) &&
            card.exactCenterY() in (screenH * 0.28f)..(screenH * 0.72f)

        for (s in snaps) {
            if (closeCandidate(s, screenW, screenH) == null) continue
            val st = s.text
            // E3：文字以"关闭"开头（淘宝 poplayer 的按钮描述就叫"关闭按钮"），
            // 且落在屏幕中央偏下——正常页面的关闭钮都在角落。
            // 用 startsWith 而不是 contains：只排除正文里顺带提到"关闭"的句子
            if (st != null && st.length <= 6 &&
                STRONG_CLOSE_WORDS.any { st.startsWith(it, ignoreCase = true) } &&
                s.rect.exactCenterX() > screenW * 0.30f && s.rect.exactCenterX() < screenW * 0.70f &&
                s.rect.exactCenterY() > screenH * 0.55f
            ) {
                lastPopupDebug = "popup-hit E3「$st」"
                return PopupHit(s.node, "关闭「$st」")
            }
            // E1：营销卡片边缘的小控件
            if (cardOk && card != null && edgeOfCard(s.rect, card, screenW, screenH)) {
                lastPopupDebug = "popup-hit E1 card=$card"
                return PopupHit(s.node, "弹窗评论按钮")
            }
            // E2：独立弹窗 Activity，节点稀疏，纵向偏下的小图片控件
            if (snaps.size <= SPARSE_NODES && s.clickable &&
                s.rect.exactCenterY() > screenH * 0.60f &&
                s.rect.width() <= screenW * 0.12f
            ) {
                lastPopupDebug = "popup-hit E2 nodes=${snaps.size}"
                return PopupHit(s.node, "弹窗角落按钮")
            }
        }
        lastPopupDebug = "popup-no-match nodes=${snaps.size} texts=${texts.size}" +
            " card=${if (cardOk) "ok" else "no"} mk=${marketingTexts.size}"
        return null
    }

    /** 节点快照：一次 BFS 拿到 rect / 类名 / 文本 / 可点，避免反复跨进程读节点 */
    private class Snap(
        val node: AccessibilityNodeInfo,
        val rect: Rect,
        val cls: String,
        val text: String?,
        val clickable: Boolean,
    )

    private fun collectSnaps(root: AccessibilityNodeInfo, screenW: Int, screenH: Int): List<Snap> {
        val out = ArrayList<Snap>(POPUP_SCAN_NODES)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && out.size < POPUP_SCAN_NODES) {
            val (node, depth) = queue.removeFirst()
            val f = factsOf(node)
            val r = f.rect
            // 负宽高（滚动动画里的数码轮）与越界节点（WebView 常用 Integer.MIN_VALUE
            // 表示"未布局"）直接丢弃，否则会把包围盒拉爆
            if (!r.isEmpty && r.width() > 0 && r.height() > 0 &&
                r.left > -screenW && r.right < screenW * 2 &&
                r.top > -screenH && r.bottom < screenH * 2
            ) {
                out.add(
                    Snap(
                        node = node,
                        rect = Rect(r),
                        cls = f.cls,
                        text = f.label,
                        clickable = f.clickable,
                    )
                )
            }
            // 超过 MAX_DEPTH 不再往下钻：信息流列表嵌套极深，
            // 每多一层就是一轮跨进程读属性，而弹窗关闭钮不会埋在十几层以下
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }
        return out
    }

    /** 关闭候选的形状与位置校验；返回非空表示形状合格（内容为尺寸描述） */
    private fun closeCandidate(s: Snap, w: Int, h: Int): String? {
        val cw = s.rect.width().toFloat()
        val ch = s.rect.height().toFloat()
        if (cw <= 0f || ch <= 0f) return null
        if (cw > w * 0.14f || ch > h * 0.08f) return null
        if (cw < w * 0.02f || ch < h * 0.008f) return null
        val cx = s.rect.exactCenterX()
        val cy = s.rect.exactCenterY()
        if (cx < w * 0.28f || cx > w * 0.92f) return null
        if (cy < h * 0.18f || cy > h * 0.88f) return null
        if (NOT_CLOSE_CLS.any { s.cls.contains(it) }) return null
        val imageLike = s.cls.contains("ImageView") || s.cls.contains("ImageButton") ||
            s.cls == "android.view.View" || s.cls.endsWith(".View")
        val t = s.text
        val textClose = t != null && CLOSE_WORDS.any { t.contains(it, ignoreCase = true) }
        if (!s.clickable && !textClose) return null
        if (!imageLike && !textClose) return null
        return "${cw.toInt()}x${ch.toInt()}"
    }

    /** 候选是否贴着卡片边缘：内部控件不点，避免点到卡片里的收藏、加购图标 */
    private fun edgeOfCard(r: Rect, card: Rect, w: Int, h: Int): Boolean {
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        val inRangeX = cx > card.left - w * 0.06f && cx < card.right + w * 0.06f
        val inRangeY = cy > card.top - h * 0.04f && cy < card.bottom + h * 0.10f
        if (!inRangeX || !inRangeY) return false
        val nearEdge = Math.abs(cx - card.left) < w * 0.12f ||
            Math.abs(cx - card.right) < w * 0.12f ||
            Math.abs(cy - card.top) < h * 0.12f ||
            Math.abs(cy - card.bottom) < h * 0.12f
        return nearEdge
    }

    private fun unionRect(rects: List<Rect>): Rect? {
        if (rects.isEmpty()) return null
        val out = Rect(rects[0])
        for (i in 1 until rects.size) out.union(rects[i])
        return out
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
        const val CORNER_SCAN_NODES = 400

        /** 负向关键词：出现即判定不是广告跳过按钮（宁可少点，不能误点引导页） */
        val NEGATIVE_HINTS = listOf(
            "教程", "引导", "新手", "跳过此步", "跳过本",
            "tutorial", "guide", "walkthrough",
        )

        // ———————— 弹窗（应用内覆盖层） ————————

        const val POPUP_SCAN_NODES = 500

        /** 遍历深度上限，控制每次扫描的跨进程调用次数 */
        const val MAX_DEPTH = 14

        /** 节点数不超过这个值的窗口才按"独立弹窗 Activity"处理 */
        const val SPARSE_NODES = 60

        /** 纯数字/符号文本不参与卡片包围盒（价格、倒计时、数码轮） */
        val NUMBER_ONLY = Regex("^[\\d.\\s¥￥:：]+$")

        /** 关闭类文案：出现即视为按钮文字 */
        val CLOSE_WORDS = listOf(
            "关闭", "close", "×", "✕", "✖",
            "我知道了", "知道了", "以后再说", "下次再说",
            "暂不", "暂不需要", "先逛逛", "残忍拒绝", "不再提示",
        )

        /** 强关闭信号：短文本命中即直接点，不再依赖卡片证据 */
        val STRONG_CLOSE_WORDS = listOf("关闭", "close", "×", "✕", "✖")

        /** 营销文案：卡片里没有这些词，就不是营销弹窗，不点 */
        val MARKETING_WORDS = listOf(
            "恭喜", "获得", "已存入", "领取", "开心收下", "签到", "红包",
            "抽奖", "膨胀", "礼包", "福利", "奖励", "无门槛", "新人专享", "免费领",
        )

        /** 业务弹窗排除词：登录、支付、授权、隐私这类弹窗点掉会打断正常操作 */
        val POPUP_BUSINESS_WORDS = listOf(
            "登录", "注册", "授权", "同意", "隐私", "协议", "密码", "验证码",
            "支付", "付款", "确认", "删除", "退出", "卸载",
        )

        /** 这些控件不可能是关闭按钮 */
        val NOT_CLOSE_CLS = listOf(
            "EditText", "SeekBar", "ProgressBar", "RecyclerView", "ListView",
            "ScrollView", "ViewPager", "WebView",
        )
    }
}
