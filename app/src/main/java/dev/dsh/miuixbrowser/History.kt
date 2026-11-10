package dev.dsh.miuixbrowser

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val url: String, val title: String, val time: Long)

class HistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("mafari_history", Context.MODE_PRIVATE)

    val items = mutableStateListOf<HistoryEntry>().apply { addAll(load()) }

    private fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        HistoryEntry(
                            url = obj.optString("url"),
                            title = obj.optString("title"),
                            time = obj.optLong("time"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun record(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        items.removeAll { it.url == url }
        items.add(0, HistoryEntry(url, title.ifBlank { hostOf(url) }, System.currentTimeMillis()))
        while (items.size > 300) items.removeAt(items.lastIndex)
        persist()
    }

    fun remove(url: String) {
        if (items.removeAll { it.url == url }) persist()
    }

    /** 记录数：最近 [withinMillis] 毫秒内的条数 */
    fun countWithin(withinMillis: Long): Int {
        val cutoff = System.currentTimeMillis() - withinMillis
        return items.count { it.time >= cutoff }
    }

    /** 清除最近 [withinMillis] 毫秒内的记录 */
    fun clearWithin(withinMillis: Long) {
        val cutoff = System.currentTimeMillis() - withinMillis
        if (items.removeAll { it.time >= cutoff }) persist()
    }

    fun clear() {
        items.clear()
        persist()
    }

    /** 主线程只拷一份快照（HistoryEntry 不可变），JSON 序列化与写 prefs 交给单线程后台，按提交顺序执行 */
    private fun persist() {
        val snapshot = items.toList()
        writer.execute {
            runCatching {
                val array = JSONArray()
                for (e in snapshot) {
                    array.put(JSONObject().put("url", e.url).put("title", e.title).put("time", e.time))
                }
                prefs.edit().putString(KEY, array.toString()).apply()
            }
        }
    }

    private companion object {
        const val KEY = "entries"

        private val writer: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "mafari-history-writer").apply { isDaemon = true }
            }
    }
}
