package com.noteatoranges.skipad.util

import android.os.SystemClock
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
 *
 * ## 为什么带耗时埋点（2026-11-09）
 *
 * design.md §8 第 24 条记着一次「统计写入整段失效」：`verified` 一直在打，
 * `skipad.db` 却不涨，`DaxiaojieIo` 里一条失败都没有，force-stop 之后自愈。
 * 当时的结论是「怀疑单线程被某个任务卡住，但没有日志可查」。
 *
 * 单线程队列有个天然盲点：**排在队尾的任务如果一直没轮到，它连一行日志都不会留**。
 * 所以这里补两条：任务从提交到真正开始执行超过 [SLOW_WAIT_MS] 就告警（说明前面
 * 有东西在堵），单个任务执行超过 [SLOW_RUN_MS] 也告警（说明它自己就是堵点）。
 * 只观测、不改行为，下一次复现时能直接看出是哪一类。
 */
object Io {

    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "skipad-io").apply { isDaemon = true }
    }

    /** 提交一个落盘任务；异常只记日志，不冒泡（落盘失败不该影响跳过主流程） */
    fun run(tag: String, task: () -> Unit) {
        val queuedAt = SystemClock.uptimeMillis()
        exec.execute {
            val waited = SystemClock.uptimeMillis() - queuedAt
            if (waited > SLOW_WAIT_MS) Log.w(TAG, "$tag 排队 ${waited}ms 才轮到（前一个任务在堵）")
            val startedAt = SystemClock.uptimeMillis()
            runCatching(task).onFailure { Log.w(TAG, "$tag failed", it) }
            val cost = SystemClock.uptimeMillis() - startedAt
            if (cost > SLOW_RUN_MS) Log.w(TAG, "$tag 执行 ${cost}ms")
        }
    }

    /** 排队超过 3 秒就当「前面有东西在堵」：正常任务都是毫秒级 */
    private const val SLOW_WAIT_MS = 3_000L

    /** 单个任务超过 5 秒：快照压图、大目录备份这类才可能到这个量级 */
    private const val SLOW_RUN_MS = 5_000L

    private const val TAG = "DaxiaojieIo"
}
