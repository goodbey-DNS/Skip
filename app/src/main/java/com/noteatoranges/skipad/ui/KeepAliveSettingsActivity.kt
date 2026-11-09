package com.noteatoranges.skipad.ui

import android.annotation.SuppressLint
import androidx.core.net.toUri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.keepalive.KeepAlive
import com.noteatoranges.skipad.store.StatsStore
import com.noteatoranges.skipad.util.Ui

/** 保活与后台二级页：模式、adb 授权、电池优化、自启动/通知跳转、保活日志与边界说明 */
class KeepAliveSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keepalive_settings)
        Ui.edgeToEdge(this, findViewById(R.id.inset_top), findViewById(R.id.inset_bottom))

        findViewById<TextView>(R.id.back_button).setOnClickListener { finish() }

        findViewById<Button>(R.id.battery_button).setOnClickListener { requestIgnoreBatteryOptimization() }
        findViewById<Button>(R.id.notif_button).setOnClickListener { openNotificationSettings() }
        findViewById<Button>(R.id.autostart_button).setOnClickListener { openAutostartSettings() }
        findViewById<Button>(R.id.app_info_button).setOnClickListener { openAppInfo() }
        findViewById<Button>(R.id.copy_cmd_button).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", KeepAlive.adbGrantCmd(this)))
            Toast.makeText(this, R.string.keepalive_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.check_now_button).setOnClickListener { btn ->
            btn.isEnabled = false
            val appContext = applicationContext
            Thread {
                val result = KeepAlive.checkAndRecover(appContext)
                runOnUiThread {
                    btn.isEnabled = true
                    Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        findViewById<TextView>(R.id.adb_cmd_text).text = KeepAlive.adbGrantCmd(this)
        findViewById<TextView>(R.id.mode_text).setText(
            if (KeepAlive.hasWriteSecureSettings(this)) R.string.keepalive_mode_silent
            else R.string.keepalive_mode_notify
        )

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        val batteryStatus = findViewById<TextView>(R.id.battery_status_text)
        batteryStatus.setText(if (ignoring) R.string.battery_status_on else R.string.battery_status_off)
        batteryStatus.setTextColor(getColor(if (ignoring) R.color.status_on else R.color.status_off))
        findViewById<Button>(R.id.battery_button).visibility =
            if (ignoring) Button.GONE else Button.VISIBLE

        val notifOn = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        val notifStatus = findViewById<TextView>(R.id.notif_status_text)
        notifStatus.setText(if (notifOn) R.string.notif_status_on else R.string.notif_status_off)
        notifStatus.setTextColor(getColor(if (notifOn) R.color.status_on else R.color.status_off))
        findViewById<Button>(R.id.notif_button).visibility =
            if (notifOn) Button.GONE else Button.VISIBLE

        val log = StatsStore.recentLog(this)
        findViewById<TextView>(R.id.ka_log_text).text =
            if (log.isEmpty()) getString(R.string.ka_log_empty) else log.joinToString("\n")
    }

    // 个人自用、不上应用商店：直接申请忽略电池优化是这个工具能常驻的前提。
    // lint 的 BatteryLife 是 Play 商店政策规则（该权限只允许特定类别应用使用），
    // 这里不适用。
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    ("package:$packageName").toUri()
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, e2.message ?: "failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            openAppInfo()
        }
    }

    /**
     * 跳各家 ROM 的「自启动 / 后台运行」管理页。
     *
     * 这些都是私有组件，名字各家不同且会随系统版本变，所以按「本机厂商最可能先命中」
     * 的顺序排，逐个试，全失败才退回应用详情页。
     */
    private fun openAutostartSettings() {
        val candidates = listOf(
            // 小米 / Redmi（HyperOS、MIUI）
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent("miui.intent.action.OP_AUTO_START")
                .addCategory(Intent.CATEGORY_DEFAULT),
            // OPPO / 一加 / realme（ColorOS）
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            ),
            // vivo / iQOO（OriginOS、Funtouch）
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.safeguard.PurviewTabActivity"
                )
            ),
            // 荣耀 / 华为
            Intent("hihonor.intent.action.HSM_STARTUPAPP_MANAGER")
                .setPackage("com.hihonor.systemmanager"),
            Intent("hihonor.intent.action.HSM_STARTUPAPP_MANAGER"),
            Intent().setComponent(
                ComponentName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            ),
            // 三星
            Intent().setComponent(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            ),
        )
        for (i in candidates) {
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
                // 组件不存在就试下一个；全部失败才退到应用详情
            }
        }
        Toast.makeText(this, "这台机型没找到自启动设置页，已在应用详情里，手动找「自启动/后台运行」", Toast.LENGTH_LONG).show()
        openAppInfo()
    }

    private fun openAppInfo() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:$packageName").toUri())
            )
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "failed", Toast.LENGTH_SHORT).show()
        }
    }
}
