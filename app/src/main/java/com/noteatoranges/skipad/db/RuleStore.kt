package com.noteatoranges.skipad.db

import android.content.Context
import com.noteatoranges.skipad.core.SkipRule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 用户自建跳过规则（GKD 式）：App + 可选页面 + 匹配条件，JSON 落盘 filesDir/rules.json。
 * kind=node（按控件 viewId/文本匹配后动作）| point（按坐标或相对某控件偏移动作）。
 * version 单调递增，服务据此感知规则变化重建扫描器。
 *
 * 0.10.0 起规则按包名和页面隔离（此前 node 规则不看 app 字段，一条淘宝规则会在所有
 * App 里生效），并补齐冷却/次数上限/动作类型/相对坐标四组参数。
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
    /** 限定页面（Activity 类名，允许只写后缀）；null = 该 App 全部页面 */
    val activity: String? = null,
    /** 命中后的动作：click / back / none */
    val action: String = SkipRule.ACTION_CLICK,
    /** 同一条规则两次动作的最小间隔，0 = 不限 */
    val cooldownMs: Long = 1000L,
    /** 同一页面内累计动作次数上限，0 = 不限 */
    val maxCount: Int = 0,
    /** 相对坐标：锚点控件 id；命中后以锚点矩形为基准计算落点 */
    val anchorVid: String? = null,
    /** 相对坐标：锚点文字（无 id 时用） */
    val anchorText: String? = null,
    /** 相对锚点中心的水平偏移（dp） */
    val dxDp: Int? = null,
    /** 相对锚点底边的垂直偏移（dp） */
    val dyDp: Int? = null,
    /** 结构路径锚点（如 "0/3/1"）：纯图标按钮没有 vid/文字时的定位手段 */
    val nodePath: String? = null,
    /** 路径锚点所属窗口 id（抓取时记录，对不上就让路） */
    val nodeWinId: Int = 0,
    /** 路径锚点记录时的控件类名：回放时类名不符就不点（防页面变动后路径指向别的控件） */
    val nodeCls: String? = null,
    /** 路径锚点记录时的大致尺寸（dp）：偏差超 40% 视为已不是同一个控件 */
    val nodeWDp: Int? = null,
    val nodeHDp: Int? = null,
    /** 屏幕中心偏移（dp）：自由取点的存储方式，换分辨率不偏 */
    val dpX: Float? = null,
    val dpY: Float? = null,
) {
    val hasAnchor: Boolean get() = !anchorVid.isNullOrBlank() || !anchorText.isNullOrBlank()
    val hasPath: Boolean get() = !nodePath.isNullOrBlank()
    val hasDpPoint: Boolean get() = dpX != null && dpY != null

    /** 动作的中文名，UI 与日志共用 */
    val actionLabel: String
        get() = when (action) {
            SkipRule.ACTION_BACK -> "按返回键"
            SkipRule.ACTION_NONE -> "只看不动"
            else -> "点一下"
        }
}

object RuleStore {

    private const val FILE = "rules.json"

    private fun file(context: Context): File = File(context.filesDir, FILE)

    /**
     * 解析单条规则。返回 null 表示该条目残缺到无法归类。
     *
     * 为什么不用 getString 硬取：以前只要有一条缺 id/app/kind，就会抛异常被外层
     * catch 吞掉，整份文件读成空列表 —— 界面上看起来「规则全没了」，此后用户随手点
     * 一下开关（setEnabled 会 write(read().filter{})）就把空列表写回磁盘，原数据真丢。
     * 现在逐条兜：缺 app 或 kind 的丢（不知道该作用于哪个 App，留着反而会变成全局规则），
     * 缺 id 的现场补一个由位置+创建时间构成的稳定 id（同一份文件反复读结果一致）。
     */
    private fun parseOne(j: JSONObject, fallbackId: String): SkipAppRule? {
        val app = j.optString("app").takeIf { it.isNotBlank() } ?: return null
        val kind = j.optString("kind").takeIf { it.isNotBlank() } ?: return null
        return SkipAppRule(
            id = j.optString("id").takeIf { it.isNotBlank() } ?: fallbackId,
            app = app,
            appLabel = j.optString("appLabel").takeIf { it.isNotBlank() } ?: app,
            kind = kind,
            viewId = j.optString("viewId").takeIf { it.isNotEmpty() },
            textContains = j.optString("textContains").takeIf { it.isNotEmpty() },
            xRatio = if (j.has("xRatio")) j.optDouble("xRatio").toFloat() else null,
            yRatio = if (j.has("yRatio")) j.optDouble("yRatio").toFloat() else null,
            delayMs = j.optLong("delayMs", 900L),
            enabled = j.optBoolean("enabled", true),
            createdAt = j.optLong("createdAt", 0L),
            activity = j.optString("activity").takeIf { it.isNotEmpty() },
            action = j.optString("action").takeIf { it.isNotEmpty() } ?: SkipRule.ACTION_CLICK,
            cooldownMs = j.optLong("cooldownMs", 1000L),
            maxCount = j.optInt("maxCount", 0),
            anchorVid = j.optString("anchorVid").takeIf { it.isNotEmpty() },
            anchorText = j.optString("anchorText").takeIf { it.isNotEmpty() },
            dxDp = if (j.has("dxDp")) j.optInt("dxDp") else null,
            dyDp = if (j.has("dyDp")) j.optInt("dyDp") else null,
            nodePath = j.optString("nodePath").takeIf { it.isNotEmpty() },
            nodeWinId = j.optInt("nodeWinId", 0),
            nodeCls = j.optString("nodeCls").takeIf { it.isNotEmpty() },
            nodeWDp = if (j.has("nodeWDp")) j.optInt("nodeWDp") else null,
            nodeHDp = if (j.has("nodeHDp")) j.optInt("nodeHDp") else null,
            dpX = if (j.has("dpX")) j.optDouble("dpX").toFloat() else null,
            dpY = if (j.has("dpY")) j.optDouble("dpY").toFloat() else null,
        )
    }

    /** 逐条解析整份数组，坏条目跳过并计数，绝不因一条坏全盘归零 */
    private fun parseAll(arr: JSONArray): Pair<MutableList<SkipAppRule>, Int> {
        val out = ArrayList<SkipAppRule>(arr.length())
        var skipped = 0
        for (i in 0 until arr.length()) {
            val j = runCatching { arr.getJSONObject(i) }.getOrNull()
            if (j == null) { skipped++; continue }
            val rule = parseOne(j, "legacy-$i-${j.optLong("createdAt", 0L)}")
            if (rule == null) skipped++ else out.add(rule)
        }
        return out to skipped
    }

    /**
     * 内存缓存：版本号 + 解析结果。
     *
     * 规则查询在无障碍回调里是热路径（每个事件都要取一次路径规则、页面规则），
     * 而 read() 是「读盘 + 解析 JSON」。信息流滑动时事件一秒十几个，每次都来一遍
     * 就是在主线程上反复卡顿。每次 write 都会让 version 自增，所以缓存失效是精确的。
     */
    @Volatile
    private var cache: List<SkipAppRule> = emptyList()

    @Volatile
    private var cacheVer: Int = -1

    private fun read(context: Context): MutableList<SkipAppRule> {
        val v = version(context)
        if (v == cacheVer) return cache.toMutableList()
        val f = file(context)
        val parsed: MutableList<SkipAppRule> = if (!f.exists()) {
            mutableListOf()
        } else try {
            val text = f.readText().trim()
            if (text.isEmpty()) mutableListOf() else parseAll(JSONArray(text)).first
        } catch (e: Exception) {
            // 整份不是合法 JSON 数组：只能当作没有，但不再连带其它逻辑出错
            mutableListOf()
        }
        cache = parsed
        cacheVer = v
        return parsed.toMutableList()
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
                r.activity?.let { put("activity", it) }
                put("action", r.action)
                put("cooldownMs", r.cooldownMs)
                put("maxCount", r.maxCount)
                r.anchorVid?.let { put("anchorVid", it) }
                r.anchorText?.let { put("anchorText", it) }
                r.dxDp?.let { put("dxDp", it) }
                r.dyDp?.let { put("dyDp", it) }
                r.nodePath?.let { put("nodePath", it) }
                if (r.nodeWinId != 0) put("nodeWinId", r.nodeWinId)
                r.nodeCls?.let { put("nodeCls", it) }
                r.nodeWDp?.let { put("nodeWDp", it) }
                r.nodeHDp?.let { put("nodeHDp", it) }
                r.dpX?.let { put("dpX", it.toDouble()) }
                r.dpY?.let { put("dpY", it.toDouble()) }
            })
        }
        // 原子写：先落临时文件再改名。直接 writeText 若中途被杀，会留下半截 JSON，
        // 那份文件连「合法数组」都不是，等于规则全丢且无从恢复。
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        bumpVersion(context)
    }

    fun all(context: Context): List<SkipAppRule> = read(context).sortedByDescending { it.createdAt }

    /** 只保留一条：同 App 同匹配条件的重复规则没有意义 */
    fun add(context: Context, rule: SkipAppRule) {
        val rules = read(context)
        rules.removeAll { sameMatcher(it, rule) }
        rules.add(rule)
        write(context, rules)
    }

    /**
     * 两条规则是不是「同一个匹配条件」。
     *
     * 以前只比 app/kind/viewId/textContains/activity，而坐标规则和层级规则这几个字段
     * 都是空的，于是在同一页建第二条时会被当成重复，把前一条覆盖掉——用户在同一页
     * 给两个按钮建规则，最后只剩后建的那一个。锚点、偏移、路径、落点都要参与比较。
     */
    private fun sameMatcher(a: SkipAppRule, b: SkipAppRule): Boolean =
        a.app == b.app && a.kind == b.kind && a.activity == b.activity &&
            a.viewId == b.viewId && a.textContains == b.textContains &&
            a.nodePath == b.nodePath &&
            a.anchorVid == b.anchorVid && a.anchorText == b.anchorText &&
            a.dxDp == b.dxDp && a.dyDp == b.dyDp &&
            a.dpX == b.dpX && a.dpY == b.dpY &&
            a.xRatio == b.xRatio && a.yRatio == b.yRatio

    fun remove(context: Context, id: String) {
        write(context, read(context).filter { it.id != id })
    }

    /** 导入结果：可用条数 + 被跳过的坏条目数 */
    data class ImportOutcome(val ok: Int, val skipped: Int)

    /**
     * 从备份恢复：解析后按当前格式规范化落盘，返回真实可用条数。
     *
     * 以前是「校验是 JSON 数组就原样写回」，于是残缺条目能骗过校验：提示恢复 5 条，
     * 实际读出来 0 条。现在先解析成对象再 write，坏条目当场丢掉并计数告诉用户，
     * 下一次读到的就是干净数据。
     */
    fun importFrom(context: Context, bytes: ByteArray): ImportOutcome {
        val text = String(bytes, Charsets.UTF_8).trim()
        val arr = JSONArray(text)
        val (parsed, skipped) = parseAll(arr)
        write(context, parsed)
        return ImportOutcome(parsed.size, skipped)
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        write(context, read(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /** 切换动作（点击 → 返回键 → 只匹配 → 点击） */
    fun cycleAction(context: Context, id: String): SkipAppRule? {
        var updated: SkipAppRule? = null
        val rules = read(context).map { r ->
            if (r.id != id) r else {
                val next = when (r.action) {
                    SkipRule.ACTION_CLICK -> SkipRule.ACTION_BACK
                    SkipRule.ACTION_BACK -> SkipRule.ACTION_NONE
                    else -> SkipRule.ACTION_CLICK
                }
                r.copy(action = next).also { updated = it }
            }
        }
        if (updated != null) write(context, rules)
        return updated
    }

    /** 切换作用范围：本页面 ↔ 全应用（需要一个 Activity 值才能设成"本页面"） */
    fun setScope(context: Context, id: String, activity: String?): SkipAppRule? {
        var updated: SkipAppRule? = null
        val rules = read(context).map { r ->
            if (r.id != id) r else r.copy(activity = activity).also { updated = it }
        }
        if (updated != null) write(context, rules)
        return updated
    }

    /**
     * 页面匹配：规则没写页面即全应用生效；写了就按类名后缀比，
     * 这样「.MainActivity」「SplashActivity」这种写法都能对上完整类名。
     */
    fun activityMatches(ruleActivity: String?, currentActivity: String?): Boolean {
        if (ruleActivity.isNullOrBlank()) return true
        if (currentActivity.isNullOrBlank()) return false
        return currentActivity == ruleActivity ||
            currentActivity.endsWith(ruleActivity) ||
            currentActivity.endsWith(".$ruleActivity")
    }

    /** 某 App（某页面）下生效的规则 */
    fun forApp(context: Context, app: String, activity: String?): List<SkipAppRule> =
        read(context).filter { it.enabled && it.app == app && activityMatches(it.activity, activity) }

    /** 节点类规则转内置扫描规则（引擎复用 NodeScanner），已按包名与页面过滤。
     *  带结构路径的规则由服务单独按路径解析，不交给扫描器
     */
    fun nodeRulesAsScannerRules(context: Context, app: String, activity: String?): List<SkipRule> =
        read(context)
            .filter {
                it.enabled && it.kind == "node" && it.app == app &&
                    it.nodePath.isNullOrBlank() &&
                    activityMatches(it.activity, activity)
            }
            .map { r ->
                SkipRule(
                    id = "user:${r.id}",
                    patterns = listOfNotNull(r.textContains),
                    viewIds = listOfNotNull(r.viewId?.lowercase(java.util.Locale.ROOT)),
                    maxAreaRatio = 0.3f,
                    action = r.action,
                    cooldownMs = r.cooldownMs,
                    maxCount = r.maxCount,
                )
            }

    /** 坐标类规则：某 App（某页面）下生效的 */
    fun pointRules(context: Context, app: String, activity: String?): List<SkipAppRule> =
        read(context).filter {
            it.enabled && it.kind == "point" && it.app == app &&
                activityMatches(it.activity, activity)
        }

    /** 带结构路径的节点规则：服务按「窗口根 → 子索引」直接定位，不靠文字/ID */
    fun pathRules(context: Context, app: String, activity: String?): List<SkipAppRule> =
        read(context).filter {
            it.enabled && it.kind == "node" && it.app == app &&
                !it.nodePath.isNullOrBlank() &&
                activityMatches(it.activity, activity)
        }

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
