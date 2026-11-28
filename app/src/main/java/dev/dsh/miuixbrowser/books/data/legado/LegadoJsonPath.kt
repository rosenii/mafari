/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books.data.legado

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 够书源用的 JSONPath 子集（在 org.json 的树上求值，不引额外依赖）：
 * `$.a.b`、`$['a']`、`$.a[0]`、`[-1]`、`[0,2]`、`[1:3]`、`[*]` / `.*`、递归 `..name`、
 * 过滤 `[?(@.k == 'v')]`（`== != < > <= >= =~`，`&&` / `||`，只写 `@.k` 表示该字段存在）。
 * 求值结果是命中节点的列表（JSON null 记作 null）。
 */
internal object LegadoJsonPath {
    private sealed class Step
    private class Key(val name: String) : Step()
    private class Names(val names: List<String>) : Step()
    private class Idx(val items: List<Int>) : Step()
    private class Slice(val start: Int?, val end: Int?, val step: Int) : Step()
    private class Deep(val inner: Step) : Step()
    private class Filter(val expr: String) : Step()
    private object Wild : Step()

    /** 把 String 解析成 JSON 树；已经是 JSONObject / JSONArray 的原样返回；失败返回 null。 */
    fun toRoot(content: Any?): Any? = when (content) {
        is JSONObject, is JSONArray -> content
        is CharSequence -> runCatching {
            val v = JSONTokener(content.toString().trim().trimStart('﻿')).nextValue()
            if (v is JSONObject || v is JSONArray) v else null
        }.getOrNull()
        else -> null
    }

    fun looksLikeJson(content: Any?): Boolean = when (content) {
        is JSONObject, is JSONArray -> true
        is CharSequence -> {
            val s = content.trimStart()
            s.startsWith("{") || s.startsWith("[")
        }
        else -> false
    }

    fun select(root: Any?, path: String): List<Any?> {
        val steps = parse(path) ?: return emptyList()
        var cur: List<Any?> = listOf(root)
        for (st in steps) {
            val next = ArrayList<Any?>()
            for (n in cur) step(n, st, next)
            cur = next
            if (cur.isEmpty()) break
        }
        return cur
    }

    /** 节点转文本：null / JSON null → null；整数值的 Double 去掉 ".0"。 */
    fun text(v: Any?): String? = when {
        v == null || v === JSONObject.NULL -> null
        v is Double -> if (!v.isInfinite() && !v.isNaN() && v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    // ------------------------------------------------------------------ 解析

    private fun parse(path: String): List<Step>? {
        var s = path.trim()
        if (s.startsWith("$") || s.startsWith("@")) s = s.substring(1)
        val steps = ArrayList<Step>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '.') {
                val deep = s.startsWith("..", i)
                i += if (deep) 2 else 1
                if (i < s.length && s[i] == '[') {
                    val (st, ni) = bracket(s, i) ?: return null
                    steps.add(if (deep) Deep(st) else st)
                    i = ni
                } else if (i < s.length && s[i] == '*') {
                    steps.add(if (deep) Deep(Wild) else Wild)
                    i++
                } else {
                    var j = i
                    while (j < s.length && s[j] != '.' && s[j] != '[') j++
                    val name = s.substring(i, j)
                    if (name.isEmpty()) return null
                    steps.add(if (deep) Deep(Key(name)) else Key(name))
                    i = j
                }
            } else if (c == '[') {
                val (st, ni) = bracket(s, i) ?: return null
                steps.add(st)
                i = ni
            } else {
                // 省略了 $ 前缀的 "a.b"
                var j = i
                while (j < s.length && s[j] != '.' && s[j] != '[') j++
                if (j == i) return null
                steps.add(Key(s.substring(i, j)))
                i = j
            }
        }
        return steps
    }

    /** 从 [start]（指向 '['）读到配对的 ']'；引号和圆括号里的 ']' 不算。返回 步骤 + 下一个位置。 */
    private fun bracket(s: String, start: Int): Pair<Step, Int>? {
        var depth = 0
        var quote = '\u0000'
        var j = start
        while (j < s.length) {
            val c = s[j]
            if (quote != '\u0000') {
                if (c == '\\') j++ else if (c == quote) quote = '\u0000'
            } else {
                when (c) {
                    '\'', '"' -> quote = c
                    '[', '(' -> depth++
                    ')' -> depth--
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            val st = parseBracket(s.substring(start + 1, j).trim()) ?: return null
                            return st to (j + 1)
                        }
                    }
                }
            }
            j++
        }
        return null
    }

    private val QUOTED = Regex("'((?:[^'\\\\]|\\\\.)*)'|\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val SLICE = Regex("^(-?\\d*)\\s*:\\s*(-?\\d*)(?:\\s*:\\s*(-?\\d+))?$")
    private val INT_LIST = Regex("^-?\\d+(?:\\s*,\\s*-?\\d+)*$")

    private fun parseBracket(inner: String): Step? {
        if (inner == "*") return Wild
        if (inner.startsWith("?")) return Filter(inner.substring(1).trim())
        if (inner.startsWith("'") || inner.startsWith("\"")) {
            val names = QUOTED.findAll(inner).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
            return if (names.isEmpty()) null else if (names.size == 1) Key(names[0]) else Names(names)
        }
        SLICE.find(inner)?.let { m ->
            return Slice(m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull(), m.groupValues[3].toIntOrNull() ?: 1)
        }
        if (INT_LIST.matches(inner)) return Idx(inner.split(',').map { it.trim().toInt() })
        return null
    }

    // ------------------------------------------------------------------ 求值

    private fun step(n: Any?, st: Step, out: MutableList<Any?>) {
        when (st) {
            is Key -> if (n is JSONObject && n.has(st.name)) out.add(unwrap(n.opt(st.name)))
            is Names -> if (n is JSONObject) for (k in st.names) if (n.has(k)) out.add(unwrap(n.opt(k)))
            is Idx -> if (n is JSONArray) for (k in st.items) {
                val idx = if (k < 0) n.length() + k else k
                if (idx in 0 until n.length()) out.add(unwrap(n.opt(idx)))
            }
            is Slice -> if (n is JSONArray) {
                val len = n.length()
                var from = st.start ?: 0
                var to = st.end ?: len
                if (from < 0) from += len
                if (to < 0) to += len
                from = from.coerceIn(0, len)
                to = to.coerceIn(0, len)
                val stride = if (st.step <= 0) 1 else st.step
                var k = from
                while (k < to) {
                    out.add(unwrap(n.opt(k)))
                    k += stride
                }
            }
            Wild -> children(n).forEach { out.add(it) }
            is Filter -> children(n).forEach { if (filterMatch(it, st.expr)) out.add(it) }
            is Deep -> descend(n) { d -> step(d, st.inner, out) }
        }
    }

    private fun unwrap(v: Any?): Any? = if (v === JSONObject.NULL) null else v

    private fun children(n: Any?): List<Any?> = when (n) {
        is JSONArray -> (0 until n.length()).map { unwrap(n.opt(it)) }
        is JSONObject -> n.keys().asSequence().map { unwrap(n.opt(it)) }.toList()
        else -> emptyList()
    }

    private fun descend(n: Any?, visit: (Any?) -> Unit) {
        visit(n)
        for (c in children(n)) descend(c, visit)
    }

    // ------------------------------------------------------------------ 过滤表达式

    private val CMP = Regex("^(@[^=!<>~\\s]*)\\s*(==|!=|<=|>=|=~|<|>)\\s*(.+)$")

    private fun filterMatch(node: Any?, expr: String): Boolean {
        var e = expr.trim()
        while (e.startsWith("(") && e.endsWith(")") && balanced(e.substring(1, e.length - 1))) e = e.substring(1, e.length - 1).trim()
        splitTop(e, "||")?.let { parts -> return parts.any { filterMatch(node, it) } }
        splitTop(e, "&&")?.let { parts -> return parts.all { filterMatch(node, it) } }
        val m = CMP.find(e)
        if (m == null) {
            if (e.startsWith("!@")) return select(node, "$" + e.substring(2)).isEmpty()
            return e.startsWith("@") && select(node, "$" + e.substring(1)).any { it != null }
        }
        val left = select(node, "$" + m.groupValues[1].substring(1))
        val rhs = m.groupValues[3].trim()
        val op = m.groupValues[2]
        return left.any { compare(it, op, rhs) }
    }

    private fun balanced(s: String): Boolean {
        var d = 0
        var q = '\u0000'
        for (c in s) {
            if (q != '\u0000') { if (c == q) q = '\u0000'; continue }
            when (c) {
                '\'', '"' -> q = c
                '(' -> d++
                ')' -> { d--; if (d < 0) return false }
            }
        }
        return d == 0
    }

    /** 在括号和引号之外按 [op] 切开；没有该运算符返回 null。 */
    private fun splitTop(s: String, op: String): List<String>? {
        val parts = ArrayList<String>()
        var depth = 0
        var q = '\u0000'
        var last = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (q != '\u0000') {
                if (c == q) q = '\u0000'
            } else if (c == '\'' || c == '"') {
                q = c
            } else if (c == '(') {
                depth++
            } else if (c == ')') {
                depth--
            } else if (depth == 0 && s.startsWith(op, i)) {
                parts.add(s.substring(last, i))
                i += op.length
                last = i
                continue
            }
            i++
        }
        if (parts.isEmpty()) return null
        parts.add(s.substring(last))
        return parts
    }

    private fun compare(left: Any?, op: String, rhsRaw: String): Boolean {
        if (op == "=~") {
            val body = rhsRaw.removePrefix("/").let { r ->
                val end = r.lastIndexOf('/')
                if (end >= 0) r.substring(0, end) else r
            }
            val flags = if (rhsRaw.endsWith("/i")) setOf(RegexOption.IGNORE_CASE) else emptySet()
            return runCatching { Regex(body, flags).containsMatchIn(text(left).orEmpty()) }.getOrDefault(false)
        }
        val quoted = (rhsRaw.startsWith("'") && rhsRaw.endsWith("'") && rhsRaw.length >= 2) ||
            (rhsRaw.startsWith("\"") && rhsRaw.endsWith("\"") && rhsRaw.length >= 2)
        val ls = text(left)
        if (quoted) {
            val r = rhsRaw.substring(1, rhsRaw.length - 1)
            return when (op) {
                "==" -> ls == r
                "!=" -> ls != r
                else -> ls != null && cmp(ls.compareTo(r), op)
            }
        }
        if (rhsRaw == "null") return if (op == "==") left == null else if (op == "!=") left != null else false
        if (rhsRaw == "true" || rhsRaw == "false") return if (op == "==") ls == rhsRaw else if (op == "!=") ls != rhsRaw else false
        val rn = rhsRaw.toDoubleOrNull() ?: return false
        val ln = ls?.toDoubleOrNull() ?: return op == "!="
        return cmp(ln.compareTo(rn), op)
    }

    private fun cmp(c: Int, op: String): Boolean = when (op) {
        "==" -> c == 0
        "!=" -> c != 0
        "<" -> c < 0
        ">" -> c > 0
        "<=" -> c <= 0
        ">=" -> c >= 0
        else -> false
    }
}
