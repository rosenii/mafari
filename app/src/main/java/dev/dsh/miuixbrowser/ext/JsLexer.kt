package dev.dsh.miuixbrowser.ext

/**
 * 只为定位 ES 模块语句（import / export / import() / import.meta）服务的 JS 词法扫描器，不是完整解析器。
 * 字符串、注释、正则字面量、模板字符串（含 `${}` 里的表达式）都会被正确跳过或展开，
 * 所以字符串 / 注释里出现 "import(" 不会被当成语句。
 *
 * `depth`：该 token 所在位置的花括号嵌套层数（含模板 `${`）；0 表示模块顶层。
 */
internal object JsLexer {
    const val WORD = 0   // 标识符 / 关键字 / 数字
    const val STR = 1    // 引号字符串，text 是引号内的原文（不做转义还原）
    const val PUNCT = 2  // 单个标点字符
    const val OTHER = 3  // 正则字面量、模板字符串的结束反引号

    class Tok(val t: Int, val s: Int, val e: Int, val text: String, val depth: Int) {
        fun isP(x: Char) = t == PUNCT && text.length == 1 && text[0] == x
        fun isW(x: String) = t == WORD && text == x
    }

    private val KW_BEFORE_REGEX = setOf(
        "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield", "await",
    )

    private fun isIdChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || c.code > 0x7f

    fun lex(src: String): List<Tok> {
        val toks = ArrayList<Tok>()
        val stack = ArrayList<Char>() // 'B' = 普通花括号，'T' = 模板里的 ${
        val n = src.length
        var i = 0

        fun add(t: Int, s: Int, e: Int, text: String) { toks.add(Tok(t, s, e, text, stack.size)) }

        // i 位于模板内容里；扫到结束反引号或 `${` 就返回
        fun scanTemplate() {
            while (i < n) {
                val c = src[i]
                if (c == '\\') { i += 2; continue }
                if (c == '`') { i++; add(OTHER, i - 1, i, "`"); return }
                if (c == '$' && i + 1 < n && src[i + 1] == '{') { i += 2; stack.add('T'); return }
                i++
            }
        }

        while (i < n) {
            val c = src[i]
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r') { i++; continue }
            if (c == '/' && i + 1 < n && src[i + 1] == '/') {
                while (i < n && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < n && src[i + 1] == '*') {
                val e = src.indexOf("*/", i + 2)
                i = if (e < 0) n else e + 2
                continue
            }
            if (c == '"' || c == '\'') {
                val s = i
                i++
                while (i < n && src[i] != c) { if (src[i] == '\\') i++; i++ }
                i++
                val end = minOf(i, n)
                add(STR, s, end, src.substring(s + 1, maxOf(s + 1, end - 1)))
                continue
            }
            if (c == '`') { i++; scanTemplate(); continue }
            if (c == '{') { add(PUNCT, i, i + 1, "{"); stack.add('B'); i++; continue }
            if (c == '}') {
                val top = if (stack.isEmpty()) 'B' else stack.removeAt(stack.size - 1)
                i++
                if (top == 'T') { scanTemplate(); continue }
                add(PUNCT, i - 1, i, "}")
                continue
            }
            if (c == '/') {
                val p = toks.lastOrNull()
                val division = p != null && (
                    p.t == STR || p.t == OTHER ||
                        (p.t == WORD && p.text !in KW_BEFORE_REGEX) ||
                        (p.t == PUNCT && (p.text == ")" || p.text == "]" || p.text == "}"))
                    )
                if (!division) {
                    val s = i
                    i++
                    var cls = false
                    while (i < n) {
                        val d = src[i]
                        if (d == '\\') { i += 2; continue }
                        if (d == '\n') break
                        if (cls) { if (d == ']') cls = false } else if (d == '[') cls = true else if (d == '/') break
                        i++
                    }
                    i++
                    add(OTHER, s, minOf(i, n), "re")
                    continue
                }
            }
            if (isIdChar(c)) {
                val s = i
                while (i < n && isIdChar(src[i])) i++
                add(WORD, s, i, src.substring(s, i))
                continue
            }
            add(PUNCT, i, i + 1, c.toString())
            i++
        }
        return toks
    }
}
