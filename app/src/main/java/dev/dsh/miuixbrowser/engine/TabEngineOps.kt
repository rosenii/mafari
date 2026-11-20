package dev.dsh.miuixbrowser.engine

import dev.dsh.miuixbrowser.engine.gecko.GeckoTabEngine
import android.webkit.WebView

/**
 * 标签页里「与内核无关的基础操作」：导航、查找、滚动位置、标题。
 *
 * 原先 [BrowserTab] 里每个操作都写 `if (isGecko) gecko?.xxx else webView.xxx`，换内核 / 加内核要改几十处。
 * 现在分支只在这里：[WebViewTabOps] 和 [GeckoTabOps] 各一份，[BrowserTab] 按 [EngineKind] 选一份后统一调用。
 * 两份实现的行为与原分支逐条一致（包括哪些调用包了 runCatching、哪些没有）。
 *
 * 只收「两个内核都有、语义相同」的操作。截图、UA、脚本注入、权限、阅读模式等差异大的能力不在这里，
 * 它们仍留在 [BrowserTab] 里按内核分支，后续再按能力拆。
 */
internal interface TabEngineOps {
    val canGoBack: Boolean
    val canGoForward: Boolean

    /** 引擎当前地址；没有则空串 */
    fun currentUrl(): String

    /** 会话历史里当前页的下标（-1 = 未知 / 没有历史） */
    fun historyIndex(): Int

    /** 页面滚动位置（像素） */
    val scrollY: Int

    /** 距页面底部还剩多少像素（0 = 已到底）；引擎给不出来时为 -1 */
    val scrollRemaining: Int

    /** 引擎当前的页面标题；没有则空串 */
    fun pageTitle(): String

    fun stop()
    fun reload()
    fun load(target: String)
    fun goBack()
    fun goForward()

    /** 页内查找；[text] 为空等于清除高亮 */
    fun findAll(text: String)
    fun findNext(forward: Boolean)
    fun clearFind()
}

/** 系统 WebView 内核。[extraHeaders] 每次加载时现取（随设置变化）。 */
internal class WebViewTabOps(
    private val webView: WebView,
    private val extraHeaders: () -> Map<String, String>,
) : TabEngineOps {

    override val canGoBack: Boolean get() = runCatching { webView.canGoBack() }.getOrDefault(false)
    override val canGoForward: Boolean get() = runCatching { webView.canGoForward() }.getOrDefault(false)

    override fun currentUrl(): String = runCatching { webView.url }.getOrNull().orEmpty()

    override fun historyIndex(): Int =
        runCatching { webView.copyBackForwardList().currentIndex }.getOrDefault(-1)

    override val scrollY: Int get() = webView.scrollY

    // contentHeight 是 CSS 像素，乘以页面缩放（含屏幕密度）才是设备像素；getScale 虽已弃用但仍是唯一可用的来源
    @Suppress("DEPRECATION")
    override val scrollRemaining: Int
        get() = runCatching {
            val total = (webView.contentHeight * webView.scale).toInt()
            if (total <= 0) -1 else (total - webView.scrollY - webView.height).coerceAtLeast(0)
        }.getOrDefault(-1)

    override fun pageTitle(): String = webView.title.orEmpty()

    override fun stop() {
        runCatching { webView.stopLoading() }
    }

    override fun reload() = webView.reload()

    override fun load(target: String) {
        val headers = extraHeaders()
        if (headers.isEmpty()) webView.loadUrl(target) else webView.loadUrl(target, headers)
    }

    override fun goBack() = webView.goBack()
    override fun goForward() = webView.goForward()

    override fun findAll(text: String) {
        if (text.isBlank()) webView.clearMatches() else webView.findAllAsync(text)
    }

    override fun findNext(forward: Boolean) {
        runCatching { webView.findNext(forward) }
    }

    override fun clearFind() {
        runCatching { webView.clearMatches() }
    }
}

/**
 * Gecko 内核。引擎是延迟创建、还可能被回收的，所以不直接持有 [GeckoTabEngine]，而是每次通过 [current] 现取
 * （读不触发创建）；只有 [load] 通过 [ensure] 在需要时才真正建出引擎。
 */
internal class GeckoTabOps(
    private val current: () -> GeckoTabEngine?,
    private val ensure: () -> GeckoTabEngine?,
) : TabEngineOps {

    override val canGoBack: Boolean get() = current()?.canGoBack ?: false
    override val canGoForward: Boolean get() = current()?.canGoForward ?: false

    override fun currentUrl(): String = current()?.currentUrl().orEmpty()

    override fun historyIndex(): Int = current()?.historyIndex ?: -1

    override val scrollY: Int get() = current()?.scrollY ?: 0

    override val scrollRemaining: Int get() = current()?.scrollRemaining ?: -1

    override fun pageTitle(): String = current()?.pageTitle.orEmpty()

    override fun stop() {
        current()?.stop()
    }

    override fun reload() {
        current()?.reload()
    }

    override fun load(target: String) {
        ensure()?.loadUri(target)
    }

    override fun goBack() {
        current()?.goBack()
    }

    override fun goForward() {
        current()?.goForward()
    }

    override fun findAll(text: String) {
        current()?.let { if (text.isBlank()) it.clearFind() else it.find(text) }
    }

    override fun findNext(forward: Boolean) {
        current()?.findNext(forward)
    }

    override fun clearFind() {
        current()?.clearFind()
    }
}
