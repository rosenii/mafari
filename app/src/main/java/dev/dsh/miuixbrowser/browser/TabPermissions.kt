/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.adblock.AdBlockBridge
import dev.dsh.miuixbrowser.adblock.RequestLog
import dev.dsh.miuixbrowser.crypto.TrustedChannel
import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.data.DESKTOP_UA
import dev.dsh.miuixbrowser.data.PrivacyStats
import dev.dsh.miuixbrowser.data.TabDb
import dev.dsh.miuixbrowser.data.TabThumbs
import dev.dsh.miuixbrowser.download.Downloader
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.GeckoTabOps
import dev.dsh.miuixbrowser.engine.TabEngineOps
import dev.dsh.miuixbrowser.engine.WebViewTabOps
import dev.dsh.miuixbrowser.engine.gecko.GeckoGm
import dev.dsh.miuixbrowser.engine.gecko.GeckoReader
import dev.dsh.miuixbrowser.engine.gecko.GeckoSitePerms
import dev.dsh.miuixbrowser.engine.gecko.GeckoTabEngine
import dev.dsh.miuixbrowser.engine.webview.FingerprintInjector
import dev.dsh.miuixbrowser.engine.webview.FpFallbackBridge
import dev.dsh.miuixbrowser.engine.webview.WebOrigin
import dev.dsh.miuixbrowser.engine.webview.WebViewDebug
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.net.WebViewProxy
import dev.dsh.miuixbrowser.script.GmBridge
import dev.dsh.miuixbrowser.script.ScriptResources
import dev.dsh.miuixbrowser.script.UserScript
import dev.dsh.miuixbrowser.script.UserscriptEngine
import dev.dsh.miuixbrowser.sync.fxa.FxaSync
import dev.dsh.miuixbrowser.util.Diag
import dev.dsh.miuixbrowser.util.ErrorPages
import dev.dsh.miuixbrowser.util.ErrorSpec
import dev.dsh.miuixbrowser.util.isSameSite
import dev.dsh.miuixbrowser.util.isUserScriptUrl
import dev.dsh.miuixbrowser.util.toUrl

/**
 * WebView 标签的站点权限（摄像头 / 麦克风 / 定位 / 指纹豁免）处理（从 BrowserTab 拆出）：
 * 「询问 / 允许 / 拒绝」判定、系统权限申请、弹窗回调。写成 [BrowserTab] 的扩展函数；模式开关仍存放在 [BrowserTab] 里。
 */

/**
 * 页面经 [TrustedChannel] 请求「关闭对我的指纹保护」。origin 是 WebView 校验过的主框架来源，
 * 只能豁免它自己的 host；必须用户确认，页面无法静默修改设置，也得不到任何回传。
 */
internal fun BrowserTab.handleFpExemptRequest(origin: WebOrigin) {
    if (!AdBlock.fingerprintActive) return
    if (AdBlock.isFingerprintExempt(origin.host)) return
    // 只接受「当前正在显示的页面」发来的请求
    val current = WebOrigin.parse(webView.url) ?: return
    if (current != origin) return
    if (promptBusy()) return
    val now = android.os.SystemClock.elapsedRealtime()
    val record = fpExemptAsked.getOrPut(origin.origin) { longArrayOf(0L, 0L) }
    if (record[0] >= 3 || (record[0] > 0 && now - record[1] < 30_000L)) return
    if (fpExemptAsked.size > 64) fpExemptAsked.clear()
    record[0]++
    record[1] = now
    askRedirect(origin.origin, tr("关闭对它的指纹保护")) { allow ->
        if (allow && AdBlock.exemptFingerprintSite(origin.host)) {
            android.widget.Toast.makeText(
                appContext, tr("已豁免 {0}，刷新后生效", origin.host), android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

internal fun BrowserTab.hasPermission(permission: String): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(appContext, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

internal fun BrowserTab.ensureAndroid(perms: Array<String>, anyOk: Boolean, result: (Boolean) -> Unit) {
    val need = perms.filter { it.isNotBlank() && !hasPermission(it) }.toTypedArray()
    fun granted() = if (anyOk) {
        perms.filter { it.isNotBlank() }.any { hasPermission(it) }
    } else {
        perms.filter { it.isNotBlank() }.all { hasPermission(it) }
    }
    if (need.isEmpty()) {
        result(granted())
        return
    }
    val request = requestAndroidPermissions
    if (request == null) {
        result(false)
    } else {
        request(need) { result(granted()) }
    }
}

internal fun BrowserTab.decide(
    mode: Int,
    origin: String,
    what: String,
    perms: Array<String>,
    anyOk: Boolean = false,
    permKey: String? = null,
    result: (Boolean) -> Unit,
) {
    // 隐私报告：记录请求本身（允许 / 拒绝都算），无痕标签页不记
    if (!incognito) PrivacyStats.recordPermission(appContext, origin, what)
    when (mode) {
        2 -> result(false)
        1 -> ensureAndroid(perms, anyOk, result)
        else -> {
            val prompt = onPermissionPrompt
            if (prompt == null) {
                result(false)
            } else {
                prompt(origin, what, permKey) { allow ->
                    if (allow) ensureAndroid(perms, anyOk, result) else result(false)
                }
            }
        }
    }
}

internal fun BrowserTab.handleWebPermission(request: PermissionRequest) {
    // Android SDK 的 PermissionRequest 仅有：
    // RESOURCE_AUDIO_CAPTURE / RESOURCE_VIDEO_CAPTURE / RESOURCE_PROTECTED_MEDIA_ID / RESOURCE_MIDI_SYSEX
    // 定位走 onGeolocationPermissionsShowPrompt；网站通知 WebView 不走 PermissionRequest（设置里用 JS 拒绝生效）
    val res = request.resources.toSet()
    val keep = mutableListOf<String>()
    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in res) keep += PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
    val host = request.origin.host ?: request.origin.toString()
    // 例外网站优先于全局设置
    val camMode = GeckoSitePerms.override(host, GeckoSitePerms.CAMERA) ?: cameraMode
    val micModeEff = GeckoSitePerms.override(host, GeckoSitePerms.MIC) ?: micMode
    val camOk = PermissionRequest.RESOURCE_VIDEO_CAPTURE in res && camMode != 2
    val micOk = PermissionRequest.RESOURCE_AUDIO_CAPTURE in res && micModeEff != 2
    fun finish(extra: List<String>) {
        val all = keep + extra
        if (all.isEmpty()) request.deny() else request.grant(all.toTypedArray())
    }
    if (!camOk && !micOk) {
        finish(emptyList())
        return
    }
    val perms = buildList {
        if (camOk) add(android.Manifest.permission.CAMERA)
        if (micOk) add(android.Manifest.permission.RECORD_AUDIO)
    }.toTypedArray()
    val what = listOfNotNull(if (camOk) tr("摄像头") else null, if (micOk) tr("麦克风") else null).joinToString(tr("、"))
    val mustAsk = (camOk && camMode == 0) || (micOk && micModeEff == 0)
    val keys = listOfNotNull(
        if (camOk) GeckoSitePerms.CAMERA else null,
        if (micOk) GeckoSitePerms.MIC else null,
    ).joinToString(",")
    decide(if (mustAsk) 0 else 1, host, tr("使用{0}", what), perms, permKey = keys) { ok ->
        finish(
            if (ok) buildList {
                if (camOk) add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (micOk) add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            } else emptyList(),
        )
    }
}
