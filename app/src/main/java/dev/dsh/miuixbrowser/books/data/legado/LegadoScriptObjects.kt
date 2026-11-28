/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.data.legado

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 书源脚本里 `java.createSymmetricCrypto(...)` 的返回值（Legado 里是 hutool 的 SymmetricCrypto）。
 * 方法名跟 hutool 保持一致：decryptStr / decrypt / encryptBase64 / encryptHex / encrypt。
 * 密文参数是十六进制或 Base64 文本（和 hutool 一样自动识别）。
 * 注意：脚本调用的是 public 方法，R8 规则里要 keep（见 proguard-rules.pro）。
 */
class LegadoSymmetricCrypto(spec: String, private val key: ByteArray, private val iv: ByteArray?) {
    private val algorithm = spec.substringBefore('/')
    private val transformation = if ('/' in spec) spec else "$spec/ECB/PKCS5Padding"

    private fun cipher(mode: Int): Cipher {
        val c = Cipher.getInstance(transformation)
        val k = SecretKeySpec(key, algorithm)
        if (iv != null && iv.isNotEmpty() && !transformation.contains("ECB", true)) c.init(mode, k, IvParameterSpec(iv)) else c.init(mode, k)
        return c
    }

    fun decrypt(data: String): ByteArray = cipher(Cipher.DECRYPT_MODE).doFinal(decodeCipherText(data))

    fun decrypt(data: ByteArray): ByteArray = cipher(Cipher.DECRYPT_MODE).doFinal(data)

    fun decryptStr(data: String): String = String(decrypt(data), Charsets.UTF_8)

    fun decryptStr(data: String, charset: String): String = String(decrypt(data), java.nio.charset.Charset.forName(charset))

    fun encrypt(data: String): ByteArray = cipher(Cipher.ENCRYPT_MODE).doFinal(data.toByteArray(Charsets.UTF_8))

    fun encrypt(data: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE).doFinal(data)

    fun encryptBase64(data: String): String = Base64.getEncoder().encodeToString(encrypt(data))

    fun encryptHex(data: String): String = encrypt(data).joinToString("") { "%02x".format(it) }

    companion object {
        private val HEX = Regex("^[0-9a-fA-F]+$")

        fun unhex(s: String): ByteArray {
            val t = s.trim()
            require(t.length % 2 == 0) { "hex 长度必须为偶数" }
            return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        fun decodeCipherText(s: String): ByteArray {
            val t = s.trim()
            return if (t.length % 2 == 0 && HEX.matches(t)) unhex(t) else Base64.getMimeDecoder().decode(t)
        }
    }
}

/**
 * `java.get(...).raw()`：Legado 里是 OkHttp 的 Response。这里给脚本常用的那几个方法：
 * code / message / header / headers / request().url() / body().string() / isSuccessful / isRedirect。
 */
class LegadoRawResponse(private val res: LegadoResponse) {
    fun code(): Int = res.status

    fun message(): String = ""

    fun isSuccessful(): Boolean = res.status in 200..299

    fun isRedirect(): Boolean = res.status in 300..399

    fun header(name: String): String? = res.header(name)

    fun header(name: String, default: String): String = res.header(name) ?: default

    fun headers(): Map<String, String> = res.headers()

    fun request(): LegadoRawRequest = LegadoRawRequest(res.finalUrl)

    fun body(): LegadoRawBody = LegadoRawBody(res)

    fun networkResponse(): LegadoRawResponse = this

    fun priorResponse(): LegadoRawResponse? = null

    override fun toString(): String = "Response{code=${res.status}, url=${res.finalUrl}}"
}

class LegadoRawRequest(private val url: String) {
    /** OkHttp 里是 HttpUrl；脚本几乎都是 `.url().toString()`，所以直接给字符串。 */
    fun url(): String = url

    fun method(): String = "GET"
}

class LegadoRawBody(private val res: LegadoResponse) {
    fun string(): String = res.body()

    fun bytes(): ByteArray = res.bytes

    fun contentType(): String = res.contentType

    fun contentLength(): Long = res.bytes.size.toLong()

    override fun toString(): String = res.body()
}
