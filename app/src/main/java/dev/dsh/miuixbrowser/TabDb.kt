package dev.dsh.miuixbrowser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom

/** 标签列表里一个普通（非无痕）标签要落盘的状态；[pos] 是它在标签列表里的顺序。 */
internal data class TabRow(
    val key: Long,
    val pos: Int,
    /** 标签承载的页面地址（回过主页的标签是被盖住的那页）；空白标签为空串 */
    val url: String,
    val title: String,
    /** 标签正显示主页，页面被盖在下面 */
    val onHome: Boolean,
    val desktop: Boolean,
    /** 打开本标签的标签的 [key]，0 = 无 */
    val openerKey: Long,
    val fromLink: Boolean,
    /** 最近一次被切到前台的时间（毫秒）：重启后回到 last_used 最大的那个标签，也用它恢复「最近使用」顺序 */
    val lastUsed: Long,
    /** 库里有和 [url] 对得上的 Gecko 会话状态（前进后退栈 + 滚动位置）；只在 [TabDb.load] 里填，写入时忽略 */
    val hasState: Boolean = false,
)

/** 备份里一个标签的完整数据：元数据 + 缩略图（WebP）+ Gecko 会话状态。 */
internal class TabBackupRow(
    val row: TabRow,
    val thumb: ByteArray?,
    val state: String?,
    val stateUrl: String,
)

/**
 * 标签列表的加密库存储：`tabs` 表，和历史、书签、下载记录同在 SQLCipher 整库加密的 history.db 里（[HistoryDb]）。
 *
 *  - 只存普通标签；无痕标签（含它的缩略图）永远不落盘。
 *  - 一行 = 一个标签：地址 / 标题 / 顺序 / 桌面模式 / opener 等元数据 + 缩略图 `thumb`（压缩后的 WebP，通常十几 KB）。
 *  - 元数据由 [sync] 整表快照、单事务写入（要么整份新列表生效，要么保持旧的）；缩略图由 [saveThumb] 单独写，
 *    不会被元数据快照覆盖。列表查询不读 `thumb`，缩略图只在标签卡片真的显示出来时才用 [loadThumb] 读。
 *  - Gecko 会话状态（`state`，SessionState 序列化后的字符串）和 `state_url`（保存状态时标签的页面地址）也是单独写
 *    （[saveState]），不被元数据快照覆盖。读状态时要求 `state_url` 等于行里当前的 `url`：快照之后页面又换了，
 *    旧状态就作废，退回只按地址加载，不会把标签恢复到更早的页面。
 *  - `saved = 1` 表示这行已被某次快照确认；只有缩略图、还没被快照确认的行（`saved = 0`）不会被恢复，下一次快照会清掉。
 */
internal object TabDb {

    const val CREATE_TABS =
        "CREATE TABLE IF NOT EXISTS tabs(" +
            "tab_key INTEGER PRIMARY KEY NOT NULL, " +
            "saved INTEGER NOT NULL DEFAULT 0, " +
            "pos INTEGER NOT NULL DEFAULT 0, " +
            "url TEXT NOT NULL DEFAULT '', " +
            "title TEXT NOT NULL DEFAULT '', " +
            "on_home INTEGER NOT NULL DEFAULT 0, " +
            "desktop INTEGER NOT NULL DEFAULT 0, " +
            "opener_key INTEGER NOT NULL DEFAULT 0, " +
            "from_link INTEGER NOT NULL DEFAULT 0, " +
            "last_used INTEGER NOT NULL DEFAULT 0, " +
            "thumb BLOB)"

    /**
     * 给旧版本建出来的 tabs 表补上 state / state_url 两列（新库同样走这里，所以 [CREATE_TABS] 不含它们）。
     * 须在 [CREATE_TABS] 之后调用，可重复调用。
     */
    fun migrate(db: SQLiteDatabase) {
        val cols = HashSet<String>()
        db.rawQuery("PRAGMA table_info(tabs)", emptyArray<String>()).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) cols.add(c.getString(nameIdx))
        }
        if ("state" !in cols) db.execSQL("ALTER TABLE tabs ADD COLUMN state TEXT")
        if ("state_url" !in cols) db.execSQL("ALTER TABLE tabs ADD COLUMN state_url TEXT NOT NULL DEFAULT ''")
    }

    const val CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_tabs_pos ON tabs(saved,pos)"

    private const val UPSERT =
        "INSERT INTO tabs(tab_key,saved,pos,url,title,on_home,desktop,opener_key,from_link,last_used) " +
            "VALUES(?,1,?,?,?,?,?,?,?,?) ON CONFLICT(tab_key) DO UPDATE SET " +
            "saved=1,pos=excluded.pos,url=excluded.url,title=excluded.title,on_home=excluded.on_home," +
            "desktop=excluded.desktop,opener_key=excluded.opener_key,from_link=excluded.from_link,last_used=excluded.last_used"

    /**
     * 备份恢复期间置 true：恢复会整表替换 tabs，而界面里仍活着的标签不能再把旧列表 / 缩略图 / 会话状态写回来
     * （恢复后进程会被重启，所以不需要复位；恢复失败时由 [restoreAll] 复位）。
     */
    @Volatile
    var frozen = false

    /** 备份里最多带多少个标签（与 BrowserState.MAX_TABS 一致） */
    private const val MAX_BACKUP_TABS = 99

    /** 新标签的持久 id（不会和库里已有的撞：63 位随机数） */
    fun newKey(): Long = ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE)

    /** 按顺序读出上次保存的标签（不含缩略图）。 */
    fun load(db: SQLiteDatabase): List<TabRow> {
        val out = ArrayList<TabRow>()
        db.rawQuery(
            "SELECT tab_key,pos,url,title,on_home,desktop,opener_key,from_link,last_used," +
                "(state IS NOT NULL AND state_url = url) FROM tabs WHERE saved=1 ORDER BY pos",
            emptyArray<String>(),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TabRow(
                        key = c.getLong(0),
                        pos = c.getInt(1),
                        url = c.getString(2),
                        title = c.getString(3),
                        onHome = c.getInt(4) != 0,
                        desktop = c.getInt(5) != 0,
                        openerKey = c.getLong(6),
                        fromLink = c.getInt(7) != 0,
                        lastUsed = c.getLong(8),
                        hasState = c.getInt(9) != 0,
                    ),
                )
            }
        }
        return out
    }

    /** 整表快照：写入 [rows]，删掉不在其中的行（含已关闭标签的缩略图）。须在 [HistoryDb.io] 上调用。 */
    fun sync(db: SQLiteDatabase, rows: List<TabRow>) {
        if (frozen) return
        db.beginTransaction()
        try {
            for (r in rows) {
                db.execSQL(
                    UPSERT,
                    arrayOf<Any?>(
                        r.key, r.pos, r.url, r.title,
                        if (r.onHome) 1 else 0, if (r.desktop) 1 else 0,
                        r.openerKey, if (r.fromLink) 1 else 0, r.lastUsed,
                    ),
                )
            }
            if (rows.isEmpty()) {
                db.execSQL("DELETE FROM tabs")
            } else {
                // 最多 99 个 Long，直接内联，不会碰到 SQLite 的变量个数上限
                db.execSQL("DELETE FROM tabs WHERE tab_key NOT IN (" + rows.joinToString(",") { it.key.toString() } + ")")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveThumb(db: SQLiteDatabase, key: Long, bytes: ByteArray) {
        if (frozen) return
        db.execSQL(
            "INSERT INTO tabs(tab_key,thumb) VALUES(?,?) ON CONFLICT(tab_key) DO UPDATE SET thumb=excluded.thumb",
            arrayOf<Any?>(key, bytes),
        )
    }

    /** 写 Gecko 会话状态；[pageUrl] 是此刻标签的页面地址（与 tabs.url 同口径），读回时据此判断状态是否过期。须在 [HistoryDb.io] 上调用。 */
    fun saveState(db: SQLiteDatabase, key: Long, state: String, pageUrl: String) {
        if (frozen) return
        db.execSQL(
            "INSERT INTO tabs(tab_key,state,state_url) VALUES(?,?,?) ON CONFLICT(tab_key) DO UPDATE SET " +
                "state=excluded.state,state_url=excluded.state_url",
            arrayOf<Any?>(key, state, pageUrl),
        )
    }

    /** 读会话状态；没有、或与行里当前 url 对不上（已过期）返回 null。 */
    fun loadState(db: SQLiteDatabase, key: Long): String? {
        db.rawQuery(
            "SELECT state FROM tabs WHERE tab_key=? AND state IS NOT NULL AND state_url=url",
            arrayOf(key.toString()),
        ).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }

    fun loadThumb(db: SQLiteDatabase, key: Long): ByteArray? {
        db.rawQuery("SELECT thumb FROM tabs WHERE tab_key=?", arrayOf(key.toString())).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null
        }
    }

    // ------------------------------------------------------------------ 备份 / 恢复

    /**
     * 备份用：已确认保存的标签（`saved=1`，按顺序）导出成明文 JSON 数组（再由备份信封加密）；没有标签返回 null。
     * 每项带缩略图（base64 的 WebP）和 Gecko 会话状态，换机恢复后卡片、前进后退栈都在。
     * 先等 [HistoryDb.io] 里已排队的写入（标签快照 / 缩略图 / 状态）落库，再读。调用方在 IO 线程调用。
     */
    fun exportJson(context: android.content.Context): String? {
        val db = HistoryDb.get(context) ?: return null
        runCatching { HistoryDb.io.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        val array = JSONArray()
        db.rawQuery(
            "SELECT tab_key,pos,url,title,on_home,desktop,opener_key,from_link,last_used,thumb,state,state_url " +
                "FROM tabs WHERE saved=1 ORDER BY pos",
            emptyArray<String>(),
        ).use { c ->
            while (c.moveToNext()) {
                val o = JSONObject()
                    .put("key", c.getLong(0))
                    .put("pos", c.getInt(1))
                    .put("url", c.getString(2))
                    .put("title", c.getString(3))
                    .put("onHome", c.getInt(4) != 0)
                    .put("desktop", c.getInt(5) != 0)
                    .put("openerKey", c.getLong(6))
                    .put("fromLink", c.getInt(7) != 0)
                    .put("lastUsed", c.getLong(8))
                if (!c.isNull(9)) o.put("thumb", Base64.encodeToString(c.getBlob(9), Base64.NO_WRAP))
                if (!c.isNull(10)) o.put("state", c.getString(10)).put("stateUrl", c.getString(11))
                array.put(o)
            }
        }
        return if (array.length() == 0) null else array.toString()
    }

    /** 解析备份里的标签 JSON；格式不对抛异常（恢复前先解析，保证写库时不会半途失败）。 */
    fun parseBackup(json: String): List<TabBackupRow> {
        val array = JSONArray(json)
        val out = ArrayList<TabBackupRow>(minOf(array.length(), MAX_BACKUP_TABS))
        val seen = HashSet<Long>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_BACKUP_TABS) break
            val o = array.getJSONObject(i)
            val key = o.getLong("key")
            if (key <= 0L || !seen.add(key)) continue
            val state = if (o.has("state")) o.getString("state") else null
            out.add(
                TabBackupRow(
                    row = TabRow(
                        key = key,
                        pos = out.size,
                        url = o.optString("url"),
                        title = o.optString("title"),
                        onHome = o.optBoolean("onHome"),
                        desktop = o.optBoolean("desktop"),
                        openerKey = o.optLong("openerKey"),
                        fromLink = o.optBoolean("fromLink"),
                        lastUsed = o.optLong("lastUsed"),
                    ),
                    thumb = if (o.has("thumb")) Base64.decode(o.getString("thumb"), Base64.DEFAULT) else null,
                    state = state,
                    stateUrl = if (state != null) o.optString("stateUrl") else "",
                ),
            )
        }
        return out
    }

    /**
     * 恢复：整表替换为 [rows]（单事务，要么全换要么保持原样）。先 [frozen]，让界面里还活着的旧标签不再写库；
     * 在 [HistoryDb.io] 上执行，排在已排队的写入之后。失败时复位 [frozen] 并抛 IOException。重启后 [load] 读到的就是恢复的列表。
     */
    fun restoreAll(context: android.content.Context, rows: List<TabBackupRow>) {
        val db = HistoryDb.get(context) ?: throw java.io.IOException("加密库不可用，无法恢复标签列表")
        frozen = true
        try {
            HistoryDb.io.submit {
                db.beginTransaction()
                try {
                    db.execSQL("DELETE FROM tabs")
                    for (r in rows) {
                        db.execSQL(
                            "INSERT OR REPLACE INTO tabs(tab_key,saved,pos,url,title,on_home,desktop,opener_key,from_link," +
                                "last_used,thumb,state,state_url) VALUES(?,1,?,?,?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any?>(
                                r.row.key, r.row.pos, r.row.url, r.row.title,
                                if (r.row.onHome) 1 else 0, if (r.row.desktop) 1 else 0,
                                r.row.openerKey, if (r.row.fromLink) 1 else 0, r.row.lastUsed,
                                r.thumb, r.state, r.stateUrl,
                            ),
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }.get()
        } catch (e: Exception) {
            frozen = false
            throw java.io.IOException("恢复标签列表失败：${(e.cause ?: e).message}", e)
        }
    }
}

/**
 * 标签缩略图的内存与落盘管线：
 *  - 截图到手后，缩小（最长 [MAX_W]×[MAX_H]、RGB_565，每张约 0.4MB，原先 480×854 ARGB_8888 约 1.6MB）和 WebP 压缩
 *    都在单线程后台执行，主线程只接收结果；原图缩完立即回收；同一时间最多处理一张。
 *  - 内存里最多同时保留 [MAX_IN_MEMORY] 张普通标签的缩略图（LRU）；被挤出去的标签缩略图置空，
 *    卡片再次显示时由 [ensureLoaded] 从 tabs 表读回并解码。无痕标签的缩略图不落盘，所以不参与淘汰。
 *  - 标签关闭时 [forget] 释放。
 */
internal object TabThumbs {

    const val MAX_W = 360
    const val MAX_H = 640
    private const val MAX_IN_MEMORY = 32
    private const val WEBP_QUALITY = 70

    /** 全局同时在途的截图上限：Gecko 的整屏原图每张约 10MB，每个标签一张 + 单线程处理之外再加一道全局闸 */
    private const val MAX_IN_FLIGHT = 2

    /** 一次截图最长占用名额的时间：Gecko 截图没回调（页面销毁 / 失败）时名额会自己过期，不会被永久占住 */
    private const val IN_FLIGHT_TIMEOUT_MS = 5_000L

    /** 在途的截图：标签 id → 开始时间（uptime）。只在主线程读写 */
    private val inFlight = HashMap<Int, Long>()

    /**
     * 主线程：开始截图前申请名额。该标签已有一张在途、或全局已满（[MAX_IN_FLIGHT]）返回 false，这次不拍；
     * 拿到名额后必须在这张截图处理完（[BrowserTab.thumbCaptureDone]）或失败时调用 [end]。
     */
    fun tryBegin(tab: BrowserTab): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val it = inFlight.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > IN_FLIGHT_TIMEOUT_MS) it.remove()
        if (inFlight.containsKey(tab.id) || inFlight.size >= MAX_IN_FLIGHT) return false
        inFlight[tab.id] = now
        return true
    }

    /** 主线程：归还 [tryBegin] 拿到的名额（没拿过也无害） */
    fun end(tab: BrowserTab) {
        inFlight.remove(tab.id)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mafari-tab-thumb").apply { isDaemon = true }
    }

    /** 访问序 LRU：只在主线程读写 */
    private val lru = LinkedHashMap<Int, BrowserTab>(48, 0.75f, true)

    /**
     * 主线程：交一张刚截好的图。[ownsSource] 为 true 时原图归本管线，缩完回收。
     * 缩小 / 压缩在后台做，完成后回到主线程交给标签。
     */
    fun submit(tab: BrowserTab, source: Bitmap, ownsSource: Boolean) {
        val persist = !tab.incognito
        worker.execute {
            val small = runCatching { shrink(source) }.getOrNull()
            if (ownsSource && small !== source) runCatching { source.recycle() }
            val bytes = if (small != null && persist) runCatching { encode(small) }.getOrNull() else null
            main.post {
                tab.thumbCaptureDone()
                if (small == null || tab.closed) return@post
                tab.applyThumbnail(small)
                if (persist) {
                    remember(tab)
                    if (bytes != null) {
                        val key = tab.dbKey
                        HistoryDb.io.execute {
                            val db = HistoryDb.get(tab.appContext) ?: return@execute
                            runCatching { TabDb.saveThumb(db, key, bytes) }
                                .onFailure { Diag.log("tabs: save thumb failed: ${it.message}") }
                        }
                    }
                }
            }
        }
    }

    /** 主线程：卡片显示时调用；内存里没有缩略图就从库里读回（解码在后台）。 */
    fun ensureLoaded(tab: BrowserTab) {
        if (tab.thumbnail != null || tab.incognito || tab.closed || tab.thumbLoading) return
        tab.thumbLoading = true
        val key = tab.dbKey
        worker.execute {
            val bmp = runCatching {
                val db = HistoryDb.get(tab.appContext) ?: return@runCatching null
                val bytes = TabDb.loadThumb(db, key) ?: return@runCatching null
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }.getOrNull()
            main.post {
                tab.thumbLoading = false
                if (bmp != null && !tab.closed && tab.thumbnail == null) {
                    tab.applyThumbnailFromDb(bmp)
                    remember(tab)
                }
            }
        }
    }

    /** 标签关闭：从 LRU 里摘掉，内存里的缩略图置空（库里的行由下一次标签列表快照删除）。 */
    fun forget(tab: BrowserTab) {
        inFlight.remove(tab.id)
        lru.remove(tab.id)
        tab.dropThumbnail()
    }

    private fun remember(tab: BrowserTab) {
        lru[tab.id] = tab
        while (lru.size > MAX_IN_MEMORY) {
            val eldest = lru.entries.iterator()
            if (!eldest.hasNext()) break
            val victim = eldest.next().value
            eldest.remove()
            if (victim !== tab) victim.dropThumbnail()
        }
    }

    private fun shrink(src: Bitmap): Bitmap {
        val scale = minOf(MAX_W.toFloat() / src.width, MAX_H.toFloat() / src.height, 1f)
        if (scale >= 1f && src.config == Bitmap.Config.RGB_565) return src
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return runCatching {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            Canvas(out).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            out
        }.getOrElse {
            // 个别 Bitmap（如硬件位图）不能画到软件画布：退回系统缩放
            Bitmap.createScaledBitmap(src, w, h, true)
        }
    }

    private fun encode(bmp: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream(24 * 1024)
        bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)
        return out.toByteArray()
    }
}
