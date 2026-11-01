package com.noteatoranges.skipad.core

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 引擎运行痕迹：进程内的滚动记录。
 *
 * 为什么需要它：引擎的全部诊断（开屏窗口开合、扫描结果、命中/放弃、异常）
 * 原本只写 logcat。但部分 ROM 会把所有日志缓冲区清空 —— 实测某台 Android 17
 * 设备上 `adb logcat -g` 显示每个缓冲区都是 `0 B readable`，logcat 读出来空空如也。
 * 那种机器上一旦出现「该跳的时候不跳」，外部就完全没有取证手段。
 *
 * 所以真相要留在 App 自己能读到的地方：最近若干条决策痕迹存在内存里，
 * 用户在「设置 → 数据与维护 → 引擎诊断」里能直接看，不需要连电脑。
 *
 * 只在内存里、进程结束即消失 —— 要回答的是「刚才那一下为什么没跳」，
 * 不是跨重启的历史审计。上限 [CAPACITY] 条，每条几十字节，开销可以忽略。
 */
object EngineTrace {

    /** 留多少条。按内容变化节流 600ms 算，足够覆盖最近几分钟的决策过程 */
    private const val CAPACITY = 400

    private val buf = ArrayDeque<String>(CAPACITY)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    /** 记一条。高频路径上调用，不加锁之外的任何开销 */
    @Synchronized
    fun add(msg: String) {
        if (buf.size >= CAPACITY) buf.removeFirst()
        buf.addLast(fmt.format(Date()) + " " + msg)
    }

    /** 当前全部痕迹，时间升序 */
    @Synchronized
    fun lines(): List<String> = buf.toList()

    @Synchronized
    fun isEmpty(): Boolean = buf.isEmpty()

    @Synchronized
    fun clear() {
        buf.clear()
    }
}
