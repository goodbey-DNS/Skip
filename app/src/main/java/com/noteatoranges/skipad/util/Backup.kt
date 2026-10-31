package com.noteatoranges.skipad.util

import android.content.Context
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
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
}
