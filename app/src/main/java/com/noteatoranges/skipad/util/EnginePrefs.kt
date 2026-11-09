package com.noteatoranges.skipad.util

import android.content.Context
import androidx.core.content.edit

/**
 * 引擎开关。
 *
 * 目前只有一个：内置默认词（跳过 / Skip / 倒计时 / skip viewId）。用户嫌它「死板」时
 * 可以整组关掉，只留自己手输的词与快照规则；关掉之后开屏窗口里若没有可用词表，
 * 引擎就完全不跑内置那一套。
 */
object EnginePrefs {

    private const val PREF = "engine"
    private const val KEY_BUILTIN_WORDS = "builtin_words"

    /**
     * 内存缓存。null = 还没读过。
     *
     * 这个开关在热窗口里被问得很密（keywordScannerFor 每 80ms 一次，右上角判定再一次），
     * 每次都走 SharedPreferences 等于每 40ms 过一道同步锁。开关只有本进程会改，
     * 写的时候同步内存即可；跨进程变化由下次冷启动兜底。
     */
    @Volatile
    private var cached: Boolean? = null

    /** 内置默认词是否参与开屏窗口匹配，默认开 */
    fun builtinWordsEnabled(context: Context): Boolean {
        cached?.let { return it }
        val v = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_BUILTIN_WORDS, true)
        cached = v
        return v
    }

    fun setBuiltinWordsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_BUILTIN_WORDS, enabled) }
        cached = enabled
    }
}
