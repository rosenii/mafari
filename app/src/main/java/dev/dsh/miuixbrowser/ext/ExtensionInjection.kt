package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 一个拓展要装到 WebView 上的东西：
 * - [bridgeName]：addWebMessageListener 的 JS 对象名，每个拓展一个，注入脚本捕获后会把它从页面全局里删掉；
 * - [originRules]：listener 与脚本共用的 origin 规则（只到 scheme://host[:port]，路径匹配在脚本里做）；
 * - [script]：addDocumentStartJavaScript 的脚本全文。
 */
class InjectionPlan(
    val extId: String,
    val bridgeName: String,
    val originRules: Set<String>,
    val script: String,
    /** 读不到、被拒绝的文件等，供兼容性报告 / 日志 */
    val warnings: List<String>,
)

object ExtensionInjection {
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    fun bridgeNameFor(extId: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(extId.toByteArray(Charsets.UTF_8))
        return "__mext_" + h.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * [shim] 是 assets/mafari_ext_shim.js 全文；[readText] 按拓展包内的相对路径读文本（读不到返回 null）。
     * 没有任何可注入的 content script 时返回 null。
     */
    fun build(ext: InstalledExtension, shim: String, readText: (String) -> String?): InjectionPlan? {
        val spec = ext.spec
        val warnings = mutableListOf<String>()
        val entries = StringBuilder("[")
        val rules = linkedSetOf<String>()
        var count = 0

        for (cs in spec.contentScripts) {
            val origin = cs.matches.flatMap { it.toOriginRules() }
            if (origin.isEmpty()) {
                warnings.add("content script 的 matches 不含 http(s)，已跳过：${cs.matches.joinToString()}")
                continue
            }
            val css = cs.css.mapNotNull { path -> readText(path) ?: null.also { warnings.add("读不到 css：$path") } }
            val js = cs.js.mapNotNull { path -> readText(path) ?: null.also { warnings.add("读不到 js：$path") } }
            if (css.isEmpty() && js.isEmpty()) continue
            rules.addAll(origin)

            if (count++ > 0) entries.append(',')
            entries.append("{\"m\":").append(descriptors(cs.matches))
            entries.append(",\"x\":").append(descriptors(cs.excludeMatches))
            entries.append(",\"all\":").append(cs.allFrames)
            entries.append(",\"at\":").append(JSONObject.quote(runAtName(cs.runAt)))
            entries.append(",\"css\":[").append(css.joinToString(",") { JSONObject.quote(it) }).append(']')
            // 同一条 content_scripts 的多个 js 文件拼进同一个函数（用 `;` 隔开）：Chrome 里它们共享同一个全局，
            // 常见写法是 ["utils.js","main.js"]，main.js 直接用 utils.js 顶层声明的函数 / 变量，分开包会让后面的文件找不到。
            // 代价：前一个文件抛错会连累后面的；变量只在这条 content script 内可见，不会泄漏到页面全局。
            val body = if (js.isEmpty()) "" else "function(browser,chrome){\n" + js.joinToString("\n;\n") + "\n}"
            entries.append(",\"js\":[").append(body).append("]}")
        }
        entries.append(']')
        if (count == 0) return null

        val effective = if ("*" in rules) setOf("*") else rules
        val bridge = bridgeNameFor(spec.id)
        val manifest = try {
            JSONObject(readText("manifest.json")?.removePrefix("\uFEFF") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val cfg = JSONObject()
            .put("extId", spec.id)
            .put("bridge", bridge)
            .put("origin", ExtensionOrigins.originFor(spec.id))
            .put("manifest", manifest)
        return InjectionPlan(spec.id, bridge, effective, assemble(shim, cfg, entries.toString()), warnings)
    }

    /**
     * 拓展自己的页面（后台页 / popup / options）的注入计划：没有 content script，只有 browser / chrome 全局与握手。
     * origin 规则就是这个拓展的虚拟 origin，所以别的页面里不会出现桥。
     */
    fun buildPage(ext: InstalledExtension, shim: String): InjectionPlan {
        val id = ext.spec.id
        val bridge = bridgeNameFor(id)
        val origin = ExtensionOrigins.originFor(id)
        val manifest = try {
            JSONObject(File(ext.rootDir, "manifest.json").readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        } catch (_: Exception) {
            JSONObject()
        }
        val cfg = JSONObject()
            .put("extId", id)
            .put("bridge", bridge)
            .put("origin", origin)
            .put("manifest", manifest)
            .put("page", true)
        return InjectionPlan(id, bridge, setOf(origin), assemble(shim, cfg, "[]"), emptyList())
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
