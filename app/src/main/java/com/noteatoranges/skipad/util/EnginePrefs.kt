package com.noteatoranges.skipad.util

import android.content.Context

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

    /** 内置默认词是否参与开屏窗口匹配，默认开 */
    fun builtinWordsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_BUILTIN_WORDS, true)

    fun setBuiltinWordsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BUILTIN_WORDS, enabled).apply()
    }
}
