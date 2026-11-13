package dev.dsh.miuixbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

data class HistoryEntry(val url: String, val title: String, val time: Long)

/**
 * 历史记录的 SQLCipher 加密数据库（整库 AES-256 加密）。同一个库里还放着：
 * GM_setValue 数据（gm_values）、隐私报告的权限请求记录（perm_events）、下载记录（downloads，见 [DownloadDb]）、
 * 书签（bookmarks / bookmark_folders，见 [BookmarkDb]）。
 *  - 库密钥：随机 32 字节（hex），经 [SecureStore]（Android Keystore 保护的加密引擎）封装后存在
 *    SharedPreferences「mafari_history_db」里；密钥取不回来时旧库无法解开，删库重建（与保险库重置一致）。
 *  - 旧版把整份历史加密成一个字符串存在 SharedPreferences「mafari_history」的 entries 里：
 *    首次打开时自动导入（同网址保留较新的一条），导入成功后删除旧值。备份恢复写回的 entries 同样在下次启动时被导入。
 */
internal object HistoryDb {

    private const val DB_NAME = "history.db"
    private const val KEY_PREFS = "mafari_history_db"
    private const val KEY_NAME = "key"
    private const val LEGACY_PREFS = "mafari_history"
    private const val LEGACY_KEY = "entries"

    private const val UPSERT =
        "INSERT INTO history(url,title,time) VALUES(?,?,?) ON CONFLICT(url) DO UPDATE SET " +
            "title=CASE WHEN excluded.time>=history.time THEN excluded.title ELSE history.title END, " +
            "time=MAX(history.time,excluded.time)"

    @Volatile
    private var instance: SQLiteDatabase? = null
    private var failed = false

    /** 所有写入共用的单线程执行器（历史、隐私统计）：按提交顺序执行 */
    val io: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "mafari-db-writer").apply { isDaemon = true }
        }

    /** 取（必要时打开并迁移）数据库；打不开返回 null（历史功能降级为不记录，不影响浏览）。 */
    fun get(context: Context): SQLiteDatabase? {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            if (failed) return null
            val app = context.applicationContext
            val db = runCatching { open(app) }
                .onFailure { Diag.log("history db: open failed: ${it.message}") }
                .getOrNull()
            if (db == null) {
                failed = true
                return null
            }
            instance = db
            runCatching { migrateLegacy(app, db) }
                .onFailure { Diag.log("history db: migrate failed: ${it.message}") }
            runCatching { migrateGm(app, db) }
                .onFailure { Diag.log("history db: gm migrate failed: ${it.message}") }
            return db
        }
    }

    private fun open(context: Context): SQLiteDatabase {
        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val prefs = context.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
        var pass: String? = prefs.getString(KEY_NAME, null)
            ?.let { SecureStore.read(context, it, SecureStore.PURPOSE_HISTORY_DB) }
        if (pass == null) {
            // 首次使用，或旧密钥已丢：旧库打不开，清掉后用新密钥重建
            deleteFiles(file)
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val hex = bytes.joinToString("") { "%02x".format(it) }
            val sealed = SecureStore.write(context, hex, SecureStore.PURPOSE_HISTORY_DB)
                ?: error("无法保存数据库密钥")
            check(prefs.edit().putString(KEY_NAME, sealed).commit()) { "写入数据库密钥失败" }
            pass = hex
        }
        val db = try {
            SQLiteDatabase.openOrCreateDatabase(file, pass, null, null)
        } catch (e: Exception) {
            Diag.log("history db: unreadable, recreate: ${e.message}")
            deleteFiles(file)
            SQLiteDatabase.openOrCreateDatabase(file, pass, null, null)
        }
        db.enableWriteAheadLogging()
        // WAL + NORMAL：提交不再逐次 fsync（断电最多丢最后几笔，不会损坏库），脚本频繁 GM_setValue 也不卡
        db.execSQL("PRAGMA synchronous=NORMAL")
        db.execSQL("CREATE TABLE IF NOT EXISTS history(url TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, time INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_time ON history(time DESC)")
        // 油猴脚本的 GM_setValue 数据（见 GmStore）
        db.execSQL("CREATE TABLE IF NOT EXISTS gm_values(sid TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, PRIMARY KEY(sid,k)) WITHOUT ROWID")
        // 隐私报告：网页请求摄像头 / 麦克风 / 定位的记录（见 PrivacyStats）
        db.execSQL("CREATE TABLE IF NOT EXISTS perm_events(id INTEGER PRIMARY KEY AUTOINCREMENT, host TEXT NOT NULL, time INTEGER NOT NULL, kinds TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_perm_time ON perm_events(time)")
        // 下载记录（见 DownloadDb / Downloader）
        db.execSQL(DownloadDb.CREATE_TABLE)
        // 书签（见 BookmarkDb / FavoritesStore；Floccus 同步读写的就是这份）
        db.execSQL(BookmarkDb.CREATE_BOOKMARKS)
        db.execSQL(BookmarkDb.CREATE_FOLDERS)
        db.execSQL(BookmarkDb.CREATE_META)
        // 油猴脚本（见 ScriptDb / ExtensionStore）
        db.execSQL(ScriptDb.CREATE_SCRIPTS)
        db.execSQL(ScriptDb.CREATE_INDEX)
        return db
    }

    private fun deleteFiles(file: File) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file.path + suffix).delete()
    }

    private fun migrateLegacy(context: Context, db: SQLiteDatabase) {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(LEGACY_KEY, null) ?: return
        val raw = SecureStore.read(context, stored, SecureStore.PURPOSE_HISTORY) ?: return
        val array = JSONArray(raw)
        db.beginTransaction()
        try {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val url = o.optString("url")
                if (url.isBlank()) continue
                db.execSQL(UPSERT, arrayOf<Any?>(url, o.optString("title").ifBlank { hostOf(url) }, o.optLong("time")))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        prefs.edit().remove(LEGACY_KEY).commit()
    }

    /** 旧版 GM 值存在 SharedPreferences「userscript_gm」（键 = 脚本id::名字）：导入后逐个删除；备份恢复写回的同样在下次启动导入。 */
    private fun migrateGm(context: Context, db: SQLiteDatabase) {
        val prefs = context.getSharedPreferences("userscript_gm", Context.MODE_PRIVATE)
        val legacy = prefs.all.filter { it.key.contains("::") && it.value is String }
        if (legacy.isEmpty()) return
        db.beginTransaction()
        try {
            for ((key, value) in legacy) {
                val i = key.indexOf("::")
                db.execSQL(
                    "INSERT OR REPLACE INTO gm_values(sid,k,v) VALUES(?,?,?)",
                    arrayOf<Any?>(key.substring(0, i), key.substring(i + 2), value as String),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val editor = prefs.edit()
        legacy.keys.forEach { editor.remove(it) }
        editor.commit()
    }

    fun upsert(db: SQLiteDatabase, url: String, title: String, time: Long) {
        db.execSQL(UPSERT, arrayOf<Any?>(url, title, time))
    }

    fun delete(db: SQLiteDatabase, url: String) {
        db.execSQL("DELETE FROM history WHERE url=?", arrayOf<Any?>(url))
    }

    fun clearWithin(db: SQLiteDatabase, cutoff: Long) {
        db.execSQL("DELETE FROM history WHERE time>=$cutoff")
    }

    fun clearAll(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM history")
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

    /** 全部历史导出成旧格式的 JSON 数组（备份用：备份里始终是明文 JSON，再由备份自己的信封加密）；没有记录返回 null。 */
    fun exportJson(context: Context): String? {
        val db = get(context) ?: return null
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
        get(context)?.let { clearAll(it) }
    }
}

/**
 * 浏览历史。数据在 [HistoryDb]（SQLCipher）里，内存只保留最近几条（首页用）和总数；
 * 所有读写都在单线程后台执行，界面通过 [query] 分页读取，[version] 变化时重新查询。
 */
class HistoryStore(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** 最近访问（首页展示用），按时间倒序，最多 [RECENT_SIZE] 条。 */
    val recent = mutableStateListOf<HistoryEntry>()

    /** 历史总条数。 */
    var totalCount by mutableIntStateOf(0)
        private set

    /** 每次数据变化 +1：历史页用它触发重新查询。 */
    var version by mutableIntStateOf(0)
        private set

    init {
        writer.execute {
            HistoryDb.get(appContext)?.let { refresh(it) }
        }
    }

    fun record(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        val entry = HistoryEntry(url, title.ifBlank { hostOf(url) }, System.currentTimeMillis())
        write { HistoryDb.upsert(it, entry.url, entry.title, entry.time) }
    }

    fun remove(url: String) = write { HistoryDb.delete(it, url) }

    /** 清除最近 [withinMillis] 毫秒内的记录 */
    fun clearWithin(withinMillis: Long) {
        val cutoff = System.currentTimeMillis() - withinMillis
        write { HistoryDb.clearWithin(it, cutoff) }
    }

    fun clear() = write { HistoryDb.clearAll(it) }

    /** 记录数：最近 [withinMillis] 毫秒内的条数（只做一次带索引的 COUNT） */
    fun countWithin(withinMillis: Long): Int {
        val db = HistoryDb.get(appContext) ?: return 0
        return HistoryDb.count(db, System.currentTimeMillis() - withinMillis)
    }

    /** 按时间倒序读最多 [limit] 条，[q] 非空时过滤。在 IO 线程调用。 */
    fun query(q: String, limit: Int): List<HistoryEntry> {
        val db = HistoryDb.get(appContext) ?: return emptyList()
        return runCatching { HistoryDb.query(db, q, limit) }.getOrDefault(emptyList())
    }

    private fun write(block: (SQLiteDatabase) -> Unit) {
        writer.execute {
            val db = HistoryDb.get(appContext) ?: return@execute
            runCatching { block(db) }.onFailure { Diag.log("history db: write failed: ${it.message}") }
            refresh(db)
        }
    }

    /** 后台线程：重读最近几条和总数，再到主线程更新状态。 */
    private fun refresh(db: SQLiteDatabase) {
        val latest = runCatching { HistoryDb.query(db, "", RECENT_SIZE) }.getOrDefault(emptyList())
        val total = runCatching { HistoryDb.count(db) }.getOrDefault(0)
        main.post {
            recent.clear()
            recent.addAll(latest)
            totalCount = total
            version++
        }
    }

    private companion object {
        const val RECENT_SIZE = 20

        private val writer: java.util.concurrent.ExecutorService get() = HistoryDb.io
    }
}
