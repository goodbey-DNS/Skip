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
import com.noteatoranges.skipad.db.StatsDb
import com.noteatoranges.skipad.store.StatsStore
import com.noteatoranges.skipad.util.AppLabels
import com.noteatoranges.skipad.util.Backup
import com.noteatoranges.skipad.util.Io
import com.noteatoranges.skipad.util.Ui
import java.util.Locale

/** 数据与维护二级页：内存占用、缓存清理、统计数据清空 */
class MaintenanceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maintenance)
        Ui.edgeToEdge(this, findViewById(R.id.inset_top), findViewById(R.id.inset_bottom))

        findViewById<TextView>(R.id.back_button).setOnClickListener { finish() }
        findViewById<Button>(R.id.clear_cache_button).setOnClickListener {
            AppLabels.clearCache()
            Toast.makeText(this, R.string.clear_mem_cache_done, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.clear_stats_button).setOnClickListener { confirmClearStats() }
        findViewById<Button>(R.id.export_backup_button).setOnClickListener { exportBackup() }
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

    private fun share(file: java.io.File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "保存或发送备份"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pssKb = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss ?: 0
        val dbKb = StatsDb.sizeKb(this)
        findViewById<TextView>(R.id.mem_text).text = getString(
            R.string.mem_format,
            String.format(Locale.CHINA, "%.1f", pssKb / 1024f),
            dbKb.toString(),
        )
    }

    private fun confirmClearStats() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_stats_title)
            .setMessage(R.string.clear_stats_msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm_clear) { _, _ ->
                StatsDb.get(this).clear()
                StatsStore.clear(this)
                Toast.makeText(this, R.string.clear_stats_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
