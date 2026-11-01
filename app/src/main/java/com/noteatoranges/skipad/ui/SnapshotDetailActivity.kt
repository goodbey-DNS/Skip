package com.noteatoranges.skipad.ui

import android.content.Intent
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
        // 缩放基准用抓取时的屏幕像素（meta），不能用解码位图尺寸：
        // 位图按 inSampleSize=2 解码，只有一半大，拿它算会让控件框整体偏 2 倍
        overlay.setContent(bmp, nodes, meta.screenW, meta.screenH)
        overlay.onNodeTapped = { node -> selectNode(node) }
        overlay.onLongTapped = { x, y -> askLongPressPoint(x, y) }
        genButton.setOnClickListener {
            selected?.let { showGenerateDialog(it) }
        }

        renderNodes()

        // 降级快照（读不到控件树，节点为空）抓取时认不出包名，让用户手动指定，
        // 否则生成的坐标规则 app 字段是错的，永远不会生效
        val appRow = findViewById<TextView>(R.id.app_row)
        if (nodes.isEmpty()) {
            appRow.visibility = View.VISIBLE
            refreshAppRow(appRow)
            appRow.setOnClickListener { pickTargetApp(appRow) }
        } else {
            appRow.visibility = View.GONE
        }
    }

    private fun refreshAppRow(row: TextView) {
        row.text = getString(R.string.snapshot_target_app, AppLabels.get(this, meta.pkg).label)
    }

    /** 从有桌面图标的已安装应用里选一个当目标 */
    private fun pickTargetApp(row: TextView) {
        val seen = LinkedHashSet<String>()
        val apps = packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
            )
            .map { it.activityInfo.packageName }
            .filter { seen.add(it) }
            .map { AppLabels.get(this, it).label to it }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.snapshot_pick_app_title)
            .setItems(apps.map { it.first }.toTypedArray()) { _, which ->
                val pkg = apps[which].second
                meta = meta.copy(pkg = pkg)
                SnapshotStore.setPkg(dir, pkg)
                refreshAppRow(row)
                findViewById<TextView>(R.id.detail_title).text = AppLabels.get(this, pkg).label
                Toast.makeText(
                    this,
                    getString(R.string.snapshot_pick_app_done, apps[which].first),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
            if (node.clickable) append(" · 能点")
            append("\n")
            if (node.text.isNotEmpty()) append("文字「${node.text.take(30)}」 ")
            if (node.desc.isNotEmpty()) append("说明「${node.desc.take(30)}」")
            if (node.vid.isNotEmpty()) append("\n编号 ${node.vid.substringAfterLast('/')}")
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
                    if (n.clickable) append(" ⬅能点")
                }
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                paint.isFakeBoldText = n.clickable
            })
            row.addView(TextView(this).apply {
                text = buildString {
                    if (n.text.isNotEmpty()) append("文字「${n.text.take(24)}」 ")
                    if (n.desc.isNotEmpty()) append("说明「${n.desc.take(24)}」")
                    if (n.vid.isNotEmpty()) append("编号 ${n.vid.substringAfterLast('/')}")
                    if (isEmpty()) append("(没有文字)")
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
        // 无文字的图片按钮唯一的稳定定位：挂在它自己（或旁边卡片）的控件引用上，
        // 偏移用 dp 而不是屏幕百分比，换机型/分屏/弹窗变高都不会偏。
        // 放在「按层级」之前：它认元素不认结构，比路径稳
        if (n.vid.isNotEmpty() || n.text.isNotEmpty() || n.desc.isNotEmpty()) opts.add(OPT_RELATIVE)
        if (n.path.isNotEmpty()) opts.add(OPT_PATH)
        opts.add(OPT_POINT)
        AlertDialog.Builder(this)
            .setTitle("用哪种方式找这个按钮")
            // 不能同时用 setMessage + setItems：AlertDialog 里两者互斥，
            // 实测选项列表会被说明文字顶掉，对话框里只剩一段话、根本没法选
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
                        val (rw, rh) = realScreenPx()
                        confirm(
                            "point",
                            xRatio = (n.l + n.w / 2f) / rw,
                            yRatio = (n.t + n.h / 2f) / rh,
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
        // 用截图自身的像素尺寸（overlay 已按 meta 与节点包围盒校准过），
        // 不用 displayMetrics：后者是不含导航栏的「应用可用高度」，中心点会偏上
        val srcW = overlay.contentW.takeIf { it > 0 } ?: dm.widthPixels
        val srcH = overlay.contentH.takeIf { it > 0 } ?: dm.heightPixels
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
                "已取点：从屏幕中心往右 ${dpX.toInt()}、往下 ${dpY.toInt()}\n" +
                    "单位约一根手指宽（dp），右下为正。数字可以直接改来微调；" +
                    "存的是这个单位不是百分比，换分辨率也不会偏。"
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
            .setTitle("选一个参照物，再填往哪边偏")
            .setMessage(
                "参照物：${anchor.take(24)}\n\n" +
                    "填「往右多少, 往下多少」，单位约一根手指宽；负数就是往左/往上。\n" +
                    "0, 0 = 点这个参照物的下边缘正中间（不是它的中心）。\n" +
                    "例：叉号在弹窗右上角，先选弹窗里的标题，再填 130, -60。"
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
            .setTitle("它上面写的哪几个字")
            .setMessage("只要按钮文字里包含这几个字就算命中。倒计时数字已经帮你去掉，也可以自己改短。")
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
                    "\n\n" + scopeNote() +
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
                sb.append("方式：按层级找（App 改版会失效）\n$nodePath\n\n")
                val hit = nodes.firstOrNull { it.path == nodePath }
                if (hit == null) {
                    sb.append("⚠ 这一帧里没找到这个层级，规则可能不生效")
                } else {
                    sb.append("这一帧命中：#${hit.idx} ${hit.cls.substringAfterLast('.')}")
                    if (hit.text.isNotEmpty() || hit.desc.isNotEmpty()) {
                        sb.append("「${hit.text.ifEmpty { hit.desc }.take(16)}」")
                    }
                    sb.append("\n位置 (${hit.l},${hit.t}) ${hit.w}×${hit.h}")
                    if (hit.vid.isEmpty() && hit.text.isEmpty() && hit.desc.isEmpty()) {
                        sb.append("\n这个按钮没字也没 id，只能按层级找")
                    }
                    sb.append("\n按页面结构记的，App 改版就会失效；失效了就重新抓一张快照")
                }
            }
            dpX != null && dpY != null -> {
                sb.append("方式：按屏幕位置，从中心往右 ${dpX.toInt()}、往下 ${dpY.toInt()}\n\n")
                val (rw, rh) = realScreenPx()
                val d = resources.displayMetrics.density
                val ax = rw / 2f + dpX * d
                val ay = rh / 2f + dpY * d
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
                // 偏移是 dp，节点矩形是像素：不换算会把「往右 130」显示成 130px
                val d = resources.displayMetrics.density
                sb.append("方式：挨着一个参照物点，往右/往下偏移\n")
                sb.append(if (anchorVid != null) "参照物编号 $anchorVid" else "参照物文字「${anchorText?.take(16)}」")
                sb.append("，偏移 ${dxDp ?: 0}, ${dyDp ?: 0}\n\n")
                val hits = nodes.filter {
                    if (anchorVid != null) it.vid.contains(anchorVid, ignoreCase = true)
                    else it.text.contains(anchorText ?: "", ignoreCase = true) ||
                        it.desc.contains(anchorText ?: "", ignoreCase = true)
                }.sortedBy { it.w.toLong() * it.h }
                if (hits.isEmpty()) {
                    sb.append("⚠ 这一帧里没找到这个参照物，规则可能不生效")
                } else {
                    val a = hits.first()
                    sb.append("这一帧找到 ${hits.size} 个，取面积最小：#${a.idx} ${a.cls.substringAfterLast('.')}")
                    sb.append("\n落点约 (${(a.l + a.w / 2 + (dxDp ?: 0) * d).toInt()}, ${(a.b + (dyDp ?: 0) * d).toInt()})")
                }
            }
            viewId != null -> {
                val hits = nodes.filter { it.vid.contains(viewId, ignoreCase = true) }
                sb.append("方式：认这个编号\n$viewId\n\n")
                if (hits.isEmpty()) {
                    sb.append("⚠ 这一帧里没有节点用这个编号，规则可能不生效")
                } else {
                    sb.append("这一帧命中 ${hits.size} 个节点")
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
                sb.append("方式：文字里包含「$textContains」\n\n")
                if (hits.isEmpty()) {
                    sb.append("⚠ 这一帧里没有匹配的文字，规则可能不生效")
                } else {
                    sb.append("这一帧命中 ${hits.size} 个节点")
                    hits.take(3).forEach {
                        sb.append("\n#${it.idx} ${it.cls.substringAfterLast('.')} ")
                        sb.append("「${it.text.ifEmpty { it.desc }.take(16)}」")
                    }
                }
            }
            else -> {
                val (rw, rh) = realScreenPx()
                val xr = xRatio ?: 0f
                val yr = yRatio ?: 0f
                val x = rw * xr
                val y = rh * yr
                sb.append("方式：屏幕的 ${(100 * xr).toInt()}%，${(100 * yr).toInt()}%\n\n")
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
                sb.append("\n\n屏幕位置规则只在打开 App 时自动点一次。")
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

    /**
     * 规则的生效范围说明。
     *
     * 快照没记到 Activity 类名时（服务在该 App 上还没收到带真实类名的窗口事件），
     * activity 存空 → 规则变成「全 App 生效」。这对用户是风险扩大，必须当面说清，
     * 不能像以前那样写一句「只在「本页面」生效」却实际放开到整个应用。
     */
    private fun scopeNote(): String {
        val act = meta.activity.trim()
        return if (act.isEmpty()) {
            "⚠ 这份快照没记到页面名：规则会对整个 App 生效，误点风险比只限一页大。\n建议重抓一次快照再生成。\n\n"
        } else {
            "只在这一页生效（${act.substringAfterLast('.')}），换到别的页面就不动手。\n\n"
        }
    }

    /**
     * 真实屏幕像素（含状态栏 / 导航栏）。
     *
     * 规则里的坐标（屏幕比例 / 相对中心 dp）都在这一套空间里生成与回放；
     * displayMetrics 给的是不含导航栏的应用可用高度，两边混用会把落点整体抬高。
     */
    private fun realScreenPx(): Pair<Int, Int> = try {
        val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    } catch (e: Exception) {
        resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
    }

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
        const val OPT_TEXT = "按文字：按钮上写着「跳过」这类字"
        const val OPT_VID = "按编号：没字，但有一串 id（最稳）"
        const val OPT_PATH = "按层级找：叉号没字没 id（易失效）"
        const val OPT_POINT = "按屏幕位置：整页读不到控件（最盲）"
        const val OPT_RELATIVE = "挨着某个元素点：叉号旁边有文字"

        /** 抽核心词用的词表，长词在前（firstOrNull 取第一个命中） */
        val CORE_WORDS = listOf("跳过广告", "关闭", "跳过", "close", "skip", "×", "✕")
    }
}
