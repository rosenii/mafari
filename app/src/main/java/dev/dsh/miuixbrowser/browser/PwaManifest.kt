/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

import org.json.JSONObject
import java.net.URI

/** manifest.icons 里的一项：[size] 取声明里最大的边长（`any` / SVG 记 0） */
data class PwaIcon(val src: String, val size: Int, val maskable: Boolean)

/**
 * 页面声明的 Web App Manifest（PWA）里「做成桌面应用」用得到的字段。
 * Gecko 的 `ContentDelegate.onWebAppManifest` 交来的 JSON 已按规范处理过（URL 为绝对地址），这里仍再按页面地址补全一次。
 *
 * @property display 规范化后只有 fullscreen / standalone / minimal-ui / browser 四种，缺省 standalone
 * @property orientation 原样小写（portrait / landscape / any …），缺省空串 = 不限制
 * @property themeColor / backgroundColor ARGB，没声明或解析不了为 null
 * @property sourceUrl 读到这份 manifest 时的页面地址：换了站点的页面不能用它
 */
data class PwaManifest(
    val name: String,
    val shortName: String,
    val startUrl: String,
    val scope: String,
    val display: String,
    val orientation: String,
    val themeColor: Int?,
    val backgroundColor: Int?,
    val icons: List<PwaIcon>,
    val sourceUrl: String,
) {
    /** 桌面应用显示名：优先 short_name（图标下空间小），其次 name */
    val label: String get() = shortName.ifBlank { name }

    /** 浏览器模式的 PWA 不要独立窗口（站点自己要求留在浏览器里） */
    val wantsStandalone: Boolean get() = display != "browser"

    /**
     * 挑做桌面图标的那一张：优先普通（any）图标，其中取最大的；SVG 解码不了先排除，
     * 只有 maskable 图标时才用它（已自带出血边，桌面再套一层底会偏小，但总比没有强）。
     */
    fun bestIcon(): PwaIcon? {
        val usable = icons.filter { it.size > 0 }
        return usable.filter { !it.maskable }.maxByOrNull { it.size }
            ?: usable.maxByOrNull { it.size }
    }

    companion object {

        /** 解析失败 / 没有可用的 start_url 返回 null */
        fun parse(json: JSONObject, pageUrl: String): PwaManifest? = runCatching {
            fun abs(raw: String): String =
                runCatching { URI(pageUrl).resolve(raw).toString() }.getOrDefault(raw)

            val name = json.optString("name").trim()
            val short = json.optString("short_name").trim()
            val rawStart = json.optString("start_url").trim()
            val start = abs(rawStart.ifBlank { pageUrl })
            if (!start.startsWith("http://", true) && !start.startsWith("https://", true)) return null
            val scope = json.optString("scope").trim().let { if (it.isBlank()) "" else abs(it) }
            val display = when (json.optString("display").trim().lowercase()) {
                "fullscreen" -> "fullscreen"
                "minimal-ui" -> "minimal-ui"
                "browser" -> "browser"
                else -> "standalone"
            }
            val icons = buildList {
                val arr = json.optJSONArray("icons") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val src = o.optString("src").trim()
                    if (src.isBlank()) continue
                    val type = o.optString("type").lowercase()
                    val sizes = o.optString("sizes").trim().lowercase()
                    val isSvg = type.contains("svg") || src.substringBefore('?').endsWith(".svg", true)
                    val size = if (isSvg) 0 else sizes.split(Regex("\\s+"))
                        .mapNotNull { it.substringBefore('x').toIntOrNull() }
                        .maxOrNull() ?: 0
                    val maskable = o.optString("purpose").lowercase().split(Regex("\\s+")).contains("maskable")
                    add(PwaIcon(abs(src), size, maskable))
                }
            }
            PwaManifest(
                name = name,
                shortName = short,
                startUrl = start,
                scope = scope,
                display = display,
                orientation = json.optString("orientation").trim().lowercase(),
                themeColor = parseCssColor(json.optString("theme_color")),
                backgroundColor = parseCssColor(json.optString("background_color")),
                icons = icons,
                sourceUrl = pageUrl,
            )
        }.getOrNull()

        /** #rgb / #rrggbb / #rrggbbaa / rgb() / rgba()；其余写法（颜色名等）返回 null，界面退回默认色 */
        fun parseCssColor(raw: String): Int? {
            val s = raw.trim().lowercase()
            if (s.isEmpty()) return null
            if (s.startsWith("#")) {
                val h = s.substring(1)
                if (!h.all { it in '0'..'9' || it in 'a'..'f' }) return null
                return when (h.length) {
                    3 -> argb(255, h[0].digitToInt(16) * 17, h[1].digitToInt(16) * 17, h[2].digitToInt(16) * 17)
                    6 -> argb(255, h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
                    8 -> argb(
                        h.substring(6, 8).toInt(16), h.substring(0, 2).toInt(16),
                        h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16),
                    )
                    else -> null
                }
            }
            val m = Regex("""rgba?\(\s*(\d+)\s*[, ]\s*(\d+)\s*[, ]\s*(\d+)(?:\s*[,/]\s*([\d.]+%?))?\s*\)""").find(s) ?: return null
            val (r, g, b, a) = m.destructured
            val alpha = when {
                a.isEmpty() -> 255
                a.endsWith("%") -> ((a.dropLast(1).toFloatOrNull() ?: 100f) * 2.55f).toInt()
                else -> ((a.toFloatOrNull() ?: 1f) * 255f).toInt()
            }
            return argb(alpha, r.toInt(), g.toInt(), b.toInt())
        }

        private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
            (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }
}
