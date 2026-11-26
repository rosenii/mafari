/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.util

/**
 * Markdown → HTML（纯逻辑，无 Android 依赖，有单测）：本地 .md 文件用它渲染成可读的网页（见 [LocalHtml]）。
 *
 * 支持：标题（ATX / Setext）、段落与硬换行、粗体 / 斜体 / 删除线、行内代码、围栏代码块（不含缩进式）、引用、
 * 有序 / 无序 / 嵌套 / 任务列表、表格（含对齐）、分隔线、链接（行内 / 引用式 / 自动链接）、图片、HTML 注释。
 *
 * 安全：文档里的原始 HTML 一律转义显示（不执行、不渲染）；链接只放行 http / https / mailto / tel / #锚点，
 * 图片只放行 http / https / data:image；页面本身还会加 CSP 禁止脚本（见 [page]）。
 */
object MarkdownHtml {

    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})[ \t]*([^`\s]*).*$""")
    private val ATX = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
    private val HR = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
    private val BULLET = Regex("""^( {0,3})([-*+])([ \t]+)(.*)$""")
    private val ORDERED = Regex("""^( {0,3})(\d{1,9})([.)])([ \t]+)(.*)$""")
    private val SETEXT = Regex("""^ {0,3}(=+|-+)[ \t]*$""")
    private val TABLE_SEP = Regex("""^ {0,3}\|?[ \t]*:?-+:?[ \t]*(\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")
    private val REFDEF = Regex("""^ {0,3}\[(?!\^)([^\]]+)\]:[ \t]*<?([^\s>]+)>?(?:[ \t]+.*)?$""")
    private val TASK = Regex("""^\[([ xX])\][ \t]+(.*)$""")

    /** 正文转 HTML 片段（不含 html / body 外壳） */
    fun toHtml(md: String): String {
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val refs = HashMap<String, String>()
        val kept = ArrayList<String>(lines.size)
        var fence: String? = null
        for (l in lines) {
            val fc = fence
            if (fc == null) {
                val f = FENCE.matchEntire(l)
                if (f != null) { fence = f.groupValues[1]; kept.add(l); continue }
                val d = REFDEF.matchEntire(l)
                if (d != null) { refs[d.groupValues[1].lowercase()] = d.groupValues[2]; continue }
                kept.add(l)
            } else {
                val t = l.trim()
                if (t.length >= fc.length && t.all { it == fc[0] }) fence = null
                kept.add(l)
            }
        }
        return Renderer(refs).blocks(kept)
    }

    /** 整页 HTML：跟随深浅色、窄屏友好；[title] 作页面标题；禁止脚本。 */
    fun page(title: String, md: String, truncated: Boolean): String {
        val note = if (truncated) "<p><em>—— 文件较大，仅显示前 8 MB ——</em></p>" else ""
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta name=\"color-scheme\" content=\"light dark\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"script-src 'none'\">" +
            "<title>${escape(title)}</title><style>$CSS</style></head><body><article>" +
            toHtml(md) + note + "</article></body></html>"
    }

    internal fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun attr(s: String): String = s.replace("\"", "&quot;")

    private fun safeLink(u: String): String? {
        val lower = u.replace("&amp;", "&").lowercase()
        val ok = lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("#")
        return if (ok) attr(u) else null
    }

    private fun safeImage(u: String): String? {
        val lower = u.replace("&amp;", "&").lowercase()
        val ok = lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("data:image/png") || lower.startsWith("data:image/jpeg") ||
            lower.startsWith("data:image/gif") || lower.startsWith("data:image/webp")
        return if (ok) attr(u) else null
    }

    private fun indentOf(l: String): Int {
        var n = 0
        for (c in l) {
            if (c == ' ') n++ else if (c == '\t') n += 4 else break
        }
        return n
    }

    private fun stripIndent(l: String, n: Int): String {
        var cut = 0
        var w = 0
        while (cut < l.length && w < n) {
            val c = l[cut]
            if (c == ' ') w++ else if (c == '\t') w += 4 else break
            cut++
        }
        return l.substring(cut)
    }

    private class Renderer(private val refs: Map<String, String>) {
        private val slugs = HashMap<String, Int>()

        private fun slug(raw: String): String {
            val base = raw.lowercase().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
                .trim().replace(' ', '-').ifEmpty { "section" }
            val n = slugs[base] ?: 0
            slugs[base] = n + 1
            return if (n == 0) base else "$base-$n"
        }

        private fun startsBlock(l: String, next: String?): Boolean {
            if (l.isBlank()) return true
            if (FENCE.matches(l) || ATX.matches(l) || HR.matches(l)) return true
            if (indentOf(l) < 4 && l.trimStart().startsWith(">")) return true
            if (BULLET.matches(l)) return true
            val o = ORDERED.matchEntire(l)
            if (o != null && o.groupValues[2] == "1") return true
            if (l.contains('|') && next != null && next.contains('-') && TABLE_SEP.matches(next)) return true
            return false
        }

        fun blocks(lines: List<String>): String {
            val out = StringBuilder()
            val n = lines.size
            var i = 0
            while (i < n) {
                val line = lines[i]
                if (line.isBlank()) { i++; continue }

                // HTML 注释
                if (line.trim().startsWith("<!--")) {
                    while (i < n && !lines[i].contains("-->")) i++
                    i++
                    continue
                }

                // 围栏代码块
                val f = FENCE.matchEntire(line)
                if (f != null) {
                    val fence = f.groupValues[1]
                    val lang = f.groupValues[2].filter { it.isLetterOrDigit() || it == '+' || it == '-' || it == '_' || it == '#' }
                    val body = ArrayList<String>()
                    i++
                    while (i < n) {
                        val t = lines[i].trim()
                        if (t.length >= fence.length && t.all { it == fence[0] }) break
                        body.add(lines[i])
                        i++
                    }
                    i++ // 收尾围栏
                    val cls = if (lang.isEmpty()) "" else " class=\"language-$lang\""
                    out.append("<pre><code").append(cls).append('>')
                        .append(escape(body.joinToString("\n"))).append("</code></pre>\n")
                    continue
                }

                // 标题
                val h = ATX.matchEntire(line)
                if (h != null) {
                    val level = h.groupValues[1].length
                    val text = h.groupValues[2]
                    out.append("<h$level id=\"${attr(slug(text))}\">").append(inline(text)).append("</h$level>\n")
                    i++
                    continue
                }

                // 分隔线
                if (HR.matches(line)) { out.append("<hr>\n"); i++; continue }

                // 引用
                if (indentOf(line) < 4 && line.trimStart().startsWith(">")) {
                    val inner = ArrayList<String>()
                    while (i < n && indentOf(lines[i]) < 4 && lines[i].trimStart().startsWith(">")) {
                        inner.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                        i++
                    }
                    out.append("<blockquote>\n").append(blocks(inner)).append("</blockquote>\n")
                    continue
                }

                // 表格
                if (line.contains('|') && i + 1 < n && lines[i + 1].contains('-') && TABLE_SEP.matches(lines[i + 1])) {
                    val head = splitRow(line)
                    val aligns = splitRow(lines[i + 1])
                    if (head.size == aligns.size) {
                        out.append("<table><thead><tr>")
                        for (c in head.indices) out.append(cell("th", head[c], aligns[c]))
                        out.append("</tr></thead><tbody>\n")
                        i += 2
                        while (i < n && lines[i].isNotBlank() && lines[i].contains('|')) {
                            val row = splitRow(lines[i])
                            out.append("<tr>")
                            for (c in head.indices) out.append(cell("td", row.getOrElse(c) { "" }, aligns[c]))
                            out.append("</tr>\n")
                            i++
                        }
                        out.append("</tbody></table>\n")
                        continue
                    }
                }

                // 列表
                if (BULLET.matches(line) || ORDERED.matches(line)) {
                    i = list(lines, i, out)
                    continue
                }

                // 段落（含 Setext 标题）
                val para = ArrayList<String>()
                para.add(line.trimStart())
                i++
                var setext = 0
                while (i < n) {
                    val l = lines[i]
                    if (l.isBlank()) break
                    val s = SETEXT.matchEntire(l)
                    if (s != null) { setext = if (s.groupValues[1][0] == '=') 1 else 2; i++; break }
                    if (startsBlock(l, lines.getOrNull(i + 1))) break
                    para.add(l.trimStart())
                    i++
                }
                if (setext != 0) {
                    val text = para.joinToString(" ")
                    out.append("<h$setext id=\"${attr(slug(text))}\">").append(inline(text)).append("</h$setext>\n")
                } else {
                    out.append("<p>").append(inline(para.joinToString("\n").trimEnd())).append("</p>\n")
                }
            }
            return out.toString()
        }

        /** 从 [start] 起解析一整个列表，写进 [out]，返回列表结束后的下一行下标 */
        private fun list(lines: List<String>, start: Int, out: StringBuilder): Int {
            val n = lines.size
            val ordered = ORDERED.matches(lines[start])
            val first = ORDERED.matchEntire(lines[start])
            val startNo = first?.groupValues?.get(2)?.toIntOrNull() ?: 1
            val items = ArrayList<List<String>>()
            var loose = false
            var i = start
            while (i < n) {
                val line = lines[i]
                val b = BULLET.matchEntire(line)
                val o = ORDERED.matchEntire(line)
                if (ordered && o == null) break
                if (!ordered && b == null) break
                val indent: Int
                val marker: Int
                val spaces: Int
                val content: String
                if (b != null) {
                    indent = b.groupValues[1].length; marker = 1; spaces = b.groupValues[3].length; content = b.groupValues[4]
                } else {
                    val m = o!!
                    indent = m.groupValues[1].length; marker = m.groupValues[2].length + 1; spaces = m.groupValues[4].length; content = m.groupValues[5]
                }
                val offset = indent + marker + (if (spaces > 4) 1 else spaces)
                val body = ArrayList<String>()
                body.add(content)
                i++
                while (i < n) {
                    val l = lines[i]
                    if (l.isBlank()) {
                        var k = i
                        while (k < n && lines[k].isBlank()) k++
                        if (k < n && indentOf(lines[k]) >= offset) {
                            for (x in i until k) body.add("")
                            i = k
                            loose = true
                            continue
                        }
                        break
                    }
                    if (indentOf(l) >= offset) {
                        body.add(stripIndent(l, offset))
                        i++
                        continue
                    }
                    // 懒续行：紧跟上一行、又不是新块的开头
                    if (body.last().isNotBlank() && !startsBlock(l, lines.getOrNull(i + 1)) && !BULLET.matches(l) && !ORDERED.matches(l)) {
                        body.add(l.trimStart())
                        i++
                        continue
                    }
                    break
                }
                items.add(body)
                // 下一项之前的空行：有空行说明是松散列表
                var k = i
                while (k < n && lines[k].isBlank()) k++
                if (k < n && k > i && (if (ordered) ORDERED.matches(lines[k]) else BULLET.matches(lines[k]))) {
                    loose = true
                    i = k
                } else if (k < n && k == i) {
                    // 紧邻：交给循环头判断是不是同类下一项
                } else {
                    break
                }
            }
            val tag = if (ordered) "ol" else "ul"
            out.append('<').append(tag)
            if (ordered && startNo != 1) out.append(" start=\"").append(startNo).append('"')
            out.append(">\n")
            for (body in items) {
                var lines0 = body
                var cls = ""
                var box = ""
                val t = TASK.matchEntire(body[0])
                if (t != null) {
                    cls = " class=\"task\""
                    box = "<input type=\"checkbox\" disabled" + (if (t.groupValues[1] != " ") " checked" else "") + "> "
                    lines0 = listOf(t.groupValues[2]) + body.drop(1)
                }
                var html = blocks(lines0)
                if (!loose) html = html.replace("<p>", "").replace("</p>\n", "\n").replace("</p>", "")
                out.append("<li").append(cls).append('>').append(box).append(html.trimEnd()).append("</li>\n")
            }
            out.append("</").append(tag).append(">\n")
            return i
        }

        private fun splitRow(row: String): List<String> {
            var s = row.trim()
            if (s.startsWith("|")) s = s.substring(1)
            if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
            val cells = ArrayList<String>()
            val cur = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length && s[i + 1] == '|') { cur.append('|'); i += 2; continue }
                if (c == '|') { cells.add(cur.toString().trim()); cur.setLength(0) } else cur.append(c)
                i++
            }
            cells.add(cur.toString().trim())
            return cells
        }

        private fun cell(tag: String, text: String, align: String): String {
            val a = align.trim()
            val style = when {
                a.startsWith(":") && a.endsWith(":") -> " style=\"text-align:center\""
                a.endsWith(":") -> " style=\"text-align:right\""
                a.startsWith(":") -> " style=\"text-align:left\""
                else -> ""
            }
            return "<$tag$style>${inline(text)}</$tag>"
        }

        // ------------------------------------------------------------------ 行内

        private fun emphasis(t: String): String {
            var s = t
            s = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*""").replace(s, "<strong>$1</strong>")
            s = Regex("""(?<![A-Za-z0-9_])__(?=\S)(.+?)(?<=\S)__(?![A-Za-z0-9_])""").replace(s, "<strong>$1</strong>")
            s = Regex("""\*(?=[^\s*])(.+?)(?<=[^\s*])\*""").replace(s, "<em>$1</em>")
            s = Regex("""(?<![A-Za-z0-9_])_(?=[^\s_])(.+?)(?<=[^\s_])_(?![A-Za-z0-9_])""").replace(s, "<em>$1</em>")
            s = Regex("""~~(?=\S)(.+?)(?<=\S)~~""").replace(s, "<del>$1</del>")
            return s
        }

        fun inline(src: String): String {
            val slots = ArrayList<String>()
            fun hold(html: String): String {
                slots.add(html)
                return "\u0000" + (slots.size - 1) + "\u0000"
            }
            fun unwrap(u: String): String = u.removePrefix("&lt;").removeSuffix("&gt;")

            var s = src
            // 1. 行内代码
            s = Regex("""(`+)(.+?)(?<!`)\1(?!`)""", RegexOption.DOT_MATCHES_ALL).replace(s) { m ->
                hold("<code>" + escape(m.groupValues[2].trim()) + "</code>")
            }
            // 2. 反斜杠转义
            s = Regex("""\\([\\`*_{}\[\]()#+\-.!|~<>])""").replace(s) { m -> hold(escape(m.groupValues[1])) }
            // 3. 其余 HTML 一律转义
            s = escape(s)
            s = s.replace("\\\n", hold("<br>\n"))
            // 4. 图片
            s = Regex("""!\[([^\]]*)\]\(\s*([^)\s]+)(?:\s+"[^"]*")?\s*\)""").replace(s) { m ->
                val alt = m.groupValues[1]
                val url = safeImage(unwrap(m.groupValues[2]))
                if (url == null) hold(alt) else hold("<img src=\"$url\" alt=\"${attr(alt)}\" loading=\"lazy\">")
            }
            // 5. 链接：行内 [文字](地址) 与引用式 [文字][标识]
            s = Regex("""\[([^\]]+)\]\(\s*([^)\s]+)(?:\s+"[^"]*")?\s*\)""").replace(s) { m ->
                val href = safeLink(unwrap(m.groupValues[2]))
                if (href == null) m.groupValues[1]
                else hold("<a href=\"$href\" rel=\"noopener noreferrer\">" + emphasis(m.groupValues[1]) + "</a>")
            }
            s = Regex("""\[([^\]]+)\]\[([^\]]*)\]""").replace(s) { m ->
                val key = m.groupValues[2].ifEmpty { m.groupValues[1] }.lowercase()
                val href = refs[key]?.let { safeLink(escape(it)) }
                if (href == null) m.value
                else hold("<a href=\"$href\" rel=\"noopener noreferrer\">" + emphasis(m.groupValues[1]) + "</a>")
            }
            // 6. 自动链接 <http://…> 与裸网址
            s = Regex("""&lt;((?:https?://|mailto:)[^\s]+?)&gt;""").replace(s) { m ->
                val href = safeLink(m.groupValues[1])
                if (href == null) m.value else hold("<a href=\"$href\" rel=\"noopener noreferrer\">${m.groupValues[1]}</a>")
            }
            s = Regex("""(?<![\w"=/])(https?://[^\s<\u0000]+)""").replace(s) { m ->
                var url = m.groupValues[1]
                var tail = ""
                while (url.isNotEmpty() && url.last() in ".,;:!?'\")") { tail = url.last() + tail; url = url.dropLast(1) }
                val href = safeLink(url)
                if (href == null) m.value else hold("<a href=\"$href\" rel=\"noopener noreferrer\">$url</a>") + tail
            }
            // 7. 强调
            s = emphasis(s)
            // 8. 硬换行（行尾两个空格）
            s = s.replace(Regex(""" {2,}\n"""), "<br>\n")
            // 9. 还原占位（占位里可能还嵌着占位）
            val ph = Regex("""\u0000(\d+)\u0000""")
            while (s.indexOf('\u0000') >= 0) {
                val next = ph.replace(s) { m -> slots[m.groupValues[1].toInt()] }
                if (next == s) break
                s = next
            }
            return s
        }
    }

    private const val CSS = """
:root{color-scheme:light dark}
body{margin:0;padding:16px 16px 56px;font:16px/1.7 system-ui,-apple-system,'Segoe UI',sans-serif;overflow-wrap:anywhere}
article{max-width:860px;margin:0 auto}
h1,h2,h3,h4,h5,h6{line-height:1.3;margin:1.4em 0 .6em}
h1{font-size:1.8em}h2{font-size:1.45em}h3{font-size:1.2em}h4{font-size:1.05em}h5,h6{font-size:1em}
h1,h2{padding-bottom:.3em;border-bottom:1px solid rgba(128,128,128,.3)}
article>:first-child{margin-top:0}
p,ul,ol,pre,blockquote,table{margin:0 0 1em}
ul,ol{padding-left:1.6em}li>ul,li>ol{margin:.2em 0}
li.task{list-style:none;margin-left:-1.3em}
code{font:.9em ui-monospace,Menlo,Consolas,monospace;background:rgba(128,128,128,.16);padding:.15em .35em;border-radius:5px}
pre{background:rgba(128,128,128,.14);padding:12px;border-radius:8px;overflow-x:auto}
pre code{background:none;padding:0;font-size:13.5px;line-height:1.55;overflow-wrap:normal;white-space:pre}
blockquote{padding:.1em 1em;border-left:4px solid rgba(128,128,128,.45);opacity:.85}
blockquote>:last-child{margin-bottom:0}
table{border-collapse:collapse;display:block;overflow-x:auto;max-width:100%}
th,td{border:1px solid rgba(128,128,128,.4);padding:6px 12px}
th{background:rgba(128,128,128,.12)}
img{max-width:100%;height:auto}
hr{border:0;border-top:1px solid rgba(128,128,128,.4);margin:1.6em 0}
a{color:#2f80ed}
@media (prefers-color-scheme:dark){a{color:#6cb0ff}}
"""
}
