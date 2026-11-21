package dev.dsh.miuixbrowser.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import androidx.compose.runtime.snapshotFlow
import dev.dsh.miuixbrowser.ext.ApiException
import dev.dsh.miuixbrowser.ext.ErrorCodes
import dev.dsh.miuixbrowser.ext.TabBridge
import dev.dsh.miuixbrowser.ext.TabChange
import dev.dsh.miuixbrowser.ext.TabChangeTracker
import dev.dsh.miuixbrowser.ext.TabCreateOptions
import dev.dsh.miuixbrowser.ext.TabEventListener
import dev.dsh.miuixbrowser.ext.TabInfo
import dev.dsh.miuixbrowser.ext.TabUpdateOptions
import dev.dsh.miuixbrowser.ext.WorldEval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * [TabBridge] 的真实实现：把拓展运行时（tabs / scripting …）接到 [BrowserState] 的标签上。
 * 约定见 [TabBridge]；这里只补实现上的取舍。
 *
 * **可见性**：只有 WebView 内核、非无痕的标签对拓展存在（[visibleToExtensions]）；Gecko 标签由 GeckoView 自己管拓展，
 * 无痕标签不装拓展。不可见的标签 [get] 返回 null、[update] / [remove] 当作不存在，拓展没法靠猜 id 探测它们。
 *
 * **主页**：标签正显示浏览器主页（[BrowserTab.url] 为空）时，页面如果还留在 WebView 里（被主页盖住），
 * 对拓展也当作「没有页面」：url / title 回空串、不能执行脚本、不能前进 / 后退 / 刷新。拓展看不到也碰不到被盖住的页面。
 *
 * **事件**：不改 [BrowserTab] / [BrowserState] 去逐处埋点，而是用 [snapshotFlow] 观察标签列表、前台下标和各标签的
 * url / title / loading 这些 Compose 状态，交给 [TabChangeTracker] 做差分。同一帧内的连续变化会合并成最后一次；
 * 第一次观察（构造时）只建立基线，所以接上桥接之前就存在的标签不会收到 onCreated。
 *
 * **已知限制**：[TabInfo.favIconUrl] 恒为 null（标签没有保存页面声明的图标地址，只按域名取图标）；
 * `reload(bypassCache = true)` 的 bypassCache 被忽略（WebView 没有只绕过当前页缓存的接口）；
 * 默认内核是 Gecko 时拓展不能新建标签（新标签会是 Gecko 的，拓展看不到，返回一个拓展不认识的 id 没有意义）。
 *
 * 必须在主线程构造；不用时调用 [close] 停止观察。
 */
class BrowserStateTabBridge(
    private val state: BrowserState,
    /**
     * 隔离世界执行入口（接 `WebViewExtensionHost.executeInIsolatedWorld`）：参数 (extId, tabId, js, done)，
     * 返回 false = 没有可用入口、什么也没执行；null = 不支持隔离世界。
     */
    private val isolatedEval: ((String, Int, String, (String?) -> Unit) -> Boolean)? = null,
) : TabBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = CopyOnWriteArrayList<TabEventListener>()

    private val forwarder = object : TabEventListener {
        override fun onCreated(tab: TabInfo) = listeners.forEach { it.onCreated(tab) }
        override fun onUpdated(tab: TabInfo, change: TabChange) = listeners.forEach { it.onUpdated(tab, change) }
        override fun onMoved(tabId: Int, fromIndex: Int, toIndex: Int) = listeners.forEach { it.onMoved(tabId, fromIndex, toIndex) }
        override fun onRemoved(tabId: Int) = listeners.forEach { it.onRemoved(tabId) }
        override fun onActivated(tabId: Int, previousTabId: Int?) = listeners.forEach { it.onActivated(tabId, previousTabId) }
    }
    private val tracker = TabChangeTracker(forwarder)

    init {
        // Main.immediate：在主线程构造时这里同步跑到第一次收集，基线在构造返回前就建好了
        scope.launch {
            // 标签列表还在分批恢复时不观察：恢复完的第一次才建立基线，恢复出来的标签不算「新建」，插队也不算「移动」
            snapshotFlow { if (state.isRestoring) null else visibleInfos() }.collect { it?.let(tracker::update) }
        }
    }

    /** 停止观察并丢掉监听器（界面销毁时调用）。之后各方法仍可调用，只是不再有事件 */
    fun close() {
        scope.cancel()
        listeners.clear()
    }

    // ------------------------------------------------------------------ 可见性 / 快照（主线程）

    private fun BrowserTab.visibleToExtensions(): Boolean =
        engineKind == EngineKind.WEBVIEW && !incognito && !destroyed

    private fun find(tabId: Int): BrowserTab? =
        state.tabs.firstOrNull { it.id == tabId && it.visibleToExtensions() }

    private fun visibleInfos(): List<TabInfo> {
        val front = state.tabs.getOrNull(state.activeIndex)
        val visible = state.tabs.filter { it.visibleToExtensions() }
        val ids = visible.mapTo(HashSet()) { it.id }
        return visible.mapIndexed { index, t ->
            val onPage = t.url.isNotEmpty()
            TabInfo(
                id = t.id,
                index = index,
                active = t === front,
                url = t.url,
                title = if (onPage) t.title else "",
                favIconUrl = null,
                loading = onPage && t.loading,
                // openerId 指向已关闭的标签时 BrowserState 会改挂到上一层，这里再兜一次：不是可见标签就当没有
                openerTabId = t.openerId.takeIf { it != -1 && it in ids },
            )
        }
    }

    private fun infoOf(tabId: Int): TabInfo? = visibleInfos().firstOrNull { it.id == tabId }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    // ------------------------------------------------------------------ TabBridge

    override suspend fun list(): List<TabInfo> = onMain { visibleInfos() }

    override suspend fun get(tabId: Int): TabInfo? = onMain { infoOf(tabId) }

    override suspend fun create(options: TabCreateOptions): TabInfo = onMain {
        if (!state.newTabUsesWebView()) {
            throw ApiException(ErrorCodes.UNSUPPORTED, "当前默认内核不是 WebView，拓展无法新建标签页")
        }
        val full = ApiException(ErrorCodes.QUOTA, "标签页数量已达上限")
        if (state.tabs.size >= BrowserState.MAX_TABS) throw full
        val opener = options.openerTabId?.let { find(it) }
        val tab = state.openTabForExtension(options.url.orEmpty(), options.active, opener) ?: throw full
        infoOf(tab.id) ?: throw ApiException(ErrorCodes.INTERNAL, "新标签页创建失败")
    }

    override suspend fun update(tabId: Int, options: TabUpdateOptions): TabInfo? = onMain {
        val tab = find(tabId) ?: return@onMain null
        // url 已由 tabs 模块过滤；load 会处理「标签正显示主页」的情况（从主页进入新页面）
        options.url?.let { tab.load(it) }
        if (options.active == true) {
            val i = state.tabs.indexOf(tab)
            if (i >= 0) state.select(i)
        }
        infoOf(tabId)
    }

    override suspend fun move(tabIds: List<Int>, index: Int): List<TabInfo> = onMain {
        if (tabIds.isEmpty()) return@onMain emptyList()
        val selected = tabIds.distinct().map { id -> find(id) ?: throw ApiException(ErrorCodes.BAD_REQUEST, "No tab with id: $id") }
        val active = state.tabs.getOrNull(state.activeIndex)
        val moving = selected.sortedBy { state.tabs.indexOf(it) }
        moving.forEach { state.tabs.remove(it) }
        val target = if (index < 0) state.tabs.size else index.coerceAtMost(state.tabs.size)
        state.tabs.addAll(target, moving)
        active?.let { state.realignActive(it) }
        moving.mapNotNull { infoOf(it.id) }
    }

    override suspend fun duplicate(tabId: Int): TabInfo? = onMain {
        val source = find(tabId) ?: return@onMain null
        if (!state.newTabUsesWebView()) throw ApiException(ErrorCodes.UNSUPPORTED, "当前默认内核不是 WebView，拓展无法复制标签页")
        if (state.tabs.size >= BrowserState.MAX_TABS) throw ApiException(ErrorCodes.QUOTA, "标签页数量已达上限")
        val duplicate = state.openTabForExtension(source.url, true, source) ?: throw ApiException(ErrorCodes.QUOTA, "标签页数量已达上限")
        infoOf(duplicate.id)
    }

    override suspend fun remove(tabIds: List<Int>): List<Int> = onMain {
        val missing = ArrayList<Int>()
        for (id in tabIds) {
            val tab = find(id)
            val i = if (tab != null) state.tabs.indexOf(tab) else -1
            if (i >= 0) state.closeTab(i) else missing += id
        }
        missing
    }

    override suspend fun reload(tabId: Int, bypassCache: Boolean): Boolean = onMain {
        val tab = find(tabId) ?: return@onMain false
        if (tab.url.isNotEmpty()) {
            // BrowserTab.reload() 在加载中是「停止」：先停掉，再来一次才是重新加载（Chrome 里加载中 reload 是重来）
            if (tab.loading) tab.reload()
            tab.reload()
        }
        true
    }

    override suspend fun goBack(tabId: Int): Boolean = onMain {
        val tab = find(tabId) ?: return@onMain false
        tab.stepHistoryForExtension(forward = false)
        true
    }

    override suspend fun goForward(tabId: Int): Boolean = onMain {
        val tab = find(tabId) ?: return@onMain false
        tab.stepHistoryForExtension(forward = true)
        true
    }

    override suspend fun evaluate(tabId: Int, js: String): String? = withContext(Dispatchers.Main.immediate) {
        val tab = find(tabId)?.takeIf { it.url.isNotEmpty() && it.errorText == null } ?: return@withContext null
        // WebView 被销毁 / 页面切走时回调可能永远不来：超时当作「页面不可执行」
        withTimeoutOrNull(EVAL_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                try {
                    tab.evaluateJs(js) { raw -> if (cont.isActive) cont.resume(raw) }
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    override suspend fun evaluateInIsolatedWorld(extId: String, tabId: Int, js: String): WorldEval =
        withContext(Dispatchers.Main.immediate) {
            val run = isolatedEval ?: return@withContext WorldEval.Unavailable
            find(tabId)?.takeIf { it.url.isNotEmpty() && it.errorText == null } ?: return@withContext WorldEval.Unavailable
            var accepted = false
            val raw = withTimeoutOrNull(EVAL_TIMEOUT_MS) {
                suspendCancellableCoroutine<String?> { cont ->
                    accepted = try {
                        run(extId, tabId, js) { r -> if (cont.isActive) cont.resume(r) }
                    } catch (_: Exception) {
                        false
                    }
                    if (!accepted && cont.isActive) cont.resume(null)
                }
            }
            if (accepted) WorldEval.Done(raw) else WorldEval.Unavailable
        }

    override suspend fun captureVisibleTab(format: String, quality: Int): String = withContext(Dispatchers.Main.immediate) {
        val tab = state.tabs.getOrNull(state.activeIndex)?.takeIf { it.visibleToExtensions() && it.url.isNotEmpty() }
            ?: throw ApiException(ErrorCodes.UNSUPPORTED, "当前没有可截图的 WebView 标签")
        val view = tab.webViewOrNull ?: throw ApiException(ErrorCodes.UNSUPPORTED, "当前标签没有 WebView")
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0 || width.toLong() * height.toLong() > MAX_CAPTURE_PIXELS) {
            throw ApiException(ErrorCodes.UNSUPPORTED, "当前 WebView 视口不可截图或尺寸过大")
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val output = ByteArrayOutputStream()
            val mime = if (format == "jpeg") "image/jpeg" else "image/png"
            val encoded = if (format == "jpeg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            if (!bitmap.compress(encoded, quality.coerceIn(0, 100), output)) {
                throw ApiException(ErrorCodes.INTERNAL, "WebView 截图编码失败")
            }
            "data:$mime;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            bitmap.recycle()
        }
    }

    override fun addListener(listener: TabEventListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: TabEventListener) {
        listeners.remove(listener)
    }

    private companion object {
        const val EVAL_TIMEOUT_MS = 10_000L
        const val MAX_CAPTURE_PIXELS = 4_000_000L
    }
}
