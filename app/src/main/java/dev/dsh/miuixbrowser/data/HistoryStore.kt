package dev.dsh.miuixbrowser.data

import dev.dsh.miuixbrowser.util.Diag
import dev.dsh.miuixbrowser.util.hostOf
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * 浏览历史。数据在 [HistoryDao]（SQLCipher，连接见 [AppDatabase]）里，内存只保留最近几条（首页用）和总数；
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
            AppDatabase.get(appContext)?.let { refresh(it) }
        }
    }

    fun record(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        val entry = HistoryEntry(url, title.ifBlank { hostOf(url) }, System.currentTimeMillis())
        write { HistoryDao.upsert(it, entry.url, entry.title, entry.time) }
    }

    fun remove(url: String) = write { HistoryDao.delete(it, url) }

    /** 清除最近 [withinMillis] 毫秒内的记录 */
    fun clearWithin(withinMillis: Long) {
        val cutoff = System.currentTimeMillis() - withinMillis
        write { HistoryDao.clearWithin(it, cutoff) }
    }

    fun clear() = write { HistoryDao.clearAll(it) }

    /** 记录数：最近 [withinMillis] 毫秒内的条数（只做一次带索引的 COUNT） */
    fun countWithin(withinMillis: Long): Int {
        val db = AppDatabase.get(appContext) ?: return 0
        return HistoryDao.count(db, System.currentTimeMillis() - withinMillis)
    }

    /** 按时间倒序读最多 [limit] 条，[q] 非空时过滤。在 IO 线程调用。 */
    fun query(q: String, limit: Int): List<HistoryEntry> {
        val db = AppDatabase.get(appContext) ?: return emptyList()
        return runCatching { HistoryDao.query(db, q, limit) }.getOrDefault(emptyList())
    }

    private fun write(block: (SQLiteDatabase) -> Unit) {
        writer.execute {
            val db = AppDatabase.get(appContext) ?: return@execute
            runCatching { block(db) }.onFailure { Diag.log("history db: write failed: ${it.message}") }
            refresh(db)
        }
    }

    /** 后台线程：重读最近几条和总数，再到主线程更新状态。 */
    private fun refresh(db: SQLiteDatabase) {
        val latest = runCatching { HistoryDao.query(db, "", RECENT_SIZE) }.getOrDefault(emptyList())
        val total = runCatching { HistoryDao.count(db) }.getOrDefault(0)
        main.post {
            recent.clear()
            recent.addAll(latest)
            totalCount = total
            version++
        }
    }

    private companion object {
        const val RECENT_SIZE = 20

        private val writer: java.util.concurrent.ExecutorService get() = AppDatabase.io
    }
}
