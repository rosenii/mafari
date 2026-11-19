package dev.dsh.miuixbrowser.ext

import java.security.MessageDigest

/**
 * 拓展页面（popup / options / 后台页）的虚拟 origin：每个拓展一个独立 host，浏览器同源策略天然隔离拓展之间的存储。
 * 由 WebViewAssetLoader 把这个 origin 映射到拓展的解包目录。拓展 ID 可能含 `@` `{}`，不适合直接放进 host，所以取哈希。
 * 注意：域名只是约定，WebViewAssetLoader 能否接管任意 https 域名需要真机验证。
 */
object ExtensionOrigins {
    const val DOMAIN = "ext.mafari.internal"

    fun hostFor(id: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return h.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) } + "." + DOMAIN
    }

    fun originFor(id: String): String = "https://" + hostFor(id)

    fun urlFor(id: String, path: String): String = originFor(id) + "/" + path.trimStart('/')

    fun isExtensionOrigin(origin: String, id: String): Boolean = origin.equals(originFor(id), ignoreCase = true)
}
