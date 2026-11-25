package dev.dsh.miuixbrowser.data

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.crypto.SecureStore
import dev.dsh.miuixbrowser.util.Diag
import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 应用主库 history.db（SQLCipher 整库 AES-256）的连接生命周期：打开、就绪状态、单写线程。
 * 只管这三件事；表结构见 [Migrations]，各表的读写见 [HistoryDao]、[BookmarkDb]、[TabDb]、[DownloadDb] 等。
 *
 *  - 库密钥：随机 32 字节（hex），经 [SecureStore]（Android Keystore 保护的加密引擎）封装后存在
 *    SharedPreferences「mafari_history_db」里；密钥取不回来时旧库无法解开，删库重建（与保险库重置一致）。
 *  - 打开方式、旧口令格式迁移见 [HistoryDbKey]；首次打开后的旧 SharedPreferences 数据导入见 [LegacyImports]。
 */
internal object AppDatabase {

    private const val DB_NAME = "history.db"
    private const val KEY_PREFS = "mafari_history_db"
    private const val KEY_NAME = "key"

    @Volatile
    private var instance: SQLiteDatabase? = null

    @Volatile
    private var failed = false

    /** [get] 首次完整走完（打开 + 建表 + 旧数据迁移，或确定打不开）后置位。 */
    @Volatile
    private var settled = false

    /** 库已完全就绪（含迁移），或已确定打不开（降级）：此时 [get] 立即返回，不会阻塞调用线程。 */
    fun isReady(): Boolean = settled

    /** 阻塞到 [get] 的首次打开 / 迁移全部结束。必须在非主线程调用。 */
    fun awaitReady(context: Context) {
        get(context)
        // instance 在迁移之前就已赋值，别的线程的 get 会提前返回；这里借打开者持有的锁等它迁移完
        synchronized(this) { }
    }

    /** 所有写入共用的单线程执行器（历史、隐私统计、书签、标签等）：按提交顺序执行 */
    val io: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "mafari-db-writer").apply { isDaemon = true }
        }

    /** 取（必要时打开并迁移）数据库；打不开返回 null（各功能降级为不记录，不影响浏览）。 */
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
                settled = true
                return null
            }
            instance = db
            LegacyImports.run(app, db)
            settled = true
            // 库已就绪、不再卡首屏之后，才把旧套件封装的库密钥换成新套件（之后每次冷启动解包都变快）
            io.execute { resealKeyIfNeeded(app) }
            return db
        }
    }

    /** 库密钥若还是旧套件（如 ML-KEM 混合套件）的密文：读出后用当前本机默认套件重封，回读校验一致才写回。后台单写线程执行。 */
    private fun resealKeyIfNeeded(app: Context) {
        runCatching {
            val prefs = app.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_NAME, null) ?: return@runCatching
            if (!SecureStore.needsUpgrade(raw)) return@runCatching
            val plain = SecureStore.read(app, raw, SecureStore.PURPOSE_HISTORY_DB) ?: return@runCatching
            val sealed = SecureStore.write(app, plain, SecureStore.PURPOSE_HISTORY_DB) ?: return@runCatching
            // 回读校验：新密文必须能解回同一把密钥，才替换旧的
            if (SecureStore.read(app, sealed, SecureStore.PURPOSE_HISTORY_DB) != plain) return@runCatching
            if (prefs.edit().putString(KEY_NAME, sealed).commit()) Diag.log("history db key: resealed with current device suite")
        }.onFailure { Diag.log("history db key: reseal skipped: ${it.message}") }
    }

    private fun open(context: Context): SQLiteDatabase {
        // 启动耗时分段记录（设置 → 高级 → Gecko 调试 里打开诊断日志后写入 diag.txt），用来判断哪一段最慢
        var t = android.os.SystemClock.elapsedRealtime()
        fun lap(name: String) {
            val now = android.os.SystemClock.elapsedRealtime()
            Diag.log("history db open: $name ${now - t}ms")
            t = now
        }
        System.loadLibrary("sqlcipher")
        lap("loadLibrary")
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val prefs = context.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
        var pass: String? = prefs.getString(KEY_NAME, null)
            ?.let { SecureStore.read(context, it, SecureStore.PURPOSE_HISTORY_DB) }
        lap("unwrap db key")
        if (pass == null) {
            // 首次使用，或旧密钥已丢：旧库打不开，清掉后用新密钥重建
            HistoryDbKey.discard(file)
            val hex = HistoryDbKey.newKeyHex()
            val sealed = SecureStore.write(context, hex, SecureStore.PURPOSE_HISTORY_DB)
                ?: error(tr("无法保存数据库密钥"))
            check(prefs.edit().putString(KEY_NAME, sealed).commit()) { tr("写入数据库密钥失败") }
            pass = hex
        }
        val db = try {
            HistoryDbKey.open(file, pass)
        } catch (e: HistoryDbKey.MigrationException) {
            throw e // 迁移没做成但库完好：绝不能删库，向上抛，本次启动按「打不开」降级，下次启动自动重试
        } catch (e: Exception) {
            Diag.log("history db: unreadable, recreate: ${e.message}")
            HistoryDbKey.discard(file)
            HistoryDbKey.open(file, pass)
        }
        lap("open (raw key, no KDF)")
        db.enableWriteAheadLogging()
        // WAL + NORMAL：提交不再逐次 fsync（断电最多丢最后几笔，不会损坏库），脚本频繁 GM_setValue 也不卡
        db.execSQL("PRAGMA synchronous=NORMAL")
        lap("WAL + pragma")
        Migrations.apply(db)
        lap("schema migrations")
        return db
    }
}
