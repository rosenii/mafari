package dev.dsh.miuixbrowser.browser

private val HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")

/** 「更新日志」小节标题（gecko 预发布正文里是 `### 更新日志`） */
private val CHANGELOG_TITLE = Regex(
    """更新日志|更新内容|更新说明|变更日志|更新记录|changelog|release\s*notes|what'?s\s*new""",
    RegexOption.IGNORE_CASE,
)

/** 日志之后的非日志内容：安装包说明、GitHub 自动生成的 What's Changed / New Contributors / Full Changelog */
private val STOP_LINE = Regex(
    """^\s*(\*\*|__)?\s*(安装包说明|full\s*changelog|完整更新日志)""",
    RegexOption.IGNORE_CASE,
)
private val STOP_HEADING = Regex("""what'?s\s*changed|new\s*contributors|安装包说明""", RegexOption.IGNORE_CASE)

/** gecko 预发布正文的开头：「来自 `gecko` 分支的…构建」+ 提交 / 分支信息，不是日志 */
private val CI_PREAMBLE = Regex("""^来自\s*`?[\w./-]+`?\s*分支""")

/** 没有日志时发布流程写入的占位文案 */
private val PLACEHOLDER = Regex("""^见\s*CHANGELOG""")

private fun headingOf(line: String): MatchResult? = HEADING.matchEntire(line)

private fun isStop(line: String): Boolean {
    if (STOP_LINE.containsMatchIn(line)) return true
    val h = headingOf(line) ?: return false
    return STOP_HEADING.containsMatchIn(h.groupValues[2])
}

/**
 * 从 GitHub Release 正文里只取「更新日志」，其余（版本标题、安装包说明、提交 / 分支信息、
 * 自动生成的 What's Changed 等）都丢掉。返回的仍是 Markdown，交给 MarkdownText 渲染；没有日志返回空串。
 *
 * 两种正文（见 release.yml「Prepare release notes」）：
 * - 预发布：有 `### 更新日志` 小节，取该小节（到下一个同级或更高级标题 / 安装包说明为止）；没有该小节就是没有日志；
 * - 正式发布：标题行 `## vX.Y.Z` 之后直接就是 CHANGELOG 小节，截到「安装包说明」/ 自动生成区之前。
 */
fun extractChangelog(raw: String): String {
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').lines()
    val titled = lines.indexOfFirst { l ->
        headingOf(l)?.let { CHANGELOG_TITLE.containsMatchIn(it.groupValues[2]) } == true
    }
    val body: List<String> = if (titled >= 0) {
        val level = headingOf(lines[titled])!!.groupValues[1].length
        lines.drop(titled + 1).takeWhile { l ->
            !isStop(l) && (headingOf(l)?.let { it.groupValues[1].length > level } ?: true)
        }
    } else {
        // 无「更新日志」小节：去掉开头的版本标题行，整段当日志（但 CI 预发布的开头说明不是日志）
        val rest = lines.dropWhile { it.isBlank() }.let { l ->
            if (l.firstOrNull()?.let { headingOf(it) } != null) l.drop(1) else l
        }.dropWhile { it.isBlank() }
        val first = rest.firstOrNull()?.trim().orEmpty()
        if (CI_PREAMBLE.containsMatchIn(first) || PLACEHOLDER.containsMatchIn(first)) emptyList()
        else rest.takeWhile { !isStop(it) }
    }
    return body
        .joinToString("\n")
        .trim()
        .trimEnd('-', ' ', '\n')       // 分隔线 `---`
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
