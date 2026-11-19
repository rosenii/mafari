package dev.dsh.miuixbrowser.ext

/**
 * WebView 适配器的 API 支持矩阵（Gecko 适配器委托 GeckoView，不看这张表）。
 * 实现一个 API 就把它改成 FULL / PARTIAL；未登记的权限一律按 NONE 处理。
 */
object ApiSupport {
    private val webView: Map<String, SupportLevel> = mapOf(
        "storage" to SupportLevel.FULL,
        "permissions" to SupportLevel.PARTIAL, // getAll / contains；缺 request / remove 与权限事件
        "tabs" to SupportLevel.PARTIAL, // sendMessage 仅支持已注入的主框架；缺 move / duplicate / captureVisibleTab 等
        "activeTab" to SupportLevel.PARTIAL, // 点击快捷菜单里的拓展授予当前标签；暂无工具栏按钮 / 快捷键等其它触发方式
        "scripting" to SupportLevel.PARTIAL, // executeScript 与动态注册已接入；缺内联 code、隔离世界及完整 frame 能力
        "contextMenus" to SupportLevel.PLANNED,
        "menus" to SupportLevel.PLANNED,
        "alarms" to SupportLevel.FULL,
        "declarativeNetRequest" to SupportLevel.PLANNED,
        "unlimitedStorage" to SupportLevel.FULL,
        "notifications" to SupportLevel.PLANNED,
        "webNavigation" to SupportLevel.PLANNED,
        "cookies" to SupportLevel.PLANNED,
        "clipboardWrite" to SupportLevel.PLANNED,
        "clipboardRead" to SupportLevel.PLANNED,
    )

    fun forWebView(permission: String): SupportLevel = webView[permission] ?: SupportLevel.NONE
}
