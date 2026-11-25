/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 标签静音：`WebViewCompat.setAudioMuted / isAudioMuted`（1.11，特性 MUTE_AUDIO），用来支持
 * `tabs.update({muted})` 和 `mutedInfo`。必须在主线程调用。
 * 没有对应 API 能读「正在发声」（audible），所以 audible 仍恒为 false。
 */
object WebViewAudio {
    fun isSupported(): Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO) }.getOrDefault(false)

    /** 不支持（或读取失败）返回 null，调用方据此把 muted 当作「未知 = 未静音」。 */
    fun isMuted(webView: WebView): Boolean? {
        if (!isSupported()) return null
        return runCatching { WebViewCompat.isAudioMuted(webView) }.getOrNull()
    }

    /** 返回是否真的设置了；不支持时什么也不做。 */
    fun setMuted(webView: WebView, muted: Boolean): Boolean {
        if (!isSupported()) return false
        return runCatching { WebViewCompat.setAudioMuted(webView, muted) }.isSuccess
    }
}
