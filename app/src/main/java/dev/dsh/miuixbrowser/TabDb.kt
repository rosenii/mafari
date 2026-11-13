package dev.dsh.miuixbrowser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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
)

/**
 * 标签列表的加密库存储：`tabs` 表，和历史、书签、下载记录同在 SQLCipher 整库加密的 history.db 里（[HistoryDb]）。
 *
 *  - 只存普通标签；无痕标签（含它的缩略图）永远不落盘。
 *  - 一行 = 一个标签：地址 / 标题 / 顺序 / 桌面模式 / opener 等元数据 + 缩略图 `thumb`（压缩后的 WebP，通常十几 KB）。
 *  - 元数据由 [sync] 整表快照、单事务写入（要么整份新列表生效，要么保持旧的）；缩略图由 [saveThumb] 单独写，
 *    不会被元数据快照覆盖。列表查询不读 `thumb`，缩略图只在标签卡片真的显示出来时才用 [loadThumb] 读。
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

    const val CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_tabs_pos ON tabs(saved,pos)"

    private const val UPSERT =
        "INSERT INTO tabs(tab_key,saved,pos,url,title,on_home,desktop,opener_key,from_link,last_used) " +
            "VALUES(?,1,?,?,?,?,?,?,?,?) ON CONFLICT(tab_key) DO UPDATE SET " +
            "saved=1,pos=excluded.pos,url=excluded.url,title=excluded.title,on_home=excluded.on_home," +
            "desktop=excluded.desktop,opener_key=excluded.opener_key,from_link=excluded.from_link,last_used=excluded.last_used"

    /** 新标签的持久 id（不会和库里已有的撞：63 位随机数） */
    fun newKey(): Long = ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE)

    /** 按顺序读出上次保存的标签（不含缩略图）。 */
    fun load(db: SQLiteDatabase): List<TabRow> {
        val out = ArrayList<TabRow>()
        db.rawQuery(
            "SELECT tab_key,pos,url,title,on_home,desktop,opener_key,from_link,last_used FROM tabs WHERE saved=1 ORDER BY pos",
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
                    ),
                )
            }
        }
        return out
    }

    /** 整表快照：写入 [rows]，删掉不在其中的行（含已关闭标签的缩略图）。须在 [HistoryDb.io] 上调用。 */
    fun sync(db: SQLiteDatabase, rows: List<TabRow>) {
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
        db.execSQL(
            "INSERT INTO tabs(tab_key,thumb) VALUES(?,?) ON CONFLICT(tab_key) DO UPDATE SET thumb=excluded.thumb",
            arrayOf<Any?>(key, bytes),
        )
    }

    fun loadThumb(db: SQLiteDatabase, key: Long): ByteArray? {
        db.rawQuery("SELECT thumb FROM tabs WHERE tab_key=?", arrayOf(key.toString())).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null
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
