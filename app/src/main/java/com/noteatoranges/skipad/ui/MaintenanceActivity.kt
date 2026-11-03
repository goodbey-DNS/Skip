package com.noteatoranges.skipad.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.core.EngineTrace
import com.noteatoranges.skipad.db.StatsDb
import com.noteatoranges.skipad.util.AppLabels
import com.noteatoranges.skipad.util.Backup
import com.noteatoranges.skipad.util.Io
import com.noteatoranges.skipad.util.Ui
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 数据与维护二级页：内存占用、缓存清理、统计数据清空 */
class MaintenanceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maintenance)
        Ui.edgeToEdge(this, findViewById(R.id.inset_top), findViewById(R.id.inset_bottom))
        // 服务没起来时（用户刚装完就进来）也要能读到落盘日志
        EngineTrace.init(this)

        findViewById<TextView>(R.id.back_button).setOnClickListener { finish() }
        findViewById<Button>(R.id.clear_cache_button).setOnClickListener {
            AppLabels.clearCache()
            Toast.makeText(this, R.string.clear_mem_cache_done, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.clear_stats_button).setOnClickListener { confirmClearStats() }
        findViewById<Button>(R.id.export_backup_button).setOnClickListener { exportBackup() }
        findViewById<Button>(R.id.import_backup_button).setOnClickListener {
            // 选文件交给系统文件选择器（SAF），不申请存储权限
            pickBackup.launch(arrayOf("*/*"))
        }
        findViewById<TextView>(R.id.restore_latest_button).setOnClickListener {
            val latest = Backup.latestBackup(applicationContext)
            if (latest == null) {
                Toast.makeText(this, "backup 目录里还没有备份文件", Toast.LENGTH_SHORT).show()
            } else {
                confirmImport(latest, "最近备份 ${latest.name}")
            }
        }
        findViewById<Button>(R.id.engine_log_button).setOnClickListener { showEngineLog() }
        findViewById<Button>(R.id.engine_log_export_button).setOnClickListener { exportEngineLog() }
        findViewById<Button>(R.id.engine_log_clear_button).setOnClickListener { confirmClearEngineLog() }
    }

    /**
     * 引擎诊断：跳到诊断页，先用中文给结论。
     *
     * 这里原来是直接把 `verified` / `no-ad-evidence` 这类英文短码弹出来的，
     * 不看代码的人打开只看到一屏代号。翻译放在 [Diagnosis]，原始日志在诊断页右上角，
     * 要贴给开发看的时候仍然拿得到。
     */
    private fun showEngineLog() {
        startActivity(Intent(this, DiagnosisActivity::class.java))
    }

    /**
     * 导出落盘日志 + 内存尾巴。
     *
     * 这条路径是为「事后分析」准备的：读内存记录必须先打开 App，而任何界面转储
     * 都会抢走无障碍通道、让服务重建实例、把内存记录清空。要留证据就得先落成文件。
     */
    private fun exportEngineLog() {
        val text = EngineTrace.exportText()
        if (text.isBlank()) {
            Toast.makeText(this, "还没有记录可导出", Toast.LENGTH_SHORT).show()
            return
        }
        Io.run("engine-export") {
            val dir = File(applicationContext.filesDir, "engine-export").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
            val f = File(dir, "大小姐-engine-$ts.log")
            val ok = runCatching { f.writeText(text); true }.getOrDefault(false)
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, "导出失败：写文件出错", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                Toast.makeText(this, "已导出 ${f.name}（${(f.length() / 1024).coerceAtLeast(1)}KB）", Toast.LENGTH_SHORT).show()
                share(f, "text/plain")
            }
        }
    }

    private fun confirmClearEngineLog() {
        AlertDialog.Builder(this)
            .setTitle("清空引擎诊断？")
            .setMessage(
                "引擎记录分三份：内存（只管「刚才那一下」）、扫描过程（完整原文）、" +
                    "结论日志（诊断页显示的那份，能翻到前几天）。\n\n" +
                    "只清内存的话，诊断页仍会显示结论日志里留下的历史记录。\n" +
                    "不影响规则、快照和跳过统计。"
            )
            .setNegativeButton("取消", null)
            .setNeutralButton("只清内存") { _, _ ->
                EngineTrace.clear()
                Toast.makeText(this, "已清内存记录，落盘日志保留", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("全部清空") { _, _ ->
                EngineTrace.clear()
                EngineTrace.clearFiles()
                Toast.makeText(this, "已全部清空", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private val pickBackup = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) confirmImport(uri, uri.lastPathSegment ?: "选中的备份文件")
    }

    private fun confirmImport(file: java.io.File, label: String) {
        AlertDialog.Builder(this)
            .setTitle("从备份恢复")
            .setMessage(
                "来自：$label\n\n" +
                    "会覆盖自建规则与关键字（备份里没有的那类不动），快照按目录合并、不删现有。\n" +
                    "恢复前会先自动导出当前数据到 backup 目录作为后悔药。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ -> doImportFile(file) }
            .show()
    }

    private fun confirmImport(uri: android.net.Uri, label: String) {
        AlertDialog.Builder(this)
            .setTitle("从备份恢复")
            .setMessage(
                "来自：$label\n\n" +
                    "会覆盖自建规则与关键字（备份里没有的那类不动），快照按目录合并、不删现有。\n" +
                    "恢复前会先自动导出当前数据到 backup 目录作为后悔药。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ -> doImportUri(uri) }
            .show()
    }

    private fun doImportFile(file: java.io.File) {
        Toast.makeText(this, "正在恢复…", Toast.LENGTH_SHORT).show()
        Io.run("backup-import") {
            val safe = Backup.export(applicationContext)
            val res = runCatching { file.inputStream().use { Backup.restore(applicationContext, it) } }.getOrNull()
            afterImport(res, safe)
        }
    }

    private fun doImportUri(uri: android.net.Uri) {
        Toast.makeText(this, "正在恢复…", Toast.LENGTH_SHORT).show()
        Io.run("backup-import") {
            val safe = Backup.export(applicationContext)
            val res = runCatching {
                contentResolver.openInputStream(uri)?.use { Backup.restore(applicationContext, it) }
            }.getOrNull()
            afterImport(res, safe)
        }
    }

    private fun afterImport(res: Backup.Restored?, safe: java.io.File?) {
        runOnUiThread {
            if (res == null) {
                Toast.makeText(this, "恢复失败：不是可识别的「大小姐」备份", Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            val sb = StringBuilder()
            sb.append("已恢复：规则 ").append(res.rules).append(" 条、关键字 ").append(res.keywords)
                .append(" 条、快照 ").append(res.snapshots).append(" 份")
            if (res.settings > 0) sb.append("、设置 ").append(res.settings).append(" 项")

            // 跳过与失败必须让用户看见：静默丢掉等于骗他说「全恢复了」
            if (res.rulesSkipped > 0) {
                sb.append("\n\n规则跳过 ").append(res.rulesSkipped).append(" 条：缺 App 包名或规则类型，无法确定作用范围，已丢弃。")
            }
            if (res.keywordsSkipped > 0) {
                sb.append("\n\n关键字跳过 ").append(res.keywordsSkipped).append(" 条：命中词与排除词都为空，留着也不会生效。")
            }
            if (res.rulesInvalid) {
                sb.append("\n\n规则文件不是合法 JSON，原有规则未被改动。")
            }
            if (res.keywordsInvalid) {
                sb.append("\n\n关键字文件不是合法 JSON，原有词表未被改动。")
            }

            val cur = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull().orEmpty()
            if (res.fromVersion.isNotBlank() && cur.isNotBlank() && res.fromVersion != cur) {
                sb.append("\n\n这份备份来自 ").append(res.fromVersion).append("，已按当前版本格式整理。")
            }
            safe?.let { sb.append("\n\n当前数据已备份为 ").append(it.name) }

            AlertDialog.Builder(this)
                .setTitle("恢复完成")
                .setMessage(sb.toString())
                .setPositiveButton("好", null)
                .show()
        }
    }

    /** 导出备份：打包在 IO 线程做，完成后直接拉起系统分享面板 */
    private fun exportBackup() {
        Toast.makeText(this, "正在打包备份…", Toast.LENGTH_SHORT).show()
        Io.run("backup-export") {
            val file = Backup.export(applicationContext)
            runOnUiThread {
                if (file == null) {
                    Toast.makeText(this, "备份失败：写文件出错", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val kb = (file.length() / 1024).coerceAtLeast(1)
                Toast.makeText(this, "已导出 ${file.name}（${kb}KB）", Toast.LENGTH_LONG).show()
                share(file)
            }
        }
    }

    private fun share(file: java.io.File, mime: String = "application/zip") {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, if (mime == "application/zip") "保存或发送备份" else "发送诊断日志"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pssKb = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss ?: 0
        val dbKb = StatsDb.sizeKb(this)
        // 快照目录要遍历文件算总量：最多 30 份、每份三个文件，几十次 stat 都在毫秒级，
        // 直接在主线程算完，不值得为它起一次线程
        val snapBytes = com.noteatoranges.skipad.db.SnapshotStore.totalSizeBytes(this)
        findViewById<TextView>(R.id.mem_text).text = getString(
            R.string.mem_format,
            String.format(Locale.CHINA, "%.1f", pssKb / 1024f),
            dbKb.toString(),
            String.format(Locale.CHINA, "%.1f", snapBytes / 1024f / 1024f),
        )
    }

    private fun confirmClearStats() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_stats_title)
            .setMessage(R.string.clear_stats_msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm_clear) { _, _ ->
                StatsDb.get(this).clear()
                // 只清跳过统计。以前这里还调了 StatsStore.clear()，但它清的是保活日志，
                // 与界面上「保活日志不受影响」的承诺相反——用户会凭空丢掉掉线记录。
                Toast.makeText(this, R.string.clear_stats_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
