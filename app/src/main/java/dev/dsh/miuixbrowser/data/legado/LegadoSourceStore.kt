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
import java.io.File

/**
 * 已导入的 Legado 书源，持久化在 `bookshelf/legado_sources.json`（每条是书源原始的 JSON）。
 *
 * 以前书源只放在进程内存里，重启 App 就要重新导入；现在导入时落盘，启动时读回来。
 * 同一 [LegadoSource.url] 只保留一条（重新导入会更新规则，但保留用户的启用 / 停用状态）。
 * 只在主线程改状态；写盘很小（几百 KB 以内），和设置页的写法一致。
 */
object LegadoSourceStore {
    private const val DIR = "bookshelf"
    private const val FILE = "legado_sources.json"
    private val lock = Any()

    /** 当前书源（按导入顺序）。界面直接读这个状态。 */
    var sources by mutableStateOf<List<LegadoSource>>(emptyList())
        private set

    private var loaded = false

    private fun file(context: Context) = File(File(context.filesDir, DIR).also { it.mkdirs() }, FILE)

    /** 读盘（只第一次真正读）。在 IO 线程调用；读完后再回主线程用 [sources]。 */
    fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            sources = read(context)
            loaded = true
        }
    }

    /** 已加载过就立即返回，避免重复读盘。 */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        load(context)
    }

    fun find(url: String): LegadoSource? = sources.firstOrNull { it.url == url }

    /**
     * 导入一批书源：同一个地址的覆盖规则并重新启用；返回新增（之前没有）的条数。
     * 在 IO 线程解析，回主线程调用。
     */
    fun add(context: Context, list: List<LegadoSource>): Int {
        if (list.isEmpty()) return 0
        synchronized(lock) {
            ensureLoadedLocked(context)
            val incoming = list.associateBy { it.url }
            var added = 0
            val merged = ArrayList<LegadoSource>(sources.size + incoming.size)
            val seen = HashSet<String>()
            for (old in sources) {
                val n = incoming[old.url]
                if (n != null) {
                    // 重新导入同一书源：换新规则，但保留用户关掉它的选择
                    merged.add(n.withSwitches(enabled = old.enabled, explore = old.exploreEnabled))
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

    /** 删除一个书源；[url] 不存在时什么也不做。 */
    fun remove(context: Context, url: String) {
        synchronized(lock) {
            ensureLoadedLocked(context)
            if (sources.none { it.url == url }) return
            sources = sources.filterNot { it.url == url }
            write(context)
        }
    }

    /** 删除全部书源。 */
    fun clear(context: Context) {
        synchronized(lock) {
            ensureLoadedLocked(context)
            sources = emptyList()
            write(context)
        }
    }

    /** 启用 / 停用书源（总开关）。 */
    fun setEnabled(context: Context, url: String, enabled: Boolean) {
        synchronized(lock) {
            ensureLoadedLocked(context)
            sources = sources.map { if (it.url == url) it.withSwitches(enabled = enabled) else it }
            write(context)
        }
    }

    /** 启用 / 停用书源的「发现」入口。 */
    fun setExploreEnabled(context: Context, url: String, enabled: Boolean) {
        synchronized(lock) {
            ensureLoadedLocked(context)
            sources = sources.map { if (it.url == url) it.withSwitches(explore = enabled) else it }
            write(context)
        }
    }

    /** 可以直接参与搜索的书源；用于「全部书源」聚合搜索。 */
    fun searchable(): List<LegadoSource> = sources.filter { it.canSearch }

    private fun ensureLoadedLocked(context: Context) {
        if (loaded) return
        sources = read(context)
        loaded = true
    }

    private fun read(context: Context): List<LegadoSource> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            val root = org.json.JSONTokener(f.readText().trim().trimStart('\uFEFF')).nextValue()
            val arr = root as? JSONArray ?: return@runCatching emptyList()
            val out = ArrayList<LegadoSource>(arr.length())
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = runCatching { LegadoSourceParser.parseOne(o) }.getOrNull() ?: continue
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
                .put("bookSourceUrl", s.url)
                .put("bookSourceName", s.name)
            o.put("bookSourceUrl", s.url)
            o.put("bookSourceName", s.name)
            o.put("enabled", s.enabled)
            o.put("enabledExplore", s.exploreEnabled)
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
