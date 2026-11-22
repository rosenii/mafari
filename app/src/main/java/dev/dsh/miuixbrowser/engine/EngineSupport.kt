package dev.dsh.miuixbrowser.engine

import dev.dsh.miuixbrowser.BuildConfig
import dev.dsh.miuixbrowser.data.ENGINE_GECKO
import dev.dsh.miuixbrowser.data.readBrowserEngine
import android.content.Context
import android.os.Build

/**
 * 内核可用性的唯一判断入口。
 *
 * 本项目有三个构建变体（product flavor，见 app/build.gradle.kts）：
 * - gecko：内嵌 GeckoView（Nightly，仅 arm64-v8a），与系统 WebView 双内核，带 Firefox 同步；
 * - lite（无内核版）：不打包任何浏览器内核，只用系统 WebView，不带 Firefox 同步；
 * - liteSync：lite + Firefox 同步（只有 arm64，文件名 sync_lite-arm64）。
 *
 * lite 变体里 GeckoView 只以 compileOnly 参与编译（让共用源码能通过编译），运行时并不存在；
 * 所以所有「可能走到 Gecko 类」的共用代码都必须先过 [BuildConfig.GECKO_ENABLED] / [geckoAvailable]，
 * 不要在 lite 里直接触碰 Gecko* 对象（否则会 NoClassDefFoundError）。
 * Firefox 同步库同理：普通 lite 只 compileOnly，共用代码要碰 Fxa* 先过 [SYNC_BUNDLED]。
 */
object EngineSupport {
    /** 本安装包是否包含 GeckoView（编译期常量，lite 为 false，R8 会据此裁掉 Gecko 相关分支） */
    const val GECKO_BUNDLED: Boolean = BuildConfig.GECKO_ENABLED

    /** 本安装包是否包含 Firefox 同步（gecko / liteSync 为 true，普通 lite 为 false；编译期常量） */
    const val SYNC_BUNDLED: Boolean = BuildConfig.SYNC_ENABLED

    /** 无内核 + 带同步（liteSync）：应用内更新据此选 `sync_lite` 安装包 */
    const val SYNC_LITE: Boolean = BuildConfig.SYNC_ENABLED && !BuildConfig.GECKO_ENABLED

    /** 本机能否使用 Gecko：安装包带了 GeckoView，且设备是 arm64-v8a（GeckoView nightly 只发布该架构） */
    fun geckoAvailable(): Boolean =
        GECKO_BUNDLED && Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    /** 当前默认内核是否实际为 Gecko（设置选了 Gecko 且本机能用）。否则按 WebView 处理；备份 / 恢复 Cookie 据此选实现。 */
    fun defaultIsGecko(context: Context): Boolean =
        geckoAvailable() && readBrowserEngine(context.applicationContext) == ENGINE_GECKO

    /**
     * 本进程是否已经创建过 WebView（标签 / 轻应用）。默认内核是 Gecko 时，一切 WebView 相关的启动工作都不做；
     * 只有用户中途切到 WebView、真的建出 WebView 之后，这个标志才为 true，需要「碰 WebView」的路径据此补做。
     */
    @Volatile
    var webViewTouched: Boolean = false
        private set

    /** 创建第一个 WebView 之前调用（幂等）：记账，并补读只在 WebView 下才加载的调试开关（远程调试须在创建 WebView 前设置）。 */
    fun markWebViewUsed(context: Context) {
        if (webViewTouched) return
        webViewTouched = true
        val app = context.applicationContext
        dev.dsh.miuixbrowser.engine.webview.WebViewDebug.load(app)
        dev.dsh.miuixbrowser.engine.webview.WebViewSpeculative.load(app)
    }

    /** 现在是否需要为 WebView 付出开销：默认内核是 WebView，或本进程已经建过 WebView（中途从 Gecko 切过来）。 */
    fun webViewNeeded(context: Context): Boolean = webViewTouched || !defaultIsGecko(context)
}
