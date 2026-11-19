package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 [MatchPattern] 变成注入脚本里 `matchDesc()` 能读的描述对象：
 * `{"s":[scheme…],"h":主机|null,"sub":是否含子域,"p":端口|null,"r":路径正则源码|null}`。
 * 语义必须与 [MatchPattern.matches] 一致，两边共用 `app/src/test/resources/ext/match_cases.json` 做对照
 * （Kotlin 侧见 MatchPatternJsTest，JS 侧见 tools/ext-shim-test/run.js）。
 */
object MatchPatternJs {
    private const val REGEX_META = "\\^$.|?+()[]{}/"

    fun descriptor(p: MatchPattern): JSONObject {
        val o = JSONObject()
        o.put("s", JSONArray(p.schemes.toList()))
        if (p.matchesAllUrls) {
            return o.put("h", JSONObject.NULL).put("sub", false).put("p", JSONObject.NULL).put("r", JSONObject.NULL)
        }
        val raw = p.raw
        val sep = raw.indexOf("://")
        val rest = raw.substring(sep + 3)
        val slash = rest.indexOf('/')
        var hostPart = rest.substring(0, slash)
        val path = rest.substring(slash)

        var port: String? = null
        val colon = hostPart.lastIndexOf(':')
        if (colon >= 0) {
            port = hostPart.substring(colon + 1)
            hostPart = hostPart.substring(0, colon)
        }
        val sub = hostPart.startsWith("*.")
        val host: String? = when {
            hostPart.isEmpty() || hostPart == "*" -> null
            sub -> hostPart.substring(2).lowercase()
            else -> hostPart.lowercase()
        }
        o.put("h", host ?: JSONObject.NULL)
        o.put("sub", sub)
        o.put("p", if (port == null || port == "*") JSONObject.NULL else port)
        o.put("r", if (path == "/*") JSONObject.NULL else pathRegex(path))
        return o
    }

    /** `*` → `.*`，其余字符按字面匹配；整串锚定 */
    internal fun pathRegex(path: String): String {
        val sb = StringBuilder("^")
        for (c in path) {
            when {
                c == '*' -> sb.append(".*")
                REGEX_META.indexOf(c) >= 0 -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return sb.append('$').toString()
    }
}
