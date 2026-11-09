package com.noteatoranges.skipad.ui

import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import android.net.Uri
import android.provider.Settings
import com.noteatoranges.skipad.util.CaptureBubble
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.SkipAccessibilityService
import com.noteatoranges.skipad.db.KeywordRule
import com.noteatoranges.skipad.db.KeywordStore
import com.noteatoranges.skipad.db.RuleStore
import com.noteatoranges.skipad.db.SnapshotStore
import com.noteatoranges.skipad.util.AppLabels
import com.noteatoranges.skipad.util.EnginePrefs
import com.noteatoranges.skipad.util.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 规则页：自建跳过规则管理 + 快照列表入口（GKD 式点选生成规则的入口页） */
class RulesActivity : AppCompatActivity() {

    companion object {
        /** 列表界面的折叠状态等，纯粹是界面偏好，丢了不影响任何功能 */
        private const val UI_PREFS = "rules_ui"
        /** 刚生成规则的那个 App：回到列表时要自动展开它的分组 */
        const val PREF_EXPAND_PKG = "expand_pkg"
    }

    /** 折叠状态：App 包名 → 是否展开。默认全部收起，用户点开才看细节 */
    private val expandedGroups = mutableMapOf<String, Boolean>()

    private lateinit var ruleContainer: LinearLayout
    private lateinit var rulesEmpty: TextView
    private lateinit var snapContainer: LinearLayout
    private lateinit var snapsEmpty: TextView
    private lateinit var kwContainer: LinearLayout
    private lateinit var kwEmpty: TextView

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)
        Ui.edgeToEdge(this, findViewById(R.id.inset_top), findViewById(R.id.inset_bottom))

        ruleContainer = findViewById(R.id.rule_container)
        rulesEmpty = findViewById(R.id.rules_empty)
        snapContainer = findViewById(R.id.snap_container)
        snapsEmpty = findViewById(R.id.snaps_empty)
        kwContainer = findViewById(R.id.kw_container)
        kwEmpty = findViewById(R.id.kw_empty)

        findViewById<TextView>(R.id.back_button).setOnClickListener { finish() }

        findViewById<TextView>(R.id.kw_add).setOnClickListener { showPickAppForKeyword() }
        val kwSw = findViewById<SwitchCompat>(R.id.kw_builtin)
        kwSw.isChecked = EnginePrefs.builtinWordsEnabled(this)
        kwSw.setOnCheckedChangeListener { _, checked ->
            EnginePrefs.setBuiltinWordsEnabled(this, checked)
            // 开关一变，服务下一次事件就会重建词表扫描器，不需要重启
            Toast.makeText(this, if (checked) "内置默认词已启用" else "已关闭内置默认词，只认你填的词", Toast.LENGTH_SHORT).show()
        }

        val sw = findViewById<SwitchCompat>(R.id.bubble_switch)
        sw.isChecked = CaptureBubble.isEnabled(this) && Settings.canDrawOverlays(this)
        sw.setOnClickListener {
            if (sw.isChecked && !Settings.canDrawOverlays(this)) {
                sw.isChecked = false
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, ("package:$packageName").toUri())
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
                Toast.makeText(this, "请先允许「显示在其他应用上层」", Toast.LENGTH_LONG).show()
            } else {
                CaptureBubble.setEnabled(this, sw.isChecked)
                CaptureBubble.apply(this)
                CaptureBubble.onClick = { bubbleClicked() }
            }
        }
    }

    private fun bubbleClicked() {
        val svc = SkipAccessibilityService.instance
        if (svc == null) {
            // 服务没连上时点圆钮什么都不会发生，用户会以为坏了
            Toast.makeText(this, "无障碍服务未连接，抓不到快照", Toast.LENGTH_SHORT).show()
            return
        }
        svc.bubbleClicked()
    }

    override fun onResume() {
        super.onResume()
        CaptureBubble.onClick = { bubbleClicked() }
        renderRules()
        renderKeywords()
        renderSnapshots()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 上面两行赋值捕获的是本 Activity，而 CaptureBubble 是进程级单例：
        // 不处理的话它会一直攥着一个已经销毁的 Activity，直到下次被覆盖。
        // 退出时改指回服务实例——服务是长生命周期的，既释放了 Activity，
        // 又不会把还挂在屏幕上的悬浮钮弄成哑巴
        val svc = SkipAccessibilityService.instance
        CaptureBubble.onClick = if (svc != null) ({ svc.bubbleClicked() }) else null
    }

    // —————————— 关键字 ——————————

    /** 关键字列表：一行一个作用对象，显示命中词与排除词 */
    private fun renderKeywords() {
        val rules = KeywordStore.all(this)
        kwContainer.removeAllViews()
        kwEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        rules.forEach { k ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = if (k.isGlobal) "所有应用" else k.appLabel.ifEmpty { k.app }
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                paint.isFakeBoldText = true
            })
            info.addView(TextView(this).apply {
                text = k.desc
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            })
            row.addView(info)
            row.addView(SwitchCompat(this).apply {
                isChecked = k.enabled
                setOnCheckedChangeListener { _, checked ->
                    KeywordStore.setEnabled(this@RulesActivity, k.id, checked)
                }
            })
            row.addView(TextView(this).apply {
                text = "删除"
                textSize = 12f
                setTextColor(getColor(R.color.status_off))
                setPadding(dp(10), dp(4), 0, dp(4))
                setOnClickListener {
                    KeywordStore.remove(this@RulesActivity, k.id)
                    renderKeywords()
                }
            })
            kwContainer.addView(row)
        }
    }

    /** 第一步：选作用对象（所有应用 + 已安装的可启动应用），避免叫用户手打包名 */
    private fun showPickAppForKeyword() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = LinkedHashMap<String, String>()
        apps[KeywordRule.GLOBAL_APP] = "所有应用"
        runCatching {
            packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.applicationInfo?.packageName }
                .distinct()
                .filter { it != packageName }
                .map { it to AppLabels.get(this, it).label }
                .sortedBy { it.second }
                .forEach { (pkg, label) -> apps.putIfAbsent(pkg, label) }
        }
        val pkgs = apps.keys.toList()
        val labels = pkgs.map { apps[it] ?: it }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("关键字作用对象")
            .setItems(labels) { _, which -> showKeywordWords(pkgs[which], apps[pkgs[which]] ?: pkgs[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 第二步：填词。命中词提高识别，排除词防误点 */
    private fun showKeywordWords(pkg: String, label: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val hit = android.widget.EditText(this).apply {
            hint = "命中词：放弃福利，暂不需要"
            setSingleLine(true)
        }
        val ex = android.widget.EditText(this).apply {
            hint = "排除词：开通会员，立即支付"
            setSingleLine(true)
        }
        box.addView(hit)
        box.addView(ex)
        AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage(
                "逗号分隔，单条 ≤${KeywordRule.MAX_LEN} 字，每类 ≤${KeywordRule.MAX_WORDS} 条。" +
                    "只在开屏窗口内参与；命中词仍要求控件像按钮，排除词命中就整屏放弃。",
            )
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val hits = KeywordStore.parseWords(hit.text.toString())
                val excludes = KeywordStore.parseWords(ex.text.toString())
                if (hits.isEmpty() && excludes.isEmpty()) {
                    Toast.makeText(this, "至少填一类词", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                KeywordStore.add(
                    this,
                    KeywordRule(
                        id = java.util.UUID.randomUUID().toString(),
                        app = pkg,
                        appLabel = label,
                        hits = hits,
                        excludes = excludes,
                        enabled = true,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                Toast.makeText(this, "已保存，下次开屏生效", Toast.LENGTH_SHORT).show()
                renderKeywords()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 规则列表：按 App 分组，组头一行、默认收起。
     *
     * 以前是扁平列表，一条规则两张卡（标题+说明+操作），十几条就要滚好几屏，
     * 想找某个 App 的规则只能靠翻。分组后 8 个 App 只占 8 行，先看有哪些 App、
     * 每个 App 几条，要看细节再点开。
     *
     * 用「组内容容器切 visibility」而不是「重建整个列表」来展开/收起：
     * 重建会把 ScrollView 的滚动位置顶回顶部，点一下跳一下没法用。
     */
    private fun renderRules() {
        val rules = RuleStore.all(this)
        ruleContainer.removeAllViews()
        rulesEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        if (rules.isEmpty()) return

        // 刚生成规则的那一组自动展开，否则用户回来看不到自己刚加的东西
        val uiPrefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        uiPrefs.getString(PREF_EXPAND_PKG, null)?.let { pkg ->
            expandedGroups[pkg] = true
            uiPrefs.edit { remove(PREF_EXPAND_PKG) }
        }

        val grouped = LinkedHashMap<String, MutableList<com.noteatoranges.skipad.db.SkipAppRule>>()
        rules.forEach { grouped.getOrPut(it.app) { mutableListOf() }.add(it) }

        grouped.forEach { (pkg, list) ->
            val label = list.first().appLabel.ifBlank { pkg }
            val open = expandedGroups[pkg] == true

            // 组内容先建好，再决定显示与否
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (open) View.VISIBLE else View.GONE
            }
            list.forEach { body.addView(buildRuleRow(it)) }

            val arrow = TextView(this).apply {
                text = if (open) "▾" else "▸"
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(6), 0, 0, 0)
            }
            ruleContainer.addView(buildGroupHeader(pkg, label, list.size, arrow) {
                val nowOpen = body.visibility != View.VISIBLE
                body.visibility = if (nowOpen) View.VISIBLE else View.GONE
                arrow.text = if (nowOpen) "▾" else "▸"
                expandedGroups[pkg] = nowOpen
            })
            ruleContainer.addView(body)
        }
    }

    /** 组头：图标 + App 名 + 数量 + 折叠箭头，整行可点 */
    private fun buildGroupHeader(
        pkg: String,
        label: String,
        count: Int,
        arrow: TextView,
        unit: String = "条",
        onToggle: () -> Unit,
    ): View {
        val info = AppLabels.get(this, pkg)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener { onToggle() }
            addView(android.widget.ImageView(this@RulesActivity).apply {
                setImageDrawable(info.icon)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            })
            addView(TextView(this@RulesActivity).apply {
                text = label
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                paint.isFakeBoldText = true
                setPadding(dp(10), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@RulesActivity).apply {
                text = getString(R.string.rule_count_unit, count, unit)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            })
            addView(arrow)
        }
    }

    /**
     * 规则记账行：这条规则多久没动手了。
     *
     * 返回 null = 不显示。刚建不到 7 天的规则一律不说，否则新用户一进列表满屏
     * 「从未命中」，等于把所有规则都标成可疑。超过 30 天没命中的用警示色，
     * 但仍然不自动删——判死权留给用户，这里只负责把事实摆出来。
     */
    private fun hitLineOf(r: com.noteatoranges.skipad.db.SkipAppRule): Pair<String, Boolean>? {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        if (r.lastHitAt > 0L) {
            val days = ((now - r.lastHitAt) / day).toInt()
            val whenText = when {
                days <= 0 -> "今天命中过"
                days == 1 -> "昨天命中过"
                else -> "$days 天前命中过"
            }
            val total = if (r.hitCount > 1) " · 共 ${r.hitCount} 次" else ""
            val ev = r.lastEvidence?.takeIf { it.isNotBlank() }?.let { " · 上次点的是 ${it.take(22)}" } ?: ""
            return (whenText + total + ev) to (days >= 30)
        }
        val age = ((now - r.createdAt) / day).toInt()
        if (age < 7) return null
        return "从未命中（建了 $age 天）" to (age >= 30)
    }

    /** 单条规则：匹配条件 / 动作 / 页面范围 / 换动作 / 开关 / 删除 */
    private fun buildRuleRow(r: com.noteatoranges.skipad.db.SkipAppRule): View {        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply {
            // 坐标规则已整体移除：老数据还留在库里，但不会再执行，标出来让用户删掉
            text = if (r.kind == "node") getString(R.string.rule_kind_node) else "坐标规则（已停用）"
            textSize = 13f
            setTextColor(getColor(R.color.text_primary))
            paint.isFakeBoldText = true
        })
        info.addView(TextView(this).apply {
            text = when (r.kind) {
                "node" -> listOfNotNull(
                    r.viewId?.let { "按编号 $it" },
                    r.textContains?.let { "文字里包含「$it」" },
                    r.nodePath?.let { "按层级 $it" },
                    "动作：${r.actionLabel}",
                ).joinToString(" / ")
                else -> "按屏幕坐标的规则已停用，建议删除"
            }
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
        })
        info.addView(TextView(this).apply {
            // 页面类名对用户没有意义（VisitorMainTabActivity 这种），只说「这一页 / 整个 App」；
            // 需要区分多条规则时看上面那行的匹配条件就够了
            val scoped = !r.activity.isNullOrBlank()
            val limits = buildString {
                if (r.cooldownMs > 0) {
                    append(getString(R.string.rules_limit_gap, (r.cooldownMs / 1000f).toString()))
                }
                if (r.maxCount > 0) {
                    val res = if (isEmpty()) R.string.rules_limit_max else R.string.rules_limit_max_cont
                    append(getString(res, r.maxCount))
                }
            }
            val scope = getString(if (scoped) R.string.rule_scope_page else R.string.rule_scope_app)
            text = if (limits.isEmpty()) scope
            else getString(R.string.rule_scope_with_limits, scope, limits)
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
        })
        // 记账行：这条规则还活着吗。
        // 规则库最大的问题不是写得全不全，是没法判断哪条已经死了——
        // 一条规则可以安静失效半年，界面上它和刚建的那条长得一模一样
        hitLineOf(r)?.let { (line, dead) ->
            info.addView(TextView(this).apply {
                text = line
                textSize = 11f
                setTextColor(getColor(if (dead) R.color.status_off else R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(info)
        // 改动作：点击 → 返回键 → 只匹配，循环切换（有些弹窗只有返回键能关）
        info.addView(TextView(this).apply {
            text = "换动作"
            textSize = 11f
            setTextColor(getColor(R.color.status_on))
            setPadding(0, dp(2), 0, dp(2))
            setOnClickListener {
                val updated = RuleStore.cycleAction(this@RulesActivity, r.id)
                Toast.makeText(
                    this@RulesActivity,
                    "已改成「${updated?.actionLabel ?: ""}」",
                    Toast.LENGTH_SHORT,
                ).show()
                renderRules()
            }
        })
        val sw = SwitchCompat(this).apply {
            isChecked = r.enabled
            setOnCheckedChangeListener { _, checked ->
                RuleStore.setEnabled(this@RulesActivity, r.id, checked)
            }
        }
        row.addView(sw)
        val del = TextView(this).apply {
            text = "删除"
            textSize = 12f
            setTextColor(getColor(R.color.status_off))
            setPadding(dp(10), dp(4), 0, dp(4))
            setOnClickListener {
                AlertDialog.Builder(this@RulesActivity)
                    .setMessage(R.string.rule_delete_confirm)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_confirm_clear) { _, _ ->
                        RuleStore.remove(this@RulesActivity, r.id)
                        Toast.makeText(this@RulesActivity, R.string.rule_deleted, Toast.LENGTH_SHORT).show()
                        renderRules()
                    }
                    .show()
            }
        }
        row.addView(del)
        return row
    }

    /** 快照列表：跟规则一样按 App 分组收起，避免几十份快照铺一屏 */
    private fun renderSnapshots() {
        val snaps = SnapshotStore.list(this)
        snapContainer.removeAllViews()
        snapsEmpty.visibility = if (snaps.isEmpty()) View.VISIBLE else View.GONE
        if (snaps.isEmpty()) return

        val grouped = LinkedHashMap<String, MutableList<java.io.File>>()
        snaps.forEach { dir ->
            val pkg = SnapshotStore.loadMeta(dir)?.pkg.orEmpty()
            grouped.getOrPut(pkg) { mutableListOf() }.add(dir)
        }

        grouped.forEach { (pkg, dirs) ->
            // meta 读不出来时 pkg 是空串：给个占位名，别渲染出一个空白的组头
            val label = if (pkg.isBlank()) "未知应用" else AppLabels.get(this, pkg).label
            val open = expandedGroups[snapGroupKey(pkg)] == true

            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (open) View.VISIBLE else View.GONE
            }
            dirs.forEach { body.addView(buildSnapshotRow(it)) }

            val arrow = TextView(this).apply {
                text = if (open) "▾" else "▸"
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(6), 0, 0, 0)
            }
            snapContainer.addView(buildGroupHeader(pkg, label, dirs.size, arrow, "份") {
                val nowOpen = body.visibility != View.VISIBLE
                body.visibility = if (nowOpen) View.VISIBLE else View.GONE
                arrow.text = if (nowOpen) "▾" else "▸"
                expandedGroups[snapGroupKey(pkg)] = nowOpen
            })
            snapContainer.addView(body)
        }
    }

    /** 快照分组和规则分组共用 expandedGroups，用前缀区分，避免同名包互相顶掉 */
    private fun snapGroupKey(pkg: String): String = "snap:$pkg"

    private fun buildSnapshotRow(dir: java.io.File): View {
        val meta = SnapshotStore.loadMeta(dir)
        val info = AppLabels.get(this, meta?.pkg ?: "")
        val custom = meta?.name?.trim().orEmpty()
        val title = custom.ifEmpty { info.label }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            setOnClickListener {
                startActivity(
                    Intent(this@RulesActivity, SnapshotDetailActivity::class.java)
                        .putExtra("dir", dir.absolutePath)
                )
            }
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(this@RulesActivity).apply {
                text = title
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@RulesActivity).apply {
                text = timeFmt.format(Date(meta?.time ?: 0))
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
            })
        })
        row.addView(TextView(this).apply {
            text = buildString {
                if (custom.isNotEmpty()) append("${info.label} · ")
                append(meta?.pkg ?: "")
                append(" · ${meta?.nodeCount ?: 0} 节点")
            }
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
        })
        // 改名 / 删除
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@RulesActivity).apply {
                text = "改名"
                textSize = 12f
                setTextColor(getColor(R.color.status_on))
                setPadding(0, dp(4), dp(16), dp(4))
                setOnClickListener { showRenameDialog(dir, meta) }
            })
            addView(TextView(this@RulesActivity).apply {
                text = "删除此快照"
                textSize = 12f
                setTextColor(getColor(R.color.status_off))
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener {
                    AlertDialog.Builder(this@RulesActivity)
                        .setMessage("删除这条快照？")
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.action_confirm_clear) { _, _ ->
                            SnapshotStore.remove(this@RulesActivity, dir)
                            renderSnapshots()
                        }
                        .show()
                }
            })
        })
        return row
    }

    /** 快照改名：只动备注，不影响截图和节点树 */
    private fun showRenameDialog(dir: java.io.File, meta: SnapshotStore.Meta?) {
        val input = android.widget.EditText(this).apply {
            setText(meta?.name?.trim().orEmpty())
            setSelection(text.length)
            hint = "比如：淘宝红包弹窗"
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("重命名快照")
            .setMessage("留空则显示应用名和时间")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                SnapshotStore.rename(dir, input.text.toString())
                renderSnapshots()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
