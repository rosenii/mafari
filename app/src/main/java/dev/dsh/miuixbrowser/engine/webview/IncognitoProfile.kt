/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.engine.webview

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.dsh.miuixbrowser.util.Diag
import java.util.Collections
import java.util.WeakHashMap

/**
 * WebView 无痕标签用的独立 Profile（androidx.webkit 多 Profile，`MULTI_PROFILE`）。
 *
 * 以前的无痕只是进程级的 `CookieManager.setAcceptCookie(false)` 开关，在切换标签时翻转；
 * 无痕标签和普通标签共用缓存、站点存储、权限，无痕里也存不了 Cookie。
 * 现在无痕标签的 WebView 在创建时就绑到一个单独的 Profile：Cookie / 缓存 / localStorage / IndexedDB /
 * Service Worker / 站点权限全部和默认 Profile 隔开，无痕里登录也能正常保持（和别的浏览器一致）。
 * 最后一个绑定它的 WebView 销毁后，整个 Profile（磁盘数据）被删除。
 *
 * 约束：
 *  - `WebViewCompat.setProfile` 必须在 WebView 首次使用前调用，所以无痕与否在标签创建时就固定（见 BrowserTab 的 webViewOrNull）；
 *  - 不支持 MULTI_PROFILE 的系统 WebView：[bind] 返回 false，无痕沿用旧的进程级 Cookie 开关；
 *  - 所有方法在主线程调用（[cookieManagerFor] / [isBound] 可在任意线程）。
 *  - Cookie 备份读的是默认 Profile 的路径，不受影响；这个 Profile 的数据不进备份。
 */
object IncognitoProfile {

    const val NAME = "mafari_incognito"

    private val lock = Any()
    private val live: MutableSet<WebView> = Collections.newSetFromMap(WeakHashMap())

    /** 本进程已经确认过 Profile 是干净的、并且拿到了它的 CookieManager */
    private var prepared = false

    /** 这个 Profile 自己的 CookieManager；Profile 被删之后置空 */
    @Volatile private var cookies: CookieManager? = null

    private val main = Handler(Looper.getMainLooper())

    fun supported(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    /**
     * 把刚创建、还没被使用的 [webView] 绑到无痕 Profile。成功返回 true；不支持 / 失败返回 false（调用方沿用旧的无痕做法）。
     * 本进程第一次用时先删掉上次崩溃 / 被杀留下的旧数据（此刻没有任何 WebView 在用它）。
     */
    fun bind(webView: WebView): Boolean {
        if (!supported()) return false
        return runCatching {
            synchronized(lock) {
                if (!prepared) {
                    val store = ProfileStore.getInstance()
                    runCatching { store.deleteProfile(NAME); true }
                    cookies = store.getOrCreateProfile(NAME).cookieManager
                    prepared = true
                }
                WebViewCompat.setProfile(webView, NAME)
                live.add(webView)
            }
            true
        }.onFailure { Diag.log("incognito profile: bind failed ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(false)
    }

    /** [webView] 是否绑在无痕 Profile 上。 */
    fun isBound(webView: WebView?): Boolean =
        webView != null && synchronized(lock) { live.contains(webView) }

    /** 新 WebView 要和 [opener] 同一个 Profile（window.open 的临时 WebView 等）：opener 在无痕 Profile 里才绑。 */
    fun bindLike(webView: WebView, opener: WebView?): Boolean = isBound(opener) && bind(webView)

    /**
     * [webView] 所在 Profile 的 CookieManager：无痕 WebView 返回无痕 Profile 的，其余是默认的（`CookieManager.getInstance()`）。
     * 任何要按标签读写 Cookie 的地方都用它，别直接用全局的 CookieManager。
     */
    fun cookieManagerFor(webView: WebView?): CookieManager {
        if (isBound(webView)) cookies?.let { return it }
        return CookieManager.getInstance()
    }

    /** WebView 销毁之后调用。绑在无痕 Profile 的最后一个销毁时，稍后把整个 Profile 删掉。 */
    fun release(webView: WebView) {
        val last = synchronized(lock) { live.remove(webView) && live.isEmpty() }
        if (last) main.postDelayed({ deleteIfUnused(attempt = 0) }, DELETE_DELAY_MS)
    }

    /** 界面出来之后扫一次：上次进程被杀留在磁盘上的无痕数据（本进程还没人用过它才删）。 */
    fun sweepStale() {
        if (!supported()) return
        synchronized(lock) {
            if (prepared || live.isNotEmpty()) return
            runCatching { ProfileStore.getInstance().deleteProfile(NAME); true }
        }
    }

    private fun deleteIfUnused(attempt: Int) {
        val done = synchronized(lock) {
            if (live.isNotEmpty() || !prepared) return
            runCatching { ProfileStore.getInstance().deleteProfile(NAME); true }
                .onFailure { Diag.log("incognito profile: delete failed (attempt $attempt) ${it.message}") }
                .getOrDefault(false)
                .also { if (it) { prepared = false; cookies = null } }
        }
        // WebView 的释放可能晚一拍：删不掉就再试一次
        if (!done && attempt < 1) main.postDelayed({ deleteIfUnused(attempt + 1) }, RETRY_DELAY_MS)
    }

    private const val DELETE_DELAY_MS = 300L
    private const val RETRY_DELAY_MS = 2_000L
}
