/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import java.io.File

/** 测试用：内存仓库 + 快速构造已安装拓展 */
class FakeRepo : ExtensionRepository {
    private val map = LinkedHashMap<String, InstalledExtension>()
    var root: File = File(System.getProperty("java.io.tmpdir"))

    override fun list() = map.values.toList()
    override fun get(id: String) = map[id]
    override fun put(ext: InstalledExtension) { map[ext.spec.id] = ext }
    override fun remove(id: String) { map.remove(id) }
    override fun extensionDir(id: String) = File(root, id)
}

object TestExt {
    const val ID = "demo@x.org"

    fun make(
        dir: File,
        id: String = ID,
        manifest: String = """{"manifest_version":3,"name":"Demo","version":"1","permissions":["storage","tabs"],
            "host_permissions":["https://granted.com/*"],
            "content_scripts":[{"matches":["https://a.com/*","https://*.b.com/foo/*"],"js":["c.js"]}]}""",
        granted: Set<String> = setOf("storage"),
        grantedHosts: List<String> = listOf("https://granted.com/*"),
        enabled: Boolean = true,
    ): InstalledExtension {
        dir.mkdirs()
        File(dir, "manifest.json").writeText(manifest)
        return InstalledExtension(
            spec = ManifestParser.parse(manifest, id),
            rootDir = dir,
            enabled = enabled,
            grantedApiPermissions = granted,
            grantedHostPermissions = grantedHosts.mapNotNull { MatchPattern.parse(it) },
            installedAt = 1L,
            sourceUrl = null,
            engines = setOf(EngineKind.WEBVIEW),
        )
    }
}
