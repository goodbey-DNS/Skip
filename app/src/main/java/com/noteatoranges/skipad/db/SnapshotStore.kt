package com.noteatoranges.skipad.db

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 快照（GKD 同款思路）：广告在屏时抓"截图 + 完整控件树"，供点选节点生成规则。
 * 目录 filesDir/snapshots/<ts>_<pkg>/：screen.webp（旧快照是 screen.png）+ tree.json + meta.json。
 */
object SnapshotStore {

    private const val SCREEN_FILE = "screen.webp"
    private const val LEGACY_SCREEN_FILE = "screen.png"

    data class Node(
        val idx: Int,
        val cls: String,
        val text: String,
        val desc: String,
        val vid: String,
        val clickable: Boolean,
        val l: Int,
        val t: Int,
        val r: Int,
        val b: Int,
        /** 从窗口根到该节点的子索引路径（如 "0/3/1"）：纯图标按钮没有 vid/文字，靠它定位 */
        val path: String = "",
        /** 节点所属窗口 id：同一个 App 的主窗口与浮层窗口要分开算路径 */
        val winId: Int = 0,
    ) {
        val w: Int get() = r - l
        val h: Int get() = b - t
    }

    data class Meta(
        val pkg: String,
        val activity: String,
        val time: Long,
        val nodeCount: Int,
        /** 用户自定义名字，空则列表显示应用名 + 时间 */
        val name: String = "",
        /** 抓取时的屏幕像素：长按取点的 dp 换算要用 */
        val screenW: Int = 0,
        val screenH: Int = 0,
    )

    fun save(
        context: Context,
        pkg: String,
        activity: String,
        nodes: List<Node>,
        screen: Bitmap?,
        screenW: Int = 0,
        screenH: Int = 0,
    ): File {
        val dir = File(File(context.filesDir, "snapshots"), "${System.currentTimeMillis()}_$pkg")
        dir.mkdirs()
        // 存 WebP 而不是 PNG：PNG 是无损格式，compress() 的 quality 参数对它完全无效，
        // 一张 1220x2712 的界面截图就是 1.5~4MB，30 份能吃到上百 MB。
        // WebP 质量 90 对"看清小字、点选按钮"绰绰有余，体积只有 PNG 的 1/5 左右
        val fmt = if (android.os.Build.VERSION.SDK_INT >= 30) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        screen?.compress(fmt, 90, java.io.FileOutputStream(File(dir, SCREEN_FILE)))
        val arr = JSONArray()
        nodes.forEach { n ->
            arr.put(JSONObject().apply {
                put("idx", n.idx)
                put("cls", n.cls)
                put("text", n.text)
                put("desc", n.desc)
                put("vid", n.vid)
                put("clickable", n.clickable)
                put("l", n.l); put("t", n.t); put("r", n.r); put("b", n.b)
                put("w", n.w); put("h", n.h)
                if (n.path.isNotEmpty()) put("path", n.path)
                if (n.winId != 0) put("winId", n.winId)
            })
        }
        File(dir, "tree.json").writeText(arr.toString())
        File(dir, "meta.json").writeText(
            JSONObject().apply {
                put("pkg", pkg)
                put("activity", activity)
                put("time", System.currentTimeMillis())
                put("nodeCount", nodes.size)
                put("name", "")
                put("screenW", screenW)
                put("screenH", screenH)
            }.toString()
        )
        prune(context)
        return dir
    }

    /**
     * 快照目录列表。
     *
     * 只认带 meta.json 的目录：保存过程中被系统杀掉会留下半截目录（有 screen.png、
     * 没 meta.json），它在列表里占一行却既没有标题也打不开，点了像坏了一样。
     */
    fun list(context: Context): List<File> =
        File(context.filesDir, "snapshots").listFiles()
            ?.filter { it.isDirectory && File(it, "meta.json").exists() }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun loadMeta(dir: File): Meta? {
        return try {
            val j = JSONObject(File(dir, "meta.json").readText())
            // pkg 缺失时从目录名（<ts>_<pkg>）兜底，别因为一个字段让整份快照在列表里消失
            val pkg = j.optString("pkg").takeIf { it.isNotBlank() }
                ?: dir.name.substringAfter('_', "").takeIf { it.isNotBlank() }
                ?: return null
            Meta(
                pkg,
                j.optString("activity"),
                j.optLong("time", dir.lastModified()),
                j.optInt("nodeCount", 0),
                j.optString("name"),
                j.optInt("screenW", 0),
                j.optInt("screenH", 0),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 重命名快照：只改 meta.json 里的 name，不动图片和节点树 */
    fun rename(dir: File, name: String): Boolean = try {
        val f = File(dir, "meta.json")
        val j = JSONObject(f.readText())
        j.put("name", name.trim())
        f.writeText(j.toString())
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 改快照的目标应用。
     *
     * 有些应用禁了无障碍，抓取时读不到包名（只能存截图），规则却必须知道包名才生效。
     * 用户在详情页手动指定后写回 meta，之后生成的规则就用这个 pkg。
     */
    fun setPkg(dir: File, pkg: String): Boolean = try {
        val f = File(dir, "meta.json")
        val j = JSONObject(f.readText())
        j.put("pkg", pkg)
        f.writeText(j.toString())
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 读节点树。逐条兜：坐标或用 idx 缺失的条目跳过，不因一条坏把整份快照读成空。
     * （全部用 opt 取值，历史备份缺 path/winId 时走默认值即可）
     */
    fun loadNodes(dir: File): List<Node> = try {
        val arr = JSONArray(File(dir, "tree.json").readText())
        val out = ArrayList<Node>(arr.length())
        for (i in 0 until arr.length()) {
            val j = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            val cls = j.optString("cls")
            if (!j.has("l") || !j.has("t") || !j.has("r") || !j.has("b")) continue
            out.add(
                Node(
                    idx = j.optInt("idx", i), cls = cls,
                    text = j.optString("text"), desc = j.optString("desc"),
                    vid = j.optString("vid"), clickable = j.optBoolean("clickable"),
                    l = j.optInt("l"), t = j.optInt("t"), r = j.optInt("r"), b = j.optInt("b"),
                    path = j.optString("path"),
                    winId = j.optInt("winId", 0),
                )
            )
        }
        out
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * 截图文件。0.17.27 起存 WebP；旧快照仍是 PNG，读的时候两种都认，
     * 不做迁移（迁移要重编码，用户可能正在用那些快照）。
     */
    fun loadScreen(dir: File): File? =
        File(dir, SCREEN_FILE).takeIf { it.exists() }
            ?: File(dir, LEGACY_SCREEN_FILE).takeIf { it.exists() }

    fun remove(context: Context, dir: File) {
        dir.deleteRecursively()
    }

    /** 只留最近 30 份（用原始目录列表，半截目录也要被清掉，不然会一直留着） */
    private fun prune(context: Context) {
        File(context.filesDir, "snapshots").listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.drop(30)
            ?.forEach { it.deleteRecursively() }
    }

    /**
     * 快照目录总占用（含截图）。
     *
     * 「数据与维护」原来只显示内存和数据库大小，而快照恰恰是唯一可能吃掉
     * 几十上百 MB 的项——用户看不到它，也就不知道空间去哪了。
     */
    fun totalSizeBytes(context: Context): Long =
        File(context.filesDir, "snapshots").walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
}
