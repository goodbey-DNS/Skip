package com.noteatoranges.skipad.util

import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 单线程后台 IO 出口。
 *
 * 无障碍回调（onAccessibilityEvent / takeScreenshot 回调）跑在主线程，落盘一律下沉：
 * 诊断树写 txt、快照压缩 PNG、统计入库，原先都在主线程同步做，广告弹出那一刻会掉帧，
 * 弱机上还有 ANR 风险。这里固定单线程，保证同一快照目录不会被并发写坏，也保证
 * 写入顺序（先 tree.json 后 meta.json 之类的依赖关系不会乱）。
 */
object Io {

    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "skipad-io").apply { isDaemon = true }
    }

    /** 提交一个落盘任务；异常只记日志，不冒泡（落盘失败不该影响跳过主流程） */
    fun run(tag: String, task: () -> Unit) {
        exec.execute {
            runCatching(task).onFailure { Log.w(TAG, "$tag failed", it) }
        }
    }

    private const val TAG = "SkipAdIo"
}
