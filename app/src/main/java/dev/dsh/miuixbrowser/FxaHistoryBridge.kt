package dev.dsh.miuixbrowser

import android.content.Context

/**
 * Places 历史镜像 ↔ HistoryDb（SQLCipher）。
 *  - 拉：自上次以来别的设备产生的访问 → HistoryDb.upsert，保留原访问时间；
 *  - 推：自上次以来本地新增的历史 → recordVisit（Places 只能记「此刻」，时间会变成同步时刻）。
 * 无痕标签不进 HistoryDb，不会被推出去。
 */
internal object FxaHistoryBridge {
    private const val PREFS = "mafari_fxa_history"
    private const val K_PULL = "lastPull"
    private const val K_PUSH = "lastPush"

    suspend fun reconcile(context: Context) {
        val db = HistoryDb.get(context) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val remoteUrls = HashSet<String>()
        for (v in FxaApi.remoteVisits(prefs.getLong(K_PULL, 0L))) {
            if (!v.url.startsWith("http")) continue
            remoteUrls += v.url
            HistoryDb.upsert(db, v.url, v.title, v.time)
        }
        prefs.edit().putLong(K_PULL, now).apply()

        val lastPush = prefs.getLong(K_PUSH, 0L)
        val fresh = HistoryDb.query(db, "", 500)
            .filter { it.time > lastPush && it.url.startsWith("http") && it.url !in remoteUrls }
        for (e in fresh) FxaApi.recordVisit(e.url, e.title)
        prefs.edit().putLong(K_PUSH, now).apply()
        if (fresh.isNotEmpty()) FxaSync.syncNow()
    }
}
