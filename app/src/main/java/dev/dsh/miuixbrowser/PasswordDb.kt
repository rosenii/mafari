package dev.dsh.miuixbrowser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/**
 * 密码的加密库存储：`passwords` 表，和历史、书签、脚本同在 SQLCipher 整库加密的 history.db 里（[HistoryDb]）。
 *
 * 两层加密：
 *  - 整库：SQLCipher AES-256，库密钥由 Android Keystore 保护的加密引擎封装（见 [HistoryDb]）；
 *  - 逐条：密码与备注先合成一个 JSON，再用 [SecureStore]（现有加密套件，当前为 X25519 + ML-KEM-768 混合套件）封进信封，
 *    存在 `secret` 列。AAD 绑定记录 id（[purposeOf]），所以把 A 行的密文拷到 B 行会解不开。
 *    这样即使有人拿到解开后的 .db（或某个备份里的明文库），拿到的也只有站点和用户名，密码仍是密文。
 *  - 旧套件 / 旧信封的行在被读出时自动换成当前套件（见 [reveal]）。
 *
 * 列表只读元数据（站点、用户名、时间），**不解密**；要看 / 复制密码才按需解开单条（[reveal]）。
 *
 * Firefox 同步的数据接口（只有数据，不含同步代码）：
 *  - 记录格式互转见 [PasswordSyncFormat]（Firefox `passwords` 集合的 payload，字段与 [PasswordEntry] 一一对应）；
 *  - `sync_status`：0 = 本地新增 / 改过，需要上传；1 = 已与服务器一致；
 *  - [pendingUpload] / [markSynced]：同步代码取待上传记录、上传成功后标记；
 *  - [mergeRemote]：把服务器记录并进本地（按「密码修改时间」较新者为准，不会再标成待上传）；
 *  - 删除会在 `passwords_deleted` 留墓碑（[tombstones] / [clearTombstones]），同步时才能把删除传出去。
 */
internal object PasswordDb {

    const val CREATE_PASSWORDS =
        "CREATE TABLE IF NOT EXISTS passwords(" +
            "guid TEXT PRIMARY KEY NOT NULL, " +
            "origin TEXT NOT NULL, " +
            "form_origin TEXT, " +
            "http_realm TEXT, " +
            "username TEXT NOT NULL, " +
            "secret TEXT NOT NULL, " +
            "username_field TEXT NOT NULL, " +
            "password_field TEXT NOT NULL, " +
            "time_created INTEGER NOT NULL, " +
            "time_last_used INTEGER NOT NULL, " +
            "time_changed INTEGER NOT NULL, " +
            "times_used INTEGER NOT NULL, " +
            "sync_status INTEGER NOT NULL DEFAULT 0)"

    const val CREATE_ORIGIN_INDEX = "CREATE INDEX IF NOT EXISTS idx_passwords_origin ON passwords(origin)"

    const val CREATE_TOMBSTONES =
        "CREATE TABLE IF NOT EXISTS passwords_deleted(guid TEXT PRIMARY KEY NOT NULL, time INTEGER NOT NULL) WITHOUT ROWID"

    const val SYNC_PENDING = 0
    const val SYNC_DONE = 1

    /** 列表用的元数据：不含密码和备注。 */
    data class Item(
        val guid: String,
        val origin: String,
        val username: String,
        val formOrigin: String?,
        val httpRealm: String?,
        val timeCreated: Long,
        val timeLastUsed: Long,
        val timePasswordChanged: Long,
        val timesUsed: Int,
    )

    /** 导入 / 合并的统计。[unchanged] = 已存在且密码相同（无需处理）。 */
    data class Stats(val added: Int, val updated: Int, val unchanged: Int, val failed: Int)

    private val rng = SecureRandom()

    /** 每次写入后递增：界面（设置页的条数摘要等）用它作为重新读取的触发器。 */
    var version by mutableIntStateOf(0)
        private set

    /** 每条记录的加密上下文（进 AAD）：把密文和记录 id 绑在一起。 */
    fun purposeOf(guid: String) = SecureStore.PURPOSE_PASSWORD_ITEM + guid

    /** Firefox 同步的记录 id 形态：9 个随机字节的 base64url（12 个字符）。 */
    fun newGuid(): String {
        val b = ByteArray(9).also { rng.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    // ------------------------------------------------------------------ 读

    fun count(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM passwords", emptyArray<String>()).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** 全部记录的元数据，按站点、用户名排序。不解密任何东西。 */
    fun list(db: SQLiteDatabase, syncedOnly: Boolean = false): List<Item> {
        val out = ArrayList<Item>()
        db.rawQuery(
            "SELECT guid,origin,username,form_origin,http_realm,time_created,time_last_used,time_changed,times_used " +
                "FROM passwords " + (if (syncedOnly) "WHERE sync_status=$SYNC_DONE " else "") +
                "ORDER BY origin COLLATE NOCASE, username COLLATE NOCASE",
            emptyArray<String>(),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Item(
                        guid = c.getString(0), origin = c.getString(1), username = c.getString(2),
                        formOrigin = if (c.isNull(3)) null else c.getString(3),
                        httpRealm = if (c.isNull(4)) null else c.getString(4),
                        timeCreated = c.getLong(5), timeLastUsed = c.getLong(6),
                        timePasswordChanged = c.getLong(7), timesUsed = c.getInt(8),
                    ),
                )
            }
        }
        return out
    }

    /** 解开单条记录（含密码）。解不开（密钥丢失 / 数据损坏）返回 null。旧套件的会顺手换成当前套件。 */
    fun reveal(context: Context, db: SQLiteDatabase, guid: String): PasswordEntry? {
        return readFull(context, db, "WHERE guid=?", arrayOf(guid), upgrade = true).firstOrNull()
    }

    private fun readFull(context: Context, db: SQLiteDatabase, where: String, args: Array<String>, upgrade: Boolean): List<PasswordEntry> {
        val out = ArrayList<PasswordEntry>()
        val upgrades = ArrayList<Pair<String, String>>()
        db.rawQuery(
            "SELECT guid,origin,form_origin,http_realm,username,secret,username_field,password_field," +
                "time_created,time_last_used,time_changed,times_used FROM passwords $where",
            args,
        ).use { c ->
            while (c.moveToNext()) {
                val guid = c.getString(0)
                val raw = c.getString(5)
                val (password, note) = openSecret(context, guid, raw) ?: continue
                if (upgrade && SecureStore.needsUpgrade(raw)) upgrades.add(guid to sealSecret(context, guid, password, note).orEmpty())
                out.add(
                    PasswordEntry(
                        guid = guid, origin = c.getString(1),
                        formOrigin = if (c.isNull(2)) null else c.getString(2),
                        httpRealm = if (c.isNull(3)) null else c.getString(3),
                        username = c.getString(4), password = password, note = note,
                        usernameField = c.getString(6), passwordField = c.getString(7),
                        timeCreated = c.getLong(8), timeLastUsed = c.getLong(9),
                        timePasswordChanged = c.getLong(10), timesUsed = c.getInt(11),
                    ),
                )
            }
        }
        for ((guid, sealed) in upgrades) {
            if (sealed.isNotEmpty()) db.execSQL("UPDATE passwords SET secret=? WHERE guid=?", arrayOf<Any?>(sealed, guid))
        }
        return out
    }

    // ------------------------------------------------------------------ 写

    /**
     * 导入一批记录（CSV 导入走这里）。单事务：任何一条加密失败就整批回滚，不会留下写了一半的状态。
     * 匹配规则：同一来源 + 同一用户名（HTTP 认证再加 realm）视为同一条凭据——
     *   密码相同 → 不处理；密码不同 → 导入的修改时间不早于本地（CSV 通常没有时间，按「较新」算）就覆盖，否则保留本地。
     */
    fun importEntries(context: Context, db: SQLiteDatabase, entries: List<PasswordEntry>, now: Long = System.currentTimeMillis()): Stats =
        merge(context, db, entries, now, remote = false)

    /** 把 Firefox 同步下来的记录并进本地：规则同 [importEntries]，但以修改时间为准（严格较新才覆盖），且合并结果标记为已同步。 */
    fun mergeRemote(context: Context, db: SQLiteDatabase, entries: List<PasswordEntry>, now: Long = System.currentTimeMillis()): Stats =
        merge(context, db, entries, now, remote = true)

    private fun merge(context: Context, db: SQLiteDatabase, entries: List<PasswordEntry>, now: Long, remote: Boolean): Stats {
        var added = 0
        var updated = 0
        var unchanged = 0
        db.beginTransaction()
        try {
            for (e in entries) {
                val existing = find(db, e)
                if (existing == null) {
                    val guid = e.guid.takeIf { it.isNotEmpty() && !guidExists(db, it) } ?: newGuid()
                    val created = if (e.timeCreated > 0) e.timeCreated else now
                    val changed = if (e.timePasswordChanged > 0) e.timePasswordChanged else created
                    insert(context, db, e.copy(guid = guid, timeCreated = created, timePasswordChanged = changed), if (remote) SYNC_DONE else SYNC_PENDING)
                    db.execSQL("DELETE FROM passwords_deleted WHERE guid=?", arrayOf<Any?>(guid))
                    added++
                    continue
                }
                val cur = openSecret(context, existing.guid, existing.secret)
                if (cur != null && cur.first == e.password) {
                    // 密码相同：无需处理
                    unchanged++
                    continue
                }
                val newer = if (remote) e.timePasswordChanged > existing.timeChanged
                else e.timePasswordChanged == 0L || e.timePasswordChanged >= existing.timeChanged
                if (!newer && cur != null) { unchanged++; continue }
                val changed = if (e.timePasswordChanged > 0) e.timePasswordChanged else now
                val note = e.note.ifEmpty { cur?.second.orEmpty() }
                val sealed = sealSecret(context, existing.guid, e.password, note) ?: throw IllegalStateException("加密失败")
                db.execSQL(
                    "UPDATE passwords SET secret=?, time_changed=?, time_last_used=MAX(time_last_used,?), " +
                        "times_used=MAX(times_used,?), sync_status=? WHERE guid=?",
                    arrayOf<Any?>(sealed, changed, e.timeLastUsed, e.timesUsed, if (remote) SYNC_DONE else SYNC_PENDING, existing.guid),
                )
                updated++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        version++
        return Stats(added, updated, unchanged, 0)
    }

    private class Existing(val guid: String, val secret: String, val timeChanged: Long)

    private fun find(db: SQLiteDatabase, e: PasswordEntry): Existing? {
        // 同 guid 且是同一站点 / 用户名：优先认 guid（Firefox 同步下来的更新）
        if (e.guid.isNotEmpty()) {
            db.rawQuery(
                "SELECT guid,secret,time_changed FROM passwords WHERE guid=? AND origin=? AND username=?",
                arrayOf(e.guid, e.origin, e.username),
            ).use { if (it.moveToFirst()) return Existing(it.getString(0), it.getString(1), it.getLong(2)) }
        }
        db.rawQuery(
            "SELECT guid,secret,time_changed FROM passwords WHERE origin=? AND username=? AND COALESCE(http_realm,'')=? LIMIT 1",
            arrayOf(e.origin, e.username, e.httpRealm.orEmpty()),
        ).use { if (it.moveToFirst()) return Existing(it.getString(0), it.getString(1), it.getLong(2)) }
        return null
    }

    private fun guidExists(db: SQLiteDatabase, guid: String): Boolean =
        db.rawQuery("SELECT 1 FROM passwords WHERE guid=?", arrayOf(guid)).use { it.moveToFirst() }

    private fun insert(context: Context, db: SQLiteDatabase, e: PasswordEntry, sync: Int) {
        val sealed = sealSecret(context, e.guid, e.password, e.note) ?: throw IllegalStateException("加密失败")
        db.execSQL(
            "INSERT INTO passwords(guid,origin,form_origin,http_realm,username,secret,username_field,password_field," +
                "time_created,time_last_used,time_changed,times_used,sync_status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                e.guid, e.origin, e.formOrigin, e.httpRealm, e.username, sealed, e.usernameField, e.passwordField,
                e.timeCreated, e.timeLastUsed, e.timePasswordChanged, e.timesUsed, sync,
            ),
        )
    }

    fun delete(db: SQLiteDatabase, guid: String, now: Long = System.currentTimeMillis()) {
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO passwords_deleted(guid,time) SELECT guid,? FROM passwords WHERE guid=?", arrayOf<Any?>(now, guid))
            db.execSQL("DELETE FROM passwords WHERE guid=?", arrayOf<Any?>(guid))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        version++
    }

    fun clear(db: SQLiteDatabase, now: Long = System.currentTimeMillis()) {
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO passwords_deleted(guid,time) SELECT guid,? FROM passwords", arrayOf<Any?>(now))
            db.execSQL("DELETE FROM passwords")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        version++
    }

    // ------------------------------------------------------------------ Firefox 同步数据接口（无同步逻辑）

    /** 需要上传的记录（新增 / 改过）。解不开的行跳过。 */
    fun pendingUpload(context: Context, db: SQLiteDatabase): List<PasswordEntry> =
        readFull(context, db, "WHERE sync_status=$SYNC_PENDING", emptyArray(), upgrade = false)

    /** 上传成功后标记。 */
    fun markSynced(db: SQLiteDatabase, guids: Collection<String>) {
        db.beginTransaction()
        try {
            for (g in guids) db.execSQL("UPDATE passwords SET sync_status=$SYNC_DONE WHERE guid=?", arrayOf<Any?>(g))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 已与服务器一致的记录 id（用来识别「服务器上已被删除」的记录）。 */
    fun syncedGuids(db: SQLiteDatabase): Set<String> {
        val out = HashSet<String>()
        db.rawQuery("SELECT guid FROM passwords WHERE sync_status=$SYNC_DONE", emptyArray<String>()).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    /**
     * 把记录 id 换成 Firefox 同步库分配的 id（Rust 库新增登录时自己生成 id，传不进去）。
     * 密文的 AAD 绑定了记录 id，所以必须解开再按新 id 重新封装。目标 id 已被占用（同一条凭据已存在）时，旧行直接丢弃，不留墓碑。
     * 成功（或旧行已不存在）返回 true；解不开等失败返回 false，调用方保持原状、下次再试。
     */
    fun reguid(context: Context, db: SQLiteDatabase, oldGuid: String, newGuid: String): Boolean {
        if (oldGuid == newGuid) return true
        val cur = readFull(context, db, "WHERE guid=?", arrayOf(oldGuid), upgrade = false).firstOrNull()
            ?: return !guidExists(db, oldGuid)
        db.beginTransaction()
        try {
            if (guidExists(db, newGuid)) {
                db.execSQL("DELETE FROM passwords WHERE guid=?", arrayOf<Any?>(oldGuid))
            } else {
                val sealed = sealSecret(context, newGuid, cur.password, cur.note) ?: return false
                db.execSQL("UPDATE passwords SET guid=?, secret=?, sync_status=$SYNC_DONE WHERE guid=?", arrayOf<Any?>(newGuid, sealed, oldGuid))
            }
            db.execSQL("DELETE FROM passwords_deleted WHERE guid=?", arrayOf<Any?>(oldGuid))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        version++
        return true
    }

    /** 退出 Firefox 账号时：全部记录重新变成「待上传」、清空待上传的删除——下一个登录的账号会收到本机现有的密码，而不是沿用上一个账号的同步状态。 */
    fun resetSyncState(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("UPDATE passwords SET sync_status=$SYNC_PENDING")
            db.execSQL("DELETE FROM passwords_deleted")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        version++
    }

    /** 待上传的删除（记录 id）。 */
    fun tombstones(db: SQLiteDatabase): List<String> {
        val out = ArrayList<String>()
        db.rawQuery("SELECT guid FROM passwords_deleted ORDER BY time", emptyArray<String>()).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    fun clearTombstones(db: SQLiteDatabase, guids: Collection<String>) {
        db.beginTransaction()
        try {
            for (g in guids) db.execSQL("DELETE FROM passwords_deleted WHERE guid=?", arrayOf<Any?>(g))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 服务器上被删除的记录：本地同步删除，且不留墓碑（这个删除本来就来自服务器）。 */
    fun applyRemoteDeletes(db: SQLiteDatabase, guids: Collection<String>) {
        db.beginTransaction()
        try {
            for (g in guids) db.execSQL("DELETE FROM passwords WHERE guid=?", arrayOf<Any?>(g))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        version++
    }

    // ------------------------------------------------------------------ 加解密（密码 + 备注）

    private fun sealSecret(context: Context, guid: String, password: String, note: String): String? {
        val json = JSONObject().put("p", password).apply { if (note.isNotEmpty()) put("n", note) }.toString()
        return SecureStore.write(context, json, purposeOf(guid))
    }

    /** @return (密码, 备注)；解不开返回 null。这一列只接受密文：明文（不以 mfe 开头）一律当作损坏，不回退。 */
    private fun openSecret(context: Context, guid: String, raw: String): Pair<String, String>? {
        if (SecureStore.isLegacyPlaintext(raw)) return null
        val json = SecureStore.read(context, raw, purposeOf(guid)) ?: return null
        return runCatching {
            val o = JSONObject(json)
            o.getString("p") to o.optString("n")
        }.getOrNull()
    }
}
