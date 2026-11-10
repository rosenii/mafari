package dev.dsh.miuixbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 油猴脚本元数据 */
data class UserscriptMeta(
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val runAt: String = "document-idle",
    val noFrames: Boolean = false,
    val matches: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val grants: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val icon: String = "",
    val namespace: String = "",
    val author: String = "",
    val homepage: String = "",
    val excludeMatches: List<String> = emptyList(),
    /** @resource name url */
    val resources: Map<String, String> = emptyMap(),
    val topLevelAwait: Boolean = false,
    /** 全部元数据键（小写）→ 值列表，原样提供给 GM_info.script.metadata */
    val raw: Map<String, List<String>> = emptyMap(),
    /** ==UserScript== 区块原文 */
    val metaBlock: String = "",
)

/**
 * Userscript 引擎：元数据解析 + 运行时拼装。
 * URL 匹配、run-at 调度与全部 GM API 都在 assets/mafari_gm_runtime.js 里完成，
 * 这样同一份脚本既能在主框架、也能在 iframe 里按各自的 URL 独立匹配。
 */
object UserscriptEngine {

    private const val ASSET = "mafari_gm_runtime.js"
    private const val M_META = "__MAFARI_META__"
    private const val M_FN = "__MAFARI_FN__"
    private const val M_CODE = "__MAFARI_CODE__"

    private val BLOCK = Regex("==UserScript==([\\s\\S]*?)==/UserScript==")
    private val LINE = Regex("^@([\\w:.-]+)(?:\\s+(.*))?$")

    @Volatile
    private var templateCache: String? = null

    private val WHITESPACE = Regex("\\s+")

    /**
     * 解析结果缓存（LRU 64 条，按脚本全文作 key）。
     * syncUserScripts / ScriptResources.missing / cachedCount / buildSource / 脚本列表每一项都会解析同一份脚本，
     * 且不少调用在主线程；脚本文本通常是同一个 String 实例，命中时只是一次引用比较。
     * UserscriptMeta 全是只读集合，共享安全。
     */
    private val parseCache = object : LinkedHashMap<String, UserscriptMeta>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UserscriptMeta>?): Boolean = size > 64
    }

    /** 解析 ==UserScript== 区块 */
    fun parse(code: String): UserscriptMeta {
        synchronized(parseCache) { parseCache[code]?.let { return it } }
        val meta = parseUncached(code)
        synchronized(parseCache) { parseCache[code] = meta }
        return meta
    }

    private fun parseUncached(code: String): UserscriptMeta {
        val block = BLOCK.find(code)?.groupValues?.get(1).orEmpty()
        val values = LinkedHashMap<String, MutableList<String>>()
        block.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("//").trim().removePrefix("/*").trim().removePrefix("*").trim()
            val m = LINE.find(line) ?: return@forEach
            values.getOrPut(m.groupValues[1].lowercase()) { mutableListOf() }
                .add(m.groupValues[2].trim())
        }
        fun first(key: String) = values[key]?.firstOrNull()?.trim().orEmpty()
        fun all(key: String) = values[key].orEmpty().filter { it.isNotBlank() }

        val resources = LinkedHashMap<String, String>()
        all("resource").forEach { entry ->
            val parts = entry.split(WHITESPACE, limit = 2)
            if (parts.size == 2) resources[parts[0]] = normalizeUrl(parts[1])
        }
        val grants = all("grant").flatMap { it.split(WHITESPACE) }.filter { it.isNotBlank() }
        return UserscriptMeta(
            name = first("name"),
            version = first("version"),
            description = first("description"),
            runAt = first("run-at").ifBlank { first("run_at") }
                .ifBlank { "document-idle" }.substringBefore(" ").replace('_', '-'),
            noFrames = values.containsKey("noframes"),
            matches = all("match"),
            includes = all("include"),
            excludes = all("exclude"),
            grants = grants,
            requires = all("require").map { normalizeUrl(it) },
            // 常见写法：@icon / @iconURL / @defaulticon / @icon64 / @icon64URL，取第一个非空的；并去掉两侧引号
            icon = first("icon").ifBlank { first("iconurl") }.ifBlank { first("defaulticon") }
                .ifBlank { first("icon64") }.ifBlank { first("icon64url") }
                .trim().trim('"', '\''),
            namespace = first("namespace"),
            author = first("author"),
            homepage = first("homepage").ifBlank { first("homepageurl") }.ifBlank { first("website") },
            excludeMatches = all("exclude-match"),
            resources = resources,
            topLevelAwait = values.containsKey("top-level-await"),
            raw = values,
            metaBlock = BLOCK.find(code)?.value.orEmpty(),
        )
    }

    /** 这些站点只是脚本托管平台，它们的 favicon 不能代表脚本本身 */
    private val GENERIC_HOSTS = setOf(
        "greasyfork.org", "sleazyfork.org", "openuserjs.org", "github.com", "gitlab.com",
        "raw.githubusercontent.com", "tampermonkey.net", "violentmonkey.github.io",
    )
    private val HOST_IN_PATTERN = Regex("^(?:[a-zA-Z*]+:)?//(?:\\*\\.)?([^/*?:\\s]+)")

    /**
     * @icon 加载失败（或没写）时，用来抓站点 favicon 的主机：
     * 先 @homepage，再第一个能确定主机名的 @match / @include。找不到返回空串。
     */
    fun fallbackIconHost(meta: UserscriptMeta): String {
        val candidates = sequenceOf(meta.homepage) + meta.matches.asSequence() + meta.includes.asSequence()
        for (raw in candidates) {
            val host = HOST_IN_PATTERN.find(raw.trim())?.groupValues?.get(1)
                ?.removePrefix("*.")?.lowercase().orEmpty()
            if (host.contains('.') && !host.contains('*') && host !in GENERIC_HOSTS) return host
        }
        return ""
    }

    /** 协议相对地址补全，并去掉 #sha256=… 之类的片段 */
    fun normalizeUrl(url: String): String {
        val u = url.trim().substringBefore('#')
        return if (u.startsWith("//")) "https:$u" else u
    }

    /** 用户在界面上手动填写、且与脚本头不同的匹配规则（逗号分隔）→ 覆盖脚本自带规则 */
    internal fun userMatchOverride(script: UserScript): List<String> {
        val manual = script.match.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (manual.isEmpty()) return emptyList()
        val derived = parseUserScript(script.code).second
        return if (script.match == derived) emptyList() else manual
    }

    private fun template(context: Context): String {
        templateCache?.let { return it }
        val text = context.applicationContext.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        templateCache = text
        return text
    }

    private fun jsonArray(list: List<String>) = JSONArray().apply { list.forEach { put(it) } }

    /** 生成注入用的完整源码；URL 与 run-at 由运行时在各自框架内判断 */
    fun buildSource(context: Context, script: UserScript): String {
        val meta = parse(script.code)
        val rawJson = JSONObject().apply {
            meta.raw.forEach { (k, v) -> put(k, jsonArray(v)) }
        }
        val resourcesJson = JSONObject().apply {
            meta.resources.forEach { (name, url) ->
                val entry = JSONObject().put("url", url)
                ScriptResources.bytes(context, url)?.let { bytes ->
                    if (bytes.size <= 3 * 1024 * 1024) {
                        entry.put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                        entry.put("mime", ScriptResources.mime(context, url))
                    }
                }
                put(name, entry)
            }
        }
        val runAt = meta.runAt.takeIf {
            it in setOf("document-start", "document-body", "document-end", "document-idle", "context-menu")
        } ?: "document-idle"
        val metaJson = JSONObject().apply {
            put("id", script.id)
            put("name", script.name.ifBlank { meta.name }.ifBlank { "userscript" })
            put("namespace", meta.namespace)
            put("version", meta.version)
            put("description", meta.description)
            put("author", meta.author)
            put("homepage", meta.homepage)
            put("icon", meta.icon)
            put("runAt", runAt)
            put("noframes", meta.noFrames)
            put("match", jsonArray(meta.matches))
            put("include", jsonArray(meta.includes))
            put("exclude", jsonArray(meta.excludes))
            put("excludeMatch", jsonArray(meta.excludeMatches))
            put("userMatch", jsonArray(userMatchOverride(script)))
            put("grant", jsonArray(meta.grants))
            put("require", jsonArray(meta.requires))
            put("resources", resourcesJson)
            put("meta", rawJson)
            put("metaBlock", meta.metaBlock)
        }.toString()

        val requireCode = meta.requires.joinToString("\n;\n") { url ->
            ScriptResources.text(context, url)
                ?: "console.warn(" + JSONObject.quote("[Mafari] @require 未能加载: $url") + ");"
        }
        val code = buildString {
            append(requireCode)
            if (requireCode.isNotEmpty()) append("\n;\n")
            append(script.code)
            append("\n")
        }
        return fill(template(context), metaJson, if (meta.topLevelAwait) "async function" else "function", code)
    }

    /** 依次替换三个占位符各一次，不会二次扫描已填入的脚本内容 */
    private fun fill(template: String, meta: String, fn: String, code: String): String {
        val sb = StringBuilder(template.length + meta.length + code.length + 64)
        var cursor = 0
        for ((marker, value) in listOf(M_META to meta, M_FN to fn, M_CODE to code)) {
            val idx = template.indexOf(marker, cursor)
            if (idx < 0) return template
            sb.append(template, cursor, idx).append(value)
            cursor = idx + marker.length
        }
        sb.append(template, cursor, template.length)
        return sb.toString()
    }
}
