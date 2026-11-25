/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val url: String, val title: String, val time: Long)

/** `history` 表的全部 SQL。连接与线程由 [AppDatabase] 管，调用方传入已打开的 db。 */
internal object HistoryDao {

    private const val UPSERT =
        "INSERT INTO history(url,title,time) VALUES(?,?,?) ON CONFLICT(url) DO UPDATE SET " +
            "title=CASE WHEN excluded.time>=history.time THEN excluded.title ELSE history.title END, " +
            "time=MAX(history.time,excluded.time)"

    fun upsert(db: SQLiteDatabase, url: String, title: String, time: Long) {
        db.execSQL(UPSERT, arrayOf<Any?>(url, title, time))
    }

    fun delete(db: SQLiteDatabase, url: String) {
        db.execSQL("DELETE FROM history WHERE url=?", arrayOf<Any?>(url))
    }

    fun clearWithin(db: SQLiteDatabase, cutoff: Long) {
        db.execSQL("DELETE FROM history WHERE time>=$cutoff")
    }

    fun deleteRange(db: SQLiteDatabase, startTime: Long, endTime: Long) {
        db.execSQL("DELETE FROM history WHERE time>=? AND time<=?", arrayOf(startTime, endTime))
    }

    fun clearAll(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM history")
    }

    /** 本地历史里的全部网址（「绝对双向同步」比对删除用）。 */
    fun allUrls(db: SQLiteDatabase): HashSet<String> {
        db.rawQuery("SELECT url FROM history", emptyArray<String>()).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            return out
        }
    }

    fun count(db: SQLiteDatabase, cutoff: Long? = null): Int {
        val where = if (cutoff != null) " WHERE time>=$cutoff" else ""
        db.rawQuery("SELECT COUNT(*) FROM history$where", emptyArray<String>()).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 按时间倒序取最多 [limit] 条；[q] 非空时按标题 / 网址包含匹配。 */
    fun query(db: SQLiteDatabase, q: String, limit: Int): List<HistoryEntry> {
        val where: String
        val args: Array<String>
        if (q.isEmpty()) {
            where = ""
            args = emptyArray()
        } else {
            where = " WHERE title LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\'"
            val p = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            args = arrayOf(p, p)
        }
        db.rawQuery("SELECT url,title,time FROM history$where ORDER BY time DESC LIMIT $limit", args).use { c ->
            val out = ArrayList<HistoryEntry>(minOf(limit, 512))
            while (c.moveToNext()) out.add(HistoryEntry(c.getString(0), c.getString(1), c.getLong(2)))
            return out
        }
    }

    /** 按时间升序取 time >= [since] 的最多 [limit] 条（同步推送用：游标式分批，不丢记录）。 */
    fun queryFrom(db: SQLiteDatabase, since: Long, limit: Int): List<HistoryEntry> {
        db.rawQuery(
            "SELECT url,title,time FROM history WHERE time>=? ORDER BY time ASC, url ASC LIMIT $limit",
            arrayOf(since.toString()),
        ).use { c ->
            val out = ArrayList<HistoryEntry>(minOf(limit, 512))
            while (c.moveToNext()) out.add(HistoryEntry(c.getString(0), c.getString(1), c.getLong(2)))
            return out
        }
    }

    /** 全部历史导出成旧格式的 JSON 数组（备份用：备份里始终是明文 JSON，再由备份自己的信封加密）；没有记录返回 null。 */
    fun exportJson(context: Context): String? {
        val db = AppDatabase.get(context) ?: return null
        val array = JSONArray()
        db.rawQuery("SELECT url,title,time FROM history ORDER BY time DESC", emptyArray<String>()).use { c ->
            while (c.moveToNext()) {
                array.put(JSONObject().put("url", c.getString(0)).put("title", c.getString(1)).put("time", c.getLong(2)))
            }
        }
        return if (array.length() == 0) null else array.toString()
    }

    /** 备份恢复前清空（恢复语义是整体替换）；打不开库就忽略，旧值随后由 entries 导入覆盖。 */
    fun clearAll(context: Context) {
        AppDatabase.get(context)?.let { clearAll(it) }
    }
}
