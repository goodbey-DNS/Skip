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

    /**
     * 服务连接事件单独写一个文件。
     *
     * 常规落盘只有 3×300 行，`scan` 一秒几条，一次冷启动就能把连接记录顶掉。
     * 2026-11-03 排查「容器广告为什么漏跳」时，正好卡在这一行被冲掉上，
     * 只能靠推断说「服务重连过」。连接是低频事件，单独留档成本可以忽略。
     */
    private const val CONNECT_FILE = "connect.log"

    /** 连接日志保留多少行 */
    private const val CONNECT_KEEP = 60

    /**
     * 结论日志：只留「有过决策」的行，单独一份文件。
     *
     * 滚动日志是 3×300 行，一次 App 冷启动的 scan 就能写掉一百多行 ——
     * 上午跳过成功的那条，下午就被噪音冲没了。而用户打开诊断想知道的往往是
     * 「刚才（甚至昨天）为什么没跳过」。结论行一天也就几十条，单独留一份成本很低。
     */
    private const val DECISION_FILE = "decision.log"

    /** 结论日志按字节裁剪，超过这个大小就只留最近 [DECISION_KEEP] 行 */
    private const val DECISION_MAX_BYTES = 64 * 1024

    private const val DECISION_KEEP = 240

    /** 哪些行算「结论」：用户真正关心的、能回答「跳没跳过」的那些 */
    private val DECISION_PREFIXES = arrayOf(
        "verified ", "giveup ", "landing ", "off-app ", "userfree-skip ", "demote-page-skip ",
        "miss ", "hot-close ", "hot-skip ",
        "capture nodes=", "capture-empty", "capture-fail", "snapshot saved ",
        "service-connected ", "service-unbind",
    )

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
        val stamped = fileFmt.format(now) + " " + msg
        appendToFile(stamped)
        // 结论行额外留一份：滚动日志很快会把它们冲掉，而这一份是给「诊断」页读的
        if (isDecision(msg)) appendDecision(stamped)
    }

    private fun isDecision(msg: String): Boolean {
        for (p in DECISION_PREFIXES) if (msg.startsWith(p)) return true
        return false
    }

    /** 追加到结论日志；文件太大时裁剪到最近 [DECISION_KEEP] 行 */
    private fun appendDecision(line: String) {
        val d = dir ?: return
        synchronized(ioLock) {
            try {
                val f = File(d, DECISION_FILE)
                f.appendText(line + "\n")
                if (f.length() > DECISION_MAX_BYTES) {
                    val kept = f.readLines().takeLast(DECISION_KEEP)
                    f.writeText(kept.joinToString("\n") + "\n")
                }
            } catch (e: Exception) {
                // 诊断层失败不影响主流程
            }
        }
    }

    /** 结论日志的全部行（时间升序）；没有文件时返回空 */
    fun decisionLines(): List<String> {
        val d = dir ?: return emptyList()
        val f = File(d, DECISION_FILE)
        if (!f.isFile) return emptyList()
        return runCatching { f.readLines() }.getOrDefault(emptyList())
    }

    /**
     * 记一次服务连接。除了走常规落盘，还单独写 [CONNECT_FILE]。
     *
     * 单独留档的理由：连接记录必须比 scan 日志活得久，否则排查「预热期漏跳」
     * 时永远看不到它。文件只保留最近 [CONNECT_KEEP] 行，每次重写，代价可忽略。
     */
    @Synchronized
    fun addConnect(msg: String) {
        add(msg)
        val d = dir ?: return
        try {
            val f = File(d, CONNECT_FILE)
            val old = if (f.exists()) runCatching { f.readLines() }.getOrDefault(emptyList()) else emptyList()
            val out = (old + fileFmt.format(Date()) + " " + msg).takeLast(CONNECT_KEEP)
            f.writeText(out.joinToString("\n") + "\n")
        } catch (e: Exception) {
            // 诊断层失败不影响主流程
        }
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

    /** 是不是滚动日志文件：排除连接留档，否则它会被当成序号 0 的老文件删掉 */
    private fun isRollingTrace(f: File): Boolean =
        f.isFile && f.name.endsWith(".log") && f.name != CONNECT_FILE && f.name != DECISION_FILE

    /** 落盘目录里现有的文件，按文件名（即序号）升序 */
    fun files(): List<File> {
        val d = dir ?: return emptyList()
        return runCatching {
            d.listFiles()?.filter { isRollingTrace(it) }
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
        files().forEach { f ->
            sb.append("===== ").append(f.name).append(" =====\n")
            runCatching { sb.append(f.readText()) }
            sb.append('\n')
        }
        dir?.let { d ->
            val c = File(d, CONNECT_FILE)
            if (c.exists()) {
                sb.append("===== ").append(CONNECT_FILE).append(" =====\n")
                runCatching { sb.append(c.readText()) }
                sb.append('\n')
            }
            // 结论日志单独一段：它就是「诊断」页读的那份，贴出去时最有用
            val dec = File(d, DECISION_FILE)
            if (dec.exists()) {
                sb.append("===== ").append(DECISION_FILE).append(" =====\n")
                runCatching { sb.append(dec.readText()) }
                sb.append('\n')
            }
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
        val all = d.listFiles()?.filter { isRollingTrace(it) } ?: return
        if (all.size <= FILE_KEEP) return
        all.sortedBy { it.name.removeSuffix(".log").toIntOrNull() ?: 0 }
            .dropLast(FILE_KEEP)
            .forEach { runCatching { it.delete() } }
    }
}
