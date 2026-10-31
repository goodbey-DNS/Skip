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
import com.noteatoranges.skipad.db.RuleStore
import com.noteatoranges.skipad.db.SnapshotStore
import com.noteatoranges.skipad.util.AppLabels
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

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)
        Ui.edgeToEdge(this, findViewById(R.id.inset_top), findViewById(R.id.inset_bottom))

        ruleContainer = findViewById(R.id.rule_container)
        rulesEmpty = findViewById(R.id.rules_empty)
        snapContainer = findViewById(R.id.snap_container)
        snapsEmpty = findViewById(R.id.snaps_empty)

        findViewById<TextView>(R.id.back_button).setOnClickListener { finish() }

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
        (SkipAccessibilityService.instance)?.bubbleClicked()
    }

    override fun onResume() {
        super.onResume()
        CaptureBubble.onClick = { bubbleClicked() }
        renderRules()
        renderSnapshots()
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
                    ).joinToString(" / ")
                    else -> "坐标 (${(100 * (r.xRatio ?: 0f)).toInt()}%, ${(100 * (r.yRatio ?: 0f)).toInt()}%) 延迟${r.delayMs}ms"
                }
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            })
            row.addView(info)
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
                    text = info.label
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
                text = "${info.label}（${meta?.pkg}）· ${meta?.nodeCount ?: 0} 节点"
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            })
            snapContainer.addView(row)
            // 删除
            snapContainer.addView(TextView(this).apply {
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
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
