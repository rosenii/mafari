package dev.dsh.miuixbrowser.ext

/**
 * WebView 适配器的 API 支持矩阵（Gecko 适配器委托 GeckoView，不看这张表）。
 * 实现一个 API 就把它改成 FULL / PARTIAL；未登记的权限一律按 NONE 处理。
 */
object ApiSupport {
    private val webView: Map<String, SupportLevel> = mapOf(
        "storage" to SupportLevel.PARTIAL, // local/session API 完整；sync 为独立持久化本地存储，尚未接入账号云同步
        "runtime" to SupportLevel.PARTIAL, // 消息与 Port 已接入；不支持跨拓展/外部连接，Port 当前单接收上下文
        "permissions" to SupportLevel.PARTIAL, // getAll/contains/request/remove 与用户确认、权限事件已接入；match pattern 子集覆盖仍有限
        "tabs" to SupportLevel.PARTIAL, // 基础标签操作、sendMessage、move、duplicate 与事件；单窗口/主框架限制，缺截图等能力
        "activeTab" to SupportLevel.PARTIAL, // 点击快捷菜单里的拓展授予当前标签；暂无工具栏按钮 / 快捷键等其它触发方式
        "scripting" to SupportLevel.PARTIAL, // executeScript 与动态注册系列已接入；无真正隔离世界、frameId 仅支持 0
        "contextMenus" to SupportLevel.PLANNED,
        "menus" to SupportLevel.PLANNED,
        "alarms" to SupportLevel.FULL,
        "declarativeNetRequest" to SupportLevel.PLANNED,
        "unlimitedStorage" to SupportLevel.FULL,
        "notifications" to SupportLevel.PLANNED,
        "webNavigation" to SupportLevel.PARTIAL, // 主框架导航事件与 getFrame/getAllFrames；不支持子框架和 documentId 完整语义,
        "cookies" to SupportLevel.PLANNED,
        "clipboardWrite" to SupportLevel.PLANNED,
        "clipboardRead" to SupportLevel.PLANNED,
    )

    fun forWebView(permission: String): SupportLevel = webView[permission] ?: SupportLevel.NONE
}
