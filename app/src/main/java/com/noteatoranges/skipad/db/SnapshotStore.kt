package com.noteatoranges.skipad.db

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 快照（GKD 同款思路）：广告在屏时抓"截图 + 完整控件树"，供点选节点生成规则。
 * 目录 filesDir/snapshots/<ts>_<pkg>/：screen.png + tree.json + meta.json。
 */
object SnapshotStore {

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
    ) {
        val w: Int get() = r - l
        val h: Int get() = b - t
    }

    data class Meta(val pkg: String, val activity: String, val time: Long, val nodeCount: Int)

    fun save(
        context: Context,
        pkg: String,
        activity: String,
        nodes: List<Node>,
        screen: Bitmap?,
    ): File {
        val dir = File(File(context.filesDir, "snapshots"), "${System.currentTimeMillis()}_$pkg")
        dir.mkdirs()
        screen?.compress(Bitmap.CompressFormat.PNG, 90, java.io.FileOutputStream(File(dir, "screen.png")))
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
            })
        }
        File(dir, "tree.json").writeText(arr.toString())
        File(dir, "meta.json").writeText(
            JSONObject().apply {
                put("pkg", pkg)
                put("activity", activity)
                put("time", System.currentTimeMillis())
                put("nodeCount", nodes.size)
            }.toString()
        )
        prune(context)
        return dir
    }

    fun list(context: Context): List<File> =
        File(context.filesDir, "snapshots").listFiles()?.filter { it.isDirectory }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun loadMeta(dir: File): Meta? = try {
        val j = JSONObject(File(dir, "meta.json").readText())
        Meta(j.getString("pkg"), j.optString("activity"), j.getLong("time"), j.optInt("nodeCount", 0))
    } catch (e: Exception) {
        null
    }

    fun loadNodes(dir: File): List<Node> = try {
        val arr = JSONArray(File(dir, "tree.json").readText())
        (0 until arr.length()).map { i ->
            val j = arr.getJSONObject(i)
            Node(
                idx = j.getInt("idx"), cls = j.getString("cls"),
                text = j.optString("text"), desc = j.optString("desc"),
                vid = j.optString("vid"), clickable = j.optBoolean("clickable"),
                l = j.getInt("l"), t = j.getInt("t"), r = j.getInt("r"), b = j.getInt("b"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun loadScreen(dir: File): File? = File(dir, "screen.png").takeIf { it.exists() }

    fun remove(context: Context, dir: File) {
        dir.deleteRecursively()
    }

    /** 只留最近 30 份 */
    private fun prune(context: Context) {
        list(context).drop(30).forEach { it.deleteRecursively() }
    }
}
