package com.noteatoranges.skipad.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
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
    private lateinit var scroll: android.widget.ScrollView
    private lateinit var infoCard: View
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
        scroll = findViewById(R.id.scroll)
        infoCard = findViewById(R.id.node_info_card)

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
        // 滚到信息卡片：选完节点就要能直接看到「生成跳过规则」按钮。
        // 之前是滚回顶部，截图占满屏时按钮又被推到视野外，等于白选
        scroll.post { scroll.smoothScrollTo(0, (infoCard.top - dp(80)).coerceAtLeast(0)) }
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
                setOnClickListener { selectNode(n) }
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
        val opts = ArrayList<String>()
        if (n.text.isNotEmpty() || n.desc.isNotEmpty()) opts.add(OPT_TEXT)
        if (n.vid.isNotEmpty()) opts.add(OPT_VID)
        if (n.path.isNotEmpty()) opts.add(OPT_PATH)
        opts.add(OPT_POINT)
        // 无文字的图片按钮唯一的稳定定位：挂在它自己（或旁边卡片）的控件引用上，
        // 偏移用 dp 而不是屏幕百分比，换机型/分屏/弹窗变高都不会偏
        if (n.vid.isNotEmpty() || n.text.isNotEmpty() || n.desc.isNotEmpty()) opts.add(OPT_RELATIVE)
        AlertDialog.Builder(this)
            .setTitle("为 ${meta.pkg} 生成规则")
            .setItems(opts.toTypedArray()) { _, which ->
                when (opts[which]) {
                    OPT_TEXT -> askText(n)
                    OPT_VID -> confirm("node", viewId = n.vid)
                    OPT_PATH -> confirm(
                        "node",
                        nodePath = n.path,
                        nodeWinId = n.winId,
                        nodeCls = n.cls,
                        nodeWDp = (n.w / resources.displayMetrics.density).toInt(),
                        nodeHDp = (n.h / resources.displayMetrics.density).toInt(),
                    )
                    OPT_RELATIVE -> askRelative(n)
                    else -> {
                        val dm = resources.displayMetrics
                        confirm(
                            "point",
                            xRatio = (n.l + n.w / 2f) / dm.widthPixels,
                            yRatio = (n.t + n.h / 2f) / dm.heightPixels,
                        )
                    }
                }
            }
            .show()
    }

    /**
     * 长按截图取点：不依赖节点树（H5 / 自绘控件根本没节点），
     * 存成「相对屏幕中心的 dp」，回放时按当前屏幕密度换算。
     */
    private fun askLongPressPoint(x: Int, y: Int) {
        val dm = resources.displayMetrics
        val srcW = if (meta.screenW > 0) meta.screenW else dm.widthPixels
        val srcH = if (meta.screenH > 0) meta.screenH else dm.heightPixels
        // 抓快照时的密度未必等于当前密度，按抓取尺寸比例换算成 dp 更稳
        val srcDensity = dm.density * (srcW.toFloat() / dm.widthPixels)
        val dpX = (x - srcW / 2f) / srcDensity
        val dpY = (y - srcH / 2f) / srcDensity
        val input = EditText(this).apply {
            setText("%.0f, %.0f".format(dpX, dpY))
            setSelection(text.length)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("长按取点")
            .setMessage(
                "点已取：屏幕中心偏移 ${dpX.toInt()}, ${dpY.toInt()} dp。\n" +
                    "格式：dx, dy（dp，右下为正）。可直接改数值微调。\n" +
                    "存的是 dp 不是百分比，换分辨率也不会偏。"
            )
            .setView(input)
            .setPositiveButton("生成规则") { _, _ ->
                val parts = input.text.toString().split(",", "，")
                val dx = parts.getOrNull(0)?.trim()?.toFloatOrNull() ?: dpX
                val dy = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: dpY
                confirm("point", dpX = dx, dpY = dy)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 相对控件坐标：落点 = 锚点中心 X + dx、锚点底边 Y + dy，单位 dp */
    private fun askRelative(n: SnapshotStore.Node) {
        val anchor = n.vid.ifEmpty { n.text.ifEmpty { n.desc } }
        val input = EditText(this).apply {
            setText("0, 0")
            setSelection(text.length)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("相对此控件")
            .setMessage(
                "锚点：${anchor.take(30)}\n\n" +
                    "格式：dx, dy（dp）。0, 0 = 点这个控件本身；" +
                    "要点头顶卡片下方的关闭钮，就选那张卡片再填 0, 24。"
            )
            .setView(input)
            .setPositiveButton("下一步") { _, _ ->
                val parts = input.text.toString().split(",", "，")
                val dx = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                val dy = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                val hasVid = n.vid.isNotEmpty()
                confirm(
                    "point",
                    anchorVid = if (hasVid) n.vid else null,
                    anchorText = if (hasVid) null else n.text.ifEmpty { n.desc }.takeIf { it.isNotEmpty() },
                    dxDp = dx,
                    dyDp = dy,
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 文字规则先让用户确认匹配词：开屏按钮常写成"3 秒后跳过"，
     * 原样保存明天变"5 秒后跳过"就失效，所以默认抽出核心词再让用户改。
     */
    private fun askText(n: SnapshotStore.Node) {
        val raw = n.text.ifEmpty { n.desc }
        val input = EditText(this).apply {
            setText(shortenText(raw))
            setSelection(text.length)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("匹配文字")
            .setMessage("按「包含」匹配。倒计时数字已自动去掉，也可以手动改短。")
            .setView(input)
            .setPositiveButton("下一步") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isEmpty()) {
                    Toast.makeText(this, "文字不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                confirm("node", textContains = t)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** '3 秒后跳过' → '跳过'；'跳过广告 5' → '跳过广告'；不含核心词才退化成去数字 */
    private fun shortenText(raw: String): String {
        val t = raw.trim()
        CORE_WORDS.firstOrNull { t.contains(it, ignoreCase = true) }?.let { return it }
        val s = t.replace(Regex("[\\d\\s]+"), "").trim()
        return s.ifEmpty { t }
    }

    /** 保存前先拿快照的节点树跑一遍，告诉用户这条规则在本帧会点到什么 */
    private fun confirm(
        kind: String,
        viewId: String? = null,
        textContains: String? = null,
        xRatio: Float? = null,
        yRatio: Float? = null,
        anchorVid: String? = null,
        anchorText: String? = null,
        dxDp: Int? = null,
        dyDp: Int? = null,
        nodePath: String? = null,
        nodeWinId: Int = 0,
        nodeCls: String? = null,
        nodeWDp: Int? = null,
        nodeHDp: Int? = null,
        dpX: Float? = null,
        dpY: Float? = null,
    ) {
        AlertDialog.Builder(this)
            .setTitle("确认生成")
            .setMessage(
                preview(
                    viewId, textContains, xRatio, yRatio, anchorVid, anchorText, dxDp, dyDp,
                    nodePath, dpX, dpY,
                ) +
                    "\n\n只在「${meta.activity.substringAfterLast('.').ifEmpty { "本页面" }}」生效。" +
                    "动作默认点击，可在规则页改成返回键。"
            )
            .setPositiveButton("保存") { _, _ ->
                RuleStore.add(
                    this,
                    rule(
                        kind,
                        viewId = viewId,
                        textContains = textContains,
                        xRatio = xRatio,
                        yRatio = yRatio,
                        anchorVid = anchorVid,
                        anchorText = anchorText,
                        dxDp = dxDp,
                        dyDp = dyDp,
                        nodePath = nodePath,
                        nodeWinId = nodeWinId,
                        nodeCls = nodeCls,
                        nodeWDp = nodeWDp,
                        nodeHDp = nodeHDp,
                        dpX = dpX,
                        dpY = dpY,
                    ),
                )
                Toast.makeText(this, R.string.rule_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun preview(
        viewId: String?,
        textContains: String?,
        xRatio: Float?,
        yRatio: Float?,
        anchorVid: String? = null,
        anchorText: String? = null,
        dxDp: Int? = null,
        dyDp: Int? = null,
        nodePath: String? = null,
        dpX: Float? = null,
        dpY: Float? = null,
    ): String {
        val sb = StringBuilder()
        when {
            nodePath != null -> {
                sb.append("方式：结构路径 $nodePath\n\n")
                val hit = nodes.firstOrNull { it.path == nodePath }
                if (hit == null) {
                    sb.append("⚠ 本帧没有这个路径，规则可能不生效")
                } else {
                    sb.append("本帧命中：#${hit.idx} ${hit.cls.substringAfterLast('.')}")
                    if (hit.text.isNotEmpty() || hit.desc.isNotEmpty()) {
                        sb.append("「${hit.text.ifEmpty { hit.desc }.take(16)}」")
                    }
                    sb.append("\n位置 (${hit.l},${hit.t}) ${hit.w}×${hit.h}")
                    if (hit.vid.isEmpty() && hit.text.isEmpty() && hit.desc.isEmpty()) {
                        sb.append("\n这是纯图标控件，没有 id/文字——路径定位正是为它准备的")
                    }
                    sb.append("\n路径跟随控件层级：App 改版后可能失效，失效时重抓快照")
                }
            }
            dpX != null && dpY != null -> {
                sb.append("方式：屏幕中心偏移 ${dpX.toInt()}, ${dpY.toInt()} dp\n\n")
                val dm = resources.displayMetrics
                val ax = dm.widthPixels / 2f + dpX * dm.density
                val ay = dm.heightPixels / 2f + dpY * dm.density
                sb.append("换算到当前屏幕：(${ax.toInt()}, ${ay.toInt()})\n")
                val under = nodes.filter {
                    it.w > 0 && it.h > 0 && ax >= it.l && ax <= it.r && ay >= it.t && ay <= it.b
                }.minByOrNull { it.w.toLong() * it.h }
                sb.append("落点：")
                if (under != null) {
                    sb.append("#${under.idx} ${under.cls.substringAfterLast('.')}")
                    if (under.text.isNotEmpty()) sb.append("「${under.text.take(16)}」")
                } else {
                    sb.append("没有落在任何节点上（可能落在图片内部，仍可点）")
                }
            }
            anchorVid != null || anchorText != null -> {
                sb.append("方式：相对控件（锚点")
                sb.append(if (anchorVid != null) " id=$anchorVid" else " 文字「${anchorText?.take(16)}」")
                sb.append("）偏移 ${dxDp ?: 0}, ${dyDp ?: 0} dp\n\n")
                val hits = nodes.filter {
                    if (anchorVid != null) it.vid.contains(anchorVid, ignoreCase = true)
                    else it.text.contains(anchorText ?: "", ignoreCase = true) ||
                        it.desc.contains(anchorText ?: "", ignoreCase = true)
                }.sortedBy { it.w.toLong() * it.h }
                if (hits.isEmpty()) {
                    sb.append("⚠ 本帧没有这个锚点，规则可能不生效")
                } else {
                    val a = hits.first()
                    sb.append("本帧命中 ${hits.size} 个锚点，取面积最小：#${a.idx} ${a.cls.substringAfterLast('.')}")
                    sb.append("\n落点约 (${a.l + a.w / 2 + (dxDp ?: 0)}, ${a.b + (dyDp ?: 0)})")
                }
            }
            viewId != null -> {
                val hits = nodes.filter { it.vid.contains(viewId, ignoreCase = true) }
                sb.append("方式：控件 ID 包含\n$viewId\n\n")
                if (hits.isEmpty()) {
                    sb.append("⚠ 本帧没有节点使用这个 ID，规则可能不生效")
                } else {
                    sb.append("本帧命中 ${hits.size} 个节点")
                    hits.take(3).forEach {
                        sb.append("\n#${it.idx} ${it.cls.substringAfterLast('.')} ${it.w}×${it.h}")
                    }
                    if (hits.size > 1) {
                        sb.append("\n\n⚠ 多个节点共用这个 ID，在别的页面可能点到别的东西")
                    }
                }
            }
            textContains != null -> {
                val hits = nodes.filter {
                    it.text.contains(textContains, ignoreCase = true) ||
                        it.desc.contains(textContains, ignoreCase = true)
                }
                sb.append("方式：文字包含「$textContains」\n\n")
                if (hits.isEmpty()) {
                    sb.append("⚠ 本帧没有节点匹配，规则可能不生效")
                } else {
                    sb.append("本帧命中 ${hits.size} 个节点")
                    hits.take(3).forEach {
                        sb.append("\n#${it.idx} ${it.cls.substringAfterLast('.')} ")
                        sb.append("「${it.text.ifEmpty { it.desc }.take(16)}」")
                    }
                }
            }
            else -> {
                val dm = resources.displayMetrics
                val xr = xRatio ?: 0f
                val yr = yRatio ?: 0f
                val x = dm.widthPixels * xr
                val y = dm.heightPixels * yr
                sb.append("方式：坐标 ${(100 * xr).toInt()}%，${(100 * yr).toInt()}%\n\n")
                // 取面积最小的包含节点：根节点覆盖全屏，取第一个永远命中根节点
                val under = nodes.filter {
                    it.w > 0 && it.h > 0 && x >= it.l && x <= it.r && y >= it.t && y <= it.b
                }.minByOrNull { it.w.toLong() * it.h }
                if (under != null) {
                    sb.append("落点：#${under.idx} ${under.cls.substringAfterLast('.')}")
                    if (under.text.isNotEmpty()) sb.append("「${under.text.take(16)}」")
                } else {
                    sb.append("落点：没有落在任何节点上")
                }
                sb.append("\n\n坐标规则只在打开 App 时自动点一次。")
                sb.append("应用内的广告，请长按悬浮钮手动执行。")
            }
        }
        return sb.toString()
    }

    private fun rule(
        kind: String,
        viewId: String? = null,
        textContains: String? = null,
        xRatio: Float? = null,
        yRatio: Float? = null,
        anchorVid: String? = null,
        anchorText: String? = null,
        dxDp: Int? = null,
        dyDp: Int? = null,
        nodePath: String? = null,
        nodeWinId: Int = 0,
        nodeCls: String? = null,
        nodeWDp: Int? = null,
        nodeHDp: Int? = null,
        dpX: Float? = null,
        dpY: Float? = null,
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
        // 快照是在哪个页面抓的，规则就先只在那个页面生效；换页不生效，误触面最小
        activity = meta.activity.takeIf { it.isNotBlank() },
        anchorVid = anchorVid,
        anchorText = anchorText,
        dxDp = dxDp,
        dyDp = dyDp,
        nodePath = nodePath,
        nodeWinId = nodeWinId,
        nodeCls = nodeCls,
        nodeWDp = nodeWDp,
        nodeHDp = nodeHDp,
        dpX = dpX,
        dpY = dpY,
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 节点列表是动态生成的（一个大快照能到 1600 个 View），
     * 页面退掉后主动断开引用，别等 GC 慢慢收
     */
    override fun onDestroy() {
        super.onDestroy()
        if (::nodeContainer.isInitialized) nodeContainer.removeAllViews()
    }

    private companion object {
        const val OPT_TEXT = "按文字"
        const val OPT_VID = "按控件 ID"
        const val OPT_PATH = "按结构路径（图标按钮推荐）"
        const val OPT_POINT = "按坐标（屏幕比例）"
        const val OPT_RELATIVE = "相对此控件（推荐无文字按钮）"

        /** 抽核心词用的词表，长词在前（firstOrNull 取第一个命中） */
        val CORE_WORDS = listOf("跳过广告", "关闭", "跳过", "close", "skip", "×", "✕")
    }
}
