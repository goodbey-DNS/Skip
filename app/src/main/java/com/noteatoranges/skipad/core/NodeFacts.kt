package com.noteatoranges.skipad.core

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.LinkedHashMap

/**
 * 单个节点的属性快照。
 *
 * 无障碍节点属性都是跨进程（Binder）读取：`node.text`、`node.className`、
 * `getBoundsInScreen()` 每一次都是一趟 IPC。一次扫描里开屏通道和弹窗通道
 * 各走一遍树，同一个节点的同一属性会被读两遍——这层把读取结果记下来，
 * 第二次直接命中内存。
 *
 * 每个属性按需读取（lazy），因为不同算法要的字段不一样：开屏通道只读
 * text/desc/viewId，角落兜底才读 clickable/visible/bounds，不该为它们付钱。
 */
class NodeFacts(val node: AccessibilityNodeInfo) {

    private var rectValue: Rect? = null
    private var clsValue: String? = null
    private var vidValue: String? = null
    private var vidRead = false
    private var textValue: String? = null
    private var textRead = false
    private var descValue: String? = null
    private var descRead = false
    private var clickableValue: Boolean? = null
    private var visibleValue: Boolean? = null

    val rect: Rect
        get() = rectValue ?: Rect().also { node.getBoundsInScreen(it) }.also { rectValue = it }

    val cls: String
        get() = clsValue ?: (node.className?.toString() ?: "").also { clsValue = it }

    /** 空白文本按"没有文本"处理，避免拿空格去匹配 */
    val text: String?
        get() {
            if (!textRead) {
                textValue = node.text?.toString()?.takeIf { it.isNotBlank() }
                textRead = true
            }
            return textValue
        }

    val desc: String?
        get() {
            if (!descRead) {
                descValue = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                descRead = true
            }
            return descValue
        }

    val vid: String?
        get() {
            if (!vidRead) {
                vidValue = node.viewIdResourceName
                vidRead = true
            }
            return vidValue
        }

    val clickable: Boolean
        get() = clickableValue ?: node.isClickable.also { clickableValue = it }

    val visible: Boolean
        get() = visibleValue ?: node.isVisibleToUser.also { visibleValue = it }

    /** text 优先，无 text 用 contentDescription（图片型按钮常只有 desc） */
    val label: String?
        get() = text ?: desc
}

/**
 * 一次界面事件内的节点属性缓存（主线程使用，不加锁）。
 *
 * 生命周期由服务控制：换 App、换页面、验证结束都清掉——无障碍节点失效后
 * 缓存里的矩形和文本都会过期，宁可多读一次也不能拿旧数据点错位置。
 */
class NodeFactsCache(
    private val maxEntries: Int = MAX_ENTRIES,
    /** 缓存有效期：无障碍节点失效后属性就不再可信，超过这个年龄整份丢弃 */
    private val ttlMs: Long = 1200L,
) {

    private val map = object : LinkedHashMap<AccessibilityNodeInfo, NodeFacts>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AccessibilityNodeInfo, NodeFacts>) =
            size > maxEntries
    }

    private var bornAt = SystemClock.elapsedRealtime()

    var hits = 0
        private set
    var misses = 0
        private set

    fun of(node: AccessibilityNodeInfo): NodeFacts {
        if (SystemClock.elapsedRealtime() - bornAt > ttlMs) clear()
        map[node]?.let {
            hits++
            return it
        }
        misses++
        return NodeFacts(node).also { map[node] = it }
    }

    fun clear() {
        map.clear()
        bornAt = SystemClock.elapsedRealtime()
        hits = 0
        misses = 0
    }

    val size: Int get() = map.size

    companion object {
        /** 一台 1080p 手机的界面通常 300~900 个节点，留一倍余量够两个通道共用 */
        const val MAX_ENTRIES = 1500
    }
}
