package com.noteatoranges.skipad.util

import android.content.Context
import android.graphics.drawable.Drawable

/** 应用名与图标解析（进程内缓存；应用被卸载时回退包名 + 系统默认图标） */
object AppLabels {

    data class Info(val label: String, val icon: Drawable?)

    private val cache = HashMap<String, Info>()

    fun get(context: Context, pkg: String): Info {
        synchronized(cache) { cache[pkg]?.let { return it } }
        val appContext = context.applicationContext
        val info = try {
            val pm = appContext.packageManager
            val app = pm.getApplicationInfo(pkg, 0)
            Info(pm.getApplicationLabel(app).toString(), pm.getApplicationIcon(pkg))
        } catch (e: Exception) {
            Info(pkg, appContext.packageManager.getDefaultActivityIcon())
        }
        synchronized(cache) { cache[pkg] = info }
        return info
    }

    /** 仅清内存缓存，无任何数据影响（"清除缓存"按钮用） */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }
}
