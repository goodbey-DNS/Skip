package com.noteatoranges.skipad.keepalive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.SkipAccessibilityService
import com.noteatoranges.skipad.store.StatsStore
import org.json.JSONArray

/**
 * 无障碍保活中枢（分级设计，详见 docs/design.md §3.1）：
 *
 * - 静默复活（需 adb 授权 WRITE_SECURE_SETTINGS；该权限系统安装发不了、必须电脑手动给，
 *   只用于把本 App 自己的无障碍服务写回系统设置，不涉及任何用户数据）：
 *   服务被杀/被关/开机/升级后，直接写回设置让系统重新绑定；
 * - 提醒模式（默认，零额外授权）：掉线时发本地通知引导去设置页开启。
 *
 * 触发源全部收敛到 [checkAndRecover]：开机/升级广播、15 分钟周期 Job、UI「立即自检」。
 * 已知边界：用户在系统设置里主动关闭后，周期任务也会把它重新开启（授权静默即视为接受）；
 * 被一键强停后 App 处于 stopped 态，一切调度失效——打开一次 App 即恢复保活。
 */
object KeepAlive {

    private const val JOB_ID = 1001
    private const val CHANNEL_ID = "keepalive"
    private const val NOTIFY_ID = 1001

    /**
     * 复活冷却：系统重绑有过渡期，isRunningNow 会滞后返回 false；
     * 没有冷却时"写回→重绑→任务又跑→又写回"会震荡成每秒几十次的活循环（0.2.0 真机首测实锤）。
     */
    private const val RESURRECT_COOLDOWN_MS = 30_000L

    /** 每小时静默复活上限：超过就退化为提醒，避免与用户的手动关闭长期对抗 */
    private const val RESURRECT_PER_HOUR = 6

    /**
     * 「刚连上」的宽限期。
     *
     * 实例为空但几分钟前才连上过，说明系统正在重绑（过渡期实例还没赋值），
     * 这时候去"复活"只会把刚起来的服务又踢一遍。宽限期取 10 秒：
     * 正常重绑在 1~2 秒内完成，真掉线则早就超过这个时长。
     */
    private const val CONNECT_GRACE_MS = 10_000L
    private const val PREF_KA = "keepalive"
    private const val KEY_RESURRECT_TS = "resurrect_ts"
    private const val KEY_LAST_CONN = "last_conn_at"

    /**
     * 记下「服务最后一次真的连上」的时间。
     *
     * 断线感知靠这个：进程被杀时没有任何回调可写，只能反过来记「最后活着的时刻」。
     * 主界面拿它算「上次连上 N 分钟前」，用户一眼能看出是刚掉的还是压根没起来。
     */
    fun markConnected(context: Context) {
        context.getSharedPreferences(PREF_KA, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CONN, System.currentTimeMillis()).apply()
    }

    fun lastConnectedAt(context: Context): Long =
        context.getSharedPreferences(PREF_KA, Context.MODE_PRIVATE).getLong(KEY_LAST_CONN, 0L)

    @Volatile
    private var lastResurrectAt = 0L

    /** 电脑上一条命令即可授予静默复活能力（UI 里一键复制）；包名取运行时值，改 applicationId 不会失效 */
    fun adbGrantCmd(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    fun serviceComponent(context: Context): ComponentName =
        ComponentName(context, SkipAccessibilityService::class.java)

    /**
     * 比较两个无障碍服务标识是否指同一个服务。
     *
     * 必须反解析成 ComponentName 再比：`AccessibilityServiceInfo.id` 用的是
     * `flattenToShortString()`，同包名的类会被缩成 `.SkipAccessibilityService`；
     * 而 `flattenToString()` 给的是完整类名。直接比字符串永远不相等——
     * 这就是「设置里明明开着，自检却说服务掉线」的根因。
     */
    private fun sameService(raw: String?, me: ComponentName): Boolean {
        raw ?: return false
        val a = ComponentName.unflattenFromString(raw) ?: return false
        return a.packageName == me.packageName && a.className == me.className
    }

    /** 配置态：系统设置里已登记为开启 */
    fun isEnabledInSettings(context: Context): Boolean {
        val cur = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = serviceComponent(context)
        return cur.split(':').any { sameService(it, me) }
    }

    /**
     * 运行态：系统当前真的绑定着本服务（比配置态可信，ROM 拦自启时配置在但没绑定）。
     *
     * 先看同进程里的服务实例，这是最直接的证据。
     *
     * 实例为空时，过去用 `getEnabledAccessibilityServiceList` 兜底，但那个列表返回的是
     * **配置态**：服务崩溃后被系统标记为不可用、不再自动绑定时，配置里仍然写着它启用，
     * 于是这里一直认为是「运行中」，永远不去复活 —— 用户看到的是服务明明开着却毫无反应。
     * 改成看「最近有没有真的连上过」：连接过渡期（几秒）算运行中，不折腾；
     * 超过宽限期就认定掉线，交给复活逻辑判断。
     */
    fun isRunningNow(context: Context): Boolean {
        if (SkipAccessibilityService.instance != null) return true
        val last = lastConnectedAt(context)
        if (last <= 0L) return false
        return System.currentTimeMillis() - last < CONNECT_GRACE_MS
    }

    fun hasWriteSecureSettings(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 统一自检入口：活着 → 不动；挂了 → 静默复活或发提醒。
     * @return 结果描述，供 UI Toast 与保活日志使用
     */
    fun checkAndRecover(context: Context): String {
        if (isRunningNow(context)) return context.getString(R.string.ka_result_running)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastResurrectAt < RESURRECT_COOLDOWN_MS) {
            return context.getString(R.string.ka_result_cooldown)
        }
        if (hasWriteSecureSettings(context) && canResurrect(context) && tryReEnable(context)) {
            markResurrect(context)
            lastResurrectAt = now
            StatsStore.logEvent(context, context.getString(R.string.ka_log_revived))
            return context.getString(R.string.ka_result_revived)
        }
        StatsStore.logEvent(context, context.getString(R.string.ka_log_detected_off))
        notifyToReEnable(context)
        // 配置态还开着却绑不上：不是「用户关了」，而是「系统里有登记但服务没起效」。
        // 这时只说「未授权静默复活」会让人去系统设置里看，可那里明明显示是开的
        return context.getString(
            if (isEnabledInSettings(context)) R.string.ka_result_not_bound
            else R.string.ka_result_notified
        )
    }

    /**
     * 把本服务写回系统设置（需 WRITE_SECURE_SETTINGS），保留其他 App 已开启的无障碍服务。
     * 配置里没有本服务 → 单次追加写入（干净拉起）；
     * 配置里已有但系统没绑定（开机被 ROM 拦）→ 两段式写入制造一次变更，系统才重新解析绑定。
     */
    fun tryReEnable(context: Context): Boolean = try {
        val cr = context.contentResolver
        val me = serviceComponent(context)
        val meStr = me.flattenToString()
        val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val all = cur.split(':').filter { it.isNotBlank() }
        val others = all.filter { !sameService(it, me) }
        val mine = all.any { sameService(it, me) }
        val target = (others + meStr).joinToString(":")
        if (mine) {
            // 同值写入系统不会重新解析绑定，必须制造一次变更。中间态（不含我）只允许
            // 存在一瞬间：紧接着必须写回 target，否则服务会从系统设置里消失。
            Settings.Secure.putString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":")
            )
        }
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, target)
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        true
    } catch (e: Exception) {
        // 第二段写失败时尽力补回，避免服务被上面的中间态"删掉"后找不回来
        runCatching {
            val me = serviceComponent(context)
            val cr = context.contentResolver
            val cur = Settings.Secure.getString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (!cur.split(':').any { sameService(it, me) }) {
                val fixed = (cur.split(':').filter { it.isNotBlank() } + me.flattenToString())
                    .joinToString(":")
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, fixed)
            }
        }
        false
    }

    /** 一小时内已静默复活次数是否还有额度 */
    private fun canResurrect(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val arr = JSONArray(
            context.getSharedPreferences(PREF_KA, Context.MODE_PRIVATE)
                .getString(KEY_RESURRECT_TS, "[]")
        )
        var n = 0
        for (i in 0 until arr.length()) if (now - arr.optLong(i) < 3600_000L) n++
        return n < RESURRECT_PER_HOUR
    }

    private fun markResurrect(context: Context) {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREF_KA, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_RESURRECT_TS, "[]"))
        val out = JSONArray().put(now)
        for (i in 0 until arr.length()) {
            val t = arr.optLong(i)
            if (now - t < 3600_000L) out.put(t)
        }
        prefs.edit().putString(KEY_RESURRECT_TS, out.toString()).apply()
    }

    /** 15 分钟周期自检；persisted 跨重启（开机广播只是让它更早跑第一次的保险）。幂等：已注册不再重排 */
    fun scheduleJob(context: Context) {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (js.getPendingJob(JOB_ID) != null) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, KeepAliveJobService::class.java))
            .setPeriodic(JobInfo.getMinPeriodMillis())
            .setPersisted(true)
            .build()
        js.schedule(info)
    }

    private fun notifyToReEnable(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nmSys =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nmSys.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.ka_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_keepalive)
            .setContentTitle(context.getString(R.string.ka_notify_title))
            .setContentText(context.getString(R.string.ka_notify_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFY_ID, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS 运行时未授：提醒模式只能就此放弃，不打扰
        }
    }
}
