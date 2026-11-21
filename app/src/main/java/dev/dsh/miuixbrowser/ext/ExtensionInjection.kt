package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 一个拓展要装到 WebView 上的东西：
 * - [bridgeName]：addWebMessageListener 的 JS 对象名，每个拓展一个；
 * - [originRules]：listener 与脚本共用的 origin 规则（只到 scheme://host[:port]，路径匹配在脚本里做）；
 * - [script]：**旧路径**（页面世界）的脚本全文，WebView 不支持隔离世界时用 addDocumentStartJavaScript 注入，
 *   桥对象在页面世界里短暂存在、由脚本捕获后删除；
 * - [worldName]：隔离世界名（`WebViewCompat.getExecutionWorld`）。桥与 ISOLATED content script 都在这个世界里，页面世界看不到；
 * - [isolatedScript] / [isolatedOriginRules]：只含 `world` 为 ISOLATED（默认）的条目，带桥与 browser / chrome；没有这类条目时为 null；
 * - [mainScript] / [mainOriginRules]：只含 `world: "MAIN"` 的条目，在页面世界里跑，没有桥（与 Chrome 一致，MAIN 世界拿不到拓展 API）。
 */
class InjectionPlan(
    val extId: String,
    val bridgeName: String,
    val originRules: Set<String>,
    val script: String,
    /** 读不到、被拒绝的文件等，供兼容性报告 / 日志 */
    val warnings: List<String>,
    val worldName: String = "",
    val isolatedScript: String? = null,
    val isolatedOriginRules: Set<String> = emptySet(),
    val mainScript: String? = null,
    val mainOriginRules: Set<String> = emptySet(),
)

object ExtensionInjection {
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** 每个拓展一个隔离世界；名字由 extId 哈希得出（只含字母数字下划线），与桥名同源，不暴露 extId */
    fun worldNameFor(extId: String): String = "mw_" + bridgeNameFor(extId).removePrefix("__mext_")

    fun bridgeNameFor(extId: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(extId.toByteArray(Charsets.UTF_8))
        return "__mext_" + h.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * [shim] 是 assets/mafari_ext_shim.js 全文；[readText] 按拓展包内的相对路径读文本（读不到返回 null）。
     * 没有任何可注入的 content script 时返回 null。
     */
    fun build(
        ext: InstalledExtension,
        shim: String,
        readText: (String) -> String?,
        dynamicContentScripts: List<ContentScriptSpec> = emptyList(),
        tabId: Int? = null,
    ): InjectionPlan? {
        val spec = ext.spec
        val warnings = mutableListOf<String>()
        val manifest = try {
            JSONObject(readText("manifest.json")?.removePrefix("\uFEFF") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val loc = Localization.of(ext, manifest, readText, warnings)
        val entries = StringBuilder("[")
        val isoEntries = StringBuilder("[")
        val mainEntries = StringBuilder("[")
        val rules = linkedSetOf<String>()
        val isoRules = linkedSetOf<String>()
        val mainRules = linkedSetOf<String>()
        var count = 0
        var isoCount = 0
        var mainCount = 0

        // Dynamic registrations are durable configuration, not durable permission grants.
        // Re-evaluate their host access every time a WebView injection plan is built.
        val authorizedDynamicScripts = dynamicContentScripts.mapNotNull { script ->
            val allowedMatches = script.matches.filter { requested ->
                ext.grantedHostPermissions.any { granted -> granted.covers(requested) }
            }
            if (allowedMatches.isEmpty()) null else script.copy(matches = allowedMatches)
        }

        for (cs in spec.contentScripts + authorizedDynamicScripts) {
            val origin = cs.matches.flatMap { it.toOriginRules() }
            if (origin.isEmpty()) {
                warnings.add("content script 的 matches 不含 http(s)，已跳过：${cs.matches.joinToString()}")
                continue
            }
            // CSS 里的 __MSG_名字__ 与 Chrome 一样在注入前展开
            val css = cs.css.mapNotNull { path ->
                readText(path)?.let { loc.localizeText(it) } ?: null.also { warnings.add("读不到 css：$path") }
            }
            val js = cs.js.mapNotNull { path -> readText(path) ?: null.also { warnings.add("读不到 js：$path") } }
            if (css.isEmpty() && js.isEmpty()) continue
            rules.addAll(origin)

            val entry = StringBuilder()
            entry.append("{\"m\":").append(descriptors(cs.matches))
            entry.append(",\"x\":").append(descriptors(cs.excludeMatches))
            entry.append(",\"all\":").append(cs.allFrames)
            entry.append(",\"at\":").append(JSONObject.quote(runAtName(cs.runAt)))
            entry.append(",\"css\":[").append(css.joinToString(",") { JSONObject.quote(it) }).append(']')
            // 同一条 content_scripts 的多个 js 文件拼进同一个函数（用 `;` 隔开）：Chrome 里它们共享同一个全局，
            // 常见写法是 ["utils.js","main.js"]，main.js 直接用 utils.js 顶层声明的函数 / 变量，分开包会让后面的文件找不到。
            // 代价：前一个文件抛错会连累后面的；变量只在这条 content script 内可见，不会泄漏到页面全局。
            val body = if (js.isEmpty()) "" else "function(browser,chrome){\n" + js.joinToString("\n;\n") + "\n}"
            entry.append(",\"js\":[").append(body).append("]}")
            val entryJson = entry.toString()

            // 旧路径：所有条目一起进同一个页面世界脚本
            if (count++ > 0) entries.append(',')
            entries.append(entryJson)
            // 新路径：按 manifest 的 world 分流
            if (cs.world == ScriptWorld.MAIN) {
                if (mainCount++ > 0) mainEntries.append(',')
                mainEntries.append(entryJson)
                mainRules.addAll(origin)
            } else {
                if (isoCount++ > 0) isoEntries.append(',')
                isoEntries.append(entryJson)
                isoRules.addAll(origin)
            }
        }
        entries.append(']')
        isoEntries.append(']')
        mainEntries.append(']')
        if (count == 0) return null

        fun effectiveOf(r: Set<String>): Set<String> = if ("*" in r) setOf("*") else r
        val effective = effectiveOf(rules)
        val bridge = bridgeNameFor(spec.id)
        val cfg = JSONObject()
            .put("extId", spec.id)
            .put("bridge", bridge)
            .put("origin", ExtensionOrigins.originFor(spec.id))
            .put("manifest", loc.localizedManifest())
        tabId?.let { cfg.put("tabId", it) }
        loc.injectable(warnings)?.let { cfg.put("i18n", it) }
        // 隔离世界：桥只注册在该世界里，页面世界既看不到桥也发不出消息；browser / chrome 也只挂在这个世界的全局上
        val isolatedScript = if (isoCount > 0) {
            val isoCfg = JSONObject(cfg.toString()).put("isolatedWorld", true)
            assemble(shim, isoCfg, isoEntries.toString())
        } else {
            null
        }
        // MAIN 世界：脚本全文对页面可见，所以 cfg 里只放一个开关，不带 extId / manifest / i18n
        val mainScript = if (mainCount > 0) {
            assemble(shim, JSONObject().put("mainWorld", true), mainEntries.toString())
        } else {
            null
        }
        return InjectionPlan(
            spec.id, bridge, effective, assemble(shim, cfg, entries.toString()), warnings,
            worldName = worldNameFor(spec.id),
            isolatedScript = isolatedScript,
            isolatedOriginRules = effectiveOf(isoRules),
            mainScript = mainScript,
            mainOriginRules = effectiveOf(mainRules),
        )
    }

    /**
     * 拓展自己的页面（后台页 / popup / options）的注入计划：没有 content script，只有 browser / chrome 全局与握手。
     * origin 规则就是这个拓展的虚拟 origin，所以别的页面里不会出现桥。
     */
    fun buildPage(ext: InstalledExtension, shim: String, background: Boolean = false, offscreen: Boolean = false): InjectionPlan {
        val id = ext.spec.id
        val bridge = bridgeNameFor(id)
        val origin = ExtensionOrigins.originFor(id)
        val read = fileReader(ext.rootDir)
        val manifest = try {
            JSONObject(File(ext.rootDir, "manifest.json").readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        } catch (_: Exception) {
            JSONObject()
        }
        val warnings = mutableListOf<String>()
        val loc = Localization.of(ext, manifest, read, warnings)
        val cfg = JSONObject()
            .put("extId", id)
            .put("bridge", bridge)
            .put("origin", origin)
            .put("manifest", loc.localizedManifest())
            .put("page", true)
        if (background) cfg.put("background", true) // extension.getBackgroundPage() 只有后台页自己能返回 window
        if (offscreen) cfg.put("offscreen", true)
        loc.injectable(warnings)?.let { cfg.put("i18n", it) }
        return InjectionPlan(id, bridge, setOf(origin), assemble(shim, cfg, "[]"), warnings)
    }

    /**
     * 一个拓展在当前设备语言下的本地化上下文：完整消息表（展开 manifest / CSS 的 `__MSG_`）
     * 与注入页面用的那份（有大小上限）。
     */
    private class Localization(
        private val extId: String,
        private val manifest: JSONObject,
        private val locale: String,
        private val result: I18nMessages.Result,
    ) {
        private val msgs: JSONObject? get() = result.json?.optJSONObject("msgs")

        fun localizeText(text: String) = I18nMessages.localize(text, msgs, extId, locale)

        /** getManifest 与 Chrome 一样返回已本地化的 manifest（原地修改 [manifest]） */
        fun localizedManifest(): JSONObject = I18nMessages.localizeJson(manifest, msgs, extId, locale)

        fun injectable(warnings: MutableList<String>): JSONObject? = I18nMessages.forInjection(result, warnings)

        companion object {
            fun of(
                ext: InstalledExtension,
                manifest: JSONObject,
                readText: (String) -> String?,
                warnings: MutableList<String>,
            ): Localization {
                val locale = I18nMessages.chromeLocale(Locale.getDefault())
                val r = I18nMessages.build(
                    ext.rootDir, manifest.optString("default_locale").ifEmpty { null }, locale, readText,
                )
                warnings.addAll(r.warnings)
                return Localization(ext.spec.id, manifest, locale, r)
            }
        }
    }

    /** 与 tools/ext-shim-test/run.js 里的 assemble() 必须保持同样的拼法 */
    internal fun assemble(shim: String, cfg: JSONObject, entriesSource: String): String =
        "(" + shim + ")(" + cfg.toString() + "," + entriesSource + ");"

    private fun descriptors(list: List<MatchPattern>): String {
        val a = JSONArray()
        list.forEach { a.put(MatchPatternJs.descriptor(it)) }
        return a.toString()
    }

    private fun runAtName(r: RunAt) = when (r) {
        RunAt.DOCUMENT_START -> "document_start"
        RunAt.DOCUMENT_END -> "document_end"
        RunAt.DOCUMENT_IDLE -> "document_idle"
    }

    /**
     * 只读拓展目录之内的文件：路径先规范化（拒绝 `..`、绝对路径），再核对规范路径仍在根目录下，
     * 超过 8MB 的文件不读。
     */
    fun fileReader(root: File): (String) -> String? {
        val canonicalRoot = try { root.canonicalFile } catch (_: Exception) { return { null } }
        val prefix = canonicalRoot.path + File.separator
        return { path ->
            try {
                val rel = PackageUnpacker.safeRelativePath(path)
                val f = rel?.let { File(canonicalRoot, it).canonicalFile }
                if (f != null && f.path.startsWith(prefix) && f.isFile && f.length() <= MAX_FILE_BYTES) {
                    f.readText(Charsets.UTF_8)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
