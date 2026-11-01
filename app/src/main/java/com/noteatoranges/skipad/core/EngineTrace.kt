package com.noteatoranges.skipad.core

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 引擎运行痕迹：进程内滚动记录 + 落盘环形日志。
 *
 * 为什么需要它：引擎的全部诊断（开屏窗口开合、扫描结果、命中/放弃、异常）
 * 原本只写 logcat。但部分 ROM 会把所有日志缓冲区清空 —— 实测某台 Android 17
 * 设备上 `adb logcat -g` 显示每个缓冲区都是 `0 B readable`，logcat 读出来空空如也。
 * 那种机器上一旦出现「该跳的时候不跳」，外部就完全没有取证手段。
 *
 * 0.17.29 起多了一层落盘：内存里的 400 条只管「刚才那一下为什么没跳」，
 * 落盘的三份文件管「今天一整天到底扫到了什么」。原因很实际 —— 读内存记录必须
 * 打开 App，而打开 App 前的任何界面转储（uiautomator dump）都会抢走无障碍通道、
 * 让服务重建实例，内存记录在看见之前就没了。有了文件，事后两天再翻也翻得到。
 *
 * 写盘策略：追加 + 每条 flush。一条几十字节，只追加不重写，代价是每次一次 write
 * 系统调用；无障碍回调里本来就有统计库的磁盘写入，多这一笔不改变量级。
 * 写失败一律吞掉 —— 诊断层不能反过来把主流程搞挂。
 */
object EngineTrace {

    /** 内存里留多少条。按内容变化节流 600ms 算，足够覆盖最近几分钟的决策过程 */
    private const val CAPACITY = 400

    /** 单个文件最多多少行，满了开新文件 */
    private const val FILE_LINES = 300

    /** 落盘保留几份 */
    private const val FILE_KEEP = 3

    /** 落盘目录（filesDir 下） */
    private const val DIR = "engine"

    private val buf = ArrayDeque<String>(CAPACITY)

    /** 落盘文件用的时间戳带日期：事后翻日志第一件事就是确认是哪天 */
    private val fileFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    /** 内存记录仍用短时间戳，界面上省宽度 */
    private val memFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    @Volatile
    private var dir: File? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var writtenInFile = 0

    @Volatile
    private var seq = 0

    private val ioLock = Any()

    /**
     * 初始化落盘目录。幂等，可以在服务与界面里重复调用。
     *
     * 之所以要显式 init 而不是自己找 Context：这是 core 包里的 object，
     * 拿不到 Context；Application 级初始化又会让「没启动过服务」的场景也建目录。
     */
    fun init(context: Context) {
        if (dir != null) return
        synchronized(ioLock) {
            if (dir != null) return
            val d = File(context.filesDir, DIR)
            if (!d.exists()) d.mkdirs()
            if (!d.isDirectory) return
            // 接着上次的序号往下写，避免重启后覆盖旧文件
            seq = (d.listFiles()
                ?.mapNotNull { it.name.removeSuffix(".log").toIntOrNull() }
                ?.maxOrNull() ?: 0)
            dir = d
            trimLocked(d)
        }
    }

    /** 记一条。高频路径上调用，不加锁之外的任何开销 */
    @Synchronized
    fun add(msg: String) {
        val now = Date()
        if (buf.size >= CAPACITY) buf.removeFirst()
        buf.addLast(memFmt.format(now) + " " + msg)
        appendToFile(fileFmt.format(now) + " " + msg)
    }

    /** 当前内存里的全部痕迹，时间升序 */
    @Synchronized
    fun lines(): List<String> = buf.toList()

    @Synchronized
    fun isEmpty(): Boolean = buf.isEmpty()

    /**
     * 清空内存记录。
     *
     * 不删落盘文件 —— 界面上写的是「只清这份内存记录」，删文件就是另一件事了。
     * 要清文件有独立入口（见 [clearFiles]）。
     */
    @Synchronized
    fun clear() {
        buf.clear()
    }

    /** 清掉落盘日志（含关闭当前写句柄） */
    fun clearFiles() {
        synchronized(ioLock) {
            runCatching { writer?.close() }
            writer = null
            writtenInFile = 0
            val d = dir ?: return
            runCatching { d.listFiles()?.forEach { it.delete() } }
            seq = 0
        }
    }

    /** 落盘目录里现有的文件，按文件名（即序号）升序 */
    fun files(): List<File> {
        val d = dir ?: return emptyList()
        return runCatching {
            d.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }
                ?.sortedBy { it.name.removeSuffix(".log").toIntOrNull() ?: 0 }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 落盘日志总字节数，给维护页显示占用 */
    fun diskBytes(): Long {
        val d = dir ?: return 0L
        return runCatching { d.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)
    }

    /**
     * 导出用文本：落盘的三份 + 当前内存里尚未落盘的尾巴。
     *
     * 内存尾部单独拼上，是因为刚发生的事可能还在内存里（写盘失败时尤其如此），
     * 导出时不能把它漏掉。
     */
    fun exportText(): String {
        val sb = StringBuilder()
        val fs = files()
        for (f in fs) {
            sb.append("===== ").append(f.name).append(" =====\n")
            runCatching { sb.append(f.readText()) }
            sb.append('\n')
        }
        val mem = synchronized(this) { buf.toList() }
        if (mem.isNotEmpty()) {
            sb.append("===== 内存（未落盘）=====\n")
            mem.forEach { sb.append(it).append('\n') }
        }
        return sb.toString()
    }

    private fun appendToFile(line: String) {
        val d = dir ?: return
        try {
            synchronized(ioLock) {
                if (writtenInFile >= FILE_LINES) rotateLocked(d)
                val w = writer ?: rotateLocked(d)
                w.append(line).append('\n')
                w.flush()
                writtenInFile++
            }
        } catch (e: Exception) {
            // 写盘失败就退回纯内存模式：诊断能力降级，但主流程不受影响
            runCatching { writer?.close() }
            writer = null
        }
    }

    /** 换一个新文件继续写（调用方需持有 ioLock） */
    private fun rotateLocked(d: File): BufferedWriter {
        runCatching { writer?.close() }
        seq++
        val f = File(d, "$seq.log")
        val w = f.bufferedWriter()
        writer = w
        writtenInFile = 0
        trimLocked(d)
        return w
    }

    /** 只留最新 [FILE_KEEP] 份。删除最老的，序号不重置 */
    private fun trimLocked(d: File) {
        val all = d.listFiles()?.filter { it.isFile && it.name.endsWith(".log") } ?: return
        if (all.size <= FILE_KEEP) return
        all.sortedBy { it.name.removeSuffix(".log").toIntOrNull() ?: 0 }
            .dropLast(FILE_KEEP)
            .forEach { runCatching { it.delete() } }
    }
}
