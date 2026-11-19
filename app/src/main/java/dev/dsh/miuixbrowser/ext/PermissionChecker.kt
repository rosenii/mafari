package dev.dsh.miuixbrowser.ext

enum class SenderKind { CONTENT_SCRIPT, EXTENSION_PAGE }

/**
 * 消息来源。[origin] 与 [isMainFrame] 必须取自传输层的回调（WebMessageListener 的 sourceOrigin / isMainFrame），
 * 绝不能取自消息体：消息体由页面侧脚本构造，不可信。
 */
class Sender(
    val kind: SenderKind,
    val origin: String,
    val isMainFrame: Boolean,
    val tabId: Int? = null,
    /** 发送方所在上下文（拓展页面）的 ID，由原生在建立连接时分配；消息投递时用来排除「发给自己」。content script 为 null */
    val contextId: String? = null,
)

/**
 * 每条消息的三项校验：拓展存在且启用 → 来源 origin 合法 → API 在权限内。
 * 默认拒绝：不在 [REQUIRED] 表里的 API 一律 UNKNOWN_API。
 */
object PermissionChecker {

    /** API 前缀 → 需要的权限（任一即可）。空集 = 无需权限。按最长前缀匹配 */
    private val REQUIRED: Map<String, Set<String>> = mapOf(
        "runtime." to emptySet(),
        "permissions." to emptySet(),
        "i18n." to emptySet(),
        "extension." to emptySet(),
        "action." to emptySet(),
        "browserAction." to emptySet(),
        "storage." to setOf("storage"),
        // tabs.query / create / update / sendMessage 本身不要权限；url、title 等敏感字段由 tabs 实现按 canSeeTabDetails 裁剪
        "tabs." to emptySet(),
        "scripting." to setOf("scripting"),
        "contextMenus." to setOf("contextMenus", "menus"),
        "menus." to setOf("menus", "contextMenus"),
        "alarms." to setOf("alarms"),
        "notifications." to setOf("notifications"),
        "bookmarks." to setOf("bookmarks"),
        "downloads." to setOf("downloads"),
        "clipboard.readText" to setOf("clipboardRead"),
        "clipboard.writeText" to setOf("clipboardWrite"),
        "cookies." to setOf("cookies"),
        "webNavigation." to setOf("webNavigation"),
        "webRequest." to setOf("webRequest"),
        "declarativeNetRequest." to setOf("declarativeNetRequest", "declarativeNetRequestWithHostAccess"),
    )

    /** content script 能直接调的 API 前缀；其余只有拓展自己的页面能调 */
    private val CONTENT_SCRIPT_ALLOWED = listOf("runtime.", "storage.", "i18n.", "extension.")

    /** storage.session 默认只对受信上下文开放（与 Chrome 默认的 TRUSTED_CONTEXTS 一致） */
    private const val SESSION_PREFIX = "storage.session."

    fun requiredFor(api: String): Set<String>? =
        REQUIRED.entries.filter { api.startsWith(it.key) }.maxByOrNull { it.key.length }?.value

    /** 通过返回，不通过抛 [ApiException] */
    fun require(ext: InstalledExtension, api: String, sender: Sender) {
        if (!ext.enabled) throw ApiException(ErrorCodes.DISABLED, "拓展已停用")

        when (sender.kind) {
            SenderKind.EXTENSION_PAGE ->
                if (!ExtensionOrigins.isExtensionOrigin(sender.origin, ext.spec.id)) {
                    throw ApiException(ErrorCodes.DENIED_ORIGIN, "来源与拓展页面不符")
                }
            SenderKind.CONTENT_SCRIPT -> {
                if (!originAllowedForContentScripts(ext, sender.origin)) {
                    throw ApiException(ErrorCodes.DENIED_ORIGIN, "拓展无权在该来源上运行")
                }
                if (CONTENT_SCRIPT_ALLOWED.none { api.startsWith(it) } || api.startsWith(SESSION_PREFIX)) {
                    throw ApiException(ErrorCodes.DENIED_PERMISSION, "content script 不能调用 $api")
                }
            }
        }

        val needed = requiredFor(api) ?: throw ApiException(ErrorCodes.UNKNOWN_API, "未知的 API：$api")
        if (needed.isNotEmpty() && needed.intersect(effectivePermissions(ext)).isEmpty()) {
            throw ApiException(ErrorCodes.DENIED_PERMISSION, "缺少权限：${needed.joinToString("/")}")
        }
    }

    /** 用户已授予、且 manifest 里声明过（必选或可选）的 API 权限 */
    fun effectivePermissions(ext: InstalledExtension): Set<String> =
        ext.grantedApiPermissions.intersect(ext.spec.apiPermissions + ext.spec.optionalApiPermissions)

    /** 拓展是否有权访问某个具体 URL（scripting.executeScript、tabs 敏感字段、cookies 等实现里用） */
    fun hasHostAccess(ext: InstalledExtension, url: String): Boolean =
        MatchPattern.anyMatches(ext.grantedHostPermissions, url)

    /** tabs 实现里：有 "tabs" 权限，或对该 URL 有 host 权限，才能把 url / title 回给拓展 */
    fun canSeeTabDetails(ext: InstalledExtension, url: String?): Boolean =
        "tabs" in effectivePermissions(ext) || (url != null && hasHostAccess(ext, url))

    /**
     * content script 的来源是否合法：origin 必须落在它的某个 content_scripts.matches，或用户授予的 host 权限里
     * （后者覆盖 scripting.registerContentScripts / executeScript 注入的情况）。
     * sourceOrigin 不含路径，所以这里只比 scheme / host / port，路径匹配留给注入时与脚本运行时。
     */
    fun originAllowedForContentScripts(ext: InstalledExtension, origin: String): Boolean {
        if (!ORIGIN.matches(origin)) return false
        val patterns = ext.spec.contentScripts.flatMap { it.matches } + ext.grantedHostPermissions
        return patterns.any { originOnly(it)?.matches("$origin/") == true }
    }

    private val ORIGIN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]+$")

    /** 去掉路径部分，只保留 scheme://host[:port]，路径一律放行（注意：KDoc 里不能写斜杠加星号，Kotlin 的块注释可嵌套） */
    private fun originOnly(p: MatchPattern): MatchPattern? {
        if (p.matchesAllUrls) return p
        val raw = p.raw
        val i = raw.indexOf("://")
        if (i < 0) return null
        val rest = raw.substring(i + 3)
        val slash = rest.indexOf('/')
        val hostPort = if (slash < 0) rest else rest.substring(0, slash)
        return MatchPattern.parse(raw.substring(0, i) + "://" + hostPort + "/*")
    }
}
