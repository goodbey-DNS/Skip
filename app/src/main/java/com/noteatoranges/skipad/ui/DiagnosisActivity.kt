package com.noteatoranges.skipad.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.core.Diagnosis
import com.noteatoranges.skipad.core.EngineTrace
import com.noteatoranges.skipad.util.AppLabels
import com.noteatoranges.skipad.util.Ui
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断页：把引擎痕迹翻译成「刚才发生了什么」。
 *
 * 维护页原来的「引擎诊断」直接把 `verified` / `no-ad-evidence` 这类英文短码甩出来，
 * 对不看代码的人等于没有信息。这一页默认只出结论，原始日志收到右上角按钮后面 ——
 * 要贴给开发看的时候仍然拿得到原文。
 */
class DiagnosisActivity : AppCompatActivity() {

    private lateinit var timeline: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var summaryText: TextView
    private lateinit var summarySubText: TextView

    private var fromDisk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnosis)
        Ui.edgeToEdge(this, findViewById(R.id.inset_top), findViewById(R.id.inset_bottom))
        EngineTrace.init(this)

        timeline = findViewById(R.id.timeline_container)
        emptyText = findViewById(R.id.empty_text)
        summaryText = findViewById(R.id.summary_text)
        summarySubText = findViewById(R.id.summary_sub_text)

        findViewById<TextView>(R.id.back_button).setOnClickListener { finish() }
        findViewById<TextView>(R.id.raw_button).setOnClickListener { showRawLog() }

        loadAsync()
    }

    private fun loadAsync() {
        val appContext = applicationContext
        Thread {
            // 三处来源合并，而不是挑一处：
            // 只读结论日志会漏 —— 它由服务进程写，UI 进程打开时可能还没读到最新几行，
            // 实测出现过「日志里有 verified，页面却是空的」。合并后再去重，哪一处有都不丢。
            val merged = collectLines()
            val labelOf = labeler(appContext)
            val events = Diagnosis.explain(merged, labelOf)
            runOnUiThread {
                fromDisk = EngineTrace.lines().isEmpty() && merged.isNotEmpty()
                render(events)
            }
        }.start()
    }

    /**
     * 合并结论日志、滚动日志、内存记录，统一成落盘时间戳格式后去重排序。
     *
     * 内存行只有 `HH:mm:ss.SSS`，没有日期，直接和时间戳完整的行放一起没法排序，
     * 所以先补上当天的 `MM-dd`。同一事件在三个来源里各有一份，补完之后字符串一致，
     * [distinct] 就能把它们收成一条。
     */
    private fun collectLines(): List<String> {
        val today = SimpleDateFormat("MM-dd", Locale.ROOT).format(Date())
        val out = ArrayList<String>(900)
        out.addAll(lastDiskLines())
        out.addAll(EngineTrace.decisionLines())
        for (line in EngineTrace.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            // 第 3 个字符是冒号 = 内存格式，缺日期
            out.add(if (t.length > 3 && t[2] == ':') "$today $t" else t)
        }
        return out.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    /** 最近一份落盘日志的尾部；没有就返回空列表 */
    private fun lastDiskLines(): List<String> {
        val f: File = EngineTrace.files().lastOrNull() ?: return emptyList()
        return runCatching { f.readLines().takeLast(200) }.getOrDefault(emptyList())
    }

    private fun labeler(context: Context): (String) -> String {
        val appContext = context.applicationContext
        return { pkg ->
            if (pkg.isBlank()) pkg else AppLabels.get(appContext, pkg).label
        }
    }

    private fun render(events: List<Diagnosis.Event>) {
        // 一条记录都没有时，概览卡是空的，留着只会让人以为页面没加载完
        findViewById<android.view.View>(R.id.summary_card).visibility =
            if (events.isEmpty()) View.GONE else View.VISIBLE
        renderSummary(events)

        // 时间线倒序：最新的在最上面，和用户的心智顺序一致。
        // 连接成功是常态，不进时间线；它只在概览里占一行，否则每次服务重连都刷一屏。
        val filtered = ArrayList<Diagnosis.Event>(events.size)
        for (e in events.asReversed()) {
            // 服务连接/断开不进时间线：概览里已经有一行当前状态，断开通常几秒就自动恢复，
            // 列在下面只会把「跳过成功」挤下去，让人以为出了大问题
            if (e.kind == Diagnosis.Kind.SERVICE) continue
            // 相邻重复只留一条：一次重连会连打两三条断开/连接，全列出来只是噪音。
            // 必须带上时间窗 —— 同一个 App 隔五分钟跳了两次，是两条独立的记录，不能合并
            val prev = filtered.lastOrNull()
            if (prev != null && prev.title == e.title && prev.tone == e.tone &&
                kotlin.math.abs(secOf(prev.time) - secOf(e.time)) <= DEDUPE_WINDOW_SEC
            ) continue
            filtered.add(e)
        }
        val shown = filtered.take(MAX_ROWS)
        timeline.removeAllViews()
        if (shown.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            emptyText.text = getString(R.string.diag_timeline_empty)
            return
        }
        emptyText.visibility = View.GONE
        for (e in shown) {
            val row = layoutInflater.inflate(R.layout.item_diagnosis, timeline, false)
            row.findViewById<TextView>(R.id.time_text).text = e.time
            val dot = row.findViewById<TextView>(R.id.dot_text)
            dot.setTextColor(getColor(when (e.tone) {
                Diagnosis.Tone.OK -> R.color.status_on
                Diagnosis.Tone.WARN -> R.color.status_off
                Diagnosis.Tone.INFO -> R.color.text_secondary
            }))
            row.findViewById<TextView>(R.id.title_text).text = e.title
            val detail = row.findViewById<TextView>(R.id.detail_text)
            if (e.detail.isBlank()) {
                detail.visibility = View.GONE
            } else {
                detail.text = e.detail
            }
            timeline.addView(row)
        }
    }

    /** `HH:mm:ss` → 当天秒数；解析不了给 0，折叠判断会退化成「同一秒」 */
    private fun secOf(time: String): Int {
        val p = time.split(':')
        if (p.size != 3) return 0
        return (p[0].toIntOrNull() ?: 0) * 3600 +
            (p[1].toIntOrNull() ?: 0) * 60 +
            (p[2].toIntOrNull() ?: 0)
    }

    private fun renderSummary(events: List<Diagnosis.Event>) {
        if (events.isEmpty()) {
            summaryText.text = "还没有记录"
            summarySubText.text = if (fromDisk) "下面是上次落盘留下的内容。" else ""
            return
        }
        val last = Diagnosis.lastSkip(events)
        summaryText.text = if (last != null) {
            "最近一次跳过：${last.title.removeSuffix(" 跳过成功")}（${last.time}）"
        } else {
            "这段时间还没有成功跳过过广告"
        }

        val lines = ArrayList<String>(3)
        if (fromDisk) lines.add("App 重启过，下面是重启前留下的记录")
        Diagnosis.lastService(events)?.let { s ->
            lines.add(
                if (s.tone == Diagnosis.Tone.OK) "无障碍服务：运行中（${s.time}）"
                else "无障碍服务：已断开（${s.time}），通常几秒内会自动重连"
            )
        }
        val t = Diagnosis.tally(events)
        if (t.ok > 0 || t.warn > 0) {
            lines.add("这些记录里：顺利跳过 ${t.ok} 次 · 没跳成 ${t.warn} 次")
        }
        summarySubText.text = lines.joinToString("\n")
    }

    /**
     * 原始日志：保持完整的原文，不是诊断页这份筛过的。
     *
     * 排查时 `grep verified`、看 `scan ... reject=` 的上下文都要靠它；
     * 用户要把问题贴给别人时，原文也比转述可靠。
     */
    private fun showRawLog() {
        val lines = EngineTrace.lines().ifEmpty { lastDiskLines() }
        if (lines.isEmpty()) {
            Toast.makeText(this, "还没有记录", Toast.LENGTH_SHORT).show()
            return
        }
        val text = lines.asReversed().joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("原始日志（最近 ${lines.size} 条）")
            .setMessage(text)
            .setPositiveButton("好", null)
            .setNeutralButton("复制全部") { _, _ -> copyToClipboard(text) }
            .show()
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("大小姐 引擎诊断", text))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "复制失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        /** 一屏也就看十几条，留多了只会让首屏渲染变慢 */
        const val MAX_ROWS = 120

        /** 相隔多少秒以内的同文案才折叠：低于这个间隔基本是同一次操作打出的多条 */
        const val DEDUPE_WINDOW_SEC = 3
    }
}
