package com.noteatoranges.skipad.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.db.StatsDb
import com.noteatoranges.skipad.keepalive.KeepAlive
import com.noteatoranges.skipad.util.AppLabels
import com.noteatoranges.skipad.util.GlassBarView
import com.noteatoranges.skipad.util.Ui
import java.util.Locale

/**
 * 单 Activity 三 Tab（悬浮底栏）：首页（状态/自检/排行榜）、设置（保活/系统权限/维护）、关于。
 * 最近记录保留二级页（列表长，从首页排行榜卡进入）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var statsSummary: TextView
    private lateinit var rankContainer: LinearLayout
    private lateinit var rankEmpty: TextView
    private lateinit var rankOthers: TextView
    private lateinit var tabBar: GlassBarView

    private var bottomInsetPx = 0
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        actionButton = findViewById(R.id.action_button)
        statsSummary = findViewById(R.id.stats_summary_text)
        rankContainer = findViewById(R.id.rank_container)
        rankEmpty = findViewById(R.id.rank_empty_text)
        rankOthers = findViewById(R.id.rank_others_text)
        tabBar = findViewById(R.id.tab_bar)
        // 玻璃：把内容层（本栏的兄弟节点）指定为模糊目标
        tabBar.setup(findViewById(R.id.blur_content))

        findViewById<View>(R.id.row_rules).setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }
        findViewById<View>(R.id.row_keepalive).setOnClickListener {
            startActivity(Intent(this, KeepAliveSettingsActivity::class.java))
        }
        findViewById<View>(R.id.row_maintain).setOnClickListener {
            startActivity(Intent(this, MaintenanceActivity::class.java))
        }

        pages = listOf(findViewById(R.id.page_home), findViewById(R.id.page_settings), findViewById(R.id.page_about))
        tabs = listOf(findViewById(R.id.tab_home), findViewById(R.id.tab_settings), findViewById(R.id.tab_about))
        tabs.forEachIndexed { i, tab -> tab.setOnClickListener { selectTab(i) } }

        // 沉浸式 + 底部安全区：内容滚到手势条底下，玻璃 Tab 栏浮在其上
        Ui.edgeToEdge(this, findViewById(R.id.inset_top)) { bottom ->
            bottomInsetPx = bottom
            tabBar.layoutParams = (tabBar.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = bottom + dp(14)
                width = glassWidth()
            }
            tabBar.requestLayout()
            pages.forEach { p ->
                (p as ViewGroup).getChildAt(0).setPadding(
                    dp(20), 0, dp(20), dp(96) + bottom
                )
            }
        }

        // —— 首页 ——
        actionButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.check_now_button).setOnClickListener { btn ->
            btn.isEnabled = false
            runCheck { btn.isEnabled = true }
        }
        findViewById<TextView>(R.id.recent_entry).setOnClickListener {
            startActivity(Intent(this, RecentActivity::class.java))
        }

        // —— 关于 ——
        findViewById<TextView>(R.id.about_version_text).text = try {
            getString(R.string.about_version_format, packageManager.getPackageInfo(packageName, 0).versionName)
        } catch (e: Exception) {
            ""
        }

        requestNotificationPermissionIfNeeded()
        // 事件表上限治理：开页时裁一次
        Thread { StatsDb.get(this).prune(1000) }.start()
        selectTab(0)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun glassWidth(): Int =
        minOf(dp(300), resources.displayMetrics.widthPixels - dp(40))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun selectTab(index: Int) {
        currentTab = index
        pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, t ->
            t.setBackgroundResource(if (i == index) R.drawable.bg_tab_on else android.R.color.transparent)
            t.setTextColor(getColor(if (i == index) R.color.primary else R.color.text_secondary))
            t.isActivated = i == index
        }
    }

    private fun refresh() {
        val enabled = KeepAlive.isEnabledInSettings(this)
        statusText.setText(if (enabled) R.string.status_on else R.string.status_off)
        statusText.setTextColor(getColor(if (enabled) R.color.status_on else R.color.status_off))
        actionButton.setText(if (enabled) R.string.action_open_settings else R.string.action_enable)
        loadStatsAsync()
    }

    private fun runCheck(onDone: () -> Unit) {
        val appContext = applicationContext
        Thread {
            val result = KeepAlive.checkAndRecover(appContext)
            runOnUiThread {
                onDone()
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }.start()
    }

    private fun loadStatsAsync() {
        val appContext = applicationContext
        Thread {
            val db = StatsDb.get(appContext)
            val total = db.total()
            val distinct = db.distinctApps()
            val counts = db.countsByPackage()
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pssKb = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
                .firstOrNull()?.totalPss ?: 0
            val dbKb = StatsDb.sizeKb(appContext)
            runOnUiThread {
                renderStats(total, distinct, counts, pssKb, dbKb)
            }
        }.start()
    }

    private fun renderStats(total: Int, distinct: Int, counts: List<StatsDb.PkgCount>, pssKb: Int, dbKb: Long) {
        statsSummary.text = getString(R.string.stats_summary_format, total, distinct)

        rankContainer.removeAllViews()
        rankOthers.visibility = View.GONE
        if (counts.isEmpty()) {
            rankEmpty.visibility = View.VISIBLE
            return
        }
        rankEmpty.visibility = View.GONE

        val max = counts.first().count.coerceAtLeast(1)
        val shown = counts.take(SHOW_TOP)
        shown.forEachIndexed { idx, pc ->
            val row = layoutInflater.inflate(R.layout.item_rank, rankContainer, false)
            row.findViewById<TextView>(R.id.rank_num).text = (idx + 1).toString()
            val info = AppLabels.get(this, pc.pkg)
            row.findViewById<TextView>(R.id.app_name).text = info.label
            row.findViewById<ImageView>(R.id.app_icon).setImageDrawable(info.icon)
            row.findViewById<TextView>(R.id.count_text).text =
                getString(R.string.times_format, pc.count)
            val bar = row.findViewById<View>(R.id.bar)
            bar.layoutParams = (bar.layoutParams as LinearLayout.LayoutParams).apply {
                weight = pc.count.toFloat() / max
            }
            rankContainer.addView(row)
        }
        val rest = counts.drop(SHOW_TOP)
        if (rest.isNotEmpty()) {
            rankOthers.visibility = View.VISIBLE
            rankOthers.text = getString(
                R.string.rank_others_format, rest.size, rest.sumOf { it.count }
            )
        }
    }

    /** 提醒模式依赖通知权限：Android 13+ 首次打开时请求一次 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    private companion object {
        const val REQ_NOTIF = 1001
        const val SHOW_TOP = 10
    }
}
