package com.noteatoranges.skipad.util

import android.content.Context
import androidx.core.content.edit

/**
 * 引擎开关。
 *
 * 目前只有一个：内置默认词（跳过 / Skip / 倒计时 / skip viewId）。用户嫌它「死板」时
 * 可以整组关掉，只留自己手输的词与快照规则；关掉之后开屏窗口里若没有可用词表，
 * 引擎就完全不跑内置那一套。
 *
 * 除开关本身，这里还记两件事，专门服务于首页那条「内置词表已关闭」的提醒
 * （判定在 core/BuiltinWordsNudge）：
 * - [KEY_OFF_SINCE]：关掉那一刻的时间戳。重复写 false 不刷新起点，否则每次冷启动
 *   重写一遍，计时永远回零，提示永远不出来；
 * - [KEY_OFF_SCANNED]：关闭期间是否真的扫过一次页面。只写一次，用来区分
 *   「关了但还在用」和「关了也没用」，后者不该被打扰。
 */
object EnginePrefs {

    private const val PREF = "engine"
    private const val KEY_BUILTIN_WORDS = "builtin_words"
    private const val KEY_OFF_SINCE = "builtin_off_since"
    private const val KEY_OFF_SCANNED = "builtin_off_scanned"

    /**
     * 内存缓存。null = 还没读过。
     *
     * 这个开关在热窗口里被问得很密（keywordScannerFor 每 80ms 一次，右上角判定再一次），
     * 每次都走 SharedPreferences 等于每 40ms 过一道同步锁。开关只有本进程会改，
     * 写的时候同步内存即可；跨进程变化由下次冷启动兜底。
     */
    @Volatile
    private var cached: Boolean? = null

    @Volatile
    private var offScannedCached: Boolean? = null

    /** 内置默认词是否参与开屏窗口匹配，默认开 */
    fun builtinWordsEnabled(context: Context): Boolean {
        cached?.let { return it }
        val v = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_BUILTIN_WORDS, true)
        cached = v
        return v
    }

    fun setBuiltinWordsEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val hadSince = prefs.getLong(KEY_OFF_SINCE, 0L) > 0L
        prefs.edit {
            putBoolean(KEY_BUILTIN_WORDS, enabled)
            if (enabled) {
                // 重新打开就跟提醒彻底无关了，把痕迹一起清掉
                putLong(KEY_OFF_SINCE, 0L)
                putBoolean(KEY_OFF_SCANNED, false)
            } else if (!hadSince) {
                putLong(KEY_OFF_SINCE, System.currentTimeMillis())
            }
        }
        cached = enabled
        offScannedCached = false
    }

    /** 关掉的那一刻（毫秒）；0 = 没关过或已清 */
    fun builtinOffSince(context: Context): Long =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_OFF_SINCE, 0L)

    /** 关闭期间是否扫过一次页面（只增不减，重新开启时清） */
    fun builtinOffScanned(context: Context): Boolean {
        offScannedCached?.let { return it }
        val v = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_OFF_SCANNED, false)
        offScannedCached = v
        return v
    }

    /**
     * 关闭状态下扫描未命中时调用，只留一次痕。
     *
     * 热路径：先看 volatile 缓存，标记过就一个布尔判断返回，不碰 SharedPreferences。
     */
    fun noteScanWhileOff(context: Context) {
        if (offScannedCached == true) return
        if (builtinWordsEnabled(context)) return
        offScannedCached = true
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_OFF_SCANNED, true) }
    }

    /**
     * 给「升级前就已经关着」的存量用户补一个起点。
     *
     * 这类用户没有 [KEY_OFF_SINCE]，如果不补，提醒永远不会出现。补的是「现在」，
     * 意味着升级后还要再等满 24 小时才会提示——不立刻弹，符合「长期关闭才提醒」的口径。
     * 只在 UI 侧调，不放进热路径。
     */
    fun ensureOffSince(context: Context) {
        if (builtinWordsEnabled(context)) return
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_OFF_SINCE, 0L) > 0L) return
        prefs.edit { putLong(KEY_OFF_SINCE, System.currentTimeMillis()) }
    }
}
