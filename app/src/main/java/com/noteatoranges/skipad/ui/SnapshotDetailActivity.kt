package com.noteatoranges.skipad.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.noteatoranges.skipad.R
import com.noteatoranges.skipad.db.RuleStore
import com.noteatoranges.skipad.db.SkipAppRule
import com.noteatoranges.skipad.db.SnapshotStore
import com.noteatoranges.skipad.util.AppLabels
import com.noteatoranges.skipad.util.Ui
import java.io.File
import java.util.UUID

/**
 * 快照详情（GKD 式可视化点选）：广告截图上叠加控件框，
 * 点图上任意元素（或下方列表行）选中 → 底部信息条确认 → 生成跳过规则。
 */
class SnapshotDetailActivity : AppCompatActivity() {

    private lateinit var dir: File
    private lateinit var meta: SnapshotStore.Meta
    private lateinit var overlay: ScreenshotOverlayView
    private lateinit var infoText: TextView
    private lateinit var genButton: Button
    private lateinit var nodeContainer: LinearLayout
    private var nodes: List<SnapshotStore.Node> = emptyList()
    private var selected: SnapshotStore.Node? = null
    private var selectedRow: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snapshot_detail)
        Ui.edgeToEdge(this, findViewById(R.id.inset_top), findViewById(R.id.inset_bottom))

        // 路径必须落在自己的 snapshots 目录内：这个 Activity 现在是 exported=false，
        // 但这里直接接受绝对路径且原样使用，一旦将来加 deep link / 改导出就是任意文件读。
        val snapRoot = runCatching { File(filesDir, "snapshots").canonicalFile }.getOrNull()
        val incoming = runCatching {
            File(intent.getStringExtra("dir") ?: "").canonicalFile
        }.getOrNull()
        if (snapRoot == null || incoming == null || !incoming.exists() ||
            !incoming.path.startsWith(snapRoot.path + File.separator)
        ) {
            finish()
            return
        }
        dir = incoming
        meta = SnapshotStore.loadMeta(dir) ?: run { finish(); return }
        overlay = findViewById(R.id.overlay)
        infoText = findViewById(R.id.node_info_text)
        genButton = findViewById(R.id.gen_button)
        nodeContainer = findViewById(R.id.node_container)

        findViewById<TextView>(R.id.back_button).setOnClickListener { finish() }
        findViewById<TextView>(R.id.detail_title).text = AppLabels.get(this, meta.pkg).label

        val screen = SnapshotStore.loadScreen(dir)
        val bmp = screen?.let { BitmapFactory.decodeFile(it.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) }
        nodes = SnapshotStore.loadNodes(dir)
        overlay.setContent(bmp, nodes)
        overlay.onNodeTapped = { node -> selectNode(node) }
        genButton.setOnClickListener {
            selected?.let { showGenerateDialog(it) }
        }

        renderNodes()
    }

    private fun selectNode(node: SnapshotStore.Node?) {
        selected = node
        overlay.select(node)
        if (node == null) {
            infoText.text = getString(R.string.snapshot_pick_hint)
            genButton.isEnabled = false
            return
        }
        infoText.text = buildString {
            append("#${node.idx} ${node.cls.substringAfterLast('.')}")
            if (node.clickable) append(" · 可点击")
            append("\n")
            if (node.text.isNotEmpty()) append("文本「${node.text.take(30)}」 ")
            if (node.desc.isNotEmpty()) append("描述「${node.desc.take(30)}」")
            if (node.vid.isNotEmpty()) append("\nid=${node.vid}")
            append("\n位置 (${node.l},${node.t}) ${node.w}×${node.h}")
        }
        genButton.isEnabled = true
        // 列表行高亮同步
        for (i in 0 until nodeContainer.childCount) {
            val row = nodeContainer.getChildAt(i)
            row.setBackgroundColor(
                if (row.tag == node) getColor(R.color.status_on) else Color.TRANSPARENT
            )
        }
        // 滚回顶部看高亮
        findViewById<android.widget.ScrollView>(R.id.scroll).smoothScrollTo(0, 0)
    }

    private fun renderNodes() {
        nodeContainer.removeAllViews()
        if (nodes.isEmpty()) {
            findViewById<TextView>(R.id.empty_text).visibility = View.VISIBLE
            return
        }
        findViewById<TextView>(R.id.empty_text).visibility = View.GONE
        val sorted = nodes.sortedWith(
            compareByDescending<SnapshotStore.Node> { it.clickable }.thenBy { it.w * it.h }
        )
        sorted.forEach { n ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setBackgroundResource(R.drawable.bg_card)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                tag = n
                setOnClickListener { selectNode(n); findViewById<android.widget.ScrollView>(R.id.scroll).smoothScrollTo(0, 0) }
            }
            row.addView(TextView(this).apply {
                text = buildString {
                    append("#${n.idx} ${n.cls.substringAfterLast('.')}")
                    if (n.clickable) append(" ⬅可点击")
                }
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                paint.isFakeBoldText = n.clickable
            })
            row.addView(TextView(this).apply {
                text = buildString {
                    if (n.text.isNotEmpty()) append("文本「${n.text.take(24)}」 ")
                    if (n.desc.isNotEmpty()) append("描述「${n.desc.take(24)}」")
                    if (n.vid.isNotEmpty()) append("id=${n.vid.substringAfterLast('/')}")
                    if (isEmpty()) append("(无文本)")
                }
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            })
            row.addView(TextView(this).apply {
                text = "位置 (${n.l},${n.t}) ${n.w}×${n.h}"
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
            })
            nodeContainer.addView(row)
        }
    }

    private fun showGenerateDialog(n: SnapshotStore.Node) {
        val options = mutableListOf(getString(R.string.rule_by_point))
        if (n.vid.isNotEmpty()) options.add(0, getString(R.string.rule_by_vid))
        if (n.text.isNotEmpty() || n.desc.isNotEmpty()) options.add(0, getString(R.string.rule_by_text))
        AlertDialog.Builder(this)
            .setTitle("为 ${meta.pkg} 生成规则")
            .setItems(options.toTypedArray()) { _, which ->
                val chosen = options[which]
                val dm = resources.displayMetrics
                when (chosen) {
                    getString(R.string.rule_by_vid) -> RuleStore.add(
                        this, rule("node", viewId = n.vid)
                    )
                    getString(R.string.rule_by_text) -> RuleStore.add(
                        this, rule("node", textContains = n.text.ifEmpty { n.desc })
                    )
                    else -> RuleStore.add(
                        this,
                        rule(
                            "point",
                            xRatio = (n.l + n.w / 2f) / dm.widthPixels,
                            yRatio = (n.t + n.h / 2f) / dm.heightPixels,
                        )
                    )
                }
                Toast.makeText(this, R.string.rule_saved, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun rule(
        kind: String,
        viewId: String? = null,
        textContains: String? = null,
        xRatio: Float? = null,
        yRatio: Float? = null,
    ) = SkipAppRule(
        id = UUID.randomUUID().toString(),
        app = meta.pkg,
        appLabel = AppLabels.get(this, meta.pkg).label,
        kind = kind,
        viewId = viewId,
        textContains = textContains,
        xRatio = xRatio,
        yRatio = yRatio,
        delayMs = 900L,
        createdAt = System.currentTimeMillis(),
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
