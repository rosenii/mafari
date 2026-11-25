/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

import dev.dsh.miuixbrowser.net.HttpFetcher
import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONObject

/**
 * 更新安装包的完整性校验。
 *
 * 安装包可能经第三方 GitHub 加速镜像下载，镜像本身不可信；发布列表也是多条线路并发抢到的，镜像给的哈希同样不可信。
 * 所以期望的 SHA-256 一律单独**直连** api.github.com（https，走系统证书校验）取 release 资产的 digest，
 * 下载完成后在本地算一遍比对，对不上就删掉、不安装。
 * 直连 GitHub 不通（地区网络）时取不到期望值：照常下载，由系统安装器按签名校验（签名不同的包装不上）。
 */
object UpdateIntegrity {

    private const val RELEASE_BY_TAG = "https://api.github.com/repos/rosenii/mafari/releases/tags/"
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** 直连 GitHub 取 [tag] 里文件名为 [fileName] 的资产 digest（sha256 十六进制小写）；取不到返回 null。阻塞调用。 */
    fun fetchDirectSha256(tag: String, fileName: String): String? = runCatching {
        val body = HttpFetcher.getText(
            RELEASE_BY_TAG + android.net.Uri.encode(tag),
            HttpFetcher.Options(
                connectTimeoutMs = 4_000,
                readTimeoutMs = 5_000,
                maxBytes = 2 * 1024 * 1024,
                userAgent = "mafari",
                accept = "application/vnd.github+json",
                httpsOnly = true,
            ),
        )
        parseDigest(body, fileName)
    }.getOrNull()

    /** release JSON 里按文件名找资产的 `digest: "sha256:<hex>"`；格式不对返回 null。 */
    internal fun parseDigest(json: String, fileName: String): String? {
        val assets = runCatching { JSONObject(json).optJSONArray("assets") }.getOrNull() ?: return null
        val wanted = runCatching { java.net.URLDecoder.decode(fileName, "UTF-8") }.getOrDefault(fileName)
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name") != wanted) continue
            val digest = a.optString("digest", "")
            if (!digest.startsWith("sha256:", ignoreCase = true)) return null
            val hex = digest.substring(7).trim().lowercase()
            return hex.takeIf { HEX64.matches(it) }
        }
        return null
    }

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 常量时间比较（十六进制小写）。 */
    fun same(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.lowercase().toByteArray(), actual.lowercase().toByteArray())
}
