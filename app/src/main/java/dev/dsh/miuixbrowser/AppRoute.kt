package dev.dsh.miuixbrowser

import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 应用级路由（miuix-nav 官方 NavDisplay 的返回栈元素）。
 *
 * 栈底永远是 [Browser]（浏览主界面）；设置、关于、下载、扩展、书签/历史作为页面压栈。
 * 预见性返回的跟手、提交/取消回弹、下层视差与遮罩全部由 NavDisplay 负责，应用不再自绘。
 * 栈是纯内存栈（navBackStackOf），所以这里不需要 @Serializable。
 */
sealed interface AppRoute : NavKey {
    data object Browser : AppRoute
    data object Settings : AppRoute
    data object About : AppRoute
    data object Downloads : AppRoute
    data object Extensions : AppRoute

    /** Gecko 专属「拓展」页（WebExtension：搜索 / 导入 / 更新 / 管理） */
    data object GeckoExtensions : AppRoute

    /** 设置 → 高级 → 去广告 */
    data object AdBlockPage : AppRoute

    /** 设置 → 隐私与安全 → 指纹保护 */
    data object FingerprintPage : AppRoute

    /** 书签 / 历史库。[tab] 0 = 书签，1 = 历史。 */
    data class Library(val tab: Int) : AppRoute
}
