package dev.dsh.miuixbrowser.download

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/** 一个可交接下载的第三方下载器 */
data class ExternalApp(val pkg: String, val label: String)

/**
 * 第三方下载器适配：把下载请求通过 ACTION_VIEW 交给 ADM / 1DM / FDM 等外部应用。
 *
 * 目标值 [target]：
 *  - ""         使用内置下载器（默认）
 *  - [CHOOSER]  每次弹出系统选择器
 *  - 包名       固定交给该应用
 *
 * 交接失败（应用被卸载、没有处理该链接的组件等）时返回 false，调用方回退到内置下载器。
 */
object ExternalDownloader {

    const val CHOOSER = "chooser"

    @Volatile
    var target: String = ""

    /** 已知的下载器；只有设备上实际装了的才会出现在设置里（Manifest 的 <queries> 要同步） */
    private val KNOWN = listOf(
        ExternalApp("com.dv.adm.pay", "ADM Pro"),
        ExternalApp("com.dv.adm", "ADM"),
        ExternalApp("idm.internet.download.manager.plus", "1DM+"),
        ExternalApp("idm.internet.download.manager", "1DM"),
        ExternalApp("org.freedownloadmanager.fdm", "Free Download Manager"),
        ExternalApp("com.gopeed", "Gopeed"),
        ExternalApp("com.xunlei.downloadprovider", "迅雷"),
    )

    /** 设备上已安装的已知下载器 */
    fun installed(context: Context): List<ExternalApp> {
        val pm = context.packageManager
        return KNOWN.filter { isInstalled(pm, it.pkg) }
    }

    @Suppress("DEPRECATION")
    private fun isInstalled(pm: PackageManager, pkg: String): Boolean =
        runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess

    /** 目标显示名，用于提示 */
    fun labelOf(value: String): String = when (value) {
        "" -> "内置下载器"
        CHOOSER -> "下载器"
        else -> KNOWN.firstOrNull { it.pkg == value }?.label ?: value
    }

    /** 是否配置了外部下载器，且（固定包名时）仍然安装着 */
    fun active(context: Context): Boolean = when (val t = target) {
        "" -> false
        CHOOSER -> true
        else -> isInstalled(context.packageManager, t)
    }

    /**
     * 把下载交给外部应用。只处理 http/https；成功拉起返回 true。必须在主线程调用。
     * [cookie] 由调用方在后台线程取好（Gecko 的 Cookie 只能异步拿）。
     * 请求头走 Browser.EXTRA_HEADERS（多数下载器都读这个），文件名走常见的 title / filename extra。
     */
    fun hand(
        context: Context,
        url: String,
        userAgent: String?,
        fileName: String,
        mimeType: String?,
        referer: String?,
        cookie: String?,
    ): Boolean {
        if (!active(context)) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false

        val headers = Bundle().apply {
            if (!userAgent.isNullOrBlank()) putString("User-Agent", userAgent)
            if (!referer.isNullOrBlank() && referer.startsWith("http", ignoreCase = true)) putString("Referer", referer)
            if (!cookie.isNullOrBlank()) putString("Cookie", cookie)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (mimeType.isNullOrBlank() || mimeType == "application/octet-stream") data = uri
            else setDataAndType(uri, mimeType)
            putExtra("com.android.browser.headers", headers)
            putExtra("android.intent.extra.TITLE", fileName)
            putExtra("com.android.extra.filename", fileName)
            if (!userAgent.isNullOrBlank()) putExtra("user_agent", userAgent)
            if (!referer.isNullOrBlank()) putExtra("referer", referer)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val t = target
        return try {
            if (t == CHOOSER) {
                context.startActivity(
                    Intent.createChooser(intent, "选择下载器").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                intent.setPackage(t)
                context.startActivity(intent)
            }
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
