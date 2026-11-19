package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * scripting.registerContentScripts 的轻量存储。
 * persistAcrossSessions=true 的条目写入扩展私有目录；false 的条目只保留在当前进程。
 * 这里只存扩展相对文件路径，不接受内联代码，避免把动态注册变成任意代码持久化入口。
 */
class DynamicContentScriptStore {
    data class Entry(
        val id: String,
        val spec: ContentScriptSpec,
        val persistAcrossSessions: Boolean,
    )

    private val transient = HashMap<String, MutableMap<String, Entry>>()

    @Synchronized
    fun all(ext: InstalledExtension): List<Entry> {
        val persisted = readPersisted(ext)
        val session = transient[ext.spec.id]?.values.orEmpty()
        return (persisted + session).distinctBy { it.id }
    }

    @Synchronized
    fun register(ext: InstalledExtension, entries: List<Entry>) {
        val current = all(ext).associateBy { it.id }.toMutableMap()
        entries.forEach { entry ->
            if (current.containsKey(entry.id)) throw ApiException(ErrorCodes.BAD_REQUEST, "脚本 ID 已存在：${entry.id}")
            current[entry.id] = entry
        }
        writePersisted(ext, current.values.filter { it.persistAcrossSessions })
        val transientEntries = current.values.filterNot { it.persistAcrossSessions }.associateBy { it.id }
        transient[ext.spec.id] = transientEntries.toMutableMap()
    }

    @Synchronized
    fun update(ext: InstalledExtension, entries: List<Entry>) {
        val current = all(ext).associateBy { it.id }.toMutableMap()
        entries.forEach { entry ->
            if (!current.containsKey(entry.id)) throw ApiException(ErrorCodes.BAD_REQUEST, "未注册的脚本 ID：${entry.id}")
            current[entry.id] = entry
        }
        writePersisted(ext, current.values.filter { it.persistAcrossSessions })
        transient[ext.spec.id] = current.values.filterNot { it.persistAcrossSessions }.associateBy { it.id }.toMutableMap()
    }

    @Synchronized
    fun unregister(ext: InstalledExtension, ids: Set<String>?) {
        val current = all(ext).associateBy { it.id }.toMutableMap()
        if (ids == null) current.clear() else ids.forEach { current.remove(it) }
        writePersisted(ext, current.values.filter { it.persistAcrossSessions })
        transient[ext.spec.id] = current.values.filterNot { it.persistAcrossSessions }.associateBy { it.id }.toMutableMap()
    }

    @Synchronized
    fun forget(extId: String) { transient.remove(extId) }

    private fun file(ext: InstalledExtension) = File(ext.rootDir, ".mafari-dynamic-content-scripts.json")

    private fun readPersisted(ext: InstalledExtension): List<Entry> {
        val f = file(ext)
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { i -> parseEntry(arr.optJSONObject(i)) }
        } catch (_: Exception) { emptyList() }
    }

    private fun writePersisted(ext: InstalledExtension, entries: List<Entry>) {
        val f = file(ext)
        val tmp = File(ext.rootDir, f.name + ".tmp")
        val arr = JSONArray()
        entries.forEach { arr.put(toJson(it)) }
        try {
            ext.rootDir.mkdirs()
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(arr.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw ApiException(ErrorCodes.INTERNAL, "动态脚本保存失败：${e.message}")
        }
    }

    private fun toJson(entry: Entry): JSONObject = JSONObject()
        .put("id", entry.id)
        .put("matches", JSONArray(entry.spec.matches.map { it.raw }))
        .put("excludeMatches", JSONArray(entry.spec.excludeMatches.map { it.raw }))
        .put("js", JSONArray(entry.spec.js))
        .put("css", JSONArray(entry.spec.css))
        .put("runAt", when (entry.spec.runAt) {
            RunAt.DOCUMENT_START -> "document_start"
            RunAt.DOCUMENT_END -> "document_end"
            RunAt.DOCUMENT_IDLE -> "document_idle"
        })
        .put("allFrames", entry.spec.allFrames)
        .put("persistAcrossSessions", entry.persistAcrossSessions)

    private fun parseEntry(o: JSONObject?): Entry? {
        o ?: return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val matches = strings(o, "matches").mapNotNull { MatchPattern.parse(it) }
        if (matches.isEmpty()) return null
        val exclude = strings(o, "excludeMatches").mapNotNull { MatchPattern.parse(it) }
        val js = strings(o, "js")
        val css = strings(o, "css")
        if (js.isEmpty() && css.isEmpty()) return null
        val runAt = when (o.optString("runAt")) {
            "document_start" -> RunAt.DOCUMENT_START
            "document_end" -> RunAt.DOCUMENT_END
            else -> RunAt.DOCUMENT_IDLE
        }
        return Entry(id, ContentScriptSpec(matches, exclude, js, css, runAt, o.optBoolean("allFrames"), false, ScriptWorld.ISOLATED), true)
    }

    private fun strings(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }
}
