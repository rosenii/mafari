package dev.dsh.miuixbrowser

import android.content.Context
import android.os.Build

/**
 * 内核可用性的唯一判断入口。
 *
 * 本项目有两个构建变体（product flavor，见 app/build.gradle.kts）：
 * - gecko：内嵌 GeckoView（Nightly，仅 arm64-v8a），与系统 WebView 双内核；
 * - lite（无内核版）：不打包任何浏览器内核，只用系统 WebView。
 *
 * lite 变体里 GeckoView 只以 compileOnly 参与编译（让共用源码能通过编译），运行时并不存在；
 * 所以所有「可能走到 Gecko 类」的共用代码都必须先过 [BuildConfig.GECKO_ENABLED] / [geckoAvailable]，
 * 不要在 lite 里直接触碰 Gecko* 对象（否则会 NoClassDefFoundError）。
 */
object EngineSupport {
    /** 本安装包是否包含 GeckoView（编译期常量，lite 为 false，R8 会据此裁掉 Gecko 相关分支） */
    const val GECKO_BUNDLED: Boolean = BuildConfig.GECKO_ENABLED

    /** 本机能否使用 Gecko：安装包带了 GeckoView，且设备是 arm64-v8a（GeckoView nightly 只发布该架构） */
    fun geckoAvailable(): Boolean =
        GECKO_BUNDLED && Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    /** 当前默认内核是否实际为 Gecko（设置选了 Gecko 且本机能用）。否则按 WebView 处理；备份 / 恢复 Cookie 据此选实现。 */
    fun defaultIsGecko(context: Context): Boolean =
        geckoAvailable() && readBrowserEngine(context.applicationContext) == ENGINE_GECKO
}
