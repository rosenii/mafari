package dev.dsh.miuixbrowser

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.StorageController
import kotlin.coroutines.resume

/**
 * Cookie 管理的执行层（两个内核共用一套界面，各自走官方 API）：
 *
 * - WebView：[CookieManager]（removeAllCookies / removeSessionCookies / setCookie / flush）+ [WebStorage]
 * - Gecko：[StorageController.clearData] / clearDataFromBaseDomain；接受策略见 [GeckoTabEngine.applyCookieMode]；
 *   没有专用 Java API 的项（Cookie 弹窗处理、保留期上限、SameSite）才走 about:config（[GeckoPrefs]）
 *
 * 接受策略与「启动时清理」存在 [AppSettings]；下面的 Gecko 专属项存在自己的偏好文件。
 */
object CookieControl {

    /** 自动处理 Cookie 弹窗：0 关闭 / 1 自动拒绝 / 2 优先拒绝，不行再接受（Gecko） */
    var banner by mutableStateOf(0)
        private set

    /** Cookie 最长保留期：0 默认 / 1 30 天 / 2 7 天 / 3 1 天（Gecko） */
    var maxAge by mutableStateOf(0)
        private set

    /** SameSite 默认 Lax：未声明 SameSite 的 Cookie 不随跨站请求发送（Gecko） */
    var sameSiteLax by mutableStateOf(false)
        private set

    private var prefs: SharedPreferences? = null

    /** 保留期档位对应的秒数；0 档 = 不限制（Gecko 默认上限 400 天） */
    private val MAX_AGE_SECONDS = intArrayOf(34_560_000, 2_592_000, 604_800, 86_400)

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("mafari_cookies", Context.MODE_PRIVATE)
        prefs = p
        banner = p.getInt("banner", 0).coerceIn(0, 2)
        maxAge = p.getInt("max_age", 0).coerceIn(0, 3)
        sameSiteLax = p.getBoolean("samesite_lax", false)
    }

    fun updateBanner(context: Context, v: Int) {
        load(context); banner = v.coerceIn(0, 2)
        prefs?.edit()?.putInt("banner", banner)?.apply(); pushGecko(context)
    }

    fun updateMaxAge(context: Context, v: Int) {
        load(context); maxAge = v.coerceIn(0, 3)
        prefs?.edit()?.putInt("max_age", maxAge)?.apply(); pushGecko(context)
    }

    fun updateSameSiteLax(context: Context, v: Boolean) {
        load(context); sameSiteLax = v
        prefs?.edit()?.putBoolean("samesite_lax", v)?.apply(); pushGecko(context)
    }

    /** 记录 Gecko 偏好（Gecko 未启动时只记录，运行时创建后由 [GeckoPrefs.flush] 写入） */
    fun pushGecko(context: Context) {
        if (!EngineSupport.GECKO_BUNDLED) return // 无内核版：没有 Gecko，不碰 GeckoPrefs
        load(context)
        GeckoPrefs.set("cookiebanners.service.mode", banner)
        GeckoPrefs.set("cookiebanners.service.mode.privateBrowsing", banner)
        GeckoPrefs.set("cookiebanners.service.enableGlobalRules", banner > 0)
        GeckoPrefs.set("cookiebanners.service.enableGlobalRules.subFrames", banner > 0)
        GeckoPrefs.set("network.cookie.maxageCap", MAX_AGE_SECONDS[maxAge])
        GeckoPrefs.set("network.cookie.sameSite.laxByDefault", sameSiteLax)
        GeckoPrefs.set("network.cookie.sameSite.noneRequiresSecure", sameSiteLax)
    }

    // ------------------------------------------------------------------ 启动时清理

    @Volatile
    private var pendingGeckoClear = 0

    /** MainActivity.onCreate 调用：WebView 立刻清；Gecko 等运行时创建后再清 */
    fun runOnColdStart(context: Context, settings: AppSettings) {
        val mode = settings.cookieClearOnLaunch
        if (mode == 0) return
        if (settings.geckoMode) {
            pendingGeckoClear = mode
            return
        }
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            if (mode == 2) WebStorage.getInstance().deleteAllData()
        }
    }

    /** [GeckoRuntimeHolder.get] 刚创建运行时后调用 */
    fun runPendingGeckoColdClear() {
        val mode = pendingGeckoClear
        if (mode == 0) return
        pendingGeckoClear = 0
        val rt = GeckoRuntimeHolder.peek() ?: return
        runCatching {
            var flags = StorageController.ClearFlags.COOKIES
            if (mode == 2) flags = flags or StorageController.ClearFlags.DOM_STORAGES
            rt.storageController.clearData(flags)
        }
    }

    // ------------------------------------------------------------------ 手动清除（主线程协程调用）

    /** 网址里的站点（仅 http / https） */
    fun siteOf(url: String): String? {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (u.scheme != "http" && u.scheme != "https") return null
        return u.host?.takeIf { it.isNotBlank() }
    }

    /** 当前网站在 WebView 里的 Cookie 数量；Gecko 没有读取 Cookie 的 API，返回 null */
    fun siteCookieCount(url: String): Int? = runCatching {
        CookieManager.getInstance().getCookie(url).orEmpty().split(";").count { it.isNotBlank() }
    }.getOrNull()

    suspend fun clearCookies(context: Context, gecko: Boolean): Boolean =
        if (gecko) geckoClear(context, StorageController.ClearFlags.COOKIES) else webViewRemoveAll()

    /** 仅 WebView：会话 Cookie（没有过期时间的那些） */
    suspend fun clearSessionCookies(): Boolean = suspendCancellableCoroutine { c ->
        runCatching {
            CookieManager.getInstance().removeSessionCookies { ok ->
                CookieManager.getInstance().flush()
                if (c.isActive) c.resume(ok)
            }
        }.onFailure { if (c.isActive) c.resume(false) }
    }

    /** 网站存储：localStorage / IndexedDB / 缓存存储等 */
    suspend fun clearSiteStorage(context: Context, gecko: Boolean): Boolean =
        if (gecko) {
            geckoClear(context, StorageController.ClearFlags.DOM_STORAGES)
        } else {
            runCatching { WebStorage.getInstance().deleteAllData() }.isSuccess
        }

    suspend fun clearEverything(context: Context, gecko: Boolean): Boolean =
        if (gecko) {
            geckoClear(context, StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES)
        } else {
            val ok = webViewRemoveAll()
            runCatching { WebStorage.getInstance().deleteAllData() }
            ok
        }

    /** 清除某个网址所在站点的 Cookie：Gecko 按基础域名清；WebView 没有按站点删除的 API，逐个让 Cookie 过期 */
    suspend fun clearSite(context: Context, url: String, gecko: Boolean): Boolean {
        val host = siteOf(url) ?: return false
        return if (gecko) {
            runCatching {
                GeckoRuntimeHolder.get(context).storageController
                    .clearDataFromBaseDomain(baseDomain(host), StorageController.ClearFlags.COOKIES)
                    .await()
            }.isSuccess
        } else {
            expireSiteCookies(url, host)
            true
        }
    }

    private suspend fun webViewRemoveAll(): Boolean = suspendCancellableCoroutine { c ->
        runCatching {
            CookieManager.getInstance().removeAllCookies { ok ->
                CookieManager.getInstance().flush()
                if (c.isActive) c.resume(ok)
            }
        }.onFailure { if (c.isActive) c.resume(false) }
    }

    private suspend fun geckoClear(context: Context, flags: Long): Boolean = runCatching {
        GeckoRuntimeHolder.get(context).storageController.clearData(flags).await()
    }.isSuccess

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

    /** 粗略取 eTLD+1：处理 co.uk / com.cn 这类两段后缀；IP 与单段主机原样返回 */
    private fun baseDomain(host: String): String {
        val parts = host.split('.')
        if (parts.size <= 2 || host.all { it.isDigit() || it == '.' || it == ':' }) return host
        val second = parts[parts.size - 2]
        val twoLevel = parts.last().length == 2 && second in setOf("com", "net", "org", "edu", "gov", "co", "ac")
        return parts.takeLast(if (twoLevel) 3 else 2).joinToString(".")
    }
}
