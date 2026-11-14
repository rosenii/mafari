package dev.dsh.miuixbrowser

import android.content.Context
import java.io.File

/**
 * 从带 Gecko 内核的旧版本升级后的一次性清理：删掉旧内核留下的浏览器配置目录、扩展安装包和偏好文件，
 * 释放磁盘空间。只执行一次（用标记位记录），在后台线程里跑，失败不影响启动。
 */
internal object LegacyCleanup {

    private const val FLAG_PREFS = "mafari_legacy"
    private const val FLAG_KEY = "gecko_removed_v1"

    private val LEGACY_PREFS = listOf("mafari_gecko_debug", "mafari_gecko_protect", "mafari_gecko_ext")

    fun runOnce(context: Context) {
        val app = context.applicationContext
        val flags = app.getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE)
        if (flags.getBoolean(FLAG_KEY, false)) return
        Thread {
            runCatching {
                File(app.filesDir, "mozilla").deleteRecursively()
                File(app.cacheDir, "mozilla").deleteRecursively()
                File(app.cacheDir, "gecko_ext_import").deleteRecursively()
                LEGACY_PREFS.forEach { app.deleteSharedPreferences(it) }
            }
            flags.edit().putBoolean(FLAG_KEY, true).apply()
        }.start()
    }
}
