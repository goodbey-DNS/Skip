package com.noteatoranges.skipad.core

/**
 * 开屏记账的两块纯逻辑（0.17.60）。
 *
 * 抽出来只有一个原因：这两条判据都**依赖时间序**，而时间序在真机和受控 App 上都
 * 很难稳定复现（第二段广告早 200ms 或晚 200ms，结果就不同）。放在服务里只能靠
 * 日志碰运气，放在这里可以在 JVM 上把边界一条条钉死。
 *
 * 两个类都不碰 Android API，也不做线程同步——调用方约定单线程使用
 * （服务里是 Io 单线程 + 主线程各自持有其中之一）。
 */

/**
 * 「过渡页回撤」：判断刚写进统计的上一条是不是本该撤掉的过渡页记录。
 *
 * 场景（design.md 第 18 条）：启动页自带的「跳过」先被点掉并记账，紧接着真正的
 * 广告页出现又被点掉。前一条不是广告，不该留在「已跳过广告」里。
 *
 * 判据只有两条：**上一条是弱证据**（点的时候页面上没有任何广告迹象），
 * 且**两条间隔不超过 [windowMs]**。强证据一律不撤——宁可多记一次，
 * 也不把真广告从统计里抹掉。
 */
class TransitionDemoter(private val windowMs: Long) {

    private class Last(val id: Long, val at: Long, val weak: Boolean)

    private val last = HashMap<String, Last>()

    /**
     * 上一条该不该撤。返回它的行 id；`0` 表示不该撤（没有上一条、上一条是强证据、
     * 或已经超出时间窗）。
     */
    fun demoteTarget(pkg: String, now: Long): Long {
        val prev = last[pkg] ?: return 0L
        if (!prev.weak) return 0L
        val age = now - prev.at
        // age < 0 说明时钟回拨（理论上不会发生），按「不撤」处理
        return if (age in 0..windowMs) prev.id else 0L
    }

    /** 记下刚写入的这一条 */
    fun record(pkg: String, id: Long, at: Long, weak: Boolean) {
        last[pkg] = Last(id, at, weak)
    }

    fun clear(pkg: String) {
        last.remove(pkg)
    }

    fun clearAll() {
        last.clear()
    }
}

/**
 * 「空窗页」学习（design.md 第 21 条）。
 *
 * 续窗落在主界面时，四秒窗口全在空扫。判「这是主界面」没有可靠依据，所以改成事后学：
 * 同一个页面作为续窗落点、连续 [threshold] 次整轮零命中，才不再为它开续窗。
 *
 * 一次不学，是因为第二段广告可能只是那次没填充；命中一次就 [forget]，
 * 把可能学错的东西立刻推翻。
 */
class PlainActTracker(private val threshold: Int) {

    private val hits = HashMap<String, Int>()
    private val plain = HashSet<String>()

    fun isPlain(pkg: String, act: String): Boolean =
        act.isNotEmpty() && key(pkg, act) in plain

    /**
     * 记一次「续窗落在这个页面上、整轮什么都没扫到」。
     * 返回是否**刚刚**达到阈值（调用方据此打一条日志）。
     *
     * 类名不可用时直接拒绝：部分 App 的窗口事件只给控件类名，`lastActivityClass`
     * 会是 `android.widget.FrameLayout`。拿它当键，等于把该 App 所有页面
     * 共用一个「空窗」标记，误学一次就全废。
     */
    fun learn(pkg: String, act: String): Boolean {
        if (!usableAct(act)) return false
        val k = key(pkg, act)
        if (k in plain) return false
        val n = (hits[k] ?: 0) + 1
        hits[k] = n
        if (n >= threshold) {
            plain.add(k)
            return true
        }
        return false
    }

    /** 这个页面确实点到了东西：之前的空窗记录作废 */
    fun forget(pkg: String, act: String) {
        if (act.isEmpty()) return
        val k = key(pkg, act)
        hits.remove(k)
        plain.remove(k)
    }

    /** 当前空窗计数，只给日志用 */
    fun countOf(pkg: String, act: String): Int = hits[key(pkg, act)] ?: 0

    private fun key(pkg: String, act: String) = "$pkg/$act"
}

/**
 * 类名能不能当「页面」用：Activity 都是 `包名.类名`，控件的类名是 `android.widget.*`。
 */
fun usableAct(act: String): Boolean =
    act.length > 3 && act.contains('.') &&
        !act.startsWith("android.") && !act.startsWith("java.")
