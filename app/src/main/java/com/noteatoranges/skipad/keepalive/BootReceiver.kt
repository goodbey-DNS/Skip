package com.noteatoranges.skipad.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 保活兜底广播：
 * - BOOT_COMPLETED：周期 Job 虽 persisted 跨重启，但首次触发最长要等一个周期；
 *   广播让开机后立刻尝试复活（荣耀等 ROM 拦自启导致服务没绑定时，静默模式直接写回拉起）。
 * - MY_PACKAGE_REPLACED：覆盖安装后系统会强制关闭无障碍，升级完立即恢复。
 * 两者都是 protected broadcast（只有系统发得出），exported=true 无安全面。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val appContext = context.applicationContext
                val pending = goAsync()
                Thread {
                    try {
                        KeepAlive.scheduleJob(appContext)
                        KeepAlive.checkAndRecover(appContext)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }
}
