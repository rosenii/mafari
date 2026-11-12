package dev.dsh.miuixbrowser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/*
 * 默认加密口令 + 自动推送同步。
 *
 *  - 默认口令经 [SecureStore] 加密只存本机（不进备份文件，也不进系统自动备份）；设置后，导出 / 上传 / 推送不再逐次询问口令。
 *    恢复仍需要口令（备份本身不含口令）。
 *  - 自动推送：按间隔（每天 / 每 3 天 / 每周）在启动时把加密备份推送到「当前」的 WebDAV 服务器 / Git 仓库，
 *    以及列表里开启了「一并同步」的其他服务器 / 仓库。
 *    需要先设好默认口令与至少一个目标；有任何一个失败就不记时间，下次启动整体重试。
 */
object BackupAuto {
    private const val PREFS = "mafari_backup_auto"
    private const val K_PASSWORD = "password"
    private const val K_MODE = "auto_mode"
    private const val K_LAST = "last_auto"

    val INTERVAL_LABELS = listOf("手动", "每天", "每 3 天", "每周")
    private val INTERVAL_DAYS = intArrayOf(0, 1, 3, 7)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPassword(context: Context): Boolean = password(context) != null

    /** 默认口令；未设置或无法解密返回 null。 */
    fun password(context: Context): String? {
        val raw = prefs(context).getString(K_PASSWORD, "").orEmpty()
        if (raw.isEmpty()) return null
        return SecureStore.read(context.applicationContext, raw, SecureStore.PURPOSE_BACKUP_PASSWORD)?.takeIf { it.isNotEmpty() }
    }

    fun setPassword(context: Context, plain: String) {
        val app = context.applicationContext
        val sealed = SecureStore.write(app, plain, SecureStore.PURPOSE_BACKUP_PASSWORD)
            ?: throw IllegalStateException("无法用本机密钥加密保存口令")
        if (!prefs(app).edit().putString(K_PASSWORD, sealed).commit()) throw IllegalStateException("保存失败")
    }

    /** 清除默认口令，同时关闭自动推送（没有口令无法无人值守加密）。 */
    fun clearPassword(context: Context) {
        prefs(context).edit().remove(K_PASSWORD).putInt(K_MODE, 0).apply()
    }

    fun mode(context: Context): Int = prefs(context).getInt(K_MODE, 0).coerceIn(0, 3)

    fun setMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(K_MODE, mode.coerceIn(0, 3)).apply()
    }

    /** 启动时调用：到期则加密一次并推送到所有已配置的目标。 */
    suspend fun runIfDue(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val days = INTERVAL_DAYS[mode(app)]
        if (days == 0) return@withContext
        val now = System.currentTimeMillis()
        if (now - prefs(app).getLong(K_LAST, 0L) < days * 86_400_000L) return@withContext
        val password = password(app) ?: return@withContext
        val davActive = WebDavStore.activeId(app)
        val dav = WebDavStore.list(app).filter { it.id == davActive || it.syncAlso }.map { it.config }
        val gitActive = GitStore.activeId(app)
        val git = GitStore.list(app).filter { it.id == gitActive || it.syncAlso }.map { it.config }
        if (dav.isEmpty() && git.isEmpty()) return@withContext

        val chars = password.toCharArray()
        val buffer = ByteArrayOutputStream()
        try {
            BackupManager.export(app, buffer, chars)
        } finally {
            chars.fill('\u0000')
        }
        val bytes = buffer.toByteArray()
        var ok = true
        for (cfg in dav) {
            if (runCatching { WebDavClient(cfg).upload(bytes) }.isSuccess) WebDavStore.markUploaded(app) else ok = false
        }
        for (cfg in git) {
            if (runCatching { GitSyncClient(app, cfg).upload(bytes) }.isSuccess) GitStore.markPushed(app) else ok = false
        }
        if (ok) prefs(app).edit().putLong(K_LAST, now).apply()
    }
}
