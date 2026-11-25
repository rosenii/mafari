/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.engine.webview

import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.dsh.miuixbrowser.util.Diag

/**
 * UA 客户端提示（User-Agent Client Hints）与 UA 字符串保持一致。
 *
 * 只设 `WebSettings.userAgentString` 时，请求头里的 `Sec-CH-UA-Mobile` / `Sec-CH-UA-Platform`
 * 和 JS 里的 `navigator.userAgentData` 仍是本机真实值（手机 Android）。桌面模式或自定义 UA 下，
 * 同时检查 UA 和这些头的站点会看到矛盾。这里按目标 UA 推出平台 / 是否移动端，用
 * [WebSettingsCompat.setUserAgentMetadata]（`USER_AGENT_METADATA`）同步过去。
 *
 * 取舍：
 *  - 目标 UA 的平台和移动端标志与系统默认 UA 一致（含「自定义的 Android Chrome UA」）时不覆盖，
 *    让 WebView 用自己的真实值（精简 UA 里的 Android 版本是冻结的假值，覆盖反而更不准）；
 *  - 品牌列表（Sec-CH-UA）不动，仍是 WebView 自己的；
 *  - 系统不支持 / 解析不出平台时什么都不做，UA 字符串照常生效。
 */
object UaClientHints {

    /** 从 UA 字符串推出的客户端提示字段（纯数据，便于 JVM 单测）。 */
    data class Hints(
        val platform: String,
        val platformVersion: String,
        val mobile: Boolean,
        val architecture: String,
        val bitness: Int,
        val model: String,
    )

    private val ANDROID_VER = Regex("""Android (\d+(?:\.\d+)*)""")
    private val ANDROID_MODEL = Regex("""Android [\d.]+; ([^;)]+)""")
    private val IOS_VER = Regex("""OS (\d+)_(\d+)(?:_(\d+))?""")
    private val MAC_VER = Regex("""Mac OS X (\d+)[_.](\d+)(?:[_.](\d+))?""")
    private val CROS_VER = Regex("""CrOS \S+ (\d+(?:\.\d+)*)""")
    private val X64 = Regex("""Win64|x64|WOW64|x86_64|amd64""", RegexOption.IGNORE_CASE)

    /** 补成 主.次.修订 三段（客户端提示里的平台版本是这个格式）。 */
    private fun padVersion(v: String): String {
        val parts = v.split('.').toMutableList()
        while (parts.size < 3) parts.add("0")
        return parts.take(3).joinToString(".")
    }

    /** 认不出平台返回 null。 */
    fun parse(ua: String): Hints? = when {
        ua.contains("Android") -> {
            val model = ANDROID_MODEL.find(ua)?.groupValues?.get(1)?.trim().orEmpty().let { if (it == "K") "" else it }
            Hints(
                platform = "Android",
                platformVersion = padVersion(ANDROID_VER.find(ua)?.groupValues?.get(1) ?: "0"),
                // 手机 UA 带 "Mobile"，平板的 Chrome UA 不带
                mobile = ua.contains(" Mobile"),
                architecture = "", bitness = 0, model = model,
            )
        }
        ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod") -> {
            val m = IOS_VER.find(ua)
            val v = if (m != null) "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3].ifEmpty { "0" }}" else "0.0.0"
            Hints("iOS", v, mobile = !ua.contains("iPad"), architecture = "", bitness = 0, model = "")
        }
        ua.contains("Windows NT") -> {
            val x64 = X64.containsMatchIn(ua)
            Hints("Windows", "10.0.0", mobile = false, architecture = "x86", bitness = if (x64) 64 else 32, model = "")
        }
        ua.contains("Macintosh") -> {
            val m = MAC_VER.find(ua)
            val v = if (m != null) "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3].ifEmpty { "0" }}" else "10.15.7"
            Hints("macOS", v, mobile = false, architecture = "x86", bitness = 64, model = "")
        }
        ua.contains("CrOS") -> {
            val v = CROS_VER.find(ua)?.groupValues?.get(1)
            val x64 = X64.containsMatchIn(ua)
            Hints("Chrome OS", padVersion(v ?: "0"), mobile = false, architecture = if (x64) "x86" else "", bitness = if (x64) 64 else 0, model = "")
        }
        ua.contains("Linux") || ua.contains("X11") -> {
            val x64 = X64.containsMatchIn(ua)
            Hints("Linux", "", mobile = false, architecture = if (x64) "x86" else "", bitness = if (x64) 64 else 0, model = "")
        }
        else -> null
    }

    /**
     * 目标 UA 是否需要覆盖客户端提示：解析得出平台、且平台或移动端标志与 [defaultUa] 不同。
     * 返回 null = 用 WebView 自己的默认值（清除之前的覆盖）。
     */
    fun overrideFor(ua: String, defaultUa: String): Hints? {
        val target = parse(ua) ?: return null
        val base = parse(defaultUa)
        if (base != null && base.platform == target.platform && base.mobile == target.mobile) return null
        return target
    }

    /**
     * 把 [ua] 对应的客户端提示同步到这个 WebView 的设置里。每次改完 `userAgentString` 后调用。
     * 对已加载的页面不生效，下一次导航起生效。
     */
    fun apply(settings: WebSettings, ua: String, defaultUa: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val hints = overrideFor(ua, defaultUa)
        runCatching {
            val meta = if (hints == null) {
                UserAgentMetadata.Builder().build() // 空的 = 不覆盖，使用 WebView 默认值
            } else {
                UserAgentMetadata.Builder()
                    .setPlatform(hints.platform)
                    .setPlatformVersion(hints.platformVersion)
                    .setMobile(hints.mobile)
                    .setArchitecture(hints.architecture)
                    .setBitness(hints.bitness)
                    .setModel(hints.model)
                    .build()
            }
            WebSettingsCompat.setUserAgentMetadata(settings, meta)
        }.onFailure { Diag.log("ua client hints: apply failed ${it.message}") }
    }
}
