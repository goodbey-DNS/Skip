package com.noteatoranges.skipad.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.noteatoranges.skipad.util.CaptureBubble
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        val kwSw = findViewById<Switch>(R.id.kw_builtin)
        kwSw.isChecked = EnginePrefs.builtinWordsEnabled(this)
        kwSw.setOnCheckedChangeListener { _, checked ->
            EnginePrefs.setBuiltinWordsEnabled(this, checked)
            // 开关一变，服务下一次事件就会重建词表扫描器，不需要重启
            Toast.makeText(this, if (checked) "内置默认词已启用" else "已关闭内置默认词，只认你填的词", Toast.LENGTH_SHORT).show()
        }

        val sw = findViewById<android.widget.Switch>(R.id.bubble_switch)
        sw.isChecked = CaptureBubble.isEnabled(this) && Settings.canDrawOverlays(this)
        sw.setOnClickListener {
            if (sw.isChecked && !Settings.canDrawOverlays(this)) {
                sw.isChecked = false
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
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
        CaptureBubble.onLongClick = { SkipAccessibilityService.instance?.bubbleLongClicked() }
        renderRules()
        renderKeywords()
        renderSnapshots()
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
            row.addView(Switch(this).apply {
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

    private fun renderRules() {
        val rules = RuleStore.all(this)
        ruleContainer.removeAllViews()
        rulesEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        rules.forEach { r ->
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
                text = "${r.appLabel} · ${getString(if (r.kind == "node") R.string.rule_kind_node else R.string.rule_kind_point)}"
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                paint.isFakeBoldText = true
            })
            info.addView(TextView(this).apply {
                text = when (r.kind) {
                    "node" -> listOfNotNull(
                        r.viewId?.let { "id=$it" },
                        r.textContains?.let { "文本=$it" },
                        r.nodePath?.let { "路径=$it" },
                        "动作=${r.actionLabel}",
                    ).joinToString(" / ")
                    else -> buildString {
                        if (r.hasDpPoint) {
                            append("屏幕中心偏移 ${r.dpX?.toInt()},${r.dpY?.toInt()}dp")
                        } else if (r.hasAnchor) {
                            append("锚点=${r.anchorVid?.substringAfterLast('/') ?: r.anchorText}")
                            append(" 偏移(${r.dxDp ?: 0},${r.dyDp ?: 0})dp")
                        } else {
                            append("坐标 (${(100 * (r.xRatio ?: 0f)).toInt()}%, ${(100 * (r.yRatio ?: 0f)).toInt()}%)")
                        }
                        append(" · 动作=${r.actionLabel} 延迟${r.delayMs}ms")
                    }
                }
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            })
            info.addView(TextView(this).apply {
                val scope = r.activity?.substringAfterLast('.')?.takeIf { it.isNotEmpty() } ?: "全应用"
                val limits = buildString {
                    if (r.cooldownMs > 0) append("冷却${r.cooldownMs}ms")
                    if (r.maxCount > 0) append(if (isEmpty()) "最多${r.maxCount}次" else " 最多${r.maxCount}次")
                }
                text = "范围：$scope${if (limits.isEmpty()) "" else " · $limits"}"
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
            })
            row.addView(info)
            // 改动作：点击 → 返回键 → 只匹配，循环切换（有些弹窗只有返回键能关）
            info.addView(TextView(this).apply {
                text = "改动作"
                textSize = 11f
                setTextColor(getColor(R.color.status_on))
                setPadding(0, dp(2), 0, dp(2))
                setOnClickListener {
                    val updated = RuleStore.cycleAction(this@RulesActivity, r.id)
                    Toast.makeText(
                        this@RulesActivity,
                        "动作已改为「${updated?.actionLabel ?: ""}」",
                        Toast.LENGTH_SHORT,
                    ).show()
                    renderRules()
                }
            })
            val sw = Switch(this).apply {
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
            ruleContainer.addView(row)
        }
    }

    private fun renderSnapshots() {
        val snaps = SnapshotStore.list(this)
        snapContainer.removeAllViews()
        snapsEmpty.visibility = if (snaps.isEmpty()) View.VISIBLE else View.GONE
        snaps.forEach { dir ->
            val meta = SnapshotStore.loadMeta(dir)
            val info = AppLabels.get(this, meta?.pkg ?: "")
            val custom = meta?.name?.trim().orEmpty()
            val title = custom.ifEmpty { info.label }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
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
            snapContainer.addView(row)

            // 改名 / 删除
            snapContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@RulesActivity).apply {
                    text = "改名"
                    textSize = 12f
                    setTextColor(getColor(R.color.status_on))
                    setPadding(0, 0, dp(16), dp(6))
                    setOnClickListener { showRenameDialog(dir, meta) }
                })
                addView(TextView(this@RulesActivity).apply {
                    text = "删除此快照"
                    textSize = 12f
                    setTextColor(getColor(R.color.status_off))
                    setPadding(0, 0, 0, dp(6))
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
        }
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
