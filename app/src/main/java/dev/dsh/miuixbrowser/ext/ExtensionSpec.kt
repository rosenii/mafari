package dev.dsh.miuixbrowser.ext

/** 与引擎无关的拓展描述：ManifestParser 把 MV2 / MV3 / Firefox 差异归一化到这里。 */
enum class ManifestVersion { MV2, MV3 }

enum class RunAt { DOCUMENT_START, DOCUMENT_END, DOCUMENT_IDLE }

/** content script 运行的 JS 世界：ISOLATED 为默认；MAIN 与页面共享全局（WebView 上两者都只能尽力模拟，见路线文档） */
enum class ScriptWorld { ISOLATED, MAIN }

enum class EngineKind { WEBVIEW, GECKO }

data class ContentScriptSpec(
    val matches: List<MatchPattern>,
    val excludeMatches: List<MatchPattern>,
    val js: List<String>,
    val css: List<String>,
    val runAt: RunAt,
    val allFrames: Boolean,
    val matchAboutBlank: Boolean,
    val world: ScriptWorld,
) {
    fun appliesTo(url: String) =
        MatchPattern.anyMatches(matches, url) && !MatchPattern.anyMatches(excludeMatches, url)
}

data class BackgroundSpec(
    val scripts: List<String>,
    val page: String?,
    val serviceWorker: String?,
    val persistent: Boolean,
)

data class ActionSpec(
    val title: String?,
    val popup: String?,
    val icons: Map<Int, String>,
)

data class WebAccessibleSpec(
    val resources: List<String>,
    /** 空表示 MV2 写法：对所有页面开放 */
    val matches: List<MatchPattern>,
)

data class ExtensionSpec(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val manifestVersion: ManifestVersion,
    /** 非 host 的 API 权限，如 storage、tabs、scripting */
    val apiPermissions: Set<String>,
    val optionalApiPermissions: Set<String>,
    val hostPermissions: List<MatchPattern>,
    val optionalHostPermissions: List<MatchPattern>,
    val contentScripts: List<ContentScriptSpec>,
    val background: BackgroundSpec?,
    val action: ActionSpec?,
    val optionsPage: String?,
    val webAccessibleResources: List<WebAccessibleSpec>,
    val icons: Map<Int, String>,
    /** browser_specific_settings.gecko.id（或旧的 applications.gecko.id） */
    val geckoId: String?,
    /** 解析时发现的、当前运行时不支持或只部分支持的特性，用于安装时的兼容性报告 */
    val compat: List<CompatIssue>,
)

enum class SupportLevel { FULL, PARTIAL, PLANNED, NONE }

data class CompatIssue(val feature: String, val level: SupportLevel, val note: String = "")
