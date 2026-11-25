/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import org.json.JSONObject

/**
 * 修复：content script 里的 `import(chrome.runtime.getURL("content_main.js"))` 在 WebView 中被页面 CSP 拦截
 * （`script-src 'self' ...` 不含拓展的虚拟 origin；Chrome 的隔离世界不受页面 CSP 约束，WebView 的会受约束）。
 *
 * 做法：构建注入计划时，把 content script 引用到的拓展内 ES 模块（及其静态依赖）读出来，
 * 由 [JsLexer] 定位 import / export / import() / import.meta 后改写成 `async function(__exp,__req,__url,__imp)`，
 * 随条目一起经 addJavaScriptOnEvent 注入（该通道不受 CSP 约束）；
 * content script 里的 `import(` 改写为 `__mafariImport(`，由 shim 在运行时从内联模块表里取，取不到再回退到原生 import()。
 *
 * 支持：default / 命名 / 命名空间 / 副作用 import，`import ... with {}`，`export {a as b}`，`export {x} from`，
 * `export * from`，`export * as ns from`，`export default`，`export const|let|var|function|class`，
 * 导出是 getter（live binding），`import.meta.url`，模块内的动态 import()。
 * 限制：`export const {a,b}=...` 这类解构导出不识别；循环依赖按“先到先得”处理。
 */
internal object ContentModuleBundler {
    const val IMPORT_FN = "__mafariImport"

    private const val MAX_MODULES = 32
    // 单个文件上限由 fileReader 的 8MB 把关；这里是一次构建里所有模块的总和（按需模式下只占原生内存，不进页面）。
    private const val MAX_TOTAL_BYTES = 16 * 1024 * 1024

    private val JS_PATH = Regex("""^(?:\.{0,2}/)?(?:[\w@.\-]+/)*[\w@.\-]+\.m?js$""")

    /**
     * [mods]：路径 → 模块函数源码（`async function(__exp,__req,__url,__imp){…}`），按依赖发现顺序。
     * 内联模式用 [modulesJs] 整体塞进条目；按需模式只把 [mods] 的 key 列表写进条目，源码留在原生侧（见 WebViewExtensionHost）。
     */
    class Result(val rewrittenJs: String, val mods: Map<String, String>, val warnings: List<String>) {
        val modulesJs: String?
            get() = if (mods.isEmpty()) null
            else "{" + mods.entries.joinToString(",") { (k, v) -> JSONObject.quote(k) + ":" + v } + "}"
    }

    /** 一个模块的转换结果；大文件（实测有 4MB+）只转换 / 扫描一次，之后按 内容 指纹 复用。 */
    private class Compiled(val fn: String, val deps: List<String>, val refs: List<String>)

    private val cache = object : LinkedHashMap<String, Compiled>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Compiled>?) = size > 48
    }

    private fun compile(path: String, src: String): Compiled {
        val key = "$path\u0000${src.length}:${src.hashCode()}"
        synchronized(cache) { cache[key]?.let { return it } }
        val deps = mutableListOf<String>()
        val c = Compiled(transform(src, path, deps), deps.toList(), stringPaths(src))
        synchronized(cache) { cache[key] = c }
        return c
    }

    private class Edit(val s: Int, val e: Int, val r: String)

    /** [js] 是一条 content_scripts 拼好的源码；没有动态 import 时原样返回。 */
    fun process(js: String, readText: (String) -> String?): Result {
        val toks = JsLexer.lex(js)
        if (!hasDynamicImport(toks)) return Result(js, emptyMap(), emptyList())
        val warnings = mutableListOf<String>()
        val mods = linkedMapOf<String, String>()
        var total = 0

        fun load(path: String) {
            if (path in mods || mods.size >= MAX_MODULES) return
            val src = readText(path) ?: return
            total += src.length
            if (total > MAX_TOTAL_BYTES) {
                warnings.add("内联模块总大小超过 ${MAX_TOTAL_BYTES / 1024 / 1024}MB，已跳过 $path（将回退到原生 import()，会被页面 CSP 拦截）")
                return
            }
            mods[path] = "" // 先占位，防止循环依赖死循环
            val c = compile(path, src)
            mods[path] = c.fn
            c.deps.forEach { load(it) }
            // 模块里动态 import 引用到的其它文件
            c.refs.forEach { lit -> resolveCandidate(lit, path, readText)?.let { load(it) } }
        }

        stringPaths(js, toks).forEach { lit -> resolveCandidate(lit, "", readText)?.let { load(it) } }
        return Result(rewriteDynamic(js, toks), mods, warnings)
    }

    private fun isDynamicImport(toks: List<JsLexer.Tok>, i: Int) =
        toks[i].isW("import") && !(i > 0 && toks[i - 1].isP('.')) && i + 1 < toks.size && toks[i + 1].isP('(')

    private fun hasDynamicImport(toks: List<JsLexer.Tok>) = toks.indices.any { isDynamicImport(toks, it) }

    private fun rewriteDynamic(src: String, toks: List<JsLexer.Tok>): String =
        applyEdits(src, toks.indices.filter { isDynamicImport(toks, it) }.map { Edit(toks[it].s, toks[it].e, IMPORT_FN) })

    /** 源码里形如模块路径的字符串字面量（词法扫描过，注释和正则里的不算）。 */
    private fun stringPaths(src: String, toks: List<JsLexer.Tok> = JsLexer.lex(src)): List<String> =
        toks.filter { it.t == JsLexer.STR && JS_PATH.matches(it.text) }.map { it.text }.distinct()

    /** [lit] 先按 [fromPath] 所在目录解析，再按拓展根目录解析，读得到才算。 */
    private fun resolveCandidate(lit: String, fromPath: String, readText: (String) -> String?): String? {
        val tries = linkedSetOf<String>()
        if (lit.startsWith("./") || lit.startsWith("../")) tries.add(resolve(fromPath, lit)) else tries.add(lit.removePrefix("/"))
        if (fromPath.isNotEmpty() && !lit.startsWith("/")) tries.add(resolve(fromPath, lit))
        return tries.firstOrNull { it.isNotEmpty() && !it.startsWith("..") && readText(it) != null }
    }

    /** 与 JS 侧 norm() 同一规则：以 [fromPath] 所在目录为基准解析相对路径，`/` 开头视为根目录。 */
    internal fun resolve(fromPath: String, rel: String): String {
        val parts = ArrayList<String>()
        if (!rel.startsWith("/")) fromPath.split('/').dropLast(1).forEach { parts.add(it) }
        for (seg in rel.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1) else parts.add("..")
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun applyEdits(src: String, edits: List<Edit>): String {
        val sb = StringBuilder()
        var pos = 0
        for (ed in edits.sortedBy { it.s }) {
            if (ed.s < pos) continue
            sb.append(src, pos, ed.s).append(ed.r)
            pos = ed.e
        }
        return sb.append(src, pos, src.length).toString()
    }

    /** `{a, b as c, default as d}` → [["a"],["b","as","c"],...]；[i] 指向 `{`，返回 (项, `}` 之后的下标) */
    private fun braceItems(toks: List<JsLexer.Tok>, start: Int): Pair<List<List<String>>, Int> {
        val items = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        var i = start + 1
        while (i < toks.size && !toks[i].isP('}')) {
            if (toks[i].isP(',')) { if (cur.isNotEmpty()) items.add(cur); cur = mutableListOf() } else cur.add(toks[i].text)
            i++
        }
        if (cur.isNotEmpty()) items.add(cur)
        return items to (i + 1)
    }

    /** [local, exported] */
    private fun asPair(a: List<String>): Pair<String, String> =
        if (a.size >= 3 && a[1] == "as") a[0] to a[2] else a[0] to a[0]

    internal fun transform(source: String, path: String, deps: MutableList<String>): String {
        val src = source.removePrefix("\uFEFF")
        val toks = JsLexer.lex(src)
        val edits = mutableListOf<Edit>()
        val header = StringBuilder()
        fun tk(i: Int) = toks.getOrNull(i)
        fun isP(i: Int, c: Char) = tk(i)?.isP(c) == true
        fun isW(i: Int, w: String) = tk(i)?.isW(w) == true
        fun dep(spec: String): String { val p = resolve(path, spec); deps.add(p); return JSONObject.quote(p) }
        fun req(spec: String) = "(await __req(${dep(spec)}))"
        fun getter(name: String, expr: String) { header.append("__d(${JSONObject.quote(name)},()=>$expr);") }

        var i = 0
        while (i < toks.size) {
            val k = toks[i]
            if (k.t != JsLexer.WORD) { i++; continue }
            val prevDot = i > 0 && isP(i - 1, '.')

            if (k.text == "import" && !prevDot) {
                if (isP(i + 1, '(')) { edits.add(Edit(k.s, k.e, "__imp")); i++; continue }
                if (isP(i + 1, '.') && isW(i + 2, "meta")) { edits.add(Edit(k.s, toks[i + 2].e, "({url:__url})")); i += 3; continue }
                if (k.depth != 0) { i++; continue }
                var j = i + 1
                val out = StringBuilder()
                if (tk(j)?.t == JsLexer.STR) {
                    out.append("await __req(${dep(tk(j)!!.text)});")
                    j++
                } else {
                    var def: String? = null
                    var ns: String? = null
                    var named: List<List<String>>? = null
                    val first = tk(j)
                    if (first != null && first.t == JsLexer.WORD && (!first.isW("from") || isW(j + 1, "from"))) {
                        def = first.text; j++
                        if (isP(j, ',')) j++
                    }
                    if (isP(j, '*') && isW(j + 1, "as")) { ns = tk(j + 2)?.text; j += 3 }
                    else if (isP(j, '{')) { val r = braceItems(toks, j); named = r.first; j = r.second }
                    if (!isW(j, "from") || tk(j + 1)?.t != JsLexer.STR) { i++; continue } // 不是 import 语句
                    val spec = tk(j + 1)!!.text
                    j += 2
                    if (ns != null) out.append("const $ns=${req(spec)};")
                    if (def != null) out.append("const $def=${req(spec)}.default;")
                    if (!named.isNullOrEmpty()) {
                        val pairs = named.joinToString(",") { a -> val (l, e) = asPair(a); if (l == e) l else "$l:$e" }
                        out.append("const {$pairs}=${req(spec)};")
                    }
                    if (ns == null && def == null && named.isNullOrEmpty()) out.append("await __req(${dep(spec)});")
                }
                if ((isW(j, "with") || isW(j, "assert")) && isP(j + 1, '{')) j = braceItems(toks, j + 1).second
                if (isP(j, ';')) j++
                edits.add(Edit(k.s, toks[j - 1].e, out.toString()))
                i = j
                continue
            }

            if (k.text == "export" && !prevDot && k.depth == 0) {
                var j = i + 1
                if (isP(j, '{')) {
                    val (items, nx) = braceItems(toks, j)
                    j = nx
                    var from: String? = null
                    if (isW(j, "from") && tk(j + 1)?.t == JsLexer.STR) { from = tk(j + 1)!!.text; j += 2 }
                    if (isP(j, ';')) j++
                    val out = StringBuilder()
                    if (from != null) {
                        val v = "__r$i"
                        out.append("{const $v=${req(from)};")
                        items.forEach { a -> val (l, e) = asPair(a); out.append("__d(${JSONObject.quote(e)},()=>$v[${JSONObject.quote(l)}]);") }
                        out.append("}")
                    } else {
                        items.forEach { a -> val (l, e) = asPair(a); getter(e, l) }
                    }
                    edits.add(Edit(k.s, toks[j - 1].e, out.toString()))
                    i = j
                    continue
                }
                if (isP(j, '*')) {
                    var ns: String? = null
                    j++
                    if (isW(j, "as")) { ns = tk(j + 1)?.text; j += 2 }
                    if (!isW(j, "from") || tk(j + 1)?.t != JsLexer.STR) { i++; continue }
                    val spec = tk(j + 1)!!.text
                    j += 2
                    if (isP(j, ';')) j++
                    val v = "__r$i"
                    val out = StringBuilder("{const $v=${req(spec)};")
                    if (ns != null) out.append("__d(${JSONObject.quote(ns)},()=>$v);")
                    else out.append("Object.keys($v).forEach(k=>{if(k!==\"default\"&&!(k in __exp))__d(k,()=>$v[k]);});")
                    out.append("}")
                    edits.add(Edit(k.s, toks[j - 1].e, out.toString()))
                    i = j
                    continue
                }
                if (isW(j, "default")) {
                    var m = j + 1
                    if (isW(m, "async") && isW(m + 1, "function")) m++
                    if (isW(m, "function") || isW(m, "class")) {
                        var n = m + 1
                        if (isP(n, '*')) n++
                        val nameTok = tk(n)
                        if (nameTok != null && nameTok.t == JsLexer.WORD) { // 具名声明：保留声明，default 走 getter
                            getter("default", nameTok.text)
                            edits.add(Edit(k.s, toks[j].e, ""))
                            i = j + 1
                            continue
                        }
                    }
                    edits.add(Edit(k.s, toks[j].e, "__exp.default="))
                    i = j + 1
                    continue
                }
                // export const|let|var|function|class|async function
                var m = j
                if (isW(m, "async")) m++
                if (isW(m, "function") || isW(m, "class")) {
                    var n = m + 1
                    if (isP(n, '*')) n++
                    val nameTok = tk(n)
                    if (nameTok != null && nameTok.t == JsLexer.WORD) getter(nameTok.text, nameTok.text)
                } else if (isW(m, "const") || isW(m, "let") || isW(m, "var")) {
                    val d = toks[m].depth
                    var n = m + 1
                    while (n < toks.size) {
                        val nt = toks[n]
                        if (nt.t == JsLexer.WORD && nt.depth == d && (n == m + 1 || isP(n - 1, ','))) getter(nt.text, nt.text)
                        var e = n + 1
                        while (e < toks.size && !(toks[e].depth == d && (toks[e].isP(',') || toks[e].isP(';'))) &&
                            !(toks[e].depth == d && (toks[e].isW("export") || toks[e].isW("import")))
                        ) e++
                        if (e < toks.size && toks[e].isP(',')) n = e + 1 else break
                    }
                }
                edits.add(Edit(k.s, k.e, ""))
                i++
                continue
            }
            i++
        }
        val body = applyEdits(src, edits)
        return "async function(__exp,__req,__url,__imp){\n" +
            "const __d=(n,g)=>Object.defineProperty(__exp,n,{enumerable:true,configurable:true,get:g});$header\n$body\n}"
    }
}
