package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ManifestException(message: String) : Exception(message)

/**
 * manifest.json → ExtensionSpec。抹平 MV2 / MV3 / Firefox 的写法差异，并顺带生成兼容性问题清单（针对 WebView 适配器）。
 * `id` 由调用方决定（CRX 公钥、gecko id 或随机生成），不在这里推导。
 * 传入 [root]（拓展包根目录）时会按设备语言展开 manifest 里的 `__MSG_xxx__`；不传则原样保留。
 */
object ManifestParser {

    /** 当前运行时完全没有的 manifest 顶层字段 */
    private val UNSUPPORTED_KEYS = listOf(
        "chrome_url_overrides", "commands", "devtools_page", "sidebar_action", "side_panel",
        "omnibox", "externally_connectable", "declarative_net_request", "theme", "chrome_settings_overrides",
    )

    fun parse(json: String, id: String, root: File? = null): ExtensionSpec {
        val m = try {
            JSONObject(json.removePrefix("\uFEFF"))
        } catch (e: Exception) {
            throw ManifestException("manifest.json 不是合法 JSON：${e.message}")
        }
        if (root != null) I18nMessages.localizeManifest(m, root, id)
        val mv = when (m.optInt("manifest_version", 0)) {
            2 -> ManifestVersion.MV2
            3 -> ManifestVersion.MV3
            else -> throw ManifestException("不支持的 manifest_version（只支持 2 和 3）")
        }
        val name = m.optString("name").trim().ifEmpty { throw ManifestException("缺少 name") }
        val version = m.optString("version").trim().ifEmpty { throw ManifestException("缺少 version") }

        val compat = mutableListOf<CompatIssue>()

        // —— 权限：MV2 的 permissions 里 API 与 host 混写，这里统一拆开 ——
        val api = linkedSetOf<String>()
        val optApi = linkedSetOf<String>()
        val hosts = mutableListOf<MatchPattern>()
        val optHosts = mutableListOf<MatchPattern>()
        fun addHost(s: String, sink: MutableList<MatchPattern>) {
            val p = MatchPattern.parse(s)
            if (p != null) sink.add(p) else compat.add(CompatIssue("invalid match pattern: $s", SupportLevel.NONE))
        }
        fun split(list: List<String>, apiSink: MutableSet<String>, hostSink: MutableList<MatchPattern>) {
            for (s in list) if (MatchPattern.looksLikeHostPermission(s)) addHost(s, hostSink) else apiSink.add(s)
        }
        split(m.strings("permissions"), api, hosts)
        split(m.strings("optional_permissions"), optApi, optHosts)
        m.strings("host_permissions").forEach { addHost(it, hosts) }
        m.strings("optional_host_permissions").forEach { addHost(it, optHosts) }
        for (p in api) {
            val level = ApiSupport.forWebView(p)
            if (level != SupportLevel.FULL) compat.add(CompatIssue("permission:$p", level, "WebView 适配器"))
        }

        // —— content scripts ——
        val scripts = mutableListOf<ContentScriptSpec>()
        m.optJSONArray("content_scripts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val matches = o.strings("matches").mapNotNull { s ->
                    MatchPattern.parse(s).also { if (it == null) compat.add(CompatIssue("invalid match pattern: $s", SupportLevel.NONE)) }
                }
                if (matches.isEmpty()) continue
                val exclude = o.strings("exclude_matches").mapNotNull { MatchPattern.parse(it) }
                if (o.has("include_globs") || o.has("exclude_globs")) {
                    compat.add(CompatIssue("content_scripts.*_globs", SupportLevel.NONE))
                }
                scripts.add(
                    ContentScriptSpec(
                        matches = matches,
                        excludeMatches = exclude,
                        js = o.strings("js"),
                        css = o.strings("css"),
                        runAt = when (o.optString("run_at")) {
                            "document_start" -> RunAt.DOCUMENT_START
                            "document_end" -> RunAt.DOCUMENT_END
                            else -> RunAt.DOCUMENT_IDLE
                        },
                        allFrames = o.optBoolean("all_frames", false),
                        matchAboutBlank = o.optBoolean("match_about_blank", false),
                        world = if (o.optString("world").equals("MAIN", ignoreCase = true)) ScriptWorld.MAIN else ScriptWorld.ISOLATED,
                    )
                )
            }
        }

        // —— background ——
        val background = m.optJSONObject("background")?.let { b ->
            val sw = b.optString("service_worker").ifEmpty { null }
            if (sw != null) compat.add(CompatIssue("background.service_worker", SupportLevel.PARTIAL, "用隐藏页面模拟"))
            BackgroundSpec(
                scripts = b.strings("scripts"),
                page = b.optString("page").ifEmpty { null },
                serviceWorker = sw,
                persistent = b.optBoolean("persistent", mv == ManifestVersion.MV2),
            )
        }

        // —— action（MV3 action / MV2 browser_action / page_action）——
        val actionObj = m.optJSONObject("action") ?: m.optJSONObject("browser_action") ?: m.optJSONObject("page_action")
        val action = actionObj?.let {
            ActionSpec(
                title = it.optString("default_title").ifEmpty { null },
                popup = it.optString("default_popup").ifEmpty { null },
                icons = parseIcons(it.opt("default_icon")),
            )
        }

        val options = m.optJSONObject("options_ui")?.optString("page")?.ifEmpty { null }
            ?: m.optString("options_page").ifEmpty { null }

        // —— web_accessible_resources：MV2 为字符串数组，MV3 为 {resources, matches} 数组 ——
        val war = mutableListOf<WebAccessibleSpec>()
        m.optJSONArray("web_accessible_resources")?.let { arr ->
            val legacy = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                when (val e = arr.opt(i)) {
                    is String -> legacy.add(e)
                    is JSONObject -> war.add(
                        WebAccessibleSpec(e.strings("resources"), e.strings("matches").mapNotNull { MatchPattern.parse(it) })
                    )
                }
            }
            if (legacy.isNotEmpty()) war.add(WebAccessibleSpec(legacy, emptyList()))
        }

        for (k in UNSUPPORTED_KEYS) if (m.has(k)) compat.add(CompatIssue("manifest:$k", SupportLevel.NONE))

        val gecko = (m.optJSONObject("browser_specific_settings") ?: m.optJSONObject("applications"))
            ?.optJSONObject("gecko")?.optString("id")?.ifEmpty { null }

        return ExtensionSpec(
            id = id,
            name = name,
            version = version,
            description = m.optString("description"),
            manifestVersion = mv,
            apiPermissions = api,
            optionalApiPermissions = optApi,
            hostPermissions = hosts,
            optionalHostPermissions = optHosts,
            contentScripts = scripts,
            background = background,
            action = action,
            optionsPage = options,
            webAccessibleResources = war,
            icons = parseIcons(m.optJSONObject("icons")),
            geckoId = gecko,
            compat = compat,
        )
    }

    private fun JSONObject.strings(key: String): List<String> {
        val a: JSONArray = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
    }

    /** default_icon / icons：字符串视为不分尺寸（key 0），对象按尺寸取 */
    private fun parseIcons(any: Any?): Map<Int, String> = when (any) {
        is JSONObject -> any.keys().asSequence()
            .mapNotNull { k -> k.toIntOrNull()?.let { it to any.optString(k) } }.toMap()
        is String -> mapOf(0 to any)
        else -> emptyMap()
    }
}
