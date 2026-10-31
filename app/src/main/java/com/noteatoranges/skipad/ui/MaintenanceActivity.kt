package com.noteatoranges.skipad.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.db.StatsDb
import com.noteatoranges.skipad.store.StatsStore
import com.noteatoranges.skipad.util.AppLabels
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
