package com.noteatoranges.skipad.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 跳过事件本地库（SQLiteOpenHelper，零外部依赖，0.3.0 起取代 SharedPreferences 统计）：
 * 单表事件流 → 聚合出 累计次数 / 分应用排行 / 最近记录。
 * 上限治理：prune 只保留最近 [KEEP_EVENTS] 条，防无限膨胀。
 */
class StatsDb private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    data class Event(val pkg: String, val detail: String, val ts: Long)
    data class PkgCount(val pkg: String, val count: Int)

    fun insert(pkg: String, detail: String) {
        val cv = ContentValues().apply {
            put(C_PKG, pkg)
            put(C_DETAIL, detail)
            put(C_TS, System.currentTimeMillis())
        }
        writableDatabase.insert(TABLE, null, cv)
    }

    fun total(): Int =
        readableDatabase.compileStatement("SELECT COUNT(*) FROM $TABLE").simpleQueryForLong().toInt()

    fun distinctApps(): Int =
        readableDatabase
            .compileStatement("SELECT COUNT(DISTINCT $C_PKG) FROM $TABLE")
            .simpleQueryForLong().toInt()

    /** 分应用计数，按次数降序（同数按最近活跃排） */
    fun countsByPackage(): List<PkgCount> {
        val out = ArrayList<PkgCount>()
        readableDatabase.rawQuery(
            "SELECT $C_PKG, COUNT(*) c FROM $TABLE GROUP BY $C_PKG ORDER BY c DESC, MAX($C_TS) DESC",
            null,
        ).use { cur ->
            while (cur.moveToNext()) out.add(PkgCount(cur.getString(0), cur.getInt(1)))
        }
        return out
    }

    fun recent(limit: Int): List<Event> {
        val out = ArrayList<Event>()
        readableDatabase.rawQuery(
            "SELECT $C_PKG, $C_DETAIL, $C_TS FROM $TABLE ORDER BY $C_TS DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cur ->
            while (cur.moveToNext()) out.add(Event(cur.getString(0), cur.getString(1) ?: "", cur.getLong(2)))
        }
        return out
    }

    fun prune(keep: Int) {
        // 按 _id 而不是 ts：同一毫秒插入的多条事件 ts 相同，用 NOT IN (ts) 会整批删除或整批保留
        writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE $C_ID NOT IN " +
                "(SELECT $C_ID FROM $TABLE ORDER BY $C_TS DESC, $C_ID DESC LIMIT ?)",
            arrayOf(keep.toString()),
        )
    }

    fun clear() {
        writableDatabase.delete(TABLE, null, null)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE $TABLE ($C_ID INTEGER PRIMARY KEY AUTOINCREMENT, $C_PKG TEXT NOT NULL, $C_DETAIL TEXT, $C_TS INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_${TABLE}_pkg ON $TABLE($C_PKG)")
        db.execSQL("CREATE INDEX idx_${TABLE}_ts ON $TABLE($C_TS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 起步，暂无迁移
    }

    companion object {
        private const val DB_NAME = "skipad.db"
        private const val DB_VERSION = 1
        private const val TABLE = "skips"
        private const val C_ID = "_id"
        private const val C_PKG = "pkg"
        private const val C_DETAIL = "detail"
        private const val C_TS = "ts"
        private const val KEEP_EVENTS = 1000

        @Volatile
        private var inst: StatsDb? = null

        fun get(context: Context): StatsDb =
            inst ?: synchronized(this) {
                inst ?: StatsDb(context.applicationContext).also { inst = it }
            }

        fun sizeKb(context: Context): Long {
            val f = java.io.File(context.getDatabasePath(DB_NAME).absolutePath)
            return if (f.exists()) f.length() / 1024 else 0
        }
    }
}
