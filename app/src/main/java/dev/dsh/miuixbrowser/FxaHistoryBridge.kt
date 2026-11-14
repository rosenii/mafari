package dev.dsh.miuixbrowser

import android.content.Context
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.concept.storage.PageObservation
import mozilla.components.concept.storage.PageVisit
import mozilla.components.concept.storage.RedirectSource
import mozilla.components.concept.storage.VisitType

/**
 * Places 历史镜像 ↔ HistoryDb（SQLCipher）。
 *  - 拉：自上次以来 isRemote 的访问（别的设备产生的）→ HistoryDb.upsert，保留原始访问时间；
 *  - 推：自上次以来本地新增的历史 → Places.recordVisit（Places 公开接口只能记「此刻」的访问，时间会变成同步时间）。
 * 无痕标签本来就不进 HistoryDb，所以不会被推出去。
 */
internal object FxaHistoryBridge {
    private const val PREFS = "mafari_fxa_history"
    private const val K_PULL = "lastPull"
    private const val K_PUSH = "lastPush"

    suspend fun reconcile(context: Context) {
        val db = HistoryDb.get(context) ?: return
        val places = PlacesHistoryStorage(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // 拉
        val lastPull = prefs.getLong(K_PULL, 0L)
        val remote = places.getDetailedVisits(lastPull, Long.MAX_VALUE).filter { it.isRemote }
        val remoteUrls = HashSet<String>()
        for (v in remote) {
            if (!v.url.startsWith("http")) continue
            remoteUrls += v.url
            HistoryDb.upsert(db, v.url, v.title.orEmpty(), v.visitTime)
        }
        prefs.edit().putLong(K_PULL, now).apply()

        // 推
        val lastPush = prefs.getLong(K_PUSH, 0L)
        val fresh = HistoryDb.query(db, "", 500).filter { it.time > lastPush && it.url.startsWith("http") && it.url !in remoteUrls }
        for (e in fresh) {
            places.recordVisit(e.url, PageVisit(VisitType.LINK, RedirectSource.NOT_SOURCE))
            if (e.title.isNotBlank()) places.recordObservation(e.url, PageObservation(e.title))
        }
        prefs.edit().putLong(K_PUSH, now).apply()
        if (fresh.isNotEmpty()) FxaSync.syncNow()
    }
}
