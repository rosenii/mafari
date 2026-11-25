/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * `chrome.i18n.getMessage` 的数据源：把 `_locales/<语言>/messages.json` 按设备语言合并成一张表，
 * 随注入配置（CFG.i18n）交给 JS 垫片。getMessage 是同步 API，所以不走原生 RPC，由垫片本地查表、展开占位符。
 *
 * 合并规则（与 Chrome 一致：缺哪条才回退哪条，而不是整份语言包回退）：
 * `default_locale` → 设备语言的「语言部分」（zh）→ 设备语言完整形式（zh_CN），后者覆盖前者。
 * 目录名按不区分大小写、`-` 与 `_` 等价来匹配。设备语言既没有精确目录、也没有同语言目录时，
 * 退而用同语言的第一个地区目录（排序后），比如设备是 `en`、拓展只有 `en_US`。
 *
 * 表里的键已转小写（Chrome 的消息名不区分大小写），占位符名同理。注入页面的那份超过 [MAX_TABLE_CHARS] 就放弃
 * （见 [forInjection]）：它会跟着每个匹配页面的注入脚本走，太大会拖慢页面加载。
 * 另外本对象负责展开 manifest 与 CSS 里的 `__MSG_名字__`（[localize] / [localizeManifest]）。
 */
object I18nMessages {
    const val MAX_TABLE_CHARS = 256 * 1024
    private const val MAX_MESSAGES_FILE_CHARS = 4 * 1024 * 1024

    /** 目录名白名单：语言 + 至多两段地区 / 变体。同时挡掉路径分隔符与 `..` */
    private val LOCALE_DIR = Regex("^[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8}){0,2}$")

    class Result(val json: JSONObject?, val warnings: List<String>)

    /** 设备语言 → Chrome 写法：`zh_CN` / `en`（只取语言与地区，不要脚本段） */
    fun chromeLocale(l: Locale): String {
        val lang = l.language.ifEmpty { "en" }
        return if (l.country.isEmpty()) lang else lang + "_" + l.country
    }

    /**
     * [readText] 按拓展包内相对路径读文本（用 [ExtensionInjection.fileReader]，已防越界 / 过大）。
     * 没有 `_locales` 目录、或一条消息都没有时 json 为 null。
     */
    fun build(root: File, defaultLocale: String?, deviceLocale: String, readText: (String) -> String?): Result {
        val dirs = File(root, "_locales").list()?.filter { LOCALE_DIR.matches(it) }?.sorted().orEmpty()
        if (dirs.isEmpty()) return Result(null, emptyList())

        val byKey = LinkedHashMap<String, String>()
        for (d in dirs) if (!byKey.containsKey(normalize(d))) byKey[normalize(d)] = d

        val device = normalize(deviceLocale)
        val lang = device.substringBefore('_')
        val chain = ArrayList<String>() // 低 → 高优先级
        defaultLocale?.let { normalize(it) }?.let { byKey[it] }?.let(chain::add)
        val langDir = byKey[lang] ?: if (byKey[device] == null) {
            byKey.entries.firstOrNull { it.key.startsWith(lang + "_") }?.value
        } else {
            null
        }
        langDir?.let { if (it !in chain) chain.add(it) }
        byKey[device]?.let { if (it !in chain) chain.add(it) }
        if (chain.isEmpty()) return Result(null, emptyList())

        val warnings = ArrayList<String>()
        val msgs = JSONObject()
        for (dir in chain) {
            val path = "_locales/$dir/messages.json"
            val text = readText(path)
            if (text == null) {
                warnings.add("读不到语言包：$path")
                continue
            }
            if (text.length > MAX_MESSAGES_FILE_CHARS) {
                warnings.add("语言包过大，已跳过：$path")
                continue
            }
            val src = try {
                JSONObject(text.removePrefix("\uFEFF"))
            } catch (_: Exception) {
                warnings.add("语言包不是合法 JSON：$path")
                continue
            }
            for (name in src.keys()) {
                val entry = src.optJSONObject(name) ?: continue
                val message = entry.opt("message") as? String ?: continue
                msgs.put(name.lowercase(), compact(message, entry.optJSONObject("placeholders")))
            }
        }
        if (msgs.length() == 0) return Result(null, warnings)
        return Result(JSONObject().put("locale", deviceLocale).put("msgs", msgs), warnings)
    }

    /**
     * 注入页面用的版本：表太大就放弃（返回 null 并写警告）。注意只限制注入；
     * manifest / CSS 的 `__MSG_` 展开走完整的表，不受这个限制。
     */
    fun forInjection(r: Result, warnings: MutableList<String>): JSONObject? {
        val json = r.json ?: return null
        if (json.toString().length <= MAX_TABLE_CHARS) return json
        warnings.add("i18n 消息表超过 ${MAX_TABLE_CHARS / 1024}KB，已放弃注入，getMessage 将返回空串")
        return null
    }

    // ------------------------------------------------------------------ __MSG_xxx__ 展开

    private val MSG = Regex("__MSG_([A-Za-z0-9_@]+?)__")
    private val TOKEN = Regex("""\$(?:(\$)|([A-Za-z_@][A-Za-z0-9_@]*)\$|([1-9]))""")
    private val DIGIT = Regex("""\$[1-9]""")
    private val RTL = Regex("""^(ar|he|iw|fa|ur)(_|$)""", RegexOption.IGNORE_CASE)

    /**
     * 把文本里的 `__MSG_名字__` 换成消息（Chrome 对 manifest 与 CSS 文件都这么做）。
     * 消息里的 `$1`-`$9` 没有可代入的值，按空串处理；`$name$` 取占位符内容；`$$` 是一个 `$`。
     * 找不到的消息原样保留，方便一眼看出问题。[msgs] 为 [Result.json] 里的 `msgs`，可为 null。
     */
    fun localize(text: String, msgs: JSONObject?, extId: String, locale: String): String {
        if (!text.contains("__MSG_")) return text
        return MSG.replace(text) { mr ->
            val key = mr.groupValues[1].lowercase()
            if (key.startsWith("@@")) {
                builtin(key, extId, locale) ?: mr.value
            } else {
                msgs?.optJSONObject(key)?.let { expand(it) } ?: mr.value
            }
        }
    }

    private fun expand(entry: JSONObject): String {
        val p = entry.optJSONObject("p")
        return TOKEN.replace(entry.optString("m")) { mr ->
            when {
                mr.groups[1] != null -> "\$"
                mr.groups[3] != null -> ""
                else -> DIGIT.replace(p?.optString(mr.groupValues[2].lowercase(), "") ?: "", "")
            }
        }
    }

    private fun builtin(key: String, extId: String, locale: String): String? {
        val rtl = RTL.containsMatchIn(locale)
        return when (key) {
            "@@extension_id" -> extId
            "@@ui_locale" -> locale
            "@@bidi_dir" -> if (rtl) "rtl" else "ltr"
            "@@bidi_reversed_dir" -> if (rtl) "ltr" else "rtl"
            "@@bidi_start_edge" -> if (rtl) "right" else "left"
            "@@bidi_end_edge" -> if (rtl) "left" else "right"
            else -> null
        }
    }

    /** 递归展开 JSON 里所有字符串值（原地修改并返回同一个对象）。键名不动 */
    fun localizeJson(v: JSONObject, msgs: JSONObject?, extId: String, locale: String): JSONObject {
        for (k in v.keys().asSequence().toList()) v.put(k, localizeValue(v.opt(k), msgs, extId, locale))
        return v
    }

    private fun localizeValue(v: Any?, msgs: JSONObject?, extId: String, locale: String): Any? = when (v) {
        is String -> localize(v, msgs, extId, locale)
        is JSONObject -> localizeJson(v, msgs, extId, locale)
        is JSONArray -> {
            for (i in 0 until v.length()) v.put(i, localizeValue(v.opt(i), msgs, extId, locale))
            v
        }
        else -> v
    }

    /**
     * 按 [deviceLocale] 展开 manifest 里的 `__MSG_`（原地修改并返回）。manifest 里没有占位符时什么都不读。
     * 取 `default_locale` 作为 [deviceLocale] 可得到与设备语言无关的稳定名称（生成本地 ID 用）。
     */
    fun localizeManifest(
        m: JSONObject,
        root: File,
        extId: String,
        deviceLocale: String = chromeLocale(Locale.getDefault()),
    ): JSONObject {
        if (!m.toString().contains("__MSG_")) return m
        val msgs = build(
            root, m.optString("default_locale").ifEmpty { null }, deviceLocale, ExtensionInjection.fileReader(root),
        ).json?.optJSONObject("msgs")
        return localizeJson(m, msgs, extId, deviceLocale)
    }

    /** `{"m": 文本, "p": {占位符名(小写): content}}`；没有占位符时省掉 p */
    private fun compact(message: String, placeholders: JSONObject?): JSONObject {
        val o = JSONObject().put("m", message)
        if (placeholders == null) return o
        val p = JSONObject()
        for (name in placeholders.keys()) {
            val content = placeholders.optJSONObject(name)?.opt("content") as? String ?: continue
            p.put(name.lowercase(), content)
        }
        if (p.length() > 0) o.put("p", p)
        return o
    }

    private fun normalize(s: String) = s.replace('-', '_').lowercase()
}
