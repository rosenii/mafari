/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data.legado

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * 已导入的 Legado 订阅源（RSS / JSON / 网页），持久化在 `bookshelf/legado_rss.json`。
 * 和书源分开保存：订阅源用 `sourceUrl`，书源用 `bookSourceUrl`。
 */
object LegadoRssStore {
    private const val DIR = "bookshelf"
    private const val FILE = "legado_rss.json"
    private val lock = Any()

    var sources by mutableStateOf<List<LegadoRssSource>>(emptyList())
        private set

    private var loaded = false

    private fun file(context: Context) = File(File(context.filesDir, DIR).also { it.mkdirs() }, FILE)

    fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            sources = read(context)
            loaded = true
        }
    }

    fun find(url: String): LegadoRssSource? = sources.firstOrNull { it.url == url }

    /** 导入一批订阅源；同地址覆盖规则并保留启用状态。返回新增条数。 */
    fun add(context: Context, list: List<LegadoRssSource>): Int {
        if (list.isEmpty()) return 0
        synchronized(lock) {
            ensureLoadedLocked(context)
            val incoming = list.associateBy { it.url }
            var added = 0
            val merged = ArrayList<LegadoRssSource>(sources.size + incoming.size)
            val seen = HashSet<String>()
            for (old in sources) {
                val n = incoming[old.url]
                if (n != null) {
                    merged.add(n.withEnabled(old.enabled))
                    seen.add(old.url)
                } else {
                    merged.add(old)
                }
            }
            for ((url, n) in incoming) {
                if (url in seen) continue
                merged.add(n)
                added++
            }
            sources = merged
            write(context)
            return added
        }
    }

    fun remove(context: Context, url: String) {
        synchronized(lock) {
            ensureLoadedLocked(context)
            if (sources.none { it.url == url }) return
            sources = sources.filterNot { it.url == url }
            write(context)
        }
    }

    fun setEnabled(context: Context, url: String, enabled: Boolean) {
        synchronized(lock) {
            ensureLoadedLocked(context)
            sources = sources.map { if (it.url == url) it.withEnabled(enabled) else it }
            write(context)
        }
    }

    fun searchable(): List<LegadoRssSource> = sources.filter { it.canBrowse }

    private fun LegadoRssSource.withEnabled(enabled: Boolean): LegadoRssSource =
        LegadoRssSource(
            name, url, group, comment, type, header, sortUrl, concurrentRate,
            ruleArticles, ruleNextPage, ruleTitle, rulePubDate, ruleDescription, ruleLink,
            ruleImage, ruleContent, ruleAuthor, ruleCategory,
            enabled, raw,
        )

    private fun ensureLoadedLocked(context: Context) {
        if (loaded) return
        sources = read(context)
        loaded = true
    }

    private fun read(context: Context): List<LegadoRssSource> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            val root = JSONTokener(f.readText().trim().trimStart('\uFEFF')).nextValue()
            val arr = root as? JSONArray ?: return@runCatching emptyList()
            val out = ArrayList<LegadoRssSource>(arr.length())
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = runCatching { LegadoRssSourceParser.parseOne(o) }.getOrNull() ?: continue
                if (!seen.add(s.url)) continue
                out.add(s)
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context) {
        val f = file(context)
        val arr = JSONArray()
        for (s in sources) {
            val o = runCatching { JSONObject(s.raw) }.getOrNull() ?: JSONObject()
                .put("sourceUrl", s.url)
                .put("sourceName", s.name)
            o.put("sourceUrl", s.url)
            o.put("sourceName", s.name)
            o.put("enabled", s.enabled)
            arr.put(o)
        }
        val tmp = File(f.parentFile, "$FILE.tmp")
        runCatching {
            tmp.writeText(arr.toString())
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(arr.toString())
                tmp.delete()
            }
        }
    }
}
