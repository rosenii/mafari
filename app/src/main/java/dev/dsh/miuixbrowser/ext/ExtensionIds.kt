/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import org.json.JSONObject
import java.security.MessageDigest

/**
 * 拓展 ID 的推导与校验。
 * 优先级（PackageInstaller 里决定）：CRX 公钥 > manifest.key > gecko id > 按名称生成的本地 ID。
 * ID 会直接用作目录名与虚拟 origin 的 host，所以必须过 [isSafeId]。
 */
object ExtensionIds {
    /** 允许 Gecko 的 `{uuid}` 与 `name@domain` 两种写法；不允许路径分隔符与空白 */
    private val SAFE = Regex("^[A-Za-z0-9._@+{}-]{1,128}$")

    fun isSafeId(id: String): Boolean = SAFE.matches(id) && !id.startsWith(".") && !id.contains("..")

    /** Chrome 的算法：公钥 DER（SubjectPublicKeyInfo）SHA-256 的前 16 字节，每个半字节映射到 a–p，共 32 位 */
    fun fromPublicKey(der: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(der)
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            val b = h[i].toInt() and 0xff
            sb.append('a' + (b shr 4))
            sb.append('a' + (b and 0x0f))
        }
        return sb.toString()
    }

    /** manifest.json 的 `key` 字段（Base64 的公钥 DER）→ ID；格式不对返回 null */
    fun fromManifestKey(base64: String?): String? {
        if (base64.isNullOrBlank()) return null
        return try {
            val der = java.util.Base64.getMimeDecoder().decode(base64.trim())
            if (der.isEmpty()) null else fromPublicKey(der)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** browser_specific_settings.gecko.id（或旧的 applications.gecko.id），不安全的值视为没有 */
    fun geckoIdOf(manifest: JSONObject): String? {
        val settings = manifest.optJSONObject("browser_specific_settings") ?: manifest.optJSONObject("applications")
        val id = settings?.optJSONObject("gecko")?.optString("id")?.trim()
        return id?.takeIf { it.isNotEmpty() && isSafeId(it) }
    }

    /** 没有任何可用来源时：按名称生成稳定 ID，同名包重装视为更新 */
    fun localId(name: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(name.trim().toByteArray(Charsets.UTF_8))
        return "local-" + h.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
