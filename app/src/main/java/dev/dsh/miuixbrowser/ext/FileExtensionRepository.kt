package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 基于文件的仓库，目录布局（[baseDir] 下）：
 * - packages/<id>/   解包后的拓展文件（含 manifest.json），即 [extensionDir]
 * - state/<id>.json  启用状态、已授予权限、安装时间、来源、可用引擎（spec 不存，每次从 manifest 重新解析）
 * - data/<id>/       拓展自己的存储，卸载时一起删（storage 层之后用）
 *
 * 状态文件写入先写临时文件再 rename；坏掉的条目启动时跳过并通过 [onError] 报告，不影响其他拓展。
 */
class FileExtensionRepository(
    private val baseDir: File,
    private val onError: (id: String, error: Throwable) -> Unit = { _, _ -> },
) : ExtensionRepository {

    private val lock = Any()
    private val cache = LinkedHashMap<String, InstalledExtension>()
    private var loaded = false

    private val packagesRoot get() = File(baseDir, "packages")
    private val stateRoot get() = File(baseDir, "state")
    private val dataRoot get() = File(baseDir, "data")

    override fun extensionDir(id: String): File {
        requireSafe(id)
        return File(packagesRoot, id)
    }

    fun dataDir(id: String): File {
        requireSafe(id)
        return File(dataRoot, id)
    }

    override fun list(): List<InstalledExtension> = synchronized(lock) {
        ensureLoaded()
        cache.values.sortedBy { it.installedAt }
    }

    override fun get(id: String): InstalledExtension? = synchronized(lock) {
        ensureLoaded()
        cache[id]
    }

    override fun put(ext: InstalledExtension) {
        val id = ext.spec.id
        requireSafe(id)
        synchronized(lock) {
            ensureLoaded()
            writeState(ext)
            cache[id] = ext
        }
    }

    override fun remove(id: String) {
        requireSafe(id)
        synchronized(lock) {
            ensureLoaded()
            cache.remove(id)
            File(stateRoot, "$id.json").delete()
            File(packagesRoot, id).deleteRecursively()
            File(dataRoot, id).deleteRecursively()
        }
    }

    // ───────────────────────── 内部 ─────────────────────────

    private fun requireSafe(id: String) {
        require(ExtensionIds.isSafeId(id)) { "不安全的拓展 ID：$id" }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val files = stateRoot.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        for (f in files) {
            val id = f.name.removeSuffix(".json")
            try {
                cache[id] = load(id, f)
            } catch (e: Exception) {
                onError(id, e)
            }
        }
    }

    private fun load(id: String, stateFile: File): InstalledExtension {
        requireSafe(id)
        val state = JSONObject(stateFile.readText(Charsets.UTF_8))
        val dir = File(packagesRoot, id)
        val manifest = File(dir, "manifest.json").readText(Charsets.UTF_8)
        val spec = ManifestParser.parse(manifest, id, dir)
        val engines = state.strings("engines").mapNotNull { name ->
            try { EngineKind.valueOf(name) } catch (_: IllegalArgumentException) { null }
        }.toSet()
        return InstalledExtension(
            spec = spec,
            rootDir = dir,
            enabled = state.optBoolean("enabled", true),
            grantedApiPermissions = state.strings("grantedApi").toSet(),
            grantedHostPermissions = state.strings("grantedHosts").mapNotNull { MatchPattern.parse(it) },
            installedAt = state.optLong("installedAt", 0L),
            sourceUrl = state.optString("sourceUrl").ifEmpty { null },
            engines = engines,
        )
    }

    private fun writeState(ext: InstalledExtension) {
        stateRoot.mkdirs()
        val json = JSONObject()
            .put("enabled", ext.enabled)
            .put("grantedApi", JSONArray(ext.grantedApiPermissions.toList()))
            .put("grantedHosts", JSONArray(ext.grantedHostPermissions.map { it.raw }))
            .put("installedAt", ext.installedAt)
            .put("engines", JSONArray(ext.engines.map { it.name }))
        ext.sourceUrl?.let { json.put("sourceUrl", it) }

        val dst = File(stateRoot, "${ext.spec.id}.json")
        val tmp = File(stateRoot, "${ext.spec.id}.json.tmp")
        tmp.writeText(json.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(dst)) {
            dst.delete()
            if (!tmp.renameTo(dst)) throw IOException("无法写入状态文件：${dst.name}")
        }
    }

    private fun JSONObject.strings(key: String): List<String> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
    }
}
