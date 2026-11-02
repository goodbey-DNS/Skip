package com.noteatoranges.skipad.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * 开屏页右上角小按钮的启发式定位。
 *
 * 存在的理由：很多开屏广告的"跳过"既没有文字也没有 skip 类 id —— 它是广告 SDK
 * 自己画的一个图标，或者文案是"3"这种倒计时数字。词表和 viewId 两条路都够不着，
 * 但它在屏幕上有一个非常稳定的形态：**右上角、小、可点击**。
 *
 * 这是本项目里唯一一条「靠位置猜」的规则，所以判据收得很紧，宁可漏：
 * - 只在开屏窗口内被调用（调用方保证，窗口外一次都不跑）；
 * - 必须真的可点击（自身或最近的可点祖先）；
 * - 面积占屏 0.15%~3.5%、宽高比 0.5~4.5，排掉全屏热区和细长横栏；
 * - 中心落在宽 72% 右侧、高 22% 上方，且不压状态栏；
 * - 文案超过 8 个字的一律不算，含「更多/菜单/搜索/分享」等语义的直接排除；
 * - 每个 App 每次切前台最多点一次（由调用方的 CORNER_RULE.maxCount 兜底）。
 *
 * 属性读取走 [NodeFactsCache]：热窗口里每 120ms 扫一次，同一个页面要过三十多遍，
 * 每次都直接读 node.text / isClickable 就是三十多轮 Binder 往返。
 */
object CornerSkipFinder {

    fun find(
        root: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int,
        cache: NodeFactsCache? = null,
    ): AccessibilityNodeInfo? {
        if (screenW <= 0 || screenH <= 0) return null
        val screenArea = screenW.toFloat() * screenH
        val minArea = screenArea * MIN_AREA_RATIO
        val maxArea = screenArea * MAX_AREA_RATIO
        val rightEdge = screenW * RIGHT_RATIO
        val bottomEdge = screenH * TOP_RATIO
        val minTop = screenH * STATUS_BAR_RATIO

        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            visited++
            val node = queue.removeFirst()
            // 节点在遍历中途失效时，childCount / isScrollable / rect / text 任一读取都会抛。
            // 单节点读失败不该让整页放弃：另起一个 try 把本节点跳过去，下一个节点照样能判。
            // 外层（调用方）也包了一层，这里是为了不让一个坏节点吞掉整页的判定机会。
            try {
                val count = runCatching { node.childCount }.getOrDefault(0)
                for (i in 0 until count) {
                    runCatching { node.getChild(i) }.getOrNull()?.let(queue::add)
                }

                val f = factsOf(node, cache)

                // 页面里存在「真的能滚动的东西」就整页放弃。开屏广告页是一整块静态画面，
                // 而 App 的正常页面（微博「我的」、商城首页、新手引导页）几乎都有可滚动容器。
                // 实测反例：微博「我的」页被当成开屏页，右上角的编辑按钮被点了。
                // 0.17.33 从「只认 RecyclerView/ListView/ScrollView」放宽到「任意 scrollable」：
                // GridView（九宫格页）、NestedScrollView（详情页）、ViewPager2（新手引导页）
                // 都是同一种形态，原来那三个类名一个都覆盖不到；而引导页右上角的「跳过」
                // 恰恰是纯图标按钮，文字排除表碰不到它，被点掉就是用户跳过新手引导。
                // 必须放在 EXCLUDED_CLASSES 之前：容器本身就在排除表里，先 continue 就查不到。
                if (runCatching { node.isScrollable }.getOrDefault(false)) return null

                if (f.cls in EXCLUDED_CLASSES) continue
                if (!f.visible) continue

                val label = f.label?.trim().orEmpty()
                if (label.length > MAX_TEXT_LEN) continue
                val lower = label.lowercase(Locale.ROOT)
                if (lower.isNotEmpty() && NEGATIVE.any { lower.contains(it) }) continue

                val target = if (f.clickable) node else clickableAncestor(node, cache) ?: continue
                val tf = if (target === node) f else factsOf(target, cache)

                val r = tf.rect
                if (r.isEmpty || r.width() <= 0 || r.height() <= 0) continue

                val area = r.width().toFloat() * r.height()
                if (area < minArea || area > maxArea) continue

                val aspect = r.width().toFloat() / r.height()
                if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) continue

                val cx = r.exactCenterX()
                val cy = r.exactCenterY()
                if (cx < rightEdge || cy > bottomEdge) continue
                if (r.top < minTop) continue

                val vid = tf.vid?.lowercase(Locale.ROOT).orEmpty()
                var score = 0
                if (vid.contains("skip")) score += 4
                if (vid.contains("close")) score += 2
                if (label.isNotEmpty()) score += 2
                if (area < maxArea * 0.3f) score += 1

                if (score > bestScore) {
                    bestScore = score
                    best = target
                }
            } catch (t: Throwable) {
                // 单个节点失效：跳过，下一个继续
            }
        }
        return best
    }

    private fun factsOf(node: AccessibilityNodeInfo, cache: NodeFactsCache?): NodeFacts =
        cache?.of(node) ?: NodeFacts(node)

    private fun clickableAncestor(node: AccessibilityNodeInfo, cache: NodeFactsCache?): AccessibilityNodeInfo? {
        var cur = node
        var hops = 0
        while (hops < ANCESTOR_HOPS) {
            val parent = runCatching { cur.parent }.getOrNull() ?: return null
            if (factsOf(parent, cache).clickable) return parent
            cur = parent
            hops++
        }
        return null
    }

    private const val MAX_NODES = 400
    private const val ANCESTOR_HOPS = 4

    /** 面积下限 0.15%：小于这个基本是图标碎屑，点不中也别点 */
    private const val MIN_AREA_RATIO = 0.0015f

    /** 面积上限 3.5%：超过它更像图片热区或整条横幅，不是跳过按钮 */
    private const val MAX_AREA_RATIO = 0.035f

    private const val RIGHT_RATIO = 0.72f
    private const val TOP_RATIO = 0.22f
    private const val STATUS_BAR_RATIO = 0.02f
    private const val MIN_ASPECT = 0.5f
    private const val MAX_ASPECT = 4.5f
    private const val MAX_TEXT_LEN = 8

    private val EXCLUDED_CLASSES = setOf(
        "android.widget.EditText",
        "android.webkit.WebView",
        "androidx.recyclerview.widget.RecyclerView",
        "android.widget.ListView",
        "android.widget.ScrollView",
        "android.widget.HorizontalScrollView",
        "androidx.viewpager.widget.ViewPager",
        "androidx.viewpager2.widget.ViewPager2",
    )

    /** 出现且可滚动，说明这是 App 的列表页面而不是开屏页 */

    /** 右上角经常出现的"非跳过"语义，命中即排除 */
    private val NEGATIVE = listOf(
        "更多", "菜单", "搜索", "分享", "设置", "个人", "我的", "消息", "通知",
        "客服", "反馈", "帮助", "登录", "注册",
        "more", "menu", "search", "share", "setting", "profile", "account",
        "notification", "help", "feedback", "login", "sign",
    )
}
