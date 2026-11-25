/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Store for scripting.registerContentScripts.
 * Persistent registrations live beside the unpacked extension; session-only registrations stay in memory.
 * Only extension-relative JS/CSS paths are persisted (never inline code).
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
        persistAndReplaceSession(ext, current.values)
    }

    @Synchronized
    fun update(ext: InstalledExtension, entries: List<Entry>) {
        val current = all(ext).associateBy { it.id }.toMutableMap()
        entries.forEach { entry ->
            if (!current.containsKey(entry.id)) throw ApiException(ErrorCodes.BAD_REQUEST, "未注册的脚本 ID：${entry.id}")
            current[entry.id] = entry
        }
        persistAndReplaceSession(ext, current.values)
    }

    @Synchronized
    fun unregister(ext: InstalledExtension, ids: Set<String>?) {
        val current = all(ext).associateBy { it.id }.toMutableMap()
        if (ids == null) current.clear() else ids.forEach(current::remove)
        persistAndReplaceSession(ext, current.values)
    }

    /** Extension disable/update must not erase registrations; session entries die naturally with this Store/process. */
    @Synchronized
    fun clearSession(extId: String) { transient.remove(extId) }

    /** Called only for an explicit uninstall. */
    @Synchronized
    fun clearAll(ext: InstalledExtension) {
        transient.remove(ext.spec.id)
        val f = file(ext)
        f.delete()
        File(ext.rootDir, f.name + ".bak").delete()
        File(ext.rootDir, f.name + ".tmp").delete()
    }

    private fun persistAndReplaceSession(ext: InstalledExtension, entries: Collection<Entry>) {
        val values = entries.toList()
        writePersisted(ext, values.filter { it.persistAcrossSessions })
        transient[ext.spec.id] = values.filterNot { it.persistAcrossSessions }.associateBy { it.id }.toMutableMap()
    }

    private fun file(ext: InstalledExtension) = File(ext.rootDir, FILE_NAME)

    private fun readPersisted(ext: InstalledExtension): List<Entry> {
        val f = file(ext)
        val backup = File(ext.rootDir, f.name + ".bak")
        if (!f.exists() && backup.isFile) backup.renameTo(f)
        if (!f.isFile) return emptyList()
        return try {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION || root.optString("extensionId") != ext.spec.id) {
                quarantine(f)
                if (backup.isFile) backup.renameTo(f)
                return if (f.isFile) readPersisted(ext) else emptyList()
            }
            val arr = root.optJSONArray("scripts") ?: run {
                quarantine(f)
                if (backup.isFile) backup.renameTo(f)
                return if (f.isFile) readPersisted(ext) else emptyList()
            }
            backup.delete()
            val restored = (0 until arr.length()).mapNotNull { i -> parseEntry(ext, arr.optJSONObject(i)) }
            if (restored.size != arr.length()) writePersisted(ext, restored)
            restored
        } catch (_: Exception) {
            // Backward compatibility: the previous implementation stored a bare JSON array.
            try {
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                val entries = (0 until arr.length()).mapNotNull { i -> parseEntry(ext, arr.optJSONObject(i)) }
                writePersisted(ext, entries)
                entries
            } catch (_: Exception) {
                quarantine(f)
                if (backup.isFile && backup.renameTo(f)) readPersisted(ext) else emptyList()
            }
        }
    }

    private fun quarantine(f: File) {
        val bad = File(f.parentFile, f.name + ".corrupt-" + System.currentTimeMillis())
        if (!f.renameTo(bad)) f.delete()
    }

    private fun writePersisted(ext: InstalledExtension, entries: List<Entry>) {
        val dir = ext.rootDir
        val f = file(ext)
        val tmp = File(dir, f.name + ".tmp")
        val backup = File(dir, f.name + ".bak")
        val scripts = JSONArray()
        entries.forEach { scripts.put(toJson(it)) }
        val payload = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("extensionId", ext.spec.id)
            .put("scripts", scripts)
            .toString()
        try {
            if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建拓展目录")
            FileOutputStream(tmp).use { stream ->
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            backup.delete()
            if (f.exists() && !f.renameTo(backup)) throw IOException("无法备份旧动态脚本文件")
            if (!tmp.renameTo(f)) {
                if (backup.exists()) backup.renameTo(f)
                throw IOException("无法提交动态脚本文件")
            }
            backup.delete()
        } catch (e: Exception) {
            tmp.delete()
            if (!f.exists() && backup.exists()) backup.renameTo(f)
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
        .put("matchAboutBlank", entry.spec.matchAboutBlank)
        .put("world", entry.spec.world.name)
        .put("persistAcrossSessions", entry.persistAcrossSessions)

    private fun parseEntry(ext: InstalledExtension, o: JSONObject?): Entry? {
        o ?: return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val matches = strings(o, "matches").mapNotNull { MatchPattern.parse(it) }
        if (matches.isEmpty()) return null
        val exclude = strings(o, "excludeMatches").mapNotNull { MatchPattern.parse(it) }
        val js = strings(o, "js")
        val css = strings(o, "css")
        if (js.isEmpty() && css.isEmpty()) return null
        if ((js + css).any { !isSafeExistingExtensionFile(ext.rootDir, it) }) return null
        val runAt = when (o.optString("runAt")) {
            "document_start" -> RunAt.DOCUMENT_START
            "document_end" -> RunAt.DOCUMENT_END
            "document_idle", "" -> RunAt.DOCUMENT_IDLE
            else -> return null
        }
        val world = runCatching { ScriptWorld.valueOf(o.optString("world", ScriptWorld.ISOLATED.name)) }
            .getOrNull() ?: return null
        val persist = o.optBoolean("persistAcrossSessions", true)
        if (!persist) return null // session-only registrations must never be restored from disk
        return Entry(
            id,
            ContentScriptSpec(matches, exclude, js, css, runAt, o.optBoolean("allFrames"), o.optBoolean("matchAboutBlank"), world),
            true,
        )
    }

    private fun isSafeExistingExtensionFile(root: File, relative: String): Boolean {
        if (relative.isBlank() || relative.startsWith('/') || '\\' in relative) return false
        return try {
            val base = root.canonicalFile
            val candidate = File(base, relative).canonicalFile
            candidate.path.startsWith(base.path + File.separator) && candidate.isFile
        } catch (_: Exception) { false }
    }

    private fun strings(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val FILE_NAME = ".mafari-dynamic-content-scripts.json"
    }
}
