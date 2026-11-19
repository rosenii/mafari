package dev.dsh.miuixbrowser.ext

import org.json.JSONObject
import java.io.File

enum class PackageFormat { ZIP, CRX }

/** 解包并解析完、但还没写进仓库的拓展：UI 在这一步展示权限与兼容性，用户确认后再 [PackageInstaller.commit] */
class PreparedPackage(
    val spec: ExtensionSpec,
    val stagingDir: File,
    val format: PackageFormat,
    val engines: Set<EngineKind>,
    val sourceUrl: String?,
)

/**
 * 包 → 仓库 的两段式安装。
 * [workDir] 放暂存与回滚用的临时目录，应与 repo 的目录在同一文件系统（这样 rename 是原子的；不同则退化为拷贝）。
 */
class PackageInstaller(
    private val repo: ExtensionRepository,
    private val workDir: File,
) {

    /**
     * 解包到暂存目录并解析 manifest。失败时已清理暂存，抛 [PackageException] / [ManifestException]。
     * [forcedId] 用于更新：沿用已安装拓展的 ID（CRX 自带的 ID 不一致时以 forcedId 为准会掩盖换钥匙，所以 CRX 与之冲突直接报错）。
     */
    fun prepare(source: File, sourceUrl: String? = null, forcedId: String? = null): PreparedPackage {
        if (forcedId != null && !ExtensionIds.isSafeId(forcedId)) throw PackageException("拓展 ID 不合法")
        workDir.mkdirs()
        val stamp = System.nanoTime()
        val staging = File(workDir, "staging_$stamp")
        var crxZip: File? = null
        try {
            val format: PackageFormat
            val zipSource: File
            var crxId: String? = null
            if (Crx.isCrx(source)) {
                format = PackageFormat.CRX
                val info = Crx.parse(source, verify = true)
                if (forcedId != null && forcedId != info.extensionId) {
                    throw PackageException("更新包的签名密钥与已安装版本不同（ID 不一致）")
                }
                crxId = info.extensionId
                crxZip = File(workDir, "crx_$stamp.zip")
                Crx.extractZip(source, info, crxZip)
                zipSource = crxZip
            } else {
                format = PackageFormat.ZIP
                zipSource = source
            }

            PackageUnpacker.unpack(zipSource, staging)
            PackageInstaller.flattenSingleRoot(staging)

            val manifestFile = File(staging, "manifest.json")
            if (!manifestFile.isFile) throw PackageException("包里没有 manifest.json")
            val text = manifestFile.readText(Charsets.UTF_8)
            val raw = try {
                JSONObject(text.removePrefix("\uFEFF"))
            } catch (e: Exception) {
                throw ManifestException("manifest.json 不是合法 JSON：${e.message}")
            }

            val id = forcedId
                ?: crxId
                ?: ExtensionIds.fromManifestKey(raw.optString("key"))
                ?: ExtensionIds.geckoIdOf(raw)
                ?: ExtensionIds.localId(stableName(raw, staging))

            val spec = ManifestParser.parse(text, id, staging)
            return PreparedPackage(spec, staging, format, enginesFor(spec, format), sourceUrl)
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        } finally {
            crxZip?.delete()
        }
    }

    /**
     * 生成本地 ID 用的名称。名称是 `__MSG_xxx__` 时取 default_locale 下的文本：
     * 一来不同拓展不会因为都叫 `__MSG_extName__` 而撞 ID，二来换设备语言也不会变。
     */
    private fun stableName(raw: JSONObject, root: File): String {
        val name = raw.optString("name")
        if (!name.contains("__MSG_")) return name
        val dl = raw.optString("default_locale").ifEmpty { return name }
        return I18nMessages.localizeManifest(JSONObject().put("name", name).put("default_locale", dl), root, "", dl)
            .optString("name")
    }

    fun discard(p: PreparedPackage) {
        p.stagingDir.deleteRecursively()
    }

    /**
     * 把暂存目录换进仓库并登记。旧版本先挪到回滚目录，任何一步失败都还原，成功后才删。
     * 更新时默认沿用旧的启用状态与安装时间。
     */
    fun commit(
        p: PreparedPackage,
        grantedApi: Set<String>,
        grantedHosts: List<MatchPattern>,
        enabled: Boolean? = null,
        now: Long = System.currentTimeMillis(),
    ): InstalledExtension {
        val id = p.spec.id
        val previous = repo.get(id)
        val target = repo.extensionDir(id)
        target.parentFile?.mkdirs()

        val backup: File? = if (target.exists()) File(workDir, "old_${id}_$now") else null
        if (backup != null) {
            backup.deleteRecursively()
            if (!target.renameTo(backup)) throw PackageException("无法替换旧版本")
        }
        fun rollback() {
            target.deleteRecursively()
            backup?.renameTo(target)
        }

        if (!p.stagingDir.renameTo(target)) {
            try {
                p.stagingDir.copyRecursively(target, overwrite = true)
                p.stagingDir.deleteRecursively()
            } catch (e: Exception) {
                rollback()
                throw PackageException("安装失败：${e.message}")
            }
        }

        val ext = InstalledExtension(
            spec = p.spec,
            rootDir = target,
            enabled = enabled ?: previous?.enabled ?: true,
            grantedApiPermissions = grantedApi,
            grantedHostPermissions = grantedHosts,
            installedAt = previous?.installedAt ?: now,
            sourceUrl = p.sourceUrl ?: previous?.sourceUrl,
            engines = p.engines,
        )
        try {
            repo.put(ext)
        } catch (e: Exception) {
            rollback()
            throw PackageException("登记失败：${e.message}")
        }
        backup?.deleteRecursively()
        return ext
    }

    companion object {
        /**
         * 能在哪些引擎上跑：
         * - CRX 是 Chrome 专有包，不交给 Gecko；
         * - 只声明了 background.service_worker（没有 scripts / page）的包，Firefox 不支持，不交给 Gecko；
         * - 其余 zip / xpi 两边都可以，由用户在管理页选。
         */
        fun enginesFor(spec: ExtensionSpec, format: PackageFormat): Set<EngineKind> {
            if (format == PackageFormat.CRX) return setOf(EngineKind.WEBVIEW)
            val bg = spec.background
            val swOnly = bg != null && bg.serviceWorker != null && bg.scripts.isEmpty() && bg.page == null
            return if (swOnly) setOf(EngineKind.WEBVIEW) else setOf(EngineKind.WEBVIEW, EngineKind.GECKO)
        }

        /** 用户把整个文件夹打成 zip 时，内容会多包一层目录：只有一个子目录且里面有 manifest.json 就提上来 */
        internal fun flattenSingleRoot(dir: File) {
            if (File(dir, "manifest.json").isFile) return
            val children = dir.listFiles() ?: return
            if (children.size != 1) return
            val only = children[0]
            if (!only.isDirectory || !File(only, "manifest.json").isFile) return
            // 先挪到同级临时名，避免子目录与它自己的子项重名时冲突
            val tmp = File(dir, ".flatten_tmp")
            if (!only.renameTo(tmp)) return
            tmp.listFiles()?.forEach { it.renameTo(File(dir, it.name)) }
            tmp.deleteRecursively()
        }
    }
}
