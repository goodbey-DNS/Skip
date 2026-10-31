package com.noteatoranges.skipad.db

import android.content.Context
import com.noteatoranges.skipad.core.SkipRule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 用户自建跳过规则（GKD 式）：App + 匹配条件，JSON 落盘 filesDir/rules.json。
 * kind=node（按控件 viewId/文本匹配后点击）| point（按屏幕比例坐标直接点）。
 * version 单调递增，服务据此感知规则变化重建扫描器。
 */
data class SkipAppRule(
    val id: String,
    val app: String,
    val appLabel: String,
    val kind: String,
    val viewId: String? = null,
    val textContains: String? = null,
    val xRatio: Float? = null,
    val yRatio: Float? = null,
    val delayMs: Long = 900L,
    val enabled: Boolean = true,
    val createdAt: Long,
)

object RuleStore {

    private const val FILE = "rules.json"

    private fun file(context: Context): File = File(context.filesDir, FILE)

    private fun read(context: Context): MutableList<SkipAppRule> = try {
        val arr = JSONArray(file(context).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until arr.length()).map { o ->
            val j = arr.getJSONObject(o)
            SkipAppRule(
                id = j.getString("id"),
                app = j.getString("app"),
                appLabel = j.optString("appLabel", j.getString("app")),
                kind = j.getString("kind"),
                viewId = j.optString("viewId").takeIf { it.isNotEmpty() },
                textContains = j.optString("textContains").takeIf { it.isNotEmpty() },
                xRatio = if (j.has("xRatio")) j.getDouble("xRatio").toFloat() else null,
                yRatio = if (j.has("yRatio")) j.getDouble("yRatio").toFloat() else null,
                delayMs = j.optLong("delayMs", 900L),
                enabled = j.optBoolean("enabled", true),
                createdAt = j.optLong("createdAt", 0L),
            )
        }.toMutableList()
    } catch (e: Exception) {
        mutableListOf()
    }

    private fun write(context: Context, rules: List<SkipAppRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("app", r.app)
                put("appLabel", r.appLabel)
                put("kind", r.kind)
                r.viewId?.let { put("viewId", it) }
                r.textContains?.let { put("textContains", it) }
                r.xRatio?.let { put("xRatio", it.toDouble()) }
                r.yRatio?.let { put("yRatio", it.toDouble()) }
                put("delayMs", r.delayMs)
                put("enabled", r.enabled)
                put("createdAt", r.createdAt)
            })
        }
        file(context).writeText(arr.toString())
        bumpVersion(context)
    }

    fun all(context: Context): List<SkipAppRule> = read(context).sortedByDescending { it.createdAt }

    fun add(context: Context, rule: SkipAppRule) {
        val rules = read(context)
        rules.removeAll { it.app == rule.app && it.kind == rule.kind && it.viewId == rule.viewId && it.textContains == rule.textContains }
        rules.add(rule)
        write(context, rules)
    }

    fun remove(context: Context, id: String) {
        write(context, read(context).filter { it.id != id })
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val rules = read(context).map { if (it.id == id) it.copy(enabled = enabled) else it }
        write(context, rules)
    }

    /** 节点类规则转内置扫描规则（引擎复用 NodeScanner） */
    fun nodeRulesAsScannerRules(context: Context): List<SkipRule> =
        read(context).filter { it.enabled && it.kind == "node" }.map { r ->
            SkipRule(
                id = "user:${r.id}",
                patterns = listOfNotNull(r.textContains),
                viewIds = listOfNotNull(r.viewId?.lowercase()),
                maxAreaRatio = 0.3f,
            )
        }

    fun pointRules(context: Context): List<SkipAppRule> =
        read(context).filter { it.enabled && it.kind == "point" }

    @Volatile
    private var ver: Int = 0

    private fun bumpVersion(context: Context) {
        ver = context.getSharedPreferences("rulever", Context.MODE_PRIVATE)
            .getInt("v", 0) + 1
        context.getSharedPreferences("rulever", Context.MODE_PRIVATE)
            .edit().putInt("v", ver).apply()
    }

    fun version(context: Context): Int =
        context.getSharedPreferences("rulever", Context.MODE_PRIVATE).getInt("v", 0)
}
