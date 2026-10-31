package com.noteatoranges.skipad.keepalive

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * 15 分钟周期自检 Job：JobScheduler 原生 API，零新依赖（不引 WorkManager）。
 * persisted=true 时系统持久化调度、重启后自动恢复；onStartJob 在主线程回调，
 * 实际检查放后台线程，完成后 jobFinished。
 */
class KeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val appContext = applicationContext
        Thread {
            try {
                KeepAlive.checkAndRecover(appContext)
            } finally {
                if (params != null) jobFinished(params, false)
            }
        }.start()
        return true // 还有后台工作未完成
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
