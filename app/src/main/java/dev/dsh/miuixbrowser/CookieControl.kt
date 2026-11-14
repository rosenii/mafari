package dev.dsh.miuixbrowser

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Cookie 管理的执行层：走 WebView 官方 API——
 * [CookieManager]（removeAllCookies / removeSessionCookies / setCookie / flush）+ [WebStorage]。
 *
 * 接受策略与「启动时清理」存在 [AppSettings]。
 */
object CookieControl {

    /** MainActivity.onCreate 调用：按设置在冷启动时清理 Cookie（及网站存储） */
    fun runOnColdStart(context: Context, settings: AppSettings) {
        val mode = settings.cookieClearOnLaunch
        if (mode == 0) return
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            if (mode == 2) WebStorage.getInstance().deleteAllData()
        }
    }

    // ------------------------------------------------------------------ 手动清除（主线程协程调用）

    /** 网址里的站点（仅 http / https） */
    fun siteOf(url: String): String? {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (u.scheme != "http" && u.scheme != "https") return null
        return u.host?.takeIf { it.isNotBlank() }
    }

    /** 当前网站在 WebView 里的 Cookie 数量 */
    fun siteCookieCount(url: String): Int? = runCatching {
        CookieManager.getInstance().getCookie(url).orEmpty().split(";").count { it.isNotBlank() }
    }.getOrNull()

    suspend fun clearCookies(): Boolean = webViewRemoveAll()

    /** 会话 Cookie（没有过期时间的那些） */
    suspend fun clearSessionCookies(): Boolean = suspendCancellableCoroutine { c ->
        runCatching {
            CookieManager.getInstance().removeSessionCookies { ok ->
                CookieManager.getInstance().flush()
                if (c.isActive) c.resume(ok)
            }
        }.onFailure { if (c.isActive) c.resume(false) }
    }

    /** 网站存储：localStorage / IndexedDB / 缓存存储等 */
    fun clearSiteStorage(): Boolean =
        runCatching { WebStorage.getInstance().deleteAllData() }.isSuccess

    suspend fun clearEverything(): Boolean {
        val ok = webViewRemoveAll()
        runCatching { WebStorage.getInstance().deleteAllData() }
        return ok
    }

    /** 清除某个网址所在站点的 Cookie：WebView 没有按站点删除的 API，逐个让 Cookie 过期 */
    fun clearSite(url: String): Boolean {
        val host = siteOf(url) ?: return false
        expireSiteCookies(url, host)
        return true
    }

    private suspend fun webViewRemoveAll(): Boolean = suspendCancellableCoroutine { c ->
        runCatching {
            CookieManager.getInstance().removeAllCookies { ok ->
                CookieManager.getInstance().flush()
                if (c.isActive) c.resume(ok)
            }
        }.onFailure { if (c.isActive) c.resume(false) }
    }

    private fun expireSiteCookies(url: String, host: String) {
        val cm = CookieManager.getInstance()
        val names = cm.getCookie(url).orEmpty().split(";")
            .map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return
        // 候选 Domain：本机 + 各级父域（至少两级）；候选 Path：根 + URL 路径的各级前缀
        val labels = host.split('.')
        val domains = buildList {
            add(null)
            add(host)
            for (i in 0..(labels.size - 2).coerceAtLeast(0)) add("." + labels.drop(i).joinToString("."))
        }.distinct()
        val segs = Uri.parse(url).path.orEmpty().split('/').filter { it.isNotEmpty() }
        val paths = buildList {
            add("/")
            var acc = ""
            segs.forEach { acc += "/$it"; add(acc) }
        }
        val past = "Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
        for (n in names) for (d in domains) for (p in paths) {
            cm.setCookie(url, "$n=; $past; Path=$p" + (d?.let { "; Domain=$it" } ?: ""))
        }
        cm.flush()
    }
}
