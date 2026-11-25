/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

import android.webkit.WebView

/**
 * WebView 的证书例外：用户在错误页明确选择「忽略风险，继续访问」的主机，仅存内存（关闭应用即失效）。
 * 无痕标签单独一份，且不会带到普通标签，反之亦然。
 */
internal object SslExceptions {
    /** 例外绑定到「主机 + 证书 SHA-256 指纹 + 当时的错误集合」，换了证书或多出新的错误类型都不再放行 */
    private data class Entry(val fp: String, val mask: Int)
    private val normal = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Entry>>()
    private val incognito = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Entry>>()
    private fun map(incog: Boolean) = if (incog) incognito else normal
    fun add(host: String, fp: String, mask: Int, incog: Boolean) {
        map(incog).getOrPut(host.lowercase()) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(Entry(fp, mask))
    }
    /** 该主机上这张证书、这组错误（不超出当时用户看到的范围）是否已被用户放行 */
    fun allows(host: String, fp: String, mask: Int, incog: Boolean): Boolean =
        map(incog)[host.lowercase()]?.any { it.fp == fp && (mask and it.mask.inv()) == 0 } == true
    fun clear() { normal.clear(); incognito.clear() }
}

/** WebView 主框架证书错误的现场：地址 / 主机 / 证书指纹 / 错误集合（见 [SslExceptions]） */
internal data class PendingSsl(val url: String, val host: String, val fp: String, val mask: Int)

/** SslError 里所有出现的错误类型，按位压成一个整数（0 未生效 … 5 其它） */
internal fun sslErrorMask(e: android.net.http.SslError): Int {
    var m = 0
    for (i in 0..5) if (e.hasError(i)) m = m or (1 shl i)
    return m
}

/** 服务器证书（DER）的 SHA-256 十六进制；取不到返回 null（此时不提供「继续」入口） */
internal fun certFingerprint(c: android.net.http.SslCertificate?): String? {
    if (c == null) return null
    val der = runCatching { if (android.os.Build.VERSION.SDK_INT >= 29) c.x509Certificate?.encoded else null }.getOrNull()
        ?: runCatching { android.net.http.SslCertificate.saveState(c).getByteArray("x509-certificate") }.getOrNull()
    return der?.let { b -> java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } }
}
