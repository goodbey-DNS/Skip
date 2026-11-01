package com.noteatoranges.skipad.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.db.StatsDb
import com.noteatoranges.skipad.util.AppLabels
import com.noteatoranges.skipad.util.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 最近记录二级页：图标 + 应用名 + 命中文案 + 时间 */
class RecentActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var emptyText: TextView

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recent)
        Ui.edgeToEdge(this, findViewById(R.id.inset_top), findViewById(R.id.inset_bottom))

        container = findViewById(R.id.recent_container)
        emptyText = findViewById(R.id.empty_text)

        findViewById<TextView>(R.id.back_button).setOnClickListener { finish() }
        findViewById<TextView>(R.id.clear_button).setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        loadAsync()
    }

    private fun loadAsync() {
        val appContext = applicationContext
        Thread {
            val events = StatsDb.get(appContext).recent(200)
            // 应用名与图标在后台线程先取好：这一步是 PackageManager 跨进程查询，
            // 200 行列表在主线程逐行查会让页面卡住好几秒；而 AppLabels 的缓存只留 30 个，
            // 行数一多就会边淘汰边查，等于每行都查一次
            val rows = events.map { e ->
                val info = AppLabels.get(appContext, e.pkg)
                Row(info.label, info.icon, e.detail, e.ts)
            }
            runOnUiThread { render(rows) }
        }.start()
    }

    private data class Row(
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
        val detail: String,
        val ts: Long,
    )

    private fun render(rows: List<Row>) {
        container.removeAllViews()
        if (rows.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            return
        }
        emptyText.visibility = View.GONE
        for (r in rows) {
            val row = layoutInflater.inflate(R.layout.item_recent, container, false)
            row.findViewById<TextView>(R.id.app_name).text = r.label
            row.findViewById<ImageView>(R.id.app_icon).setImageDrawable(r.icon)
            row.findViewById<TextView>(R.id.time_text).text = timeFmt.format(Date(r.ts))
            row.findViewById<TextView>(R.id.detail_text).text =
                getString(R.string.times_format_detail, r.detail)
            container.addView(row)
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_stats_title)
            .setMessage(R.string.clear_stats_msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm_clear) { _, _ ->
                StatsDb.get(this).clear()
                Toast.makeText(this, R.string.clear_stats_done, Toast.LENGTH_SHORT).show()
                loadAsync()
            }
            .show()
    }
}
