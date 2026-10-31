package com.noteatoranges.skipad.util

import android.content.Context
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份导出：把「规则 + 关键字 + 快照节点树」打成一个 zip。
 *
 * 为什么不含 screen.png：一份全屏图 10MB 级，30 份快照就是几百 MB，
 * 备份的本意是保住「重建规则所需的最小数据」——节点树 + meta 足够，
 * 截图丢了可以重抓，规则丢了要重新点一遍。
 */
object Backup {

    /** 导出的 zip 落在外部私有目录 backup/ 下，返回 null 表示失败 */
    fun export(context: Context): File? = try {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "backup").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val out = File(dir, "SkipAd-backup-$ts.zip")
        var count = 0

        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            fun put(name: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
                count++
            }

            File(context.filesDir, "rules.json").takeIf { it.exists() }?.let {
                put("rules.json", it.readBytes())
            }
            File(context.filesDir, "keywords.json").takeIf { it.exists() }?.let {
                put("keywords.json", it.readBytes())
            }
            // 快照只带 meta + 节点树，不带截图
            File(context.filesDir, "snapshots").listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name }
                ?.forEach { d ->
                    listOf("meta.json", "tree.json").forEach { n ->
                        File(d, n).takeIf { it.exists() }?.let { put("snapshots/${d.name}/$n", it.readBytes()) }
                    }
                }

            val ver = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: ""
            val manifest = JSONObject().apply {
                put("app", "SkipAd")
                put("version", ver)
                put("createdAt", System.currentTimeMillis())
                put("fileCount", count)
                put("note", "不含 screen.png；tree.json + meta.json 足以在恢复后重建规则")
            }
            put("manifest.json", manifest.toString().toByteArray(Charsets.UTF_8))
        }
        out
    } catch (e: Exception) {
        null
    }

    /** 恢复结果：各恢复了几条 */
    data class Restored(val rules: Int, val keywords: Int, val snapshots: Int)

    /** backup 目录里最新的一份 zip（按修改时间） */
    fun latestBackup(context: Context): File? {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, "backup").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * 从备份 zip 恢复。
     *
     * 覆盖策略：rules.json / keywords.json 存在才覆盖（备份里没有就不动现有数据），
     * 快照按目录名合并（不删已有），因为目录名带时间戳，重名即同一份。
     * 调用方负责先做一次自保备份。
     */
    fun restore(context: Context, input: InputStream): Restored? = try {
        val rules = ArrayList<ByteArray>()
        val keywords = ArrayList<ByteArray>()
        val snaps = HashMap<String, ByteArray>() // "dir/file" -> bytes
        var hasManifest = false

        ZipInputStream(input.buffered()).use { zis ->
            var e: ZipEntry? = zis.nextEntry
            while (e != null) {
                val name = e.name
                if (!e.isDirectory) {
                    val bytes = zis.readBytes()
                    when {
                        name == "manifest.json" -> hasManifest = true
                        name == "rules.json" -> rules.add(bytes)
                        name == "keywords.json" -> keywords.add(bytes)
                        name.startsWith("snapshots/") && (name.endsWith("/meta.json") || name.endsWith("/tree.json")) ->
                            snaps[name] = bytes
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        // 不是我们的备份就拒绝，别把别的 zip 当数据写进去（表达式函数里不能 return，用抛错走 catch）
        if (!hasManifest && rules.isEmpty() && keywords.isEmpty() && snaps.isEmpty()) {
            throw IllegalStateException("not a SkipAd backup")
        }

        var rulesCount = 0
        var kwCount = 0
        rules.firstOrNull()?.let { rulesCount = com.noteatoranges.skipad.db.RuleStore.importFrom(context, it) }
        keywords.firstOrNull()?.let { kwCount = com.noteatoranges.skipad.db.KeywordStore.importFrom(context, it) }

        val snapRoot = File(context.filesDir, "snapshots").apply { mkdirs() }
        var snapCount = 0
        val dirs = snaps.keys.map { it.substringBeforeLast('/') }.distinct()
        for (dir in dirs) {
            val target = File(snapRoot, dir.substringAfter("snapshots/"))
            target.mkdirs()
            var wrote = false
            listOf("meta.json", "tree.json").forEach { n ->
                snaps["$dir/$n"]?.let {
                    File(target, n).writeBytes(it)
                    wrote = true
                }
            }
            if (wrote) snapCount++
        }
        Restored(rulesCount, kwCount, snapCount)
    } catch (e: Exception) {
        null
    }
}
