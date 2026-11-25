/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

/**
 * 标签页桥接：拓展运行时（tabs / scripting / action …）与浏览器标签页之间的唯一边界。
 *
 * 为什么要单独一层：
 * - `ext` 包不依赖 `browser` 包（BrowserState / BrowserTab 带着 Compose 状态与 Android 视图），
 *   这样 tabs / scripting 等模块能在纯 JVM 里测试，换内核也只换实现；
 * - 拓展能看到、能碰到哪些标签，全部在实现里收口（见下面的「可见性」），上层模块不再各自判断。
 *
 * 约定：
 * - **线程**：所有方法都是 suspend，可以从任意线程调用；实现负责切到主线程操作标签。
 *   监听器回调一律在主线程，回调里不要阻塞，也不要再同步调回桥接。
 * - **可见性**：实现只暴露「运行在 WebView 内核上的、非无痕的标签」。无痕标签与 Gecko 标签对拓展不存在：
 *   [list] 不返回，[get] 返回 null，其余按「标签不存在」处理，拓展无法通过猜 id 探测它们。
 * - **缺失的标签**：不抛异常。[get] / [update] / [evaluate] 返回 null，[reload] / [goBack] / [goForward] 返回 false，
 *   [remove] 返回没找到的 id。由 tabs 模块统一翻译成 Chrome 的 `No tab with id: N.`。
 * - **权限不在这里**：桥接不看拓展权限。url / title 要不要回给拓展（`tabs` 权限或 host 权限）、
 *   能不能对某个页面注入脚本，都由上层用 [PermissionChecker] 判断后再调用或裁剪。
 * - **url 不在这里校验**：传进 [create] / [update] 的 url 必须已由 tabs 模块过滤
 *   （只放行 http / https / about:blank 与本拓展自己的页面；`javascript:` / `file:` / `intent:` 等一律拒绝）。
 *   桥接只管把它交给浏览器。
 * - **失败**：能预期的失败（标签数到上限等）抛 [ApiException]，message 可以直接回给拓展。
 */
interface TabBridge {

    /** 当前可见的所有标签，按标签栏顺序（[TabInfo.index] 即下标） */
    suspend fun list(): List<TabInfo>

    suspend fun get(tabId: Int): TabInfo?

    /**
     * 新建标签。[TabCreateOptions.active] 为 true 时切到前台，false 为后台打开。
     * 标签数到上限 → 抛 [ApiException]（[ErrorCodes.QUOTA]）。
     */
    suspend fun create(options: TabCreateOptions): TabInfo

    /** 更新标签（改地址 / 切到前台）。标签不存在返回 null；成功返回更新后的快照（导航才刚开始，url 可能还是旧的） */
    suspend fun update(tabId: Int, options: TabUpdateOptions): TabInfo?

    /** 把可见标签移动到目标下标；返回移动后的快照。 */
    suspend fun move(tabIds: List<Int>, index: Int): List<TabInfo> =
        throw ApiException(ErrorCodes.UNSUPPORTED, "标签排序暂不可用")

    /** 复制标签。默认实现复制当前 URL；宿主可覆盖以保留更多会话状态。 */
    suspend fun duplicate(tabId: Int): TabInfo? {
        val source = get(tabId) ?: return null
        return create(TabCreateOptions(url = source.url.takeIf { it.isNotBlank() }, active = true, openerTabId = source.id))
    }

    /** 关闭标签。存在的会被关闭，返回的是**不存在**的 id（空 = 全部成功） */
    suspend fun remove(tabIds: List<Int>): List<Int>

    suspend fun reload(tabId: Int, bypassCache: Boolean = false): Boolean

    /** 历史里没有可后退的页面时也返回 true（只表示标签存在、请求已发出），与 Chrome 一致 */
    suspend fun goBack(tabId: Int): Boolean

    suspend fun goForward(tabId: Int): Boolean

    /**
     * 在标签的主框架里执行一段 JS（供 scripting.executeScript / insertCSS 等使用）。
     * 返回 `evaluateJavascript` 给的 JSON 文本（页面返回 undefined / null 时是字符串 "null"）；
     * 标签不存在、页面不可执行（主页、错误页）时返回 null。
     * 限制：只能进主框架、只能在页面世界执行，子框架注入要走 document-start 脚本，不走这里。
     * 需要隔离世界请用 [evaluateInIsolatedWorld]。
     */
    suspend fun evaluate(tabId: Int, js: String): String?

    /**
     * 在拓展 [extId] 自己的隔离世界里对标签主框架执行一段 JS：该世界的全局（含 browser / chrome）页面脚本看不到，
     * 也不会和页面的全局变量互相覆盖。语义同 [evaluate]，结果也是 `evaluateJavascript` 风格的 JSON 文本。
     *
     * 不是所有页面都有这个世界：只有该拓展的 ISOLATED content script 已经在这个标签的主框架里跑起来时才有入口。
     * 没有入口返回 [WorldEval.Unavailable]（**什么也没执行**），由调用方决定是否降级到页面世界的 [evaluate]。
     * 默认实现恒为 Unavailable（不支持隔离世界的桥接 / 测试替身）。
     */
    suspend fun evaluateInIsolatedWorld(extId: String, tabId: Int, js: String): WorldEval = WorldEval.Unavailable

    /** Capture the currently visible WebView viewport as a PNG/JPEG data URL. */
    suspend fun captureVisibleTab(format: String = "png", quality: Int = 90): String =
        throw ApiException(ErrorCodes.UNSUPPORTED, "captureVisibleTab 暂不可用")

    /** 同一个监听器重复添加只生效一次 */
    fun addListener(listener: TabEventListener)

    fun removeListener(listener: TabEventListener)

    companion object {
        /** 浏览器只有一个窗口，所有标签的 windowId 都是它 */
        const val WINDOW_ID = 1

        /** 与 Chrome 的 `tabs.TAB_ID_NONE` 一致 */
        const val TAB_ID_NONE = -1
    }
}

/**
 * 拓展眼中的一个标签页快照。只放浏览器里真实存在的信息；
 * Chrome 的 pinned / incognito / audible 等字段这个浏览器没有对应概念，由 tabs 模块序列化时填固定值，不在这里造假。
 */
data class TabInfo(
    /** 即 BrowserTab.id，进程内唯一，关闭后不复用 */
    val id: Int,
    /** 在可见标签里的下标，从 0 开始 */
    val index: Int,
    val active: Boolean,
    val url: String,
    val title: String,
    val favIconUrl: String?,
    /** true → `status: "loading"`，false → `"complete"` */
    val loading: Boolean,
    /** 打开它的标签；没有或已关闭为 null */
    val openerTabId: Int?,
    /** 标签音频是否静音。null = 这个系统 WebView 不支持静音（没有 MUTE_AUDIO），序列化时按「未静音」处理 */
    val muted: Boolean? = null,
) {
    val status: String get() = if (loading) TabChange.STATUS_LOADING else TabChange.STATUS_COMPLETE
}

data class TabCreateOptions(
    /** null / 空 = 空白新标签页 */
    val url: String? = null,
    val active: Boolean = true,
    /** 打开者：关闭新标签后优先回到它。必须是可见标签，否则忽略 */
    val openerTabId: Int? = null,
)

/** 全部字段为 null 的 update 什么也不做，只返回当前快照 */
data class TabUpdateOptions(
    val url: String? = null,
    /** true = 切到前台。false 不会把标签「切到后台」（Chrome 里 active:false 同样是空操作） */
    val active: Boolean? = null,
    /** true / false = 静音 / 取消静音；系统 WebView 不支持静音时是空操作。null = 不改 */
    val muted: Boolean? = null,
    /** true = 加载 [url] 时替换当前历史项（Firefox `loadReplace`）；用 `WebViewCompat.navigate`，系统 WebView 不支持时忽略 */
    val loadReplace: Boolean? = null,
)

/**
 * `tabs.onUpdated` 的 changeInfo：只有真正变化的字段非 null。
 * [status] 用 [STATUS_LOADING] / [STATUS_COMPLETE]。
 */
data class TabChange(
    val status: String? = null,
    val url: String? = null,
    val title: String? = null,
    val favIconUrl: String? = null,
    /** 静音状态变化（只在支持静音的标签上出现） */
    val muted: Boolean? = null,
) {
    fun isEmpty(): Boolean = status == null && url == null && title == null && favIconUrl == null && muted == null

    companion object {
        const val STATUS_LOADING = "loading"
        const val STATUS_COMPLETE = "complete"
    }
}

/** 标签变化的推送。所有回调在主线程；默认空实现，按需覆盖 */
interface TabEventListener {
    fun onCreated(tab: TabInfo) {}

    /** [tab] 是变化后的完整快照，[change] 只含变化的字段 */
    fun onUpdated(tab: TabInfo, change: TabChange) {}

    fun onMoved(tabId: Int, fromIndex: Int, toIndex: Int) {}

    /** 标签已被关闭（快照已不存在，只给 id） */
    fun onRemoved(tabId: Int) {}

    /** [previousTabId] 为 null 表示之前没有前台标签（或它已被关闭） */
    fun onActivated(tabId: Int, previousTabId: Int?) {}
}

/** [TabBridge.evaluateInIsolatedWorld] 的结果 */
sealed interface WorldEval {
    /** 此刻没有可用的隔离世界入口，脚本没有被执行 */
    data object Unavailable : WorldEval

    /** 脚本已发往隔离世界；[raw] 同 [TabBridge.evaluate]（出错 / 帧已销毁 / 超时为 null） */
    class Done(val raw: String?) : WorldEval
}
