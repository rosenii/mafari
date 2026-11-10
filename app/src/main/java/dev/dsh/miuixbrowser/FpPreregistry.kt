package dev.dsh.miuixbrowser

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条来源规则，语法与 addDocumentStartJavaScript 的 allowedOriginRules 一致，且只允许两种形态：
 * - `scheme://host[:port]`            精确来源
 * - `scheme:` + 双斜杠 + `*.host[:port]`   仅匹配子域名，**不含**裸域（裸域要另写一条）；
 *   （这里不直接写成连续字符，因为 Kotlin 的块注释会嵌套，双斜杠后紧跟星号会被当成注释开头）
 *
 * 不接受：裸 `*`、中间 / 末尾通配符、公共后缀上的通配符（`*.com`、`*.co.uk`）、IP 上的通配符、非 http(s)。
 * 解析后的 [text] 是规范形式（host 小写 punycode，默认端口省略），同时用于原生注册和脚本内的运行时校验。
 */
class OriginRule private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
    val wildcard: Boolean,
) {
    val text: String = buildString {
        append(scheme).append("://")
        if (wildcard) append("*.")
        append(host)
        if (port != -1) append(':').append(port)
    }

    /** 规则所属的可注册域名（用于 seed 派生与按站点分组）。 */
    val site: String get() = AdMatch.registrable(host)

    fun matches(origin: WebOrigin): Boolean {
        if (origin.scheme != scheme || origin.port != port) return false
        return if (wildcard) origin.host.endsWith(".$host") else origin.host == host
    }

    override fun equals(other: Any?) = other is OriginRule && other.text == text
    override fun hashCode() = text.hashCode()
    override fun toString() = text

    companion object {
        fun parse(raw: String?): OriginRule? {
            val s = raw?.trim()?.lowercase() ?: return null
            val sep = s.indexOf("://")
            if (sep < 1) return null
            val scheme = s.substring(0, sep)
            if (scheme != "http" && scheme != "https") return null
            var body = s.substring(sep + 3)
            if (body.isEmpty() || body.any { it == '/' || it == '?' || it == '#' || it == '@' || it.isWhitespace() }) return null
            val wildcard = body.startsWith("*.")
            if (wildcard) body = body.substring(2)
            if ('*' in body) return null
            // 借 WebOrigin 做规范化（punycode、默认端口、端口范围）
            val o = WebOrigin.parse("$scheme://$body") ?: return null
            if (wildcard) {
                val h = o.host
                if (h.startsWith("[") || h.all { it.isDigit() || it == '.' }) return null
                if (!AdMatch.isRegistrableOrDeeper(h)) return null
            }
            return OriginRule(o.scheme, o.host, o.port, wildcard)
        }

    }
}

/**
 * 预注册清单（产品 / 运营侧维护，assets/mafari_fp_prereg.json），两类：
 *
 * - **embeds**：「嵌入方 → 被嵌入的跨源框架」。frames 里的来源会在 WebView 初始化后、首次导航前批量注册，
 *   并把 embedders 作为「允许的跨源祖先」内联进脚本；祖先链上出现清单之外的站点时脚本自行退出。
 *   这是覆盖跨源 iframe 的唯一机制，清单之外的跨源 iframe 一律不保护。
 * - **redirects**：「站点 → 导航到它时可能被重定向到的落点」（OIDC / 社交登录的 IdP 等）。
 *   导航到 site 之前，targets 会一并注册。
 *
 * 清单格式见 docs/FINGERPRINT.md §9。解析是宽松的：非法条目丢弃，整体 JSON 损坏则视为空清单。
 */
object FpPreregistry {

    class Embed(val embedders: List<OriginRule>, val frames: List<OriginRule>)
    class Redirect(val site: String, val targets: List<OriginRule>)
    class Data(val embeds: List<Embed>, val redirects: List<Redirect>) {
        companion object { val EMPTY = Data(emptyList(), emptyList()) }
    }

    private const val MAX_ENTRIES = 64
    private const val MAX_RULES_PER_LIST = 16

    @Volatile private var cached: Data? = null

    fun data(): Data {
        cached?.let { return it }
        val text = AdBlock.fingerprintPreregJson()
        val d = parse(text)
        if (text.isNotBlank()) cached = d // 读取失败得到的空清单不缓存
        return d
    }

    /** 测试 / 清单热更新时使用。 */
    fun invalidate() { cached = null }

    /** 与 Android 无关的解析逻辑，便于单独验证。 */
    internal fun parse(text: String): Data {
        if (text.isBlank()) return Data.EMPTY
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return Data.EMPTY
        val embeds = ArrayList<Embed>()
        root.optJSONArray("embeds")?.let { arr ->
            for (i in 0 until minOf(arr.length(), MAX_ENTRIES)) {
                val o = arr.optJSONObject(i) ?: continue
                val embedders = rules(o.optJSONArray("embedders"))
                val frames = rules(o.optJSONArray("frames"))
                if (embedders.isNotEmpty() && frames.isNotEmpty()) embeds.add(Embed(embedders, frames))
            }
        }
        val redirects = ArrayList<Redirect>()
        root.optJSONArray("redirects")?.let { arr ->
            for (i in 0 until minOf(arr.length(), MAX_ENTRIES)) {
                val o = arr.optJSONObject(i) ?: continue
                val site = o.optString("site").trim().lowercase()
                // site 必须本身就是可注册域名（example.com），不接受子域名 / 公共后缀
                if (site.isEmpty() || '.' !in site || site != AdMatch.registrable(site) ||
                    !AdMatch.isRegistrableOrDeeper(site)
                ) continue
                val targets = rules(o.optJSONArray("targets"))
                if (targets.isNotEmpty()) redirects.add(Redirect(site, targets))
            }
        }
        return Data(embeds, redirects)
    }

    private fun rules(arr: JSONArray?): List<OriginRule> {
        if (arr == null) return emptyList()
        val out = LinkedHashSet<OriginRule>()
        for (i in 0 until minOf(arr.length(), MAX_RULES_PER_LIST)) {
            OriginRule.parse(arr.optString(i, null))?.let { out.add(it) }
        }
        return out.toList()
    }
}
