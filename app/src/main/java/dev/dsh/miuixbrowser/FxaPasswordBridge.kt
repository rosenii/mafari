package dev.dsh.miuixbrowser

import android.content.Context

/**
 * 密码：Rust logins 镜像库（Firefox 同步引擎的本地副本）↔ [PasswordDb]（本机真正存放密码的加密库）。
 *
 * 本机密码仍只放在 PasswordDb；镜像库只是同步的中转（FxaApi 里 SyncableLoginsStorage），每次同步结束后对一次账：
 *  1. 拉：镜像库全部登录 → [PasswordDb.mergeRemote]（同一凭据按「密码修改时间」较新者为准，合并结果标记为已同步；备注只在本地，不动）；
 *  2. 服务器删除：上一次对账时镜像库里有、这次没有，且本地没有未上传的改动 → 本地同步删除（不留墓碑）。
 *     「上次的 id 集合」存在 prefs；集合为空（首次 / 退出账号后）绝不据此删任何东西，避免换账号或同步失败时误删本机密码；
 *  3. 推：[PasswordDb.pendingUpload] → 镜像库（已有 id 用 update，没有的 addOrUpdate，库里已有同站点同用户名的会合并），
 *     新增时库自己分配 id，随后把本地记录换成这个 id（[PasswordDb.reguid]）；本地删除的墓碑 → 镜像库 delete；
 *  4. 推了东西就再同步一轮，让 Rust 引擎把镜像库的改动上传到服务器。
 * 数据库没解锁 / 打不开时整体跳过。镜像库里的明文只在内存中过手，不写日志。
 * 已知限制：Rust 库新增登录时「创建时间 / 最近使用」记为同步时刻（本地保留原值，不会被覆盖）。
 */
internal object FxaPasswordBridge {
    private const val PREFS = "mafari_fxa_passwords"
    private const val K_MIRROR = "mirrorGuids"

    class Report(val pulled: Int, val pushed: Int, val deletedRemote: Int)

    suspend fun reconcile(context: Context): Report {
        val db = HistoryDb.get(context) ?: return Report(0, 0, 0)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastMirror = prefs.getStringSet(K_MIRROR, emptySet()).orEmpty().toHashSet()

        // ---- 拉 ----
        val mirror = FxaApi.loginList()
        val mirrorIds = mirror.mapTo(HashSet()) { it.guid }
        val incoming = mirror.mapNotNull { PasswordSyncFormat.fromLogin(it) }
        val stats = if (incoming.isNotEmpty()) PasswordDb.mergeRemote(context, db, incoming) else null

        // ---- 服务器删除 ----
        val synced = PasswordDb.syncedGuids(db) // 只含「没有未上传改动」的行
        val goneRemotely = synced.filter { it in lastMirror && it !in mirrorIds }
        if (goneRemotely.isNotEmpty()) PasswordDb.applyRemoteDeletes(db, goneRemotely)

        // ---- 推 ----
        var pushed = 0
        val pushedIds = HashSet<String>()
        for (e in PasswordDb.pendingUpload(context, db)) {
            val l = PasswordSyncFormat.toLogin(e)
            val id = if (e.guid in mirrorIds && FxaApi.loginUpdate(e.guid, l)) e.guid else FxaApi.loginAddOrUpdate(l)
            if (PasswordDb.reguid(context, db, e.guid, id)) {
                PasswordDb.markSynced(db, listOf(id))
                pushedIds += id
                pushed++
            }
        }
        val tombs = PasswordDb.tombstones(db)
        if (tombs.isNotEmpty()) {
            tombs.forEach { FxaApi.loginDelete(it) }
            PasswordDb.clearTombstones(db, tombs)
            pushed += tombs.size
        }

        // 记下这次镜像库的 id（含刚推进去的），下次据此判断「服务器上被删了」
        prefs.edit().putStringSet(K_MIRROR, (mirrorIds + pushedIds).toHashSet()).apply()
        if (pushed > 0) FxaSync.syncFollowUp()
        return Report((stats?.added ?: 0) + (stats?.updated ?: 0), pushed, goneRemotely.size)
    }

    /** 退出账号：清空镜像库与同步状态，下一个账号不会继承上一个账号的数据。 */
    suspend fun onLoggedOut(context: Context) {
        runCatching { FxaApi.loginWipeLocal() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(K_MIRROR).apply()
        HistoryDb.get(context)?.let { PasswordDb.resetSyncState(it) }
    }
}
