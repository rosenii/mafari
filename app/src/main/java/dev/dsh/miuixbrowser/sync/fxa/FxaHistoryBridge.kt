/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sync.fxa

import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.data.HistoryDao
import dev.dsh.miuixbrowser.util.hostOf
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Places 历史镜像 ↔ AppDatabase（SQLCipher）。
 *
 * 拉（完整性）：每次都全量读取其他设备的访问，按网址合并后写入（UPSERT 取最大时间，重复写入无副作用），
 *   不再按「上次拉取时间」过滤——离线很久的设备事后才上传的旧访问，按时间过滤会永久漏掉。
 * 推（完整性）：按「时间游标」分批推送，游标只在一批推完后才前移；单次最多 [MAX_PUSH_PER_RUN] 条，
 *   没推完会自动再同步一轮，不会像之前那样超过 500 条就丢。
 * 准确性：本地条目的时间不晚于该网址在远端的最新访问时，说明它只是拉下来的镜像，不再推回去；
 *   晚于远端的才是真正的新访问。Places 只能记「此刻」，推送出去的访问时间会变成同步时刻。
 * 绝对双向同步（FxaSync.absoluteSync）：以「上一轮两边都有的网址」为基线（存在 filesDir 的文本文件里），
 *   基线里有、本机现在没有、云端还有 → 删云端；基线里有、云端现在没有、本机还有 → 删本机。
 *   刚开启时没有基线，第一轮只记录不删；云端→本机一轮要删超过基线一半（且 ≥ [MASS_DELETE_MIN] 条）时视为异常，这一方向本轮不删。
 * 安全：无痕标签不进 AppDatabase（不会被推）；推送前过滤带账号密码的网址、本机 / 内网地址；
 *   拉取时只收 http(s)、限制网址和标题长度、去掉控制字符。
 */
internal object FxaHistoryBridge {
    private const val PREFS = "mafari_fxa_history"
    private const val K_PUSH = "lastPush"
    private const val BATCH = 500
    private const val MAX_PUSH_PER_RUN = 2000
    private const val MAX_URL = 2048
    private const val MAX_TITLE = 512
    private const val BASE_FILE = "fxa_history_baseline.txt"
    private const val MASS_DELETE_MIN = 20

    /** 关 / 开「绝对双向同步」、退出账号时清掉删除基线：下一轮只重新记录，不删东西。 */
    fun resetBaseline(context: Context) {
        runCatching { File(context.filesDir, BASE_FILE).delete() }
    }

    suspend fun reconcile(context: Context) {
        val db = AppDatabase.get(context) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // ---- 拉：全量，按网址合并（保留最新访问的时间和标题）----
        class Latest(var time: Long, var title: String)
        val remote = HashMap<String, Latest>()
        for (v in FxaApi.remoteVisits(0L)) {
            if (!acceptable(v.url)) continue
            val cur = remote[v.url]
            if (cur == null) remote[v.url] = Latest(v.time, v.title)
            else if (v.time >= cur.time) { cur.time = v.time; cur.title = v.title }
        }
        // ---- 绝对双向同步：先把删除传过去 ----
        val absolute = FxaSync.absoluteSync
        val baseFile = File(context.filesDir, BASE_FILE)
        val baseline: Set<String>? = if (absolute && baseFile.exists()) runCatching { baseFile.readLines().toHashSet() }.getOrNull() else null
        val presence: HashSet<String>? = if (absolute) FxaApi.placesUrls().filterTo(HashSet()) { acceptable(it) } else null
        if (absolute && baseline != null && presence != null) {
            val localUrls = HistoryDao.allUrls(db)
            // 本机删了、云端还在 → 删云端
            for (u in baseline) {
                if (u in presence && u !in localUrls && runCatching { FxaApi.deleteHistory(u) }.isSuccess) {
                    presence.remove(u)
                    remote.remove(u)
                }
            }
            // 云端删了、本机还在 → 删本机
            val delLocal = baseline.filter { it in localUrls && it !in presence }
            if (delLocal.isNotEmpty() && !(delLocal.size >= MASS_DELETE_MIN && delLocal.size * 2 > baseline.size)) {
                var k = 0
                while (k < delLocal.size) {
                    db.beginTransaction()
                    try {
                        val end = minOf(k + BATCH, delLocal.size)
                        for (n in k until end) HistoryDao.delete(db, delLocal[n])
                        db.setTransactionSuccessful()
                        k = end
                    } finally {
                        db.endTransaction()
                    }
                }
            }
        }

        val pulled = remote.entries.toList()
        var i = 0
        while (i < pulled.size) { // 分批事务：不长时间占着写锁，界面线程写本地历史不会卡
            db.beginTransaction()
            try {
                val end = minOf(i + BATCH, pulled.size)
                for (k in i until end) {
                    val (url, l) = pulled[k]
                    val title = cleanTitle(l.title).ifBlank { hostOf(url) }
                    HistoryDao.upsert(db, url, title, l.time)
                }
                db.setTransactionSuccessful()
                i = end
            } finally {
                db.endTransaction()
            }
        }

        // ---- 推：时间游标分批 ----
        // 存的游标是「已处理到的最后一条的时间」：下一轮从它之后开始查。
        // 此前直接从游标本身（含）开始，游标那一条每轮都被当成新访问重推 → pushed 永远 > 0 → 同步完又同步，无限循环
        var cursor = prefs.getLong(K_PUSH, 0L) + 1
        var seenAtCursor = HashSet<String>() // 游标那一毫秒已处理过的网址（同一时刻多条时防重复 / 防遗漏）
        var pushed = 0
        val pushedUrls = HashSet<String>()
        var more = true
        while (more && pushed < MAX_PUSH_PER_RUN) {
            val rows = HistoryDao.queryFrom(db, cursor, BATCH)
            val fresh = rows.filter { !(it.time == cursor && it.url in seenAtCursor) }
            if (fresh.isEmpty()) break
            more = rows.size >= BATCH
            for (e in fresh) {
                val mirrored = remote[e.url]?.let { it.time >= e.time } == true
                if (!mirrored && shareable(e.url)) {
                    FxaApi.recordVisit(e.url, cleanTitle(e.title))
                    pushedUrls += e.url
                    pushed++
                }
            }
            val lastTime = fresh.last().time
            if (lastTime != cursor) seenAtCursor = HashSet()
            fresh.filter { it.time == lastTime }.forEach { seenAtCursor.add(it.url) }
            cursor = lastTime
            prefs.edit().putLong(K_PUSH, cursor).apply() // 一批处理完才前移游标
        }
        // 记新基线：此刻本机和云端都有的网址
        if (absolute && presence != null) {
            val cloud = presence.toHashSet().apply { addAll(remote.keys); addAll(pushedUrls) }
            val both = HistoryDao.allUrls(db).filter { it in cloud }
            runCatching { baseFile.writeText(both.joinToString("\n")) }
        }
        if (pushed > 0) FxaSync.syncFollowUp() // 没推完的下一轮同步继续
    }

    // ------------------------------------------------------------ 过滤 / 清洗

    private fun hostOf(url: String): String = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { url.take(64) }

    private fun cleanTitle(t: String): String = t.filter { !it.isISOControl() }.trim().take(MAX_TITLE)

    /** 能收进本地：只要 http(s)，且长度合理。 */
    private fun acceptable(url: String): Boolean {
        if (url.length > MAX_URL) return false
        val scheme = url.substringBefore(':').lowercase()
        return scheme == "http" || scheme == "https"
    }

    /** 能推到 Firefox：在 [acceptable] 之上，排除带账号密码的网址、本机和内网地址。 */
    private fun shareable(url: String): Boolean {
        if (!acceptable(url)) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!uri.userInfo.isNullOrEmpty()) return false
        val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
        return !isPrivateHost(host)
    }

    private fun isPrivateHost(h: String): Boolean {
        if (h == "localhost" || !h.contains('.') && !h.contains(':')) return true // 单标签主机名（路由器等）
        if (listOf(".local", ".lan", ".internal", ".home.arpa", ".localdomain").any { h.endsWith(it) }) return true
        if (h.contains(':')) return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size == 4) {
            return p[0] == 10 || p[0] == 127 || p[0] == 0 ||
                (p[0] == 169 && p[1] == 254) ||
                (p[0] == 172 && p[1] in 16..31) ||
                (p[0] == 192 && p[1] == 168)
        }
        return false
    }
}
