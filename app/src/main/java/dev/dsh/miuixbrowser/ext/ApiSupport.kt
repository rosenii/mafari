package dev.dsh.miuixbrowser.ext

/**
 * WebView 适配器的 API 支持矩阵（Gecko 适配器委托 GeckoView，不看这张表）。
 * 实现一个 API 就把它改成 FULL / PARTIAL；未登记的权限一律按 NONE 处理。
 */
object ApiSupport {
    private val webView: Map<String, SupportLevel> = mapOf(
        "storage" to SupportLevel.FULL,
        "tabs" to SupportLevel.PLANNED,
        "activeTab" to SupportLevel.PLANNED,
        "scripting" to SupportLevel.PLANNED,
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
