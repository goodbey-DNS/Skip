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
            runOnUiThread { render(events) }
        }.start()
    }

    private fun render(events: List<StatsDb.Event>) {
        container.removeAllViews()
        if (events.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            return
        }
        emptyText.visibility = View.GONE
        for (e in events) {
            val row = layoutInflater.inflate(R.layout.item_recent, container, false)
            val info = AppLabels.get(this, e.pkg)
            row.findViewById<TextView>(R.id.app_name).text = info.label
            row.findViewById<ImageView>(R.id.app_icon).setImageDrawable(info.icon)
            row.findViewById<TextView>(R.id.time_text).text = timeFmt.format(Date(e.ts))
            row.findViewById<TextView>(R.id.detail_text).text =
                getString(R.string.times_format_detail, e.detail)
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
