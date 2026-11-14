package dev.dsh.miuixbrowser

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * 书签的加密库存储（和历史、下载记录同在 SQLCipher 整库加密的 history.db 里）。
 *
 * 库里完整保留这几样：
 *  - 书签 id：[Favorite.id]，稳定不变；
 *  - 文件夹 id：和路径一一对应，改名 / 搬家后 id 不变（见 FavoritesStore 的文件夹注册表）；
 *  - 顺序：`bookmarks.pos` 是总列表下标，同一分组内的先后、首页顺序都由它推出；`bookmark_folders.pos` 是注册表顺序；
 *  - 空文件夹：只在 `bookmark_folders` 里存在，没有书签也保留。
 *
 * 写入一律是「整表快照、单事务」：要么整份新数据生效，要么保持旧数据，不会出现写一半的树。
 * `bookmark_meta` 里的 initialized 标记区分「还没存过（用默认书签）」和「用户把书签全删了」。
 */
internal object BookmarkDb {

    const val CREATE_BOOKMARKS =
        "CREATE TABLE IF NOT EXISTS bookmarks(" +
            "pos INTEGER PRIMARY KEY NOT NULL, " +
            "id TEXT NOT NULL, " +
            "name TEXT NOT NULL, " +
            "url TEXT NOT NULL, " +
            "grp TEXT NOT NULL)"

    const val CREATE_FOLDERS =
        "CREATE TABLE IF NOT EXISTS bookmark_folders(" +
            "pos INTEGER PRIMARY KEY NOT NULL, " +
            "path TEXT NOT NULL, " +
            "id TEXT NOT NULL)"

    const val CREATE_META =
        "CREATE TABLE IF NOT EXISTS bookmark_meta(k TEXT PRIMARY KEY NOT NULL, v TEXT NOT NULL) WITHOUT ROWID"

    class Loaded(val items: List<Favorite>, val changed: Boolean)

    fun isInitialized(db: SQLiteDatabase): Boolean =
        db.rawQuery("SELECT 1 FROM bookmark_meta WHERE k='initialized'", emptyArray<String>()).use { it.moveToFirst() }

    /** 按总列表顺序读出全部书签；id 为空 / 重复的补发新 id（[Loaded.changed] = true，调用方应重新落盘）。 */
    fun loadBookmarks(db: SQLiteDatabase): Loaded {
        val rows = ArrayList<Favorite>()
        db.rawQuery("SELECT id,name,url,grp FROM bookmarks ORDER BY pos", emptyArray<String>()).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Favorite(
                        name = c.getString(1),
                        url = c.getString(2),
                        group = c.getString(3).ifBlank { Favorite.GROUP_PERSONAL },
                        id = c.getString(0),
                    ),
                )
            }
        }
        return dedupeIds(rows)
    }

    /** 文件夹注册表：(路径, id)，按注册表顺序。 */
    fun loadFolders(db: SQLiteDatabase): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        db.rawQuery("SELECT path,id FROM bookmark_folders ORDER BY pos", emptyArray<String>()).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        return out
    }

    fun saveBookmarks(db: SQLiteDatabase, items: List<Favorite>) = inTransaction(db) { writeBookmarks(db, items) }

    fun saveFolders(db: SQLiteDatabase, folders: List<Pair<String, String>>) = inTransaction(db) { writeFolders(db, folders) }

    /** 迁移 / 恢复：书签和文件夹在同一个事务里整体替换。 */
    fun replaceAll(db: SQLiteDatabase, items: List<Favorite>, folders: List<Pair<String, String>>) =
        inTransaction(db) {
            writeBookmarks(db, items)
            writeFolders(db, folders)
        }

    /** 备份恢复前清空（恢复语义是整体替换）；清掉 initialized 标记，备份里没有书签时回到默认书签。 */
    fun clearAll(context: Context) {
        val db = HistoryDb.get(context) ?: return
        runCatching {
            inTransaction(db) {
                db.execSQL("DELETE FROM bookmarks")
                db.execSQL("DELETE FROM bookmark_folders")
                db.execSQL("DELETE FROM bookmark_meta")
            }
        }
    }

    /**
     * 备份用：(书签 JSON, 文件夹 JSON)，格式与旧版 SharedPreferences 里的明文一致（恢复后下次启动自动导入）。
     * 界面正在用的那份在内存里最新（写库有短暂延迟），优先取它；没有则读库；都没有（从没存过）返回 null。
     */
    fun exportBackup(context: Context): Pair<String, String>? {
        runCatching { FavoritesStore.current?.snapshotJson() }.getOrNull()?.let { return it }
        val db = HistoryDb.get(context) ?: return null
        return runCatching {
            if (!isInitialized(db)) null
            else itemsToJson(loadBookmarks(db).items) to foldersToJson(loadFolders(db))
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 旧格式 JSON（备份 / 旧存储）

    fun itemsToJson(items: List<Favorite>): String {
        val array = JSONArray()
        items.forEach {
            array.put(JSONObject().put("name", it.name).put("url", it.url).put("group", it.group).put("id", it.id))
        }
        return array.toString()
    }

    fun foldersToJson(folders: List<Pair<String, String>>): String {
        val array = JSONArray()
        folders.forEach { (path, id) -> array.put(JSONObject().put("path", path).put("id", id)) }
        return array.toString()
    }

    /** 解析旧格式书签数组；id 可能为空，由 [dedupeIds] 补发。格式不对抛异常。 */
    fun parseItems(raw: String): List<Favorite> {
        val array = JSONArray(raw)
        val out = ArrayList<Favorite>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            out.add(
                Favorite(
                    name = o.optString("name"),
                    url = o.optString("url"),
                    group = o.optString("group").ifBlank { Favorite.GROUP_PERSONAL },
                    id = o.optString("id"),
                ),
            )
        }
        return out
    }

    /** 解析旧格式文件夹注册表；路径 / id 为空、路径重复的跳过。格式不对抛异常。 */
    fun parseFolders(raw: String): List<Pair<String, String>> {
        val array = JSONArray(raw)
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val path = o.optString("path")
            val id = o.optString("id")
            if (path.isNotBlank() && id.isNotBlank() && seen.add(path)) out.add(path to id)
        }
        return out
    }

    /** id 为空或重复的书签补发新 id（重复的只保留第一条的原 id），保证每条书签 id 唯一。 */
    fun dedupeIds(list: List<Favorite>): Loaded {
        val seen = HashSet<String>()
        var changed = false
        val out = list.map { f ->
            if (f.id.isBlank() || !seen.add(f.id)) {
                changed = true
                val fresh = f.copy(id = java.util.UUID.randomUUID().toString())
                seen.add(fresh.id)
                fresh
            } else {
                f
            }
        }
        return Loaded(out, changed)
    }

    // ------------------------------------------------------------------ 内部

    private fun writeBookmarks(db: SQLiteDatabase, items: List<Favorite>) {
        db.execSQL("DELETE FROM bookmarks")
        items.forEachIndexed { i, f ->
            db.execSQL(
                "INSERT INTO bookmarks(pos,id,name,url,grp) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(i.toLong(), f.id, f.name, f.url, f.group),
            )
        }
        db.execSQL("INSERT OR REPLACE INTO bookmark_meta(k,v) VALUES('initialized','1')")
    }

    private fun writeFolders(db: SQLiteDatabase, folders: List<Pair<String, String>>) {
        db.execSQL("DELETE FROM bookmark_folders")
        folders.forEachIndexed { i, (path, id) ->
            db.execSQL(
                "INSERT INTO bookmark_folders(pos,path,id) VALUES(?,?,?)",
                arrayOf<Any?>(i.toLong(), path, id),
            )
        }
    }

    private inline fun inTransaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
