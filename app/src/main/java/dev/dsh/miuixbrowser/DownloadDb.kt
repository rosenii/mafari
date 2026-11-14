package dev.dsh.miuixbrowser

import android.net.Uri
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * 下载记录表（`downloads`），和浏览历史共用同一个 SQLCipher 整库加密数据库 [HistoryDb]（history.db）。
 * 一行 = 一个下载任务：元数据（含网址 / Referer / 保存位置）+ 断点续传用的分段进度。
 * 数据文件（data.part / data.seg）仍在 filesDir/downloads/<id>/，库里不存文件内容。
 */
internal object DownloadDb {

    const val CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS downloads(" +
            "id INTEGER PRIMARY KEY NOT NULL, " +
            "name TEXT NOT NULL, " +
            "url TEXT NOT NULL, " +
            "ua TEXT, " +
            "mime TEXT, " +
            "uri TEXT, " +
            "bytes INTEGER NOT NULL DEFAULT 0, " +
            "total INTEGER NOT NULL DEFAULT 0, " +
            "state TEXT NOT NULL, " +
            "error TEXT, " +
            "referer TEXT, " +
            "with_cookies INTEGER NOT NULL DEFAULT 0, " +
            "etag TEXT, " +
            "last_modified TEXT, " +
            "seg_count INTEGER NOT NULL DEFAULT 0, " +
            "fetched INTEGER NOT NULL DEFAULT 0, " +
            // 各分段已完成字节数，逗号分隔；单连接任务为 NULL
            "seg_done TEXT)"

    private const val COLUMNS =
        "id,name,url,ua,mime,uri,bytes,total,state,error,referer,with_cookies," +
            "etag,last_modified,seg_count,fetched,seg_done"

    fun upsert(db: SQLiteDatabase, t: DownloadTask, seg: LongArray?) {
        db.execSQL(
            "INSERT OR REPLACE INTO downloads($COLUMNS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                t.id, t.name, t.url, t.userAgent, t.mime, t.uri?.toString(),
                t.bytes, t.total, t.state.name, t.error, t.referer,
                if (t.withCookies) 1 else 0,
                t.etag, t.lastModified, t.segCount, if (t.fetched) 1 else 0,
                seg?.joinToString(","),
            ),
        )
    }

    fun delete(db: SQLiteDatabase, id: Long) {
        db.execSQL("DELETE FROM downloads WHERE id=?", arrayOf<Any?>(id))
    }

    /** 全部任务（新的在前）及各自的分段进度（没有则 null）。 */
    fun loadAll(db: SQLiteDatabase): List<Pair<DownloadTask, LongArray?>> {
        val out = ArrayList<Pair<DownloadTask, LongArray?>>()
        db.rawQuery("SELECT $COLUMNS FROM downloads ORDER BY id DESC", emptyArray<String>()).use { c ->
            while (c.moveToNext()) {
                fun str(i: Int): String? = if (c.isNull(i)) null else c.getString(i).takeIf { it.isNotEmpty() }
                val task = DownloadTask(
                    id = c.getLong(0),
                    name = c.getString(1),
                    url = c.getString(2),
                    userAgent = str(3),
                    mime = str(4),
                    uri = str(5)?.let { Uri.parse(it) },
                    bytes = c.getLong(6),
                    total = c.getLong(7),
                    state = runCatching { DownloadState.valueOf(c.getString(8)) }.getOrDefault(DownloadState.PAUSED),
                    error = str(9),
                    referer = str(10),
                    withCookies = c.getInt(11) != 0,
                    etag = str(12),
                    lastModified = str(13),
                    segCount = c.getInt(14),
                    fetched = c.getInt(15) != 0,
                )
                val seg = str(16)?.let { s ->
                    runCatching { s.split(',').map { it.toLong() }.toLongArray() }.getOrNull()
                }
                out += task to seg
            }
        }
        return out
    }
}
