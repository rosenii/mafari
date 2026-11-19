package dev.dsh.miuixbrowser.ext

/**
 * WebView 适配器的 API 支持矩阵（Gecko 适配器委托 GeckoView，不看这张表）。
 * 实现一个 API 就把它改成 FULL / PARTIAL；未登记的权限一律按 NONE 处理。
 */
object ApiSupport {
    private val webView: Map<String, SupportLevel> = mapOf(
        "storage" to SupportLevel.PARTIAL, // local/session API 完整；sync 为独立持久化本地存储，尚未接入账号云同步
        "runtime" to SupportLevel.PARTIAL, // 消息与 Port 已接入；Port 支持同拓展多个已就绪页面上下文；不支持跨拓展/外部连接
        "permissions" to SupportLevel.PARTIAL, // getAll/contains/request/remove 与用户确认、权限事件已接入；match pattern 子集覆盖仍有限
        "tabs" to SupportLevel.PARTIAL, // 基础标签操作、sendMessage frameId 路由、move、duplicate 与事件；宿主当前仅注册主框架 endpoint，单窗口/缺截图等能力
        "activeTab" to SupportLevel.PARTIAL, // 点击拓展快捷入口授予当前标签
        "scripting" to SupportLevel.PARTIAL, // executeScript 与动态注册系列已接入；无真正隔离世界、frameId 仅支持 0
        "action" to SupportLevel.PARTIAL, // action / browserAction 共用原生状态并同步到拓展快捷菜单
        "browserAction" to SupportLevel.PARTIAL,
        "contextMenus" to SupportLevel.PARTIAL,
        "menus" to SupportLevel.PARTIAL,
        "alarms" to SupportLevel.PARTIAL, // 进程死亡期间不能准点执行，启动后补发
        "declarativeNetRequest" to SupportLevel.PLANNED,
        "unlimitedStorage" to SupportLevel.FULL,
        "notifications" to SupportLevel.PLANNED,
        "webNavigation" to SupportLevel.PARTIAL, // 主框架 before/commit/complete/error 与 SPA 近似事件；无子框架 / documentId，DOMContentLoaded 受限
        "webRequest" to SupportLevel.PARTIAL, // onBeforeRequest 观察事件；blocking 与 response 生命周期暂不支持
        "webRequestBlocking" to SupportLevel.NONE, // WebView 后台页异步模型不能安全实现同步阻断
        "cookies" to SupportLevel.PLANNED,
        "clipboardWrite" to SupportLevel.PLANNED,
        "clipboardRead" to SupportLevel.PLANNED,
    )

    fun forWebView(permission: String): SupportLevel = webView[permission] ?: SupportLevel.NONE
}
