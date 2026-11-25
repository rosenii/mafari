/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import dev.dsh.miuixbrowser.data.HistoryStore
import org.json.JSONArray
import org.json.JSONObject

/** WebExtensions history API backed by Mafari's encrypted browsing-history database. */
class HistoryApi(private val history: HistoryStore) {
    fun register(router: ApiRouter) {
        router.register("history.search") { _, args ->
            val details = args.optJSONObject(0) ?: JSONObject()
            val text = details.optString("text", "")
            val start = details.optLong("startTime", Long.MIN_VALUE)
            val end = details.optLong("endTime", Long.MAX_VALUE)
            if (start > end) throw ApiException(ErrorCodes.BAD_REQUEST, "startTime 不能晚于 endTime")
            val max = details.optInt("maxResults", 100)
            if (max < 0 || max > 10_000) throw ApiException(ErrorCodes.BAD_REQUEST, "maxResults 超出范围")
            val rows = history.query(text, if (max == 0) 10_000 else max.coerceAtLeast(1))
            JSONArray().apply {
                rows.asSequence().filter { it.time in start..end }.take(if (max == 0) 10_000 else max).forEach { row ->
                    put(JSONObject()
                        .put("id", row.url)
                        .put("url", row.url)
                        .put("title", row.title)
                        .put("lastVisitTime", row.time))
                }
            }
        }
        router.register("history.deleteUrl") { _, args ->
            val url = args.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "缺少有效 url")
            history.remove(url)
            null
        }
        router.register("history.deleteRange") { _, args ->
            val details = args.optJSONObject(0) ?: throw ApiException(ErrorCodes.BAD_REQUEST, "缺少时间范围")
            val start = details.optLong("startTime", Long.MIN_VALUE)
            val end = details.optLong("endTime", Long.MAX_VALUE)
            if (start > end) throw ApiException(ErrorCodes.BAD_REQUEST, "startTime 不能晚于 endTime")
            history.removeRange(start, end)
            null
        }
        router.register("history.deleteAll") { _, _ ->
            history.clear()
            null
        }
    }
}
