package dev.dsh.miuixbrowser.data

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.util.Diag
import android.os.SystemClock
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.SecureRandom

/**
 * 历史库（[AppDatabase]）的密钥格式、打开方式和老库迁移，集中在这一个文件里。
 *
 * 为什么存在：库密钥本身就是 32 字节随机数（hex），不是人输入的口令。把它当普通口令传给 SQLCipher 会触发 PBKDF2
 * （约 500ms，WAL 再开一条连接时还会再来一次），对高熵密钥没有安全收益，只拖慢冷启动。
 * 改用 SQLCipher 原始密钥语法 x'<64位hex>'：直接当 AES-256 密钥，跳过 PBKDF2。加密算法、页大小、HMAC 都不变。
 *
 * 老用户的库是按「口令 + PBKDF2」加密的，首次打开时 rekey 成原始密钥（只发生一次）。数据无损靠这几层：
 *  1. 迁移前先把库合并成单文件（journal_mode=DELETE），再整份备份成 history.db.premigrate。
 *     备份先写到 .premigrate.tmp，长度核对无误后才改名：磁盘上出现的 .premigrate 一定是完整的。
 *  2. 迁移前后逐表核对行数，并对新库做 integrity_check；任何不一致都从备份还原。
 *  3. 迁移中途被杀进程：下次 [open] 开头先 [recoverInterrupted]，新格式打得开就丢弃备份，否则还原备份。
 *  4. 迁移失败抛 [MigrationException]：库文件已经保持原样，调用方绝不能删库，下次启动自动重试。
 *     只有「新旧两种密钥都打不开」（密钥确实丢了）才抛普通异常，由调用方删库重建。
 *
 * 其他地方不要再直接 openOrCreateDatabase(history.db)，一律走 [open]。
 */
internal object HistoryDbKey {

    /** 迁移没做成，但库文件完好（已还原或根本没动过）。调用方不要删库。 */
    class MigrationException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val HEX64 = Regex("[0-9a-fA-F]{64}")
    private const val BACKUP_SUFFIX = ".premigrate"
    private const val BACKUP_TMP_SUFFIX = ".premigrate.tmp"
    private const val RESTORE_TMP_SUFFIX = ".restore.tmp"
    private val SIDE_SUFFIXES = listOf("-wal", "-shm", "-journal")

    /** 新库密钥：随机 32 字节的 hex（64 个字符）。 */
    fun newKeyHex(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** SQLCipher 原始密钥写法：x'<64位hex>'。 */
    private fun raw(hex: String): String = "x'$hex'"

    private fun backupOf(file: File) = File(file.path + BACKUP_SUFFIX)

    /**
     * 打开（必要时创建 / 迁移）加密库。[hex] 必须是 [newKeyHex] 那种 64 位 hex，否则抛 [IllegalArgumentException]。
     * 抛 [MigrationException] = 迁移失败但数据完好；抛其他异常 = 新旧密钥都打不开，由调用方决定是否 [discard] 后重建。
     */
    fun open(file: File, hex: String): SQLiteDatabase {
        require(HEX64.matches(hex)) { tr("数据库密钥格式不对") }
        recoverInterrupted(file, hex)
        if (!file.exists()) return openRaw(file, hex) // 新库：直接是原始密钥
        openRawOrNull(file, hex)?.let { return it }   // 已迁移过：快路径，错密钥在这里很快就失败，不吃 KDF
        migrateLegacy(file, hex)                      // 旧格式：最后再付一次 KDF 的钱
        return openRaw(file, hex)
    }

    /** 删掉库文件、WAL/SHM/日志和所有迁移中间文件（密钥确实丢了、库无法解开时用）。 */
    fun discard(file: File) {
        val names = listOf("") + SIDE_SUFFIXES + listOf(BACKUP_SUFFIX, BACKUP_TMP_SUFFIX, RESTORE_TMP_SUFFIX)
        for (s in names) File(file.path + s).delete()
    }

    private fun openRaw(file: File, hex: String): SQLiteDatabase {
        val db = SQLiteDatabase.openOrCreateDatabase(file, raw(hex), null, null)
        try {
            verify(db)
        } catch (e: Exception) {
            runCatching { db.close() }
            throw e
        }
        return db
    }

    private fun openRawOrNull(file: File, hex: String): SQLiteDatabase? =
        try {
            openRaw(file, hex)
        } catch (e: Exception) {
            null
        }

    /** 真正读一次 sqlite_master：密钥不对会在这里抛「file is not a database」，不会拖到第一次业务查询才暴露。 */
    private fun verify(db: SQLiteDatabase) {
        db.rawQuery("SELECT count(*) FROM sqlite_master", emptyArray<String>()).use { it.moveToFirst() }
    }

    private fun tableCounts(db: SQLiteDatabase): Map<String, Long> {
        val names = ArrayList<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            emptyArray<String>(),
        ).use { c -> while (c.moveToNext()) names += c.getString(0) }
        val out = LinkedHashMap<String, Long>()
        for (n in names) {
            val quoted = "\"" + n.replace("\"", "\"\"") + "\""
            db.rawQuery("SELECT count(*) FROM $quoted", emptyArray<String>()).use { c ->
                if (c.moveToFirst()) out[n] = c.getLong(0)
            }
        }
        return out
    }

    private fun integrityOk(db: SQLiteDatabase): Boolean =
        db.rawQuery("PRAGMA integrity_check", emptyArray<String>()).use { c ->
            c.moveToFirst() && c.getString(0).equals("ok", ignoreCase = true)
        }

    /** 上次迁移被打断（备份文件还在）：新格式能打开就说明已经迁完，丢弃备份；否则从备份还原。 */
    private fun recoverInterrupted(file: File, hex: String) {
        File(file.path + BACKUP_TMP_SUFFIX).delete() // 写了一半的备份无效，主库此时一定还是完整的
        File(file.path + RESTORE_TMP_SUFFIX).delete()
        val backup = backupOf(file)
        if (!backup.exists()) return
        val migrated = file.exists() && openRawOrNull(file, hex)?.also { runCatching { it.close() } } != null
        if (migrated) {
            backup.delete()
            Diag.log("history db key: interrupted migration had completed, backup dropped")
        } else {
            restoreBackup(file)
            Diag.log("history db key: interrupted migration, restored from backup")
        }
    }

    /** 用备份还原主库：先写临时文件再改名，备份在还原完成后才删，中途被杀下次还能再来一遍。 */
    private fun restoreBackup(file: File) {
        val backup = backupOf(file)
        if (!backup.exists()) return
        val tmp = File(file.path + RESTORE_TMP_SUFFIX)
        backup.copyTo(tmp, overwrite = true)
        if (tmp.length() != backup.length()) {
            tmp.delete()
            throw MigrationException(tr("还原备份时长度不一致"))
        }
        for (s in SIDE_SUFFIXES) File(file.path + s).delete()
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw MigrationException(tr("还原备份失败"))
        }
        backup.delete()
    }

    /** 把「口令 + PBKDF2」加密的旧库 rekey 成原始密钥。任何失败都还原并抛 [MigrationException]。 */
    private fun migrateLegacy(file: File, hex: String) {
        val t0 = SystemClock.elapsedRealtime()
        // 新旧两种密钥都打不开：这里抛普通异常（密钥确实丢了），由调用方删库重建
        val old = SQLiteDatabase.openOrCreateDatabase(file, hex, null, null)
        try {
            verify(old)
        } catch (e: Exception) {
            runCatching { old.close() }
            throw e
        }
        val backup = backupOf(file)
        val backupTmp = File(file.path + BACKUP_TMP_SUFFIX)
        try {
            val before = tableCounts(old)
            val oldHealthy = integrityOk(old)
            // rekey 不能在 WAL 下做：切回 DELETE（同时把 WAL 合并进主文件），并确认真的切成功了
            val mode = old.rawQuery("PRAGMA journal_mode=DELETE", emptyArray<String>()).use { c ->
                if (c.moveToFirst()) c.getString(0) else ""
            }
            if (!mode.equals("delete", ignoreCase = true)) throw MigrationException(tr("无法切换日志模式：{0}", mode))
            // 整份备份：先写临时文件，长度核对后才改名成 .premigrate
            file.copyTo(backupTmp, overwrite = true)
            if (backupTmp.length() != file.length()) throw MigrationException(tr("备份长度不一致"))
            if (!backupTmp.renameTo(backup)) throw MigrationException(tr("备份改名失败"))

            // sqlcipher-android 4.x 的 execSQL 拒绝会返回结果集的语句（「Queries can be performed using ... rawQuery methods only」），
            // PRAGMA rekey 就属于这一类：必须走 rawQuery 并读一下游标让语句真正执行
            old.rawQuery("PRAGMA rekey = \"${raw(hex)}\"", emptyArray<String>()).use { it.moveToFirst() }
            old.close()

            val fresh = openRaw(file, hex)
            try {
                val after = tableCounts(fresh)
                if (after != before) throw MigrationException(tr("迁移后行数不一致：{0} -> {1}", before, after))
                if (oldHealthy && !integrityOk(fresh)) throw MigrationException(tr("迁移后完整性检查未通过"))
            } finally {
                runCatching { fresh.close() }
            }
            backup.delete()
            Diag.log("history db key: migrated to raw key in ${SystemClock.elapsedRealtime() - t0}ms")
        } catch (e: Exception) {
            runCatching { old.close() }
            backupTmp.delete()
            // 备份存在 = 已经动过主库，还原；不存在 = 主库根本没动
            runCatching { restoreBackup(file) }
            Diag.log("history db key: migrate failed, data kept: ${e.message}")
            throw if (e is MigrationException) e else MigrationException(e.message ?: tr("迁移失败"), e)
        }
    }
}
