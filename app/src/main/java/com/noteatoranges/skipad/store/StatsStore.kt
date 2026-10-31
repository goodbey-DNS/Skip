package com.noteatoranges.skipad.store

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保活事件日志环（SharedPreferences，50 条）。
 * 0.3.0 起跳过统计/排行/最近记录迁往 StatsDb（SQLite），这里只存保活流水。
 */
object StatsStore {

    private const val PREFS = "skipad_stats"
    private const val KEY_LOG = "log"
    private const val LOG_LIMIT = 50

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    fun logEvent(context: Context, message: String) {
        pushLog(prefs(context), timeFmt.format(Date()) + " " + message)
    }

    fun recentLog(context: Context): List<String> {
        val arr = JSONArray(prefs(context).getString(KEY_LOG, "[]"))
        return List(arr.length()) { arr.optString(it) }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 新行插头部，超 [LOG_LIMIT] 截断 */
    private fun pushLog(prefs: SharedPreferences, line: String) {
        val old = JSONArray(prefs.getString(KEY_LOG, "[]"))
        val fresh = JSONArray()
        fresh.put(line)
        for (i in 0 until old.length()) {
            if (fresh.length() >= LOG_LIMIT) break
            fresh.put(old.optString(i))
        }
        prefs.edit().putString(KEY_LOG, fresh.toString()).apply()
    }
}
