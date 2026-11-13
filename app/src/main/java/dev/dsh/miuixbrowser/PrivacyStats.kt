package dev.dsh.miuixbrowser

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * 隐私报告里「频繁获取敏感权限的网站」的数据源：记录每次网页发起的摄像头 / 麦克风 / 定位请求
 * （无论用户允许还是拒绝，记的是「请求」本身），按主机统计近 24 小时的次数。
 *
 * 只存主机名、请求类别和时间，最多保留 200 条、7 天；无痕标签页不记录（调用方负责）。
 * 存在 SQLCipher 加密库的 perm_events 表里（旧版是 SharedPreferences 里的明文 JSON，首次启动自动迁移）。
 */
object PrivacyStats {

    private data class PermEvent(val host: String, val kinds: List<String>, val time: Long)

    /** 一个被提醒的网站：近 [WINDOW_MS] 内请求次数 ≥ [THRESHOLD]。 */
    class Frequent(val host: String, val count: Int, val kinds: List<String>)

    const val WINDOW_MS = 24L * 60 * 60 * 1000
    const val THRESHOLD = 3
    private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_EVENTS = 200
    private const val PREFS = "mafari_privacy"
    private const val KEY = "perm_events"

    private val events = mutableStateListOf<PermEvent>()
    private var loadStarted = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 首次使用时在后台从加密库（perm_events 表）读入；读完后在主线程并入列表，界面随状态重组。 */
    private fun ensureLoaded(context: Context) {
        if (loadStarted) return
        loadStarted = true
        val app = context.applicationContext
        HistoryDb.io.execute {
            val db = HistoryDb.get(app) ?: return@execute
            runCatching { migrateLegacy(app, db) }
            val cutoff = System.currentTimeMillis() - KEEP_MS
            val rows = ArrayList<PermEvent>()
            runCatching {
                db.rawQuery(
                    "SELECT host,time,kinds FROM perm_events WHERE time>=$cutoff ORDER BY time ASC",
                    emptyArray<String>(),
                ).use { c ->
                    while (c.moveToNext()) {
                        rows.add(PermEvent(c.getString(0), c.getString(2).split(',').filter { it.isNotEmpty() }, c.getLong(1)))
                    }
                }
            }
            main.post { events.addAll(0, rows) }
        }
    }

    /** 旧版存在 SharedPreferences「mafari_privacy」的明文 JSON：导入加密库后删除。 */
    private fun migrateLegacy(context: Context, db: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return
        val arr = JSONArray(raw)
        db.beginTransaction()
        try {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ks = o.optJSONArray("k")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                db.execSQL(
                    "INSERT INTO perm_events(host,time,kinds) VALUES(?,?,?)",
                    arrayOf<Any?>(o.optString("h"), o.optLong("t"), ks.joinToString(",")),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        prefs.edit().remove(KEY).commit()
    }

    /** what 是界面文案（「使用摄像头、麦克风」「获取你的位置」），这里归一成类别。 */
    fun recordPermission(context: Context, host: String, what: String) {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return
        val kinds = buildList {
            if ("摄像头" in what) add("摄像头")
            if ("麦克风" in what) add("麦克风")
            if ("位置" in what || "定位" in what) add("定位")
        }
        if (kinds.isEmpty()) return
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        events.add(PermEvent(h, kinds, now))
        val cutoff = now - KEEP_MS
        events.removeAll { it.time < cutoff }
        while (events.size > MAX_EVENTS) events.removeAt(0)
        val app = context.applicationContext
        // 与加载共用同一个单线程队列：一定排在加载之后，不会重复也不会丢
        HistoryDb.io.execute {
            val db = HistoryDb.get(app) ?: return@execute
            runCatching {
                db.execSQL("INSERT INTO perm_events(host,time,kinds) VALUES(?,?,?)", arrayOf<Any?>(h, now, kinds.joinToString(",")))
                db.execSQL(
                    "DELETE FROM perm_events WHERE time<$cutoff OR id NOT IN (SELECT id FROM perm_events ORDER BY time DESC LIMIT $MAX_EVENTS)",
                )
            }
        }
    }

    /** 近 24 小时请求次数 ≥ 阈值的网站，次数多的在前；读的是 Compose 状态，列表变化会触发重组。 */
    fun frequent(context: Context): List<Frequent> {
        ensureLoaded(context)
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        return events.filter { it.time >= cutoff }
            .groupBy { it.host }
            .filter { it.value.size >= THRESHOLD }
            .map { (host, list) -> Frequent(host, list.size, list.flatMap { it.kinds }.distinct()) }
            .sortedByDescending { it.count }
    }
}
