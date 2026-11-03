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

    /** 返回新行 id；0.17.60 起供「过渡页跳过」回撤用（见 SkipAccessibilityService） */
    fun insert(pkg: String, detail: String): Long {
        val cv = ContentValues().apply {
            put(C_PKG, pkg)
            put(C_DETAIL, detail)
            put(C_TS, System.currentTimeMillis())
        }
        val id = writableDatabase.insert(TABLE, null, cv)
        // 每写 PRUNE_EVERY 条顺手清理一次。
        // 以前 prune 只在打开 App 时跑（MainActivity），用户装完基本不再打开的话
        // 表就只增不减，"保留最近 1000 条"的设计等于没落地。
        // 调用方都是后台 IO 线程（Io.run("stats-insert")），不需要额外加锁
        if (++insertSincePrune >= PRUNE_EVERY) {
            insertSincePrune = 0
            runCatching { prune(KEEP_EVENTS) }
        }
        return id
    }

    /**
     * 删掉一条记录，返回是否真的删到。
     *
     * 只服务于「回撤」这一个场景：刚记下的那次点击事后被认定为过渡页按钮，
     * 不是广告，就不该留在「已跳过广告」的计数里。不做通用删除接口。
     */
    fun deleteById(id: Long): Boolean {
        if (id <= 0) return false
        return writableDatabase.delete(TABLE, "$C_ID = ?", arrayOf(id.toString())) > 0
    }

    /** 距上次清理累计写入条数（只在 IO 线程访问） */
    private var insertSincePrune = 0

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

        /** 每写入这么多条触发一次 prune，避免只在打开 App 时才清理 */
        private const val PRUNE_EVERY = 200

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
