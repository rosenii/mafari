package dev.dsh.miuixbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * 油猴脚本 GM_setValue / GM_getValue 的存储：SQLCipher 库（[HistoryDb]）里的 gm_values 表，
 * 主键 (脚本 id, 名字)。WebView 的 [GmBridge] 和 Gecko 的 GeckoGm 共用。
 *
 * - 取代旧的 SharedPreferences「userscript_gm」：改一个值只写一行，不再整份文件重写，也不再把所有脚本的值常驻内存；数据同时变成加密存储。
 * - 值最大 200 万字符，而 CursorWindow 单行上限约 2MB：超过 [CHUNK] 的值按 substr 分块读出。
 * - 值变化（内容真的变了）会回调 [listener]（主线程），Gecko 端据此广播给各个框架。
 */
internal object GmStore {

    private const val CHUNK = 400_000

    /** (脚本 id, 名字, 新值；删除为 null)，主线程回调 */
    @Volatile
    var listener: ((sid: String, key: String, value: String?) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    private fun notify(sid: String, key: String, value: String?) {
        main.post { listener?.invoke(sid, key, value) }
    }

    /** 值的字符数；不存在返回 -1。 */
    fun length(context: Context, sid: String, key: String): Int {
        val db = HistoryDb.get(context) ?: return -1
        db.rawQuery("SELECT length(v) FROM gm_values WHERE sid=? AND k=?", arrayOf(sid, key)).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else -1
        }
    }

    fun get(context: Context, sid: String, key: String): String? {
        val db = HistoryDb.get(context) ?: return null
        val len = length(context, sid, key)
        if (len < 0) return null
        if (len <= CHUNK) {
            db.rawQuery("SELECT v FROM gm_values WHERE sid=? AND k=?", arrayOf(sid, key)).use { c ->
                return if (c.moveToFirst()) c.getString(0) else null
            }
        }
        val sb = StringBuilder(len)
        var pos = 1
        while (pos <= len) {
            db.rawQuery("SELECT substr(v,$pos,$CHUNK) FROM gm_values WHERE sid=? AND k=?", arrayOf(sid, key)).use { c ->
                if (!c.moveToFirst()) return null
                sb.append(c.getString(0))
            }
            pos += CHUNK
        }
        return sb.toString()
    }

    fun set(context: Context, sid: String, key: String, value: String) {
        val db = HistoryDb.get(context) ?: return
        // 内容没变就不写、不通知（与 SharedPreferences 的行为一致）；比较在库内完成，不把旧值读出来
        val same = db.rawQuery("SELECT v=? FROM gm_values WHERE sid=? AND k=?", arrayOf(value, sid, key)).use { c ->
            c.moveToFirst() && c.getInt(0) == 1
        }
        if (same) return
        db.execSQL("INSERT OR REPLACE INTO gm_values(sid,k,v) VALUES(?,?,?)", arrayOf<Any?>(sid, key, value))
        notify(sid, key, value)
    }

    fun delete(context: Context, sid: String, key: String) {
        val db = HistoryDb.get(context) ?: return
        if (length(context, sid, key) < 0) return
        db.execSQL("DELETE FROM gm_values WHERE sid=? AND k=?", arrayOf<Any?>(sid, key))
        notify(sid, key, null)
    }

    fun keys(context: Context, sid: String): List<String> {
        val db = HistoryDb.get(context) ?: return emptyList()
        db.rawQuery("SELECT k FROM gm_values WHERE sid=? ORDER BY k", arrayOf(sid)).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            return out
        }
    }

    /** 某脚本的全部 (名字, 字符数)，不读值本身；调用方按大小决定读哪些。 */
    fun entries(context: Context, sid: String): List<Pair<String, Int>> {
        val db = HistoryDb.get(context) ?: return emptyList()
        db.rawQuery("SELECT k,length(v) FROM gm_values WHERE sid=?", arrayOf(sid)).use { c ->
            val out = ArrayList<Pair<String, Int>>()
            while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1))
            return out
        }
    }

    /** 备份用：导出成旧格式（键 = 脚本id::名字，值为 {"t":"s","v":..}）；没有数据返回 null。 */
    fun exportJson(context: Context): String? {
        val db = HistoryDb.get(context) ?: return null
        val pairs = ArrayList<Pair<String, String>>()
        db.rawQuery("SELECT sid,k FROM gm_values", emptyArray<String>()).use { c ->
            while (c.moveToNext()) pairs.add(c.getString(0) to c.getString(1))
        }
        if (pairs.isEmpty()) return null
        val root = JSONObject()
        for ((sid, k) in pairs) {
            val v = get(context, sid, k) ?: continue
            root.put("$sid::$k", JSONObject().put("t", "s").put("v", v))
        }
        return root.toString()
    }

    /** 备份恢复前清空（恢复是整体替换），不通知监听器。 */
    fun clearAll(context: Context) {
        HistoryDb.get(context)?.execSQL("DELETE FROM gm_values")
    }
}
