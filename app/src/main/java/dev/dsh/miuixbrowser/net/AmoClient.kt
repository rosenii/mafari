/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.net

import android.net.Uri
import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Firefox 拓展商店（addons.mozilla.org）的搜索 / 页面解析。与 GeckoView 无关，
 * Gecko 与 WebView 两个内核的拓展管理页共用（WebView 内核同样能安装商店里的 .xpi）。
 */
object AmoClient {

    private const val AMO_API = "https://addons.mozilla.org/api/v5/addons"

    suspend fun search(query: String): Result<List<AmoAddon>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$AMO_API/search/?app=android&type=extension&sort=users&page_size=20&lang=zh-CN&q=" +
                URLEncoder.encode(query, "UTF-8")
            val results = JSONObject(httpGet(url)).optJSONArray("results") ?: return@runCatching emptyList()
            (0 until results.length()).mapNotNull { parseAddon(results.getJSONObject(it)) }
        }.recoverCatching { throw IllegalStateException(netMessage(it)) }
    }

    /** 把 addons.mozilla.org 的商店页面链接解析成拓展信息（取 xpi 下载地址）；不是商店页面返回 null */
    suspend fun resolvePage(url: String): Result<AmoAddon?> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(url)
            val host = uri.host.orEmpty()
            if (!host.endsWith("addons.mozilla.org")) return@runCatching null
            val slug = Regex("/addon/([^/?#]+)").find(uri.path.orEmpty())?.groupValues?.get(1)
                ?: return@runCatching null
            parseAddon(JSONObject(httpGet("$AMO_API/addon/${URLEncoder.encode(slug, "UTF-8")}/?lang=zh-CN")))
                ?: error(tr("商店里找不到这个拓展"))
        }.recoverCatching { throw IllegalStateException(netMessage(it)) }
    }

    private fun netMessage(t: Throwable) =
        t.message?.takeIf { it.startsWith(tr("服务器返回")) || it.startsWith(tr("商店")) } ?: tr("访问商店失败，检查网络")

    /** 读字符串字段：缺失或 JSON null 都当空串（Android 的 optString 对 JSON null 会返回 "null" 字样） */
    private fun str(o: JSONObject, key: String): String =
        if (!o.has(key) || o.isNull(key)) "" else o.optString(key).trim()

    /**
     * AMO 的多语言字段：带 lang 参数时是字符串，否则是 {语言: 文本}；某个语言没有译文时值是 JSON null。
     * 依次取 zh-CN、zh-TW、en-US、en-GB，最后取任意一个非空的语言。
     */
    private fun localized(o: JSONObject, key: String): String {
        if (!o.has(key) || o.isNull(key)) return ""
        return when (val v = o.opt(key)) {
            is String -> v.trim().takeUnless { it.equals("null", true) }.orEmpty()
            is JSONObject -> {
                val order = listOf("zh-CN", "zh-TW", "en-US", "en-GB") + v.keys().asSequence().toList()
                order.firstNotNullOfOrNull { k -> str(v, k).takeIf { it.isNotEmpty() } }.orEmpty()
            }
            else -> ""
        }
    }

    private fun parseAddon(o: JSONObject): AmoAddon? {
        val current = o.optJSONObject("current_version") ?: return null
        val xpi = current.optJSONObject("file")?.let { str(it, "url") }?.takeIf { it.isNotBlank() }
            ?: current.optJSONArray("files")?.optJSONObject(0)?.let { str(it, "url") }?.takeIf { it.isNotBlank() }
            ?: return null
        return AmoAddon(
            id = o.optLong("id"),
            slug = str(o, "slug"),
            guid = str(o, "guid"),
            name = localized(o, "name").ifBlank { str(o, "slug") },
            summary = localized(o, "summary").replace(Regex("<[^>]+>"), "").trim(),
            iconUrl = str(o, "icon_url"),
            xpiUrl = xpi,
            version = str(current, "version"),
            users = o.optLong("average_daily_users"),
            rating = o.optJSONObject("ratings")?.optDouble("average", 0.0) ?: 0.0,
        )
    }

    private fun httpGet(url: String): String = HttpFetcher.getText(
        url,
        HttpFetcher.Options(
            connectTimeoutMs = 10_000,
            readTimeoutMs = 20_000,
            maxBytes = 8 * 1024 * 1024,
            userAgent = "Mozilla/5.0 mafari",
            accept = "application/json",
            httpsOnly = true,
        ),
    )
}
