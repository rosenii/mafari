/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 拓展页（后台页 / popup / options）里跨域 fetch 的代理：Chrome / Firefox 给拓展的 host 权限可以绕过 CORS，
 * WebView 里扩展页只是个普通网页，浏览器会按 CORS 拦下。shim 把「指向别的 origin 的 fetch」转到这里，
 * 由原生用 OkHttp 发请求，只放行拓展已获授权的 host，响应不带 CORS 限制地回给页面。
 *
 * 边界：只接受 http(s)；不带也不写 WebView 的 cookie（等同 credentials: omit）；请求体 / 响应体各 [MAX_BYTES]；
 * 只在拓展自己的页面可用（PermissionChecker 里 content script 的白名单不含 mafari.net.*）。
 */
class NetApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) {
    fun register(router: ApiRouter) {
        router.register("mafari.net.fetch") { ctx, a -> fetch(ctx, a.argOrNull(0) as? JSONObject ?: throw bad("缺少请求参数")) }
    }

    private suspend fun fetch(ctx: CallContext, d: JSONObject): Any? {
        val url = d.optString("url")
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) throw bad("只支持 http(s) 地址")
        if (!PermissionChecker.hasHostAccess(ctx.ext, url)) {
            throw ApiException(ErrorCodes.DENIED_PERMISSION, "拓展没有访问该网站的权限：$url")
        }
        val method = d.optString("method", "GET").uppercase()
        val bodyB64 = if (d.has("bodyB64") && !d.isNull("bodyB64")) d.optString("bodyB64") else null
        val body = bodyB64?.let {
            if (it.length > MAX_BYTES * 4 / 3 + 8) throw bad("请求体过大")
            Base64.decode(it, Base64.DEFAULT)
        }
        if (body != null && (method == "GET" || method == "HEAD")) throw bad("$method 请求不能带请求体")

        val hb = Headers.Builder()
        d.optJSONArray("headers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val pair = arr.optJSONArray(i) ?: continue
                val k = pair.optString(0)
                if (k.isBlank() || k.lowercase() in FORBIDDEN) continue
                try { hb.add(k, pair.optString(1)) } catch (_: IllegalArgumentException) { /* 非法头直接丢掉 */ }
            }
        }
        val headers = hb.build()
        val contentType = headers["Content-Type"]?.toMediaTypeOrNull()
        val requestBody = when {
            body != null -> body.toRequestBody(contentType)
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(contentType)
            else -> null
        }
        val request = try {
            Request.Builder().url(url).headers(headers).method(method, requestBody).build()
        } catch (e: IllegalArgumentException) {
            throw bad("请求格式错误：${e.message}")
        }
        val client = if (d.optString("redirect") == "manual") this.client.newBuilder().followRedirects(false).followSslRedirects(false).build() else this.client

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { resp ->
                    val declared = resp.body.contentLength()
                    if (declared > MAX_BYTES) throw ApiException(ErrorCodes.BAD_REQUEST, "响应体超过 ${MAX_BYTES / 1024 / 1024}MB")
                    val bytes = resp.body.bytes()
                    if (bytes.size > MAX_BYTES) throw ApiException(ErrorCodes.BAD_REQUEST, "响应体超过 ${MAX_BYTES / 1024 / 1024}MB")
                    val out = JSONArray()
                    for (i in 0 until resp.headers.size) out.put(JSONArray().put(resp.headers.name(i)).put(resp.headers.value(i)))
                    JSONObject().put("status", resp.code).put("statusText", resp.message).put("url", resp.request.url.toString())
                        .put("headers", out).put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: java.io.IOException) {
                // 与浏览器一致：网络错误对页面就是 TypeError: Failed to fetch；message 不带内部细节
                throw ApiException(ErrorCodes.NETWORK, "网络请求失败")
            }
        }
    }

    private fun bad(msg: String) = ApiException(ErrorCodes.BAD_REQUEST, msg)

    private companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        /** 浏览器不允许 fetch 设置的头（页面传了也直接丢，交给 OkHttp 自己生成） */
        val FORBIDDEN = setOf("host", "content-length", "connection", "cookie", "origin", "referer", "user-agent", "accept-encoding", "transfer-encoding")
    }
}
