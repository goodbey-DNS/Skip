package com.noteatoranges.skipad.db

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 关键字表（用户自建）：命中词提高识别，排除词降低误点。
 *
 * 为什么需要：内置词表只有「关闭 / 跳过 / × / 我知道了 / 以后再说」这些通用说法，
 * App 自己的措辞（「放弃福利」「暂不需要」之类）覆盖不到，只能靠几何证据硬猜，
 * 那正是误点最集中的地方。用户知道某个 App 的按钮叫什么，直接告诉他引擎最省事。
 *
 * 约束（写死，不做成配置）：
 * - 单条 ≤10 字；
 * - 每个作用对象命中词 / 排除词各 ≤20 条；
 * - 命中词只是「文本证据」，仍要求控件尺寸像按钮，不允许纯文本命中就点；
 * - 排除词优先级高于命中词，命中就整窗放弃。
 */
data class KeywordRule(
    val id: String,
    val app: String,
    val appLabel: String,
    val hits: List<String>,
    val excludes: List<String>,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
) {
    val isGlobal: Boolean get() = app == GLOBAL_APP
    val desc: String
        get() = buildString {
            if (hits.isNotEmpty()) append("命中：").append(hits.joinToString("、"))
            if (hits.isNotEmpty() && excludes.isNotEmpty()) append("　")
            if (excludes.isNotEmpty()) append("排除：").append(excludes.joinToString("、"))
        }.ifEmpty { "（空）" }

    companion object {
        const val GLOBAL_APP = "*"
        const val MAX_WORDS = 20
        const val MAX_LEN = 10
    }
}

object KeywordStore {

    private const val FILE = "keywords.json"
    private const val PREFS = "kwver"

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun splitWords(raw: String): List<String> = raw
        .split(',', '，', '、', ';', '；', ' ')
        .map { it.trim().take(KeywordRule.MAX_LEN) }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(KeywordRule.MAX_WORDS)

    fun parseWords(raw: String): List<String> = splitWords(raw)

    private fun read(context: Context): List<KeywordRule> = try {
        val f = file(context)
        if (!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val j = arr.getJSONObject(i)
                KeywordRule(
                    id = j.getString("id"),
                    app = j.optString("app", KeywordRule.GLOBAL_APP),
                    appLabel = j.optString("appLabel", ""),
                    hits = j.optJSONArray("hits")?.let { a ->
                        (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
                    } ?: emptyList(),
                    excludes = j.optJSONArray("excludes")?.let { a ->
                        (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
                    } ?: emptyList(),
                    enabled = j.optBoolean("enabled", true),
                    createdAt = j.optLong("createdAt", 0),
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(context: Context, rules: List<KeywordRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("app", r.app)
                put("appLabel", r.appLabel)
                put("hits", JSONArray(r.hits))
                put("excludes", JSONArray(r.excludes))
                put("enabled", r.enabled)
                put("createdAt", r.createdAt)
            })
        }
        file(context).writeText(arr.toString())
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("v", 0) + 1
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("v", v).apply()
    }

    fun all(context: Context): List<KeywordRule> = read(context).sortedByDescending { it.createdAt }

    /** 同 App 同方向只保留一条：重复条目没有意义 */
    fun add(context: Context, rule: KeywordRule) {
        val rules = read(context).toMutableList()
        rules.removeAll { it.app == rule.app }
        rules.add(rule)
        write(context, rules)
    }

    fun remove(context: Context, id: String) {
        write(context, read(context).filter { it.id != id })
    }

    /** 从备份恢复：合法 JSON 才覆盖，并让服务重新取词 */
    fun importFrom(context: Context, bytes: ByteArray): Int {
        val text = String(bytes, Charsets.UTF_8).trim()
        val arr = JSONArray(text)
        file(context).writeText(text)
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("v", 0) + 1
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("v", v).apply()
        return arr.length()
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        write(context, read(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /**
     * 某 App 生效的词表：全局条目 + 该 App 条目合并去重。
     * 排除词先于命中词生效（调用方负责顺序）。
     */
    fun forApp(context: Context, pkg: String): Pair<List<String>, List<String>> {
        val rules = read(context).filter { it.enabled && (it.app == pkg || it.isGlobal) }
        val hits = LinkedHashSet<String>()
        val excludes = LinkedHashSet<String>()
        rules.forEach { r ->
            hits.addAll(r.hits)
            excludes.addAll(r.excludes)
        }
        return hits.toList() to excludes.toList()
    }

    fun version(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("v", 0)
}
