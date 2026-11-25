/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import java.io.File

/** 已安装的拓展：解析后的 spec + 磁盘位置 + 用户授予的权限 + 可运行的引擎集合。 */
data class InstalledExtension(
    val spec: ExtensionSpec,
    val rootDir: File,
    val enabled: Boolean,
    val grantedApiPermissions: Set<String>,
    val grantedHostPermissions: List<MatchPattern>,
    val installedAt: Long,
    val sourceUrl: String?,
    /** 能在哪些引擎上运行：CRX / Chrome 专有 API 的包不含 GECKO */
    val engines: Set<EngineKind>,
)

/**
 * 引擎适配器：只管「怎么把拓展跑起来」，不含权限与消息路由逻辑（那些在引擎无关的 Runtime 里）。
 * WebView 适配器：自研 API；Gecko 适配器：把包交给 WebExtensionController，只做安装 / 启停。
 */
interface ExtensionAdapter {
    val engine: EngineKind

    /** 该引擎上运行此拓展的兼容性问题（空 = 完全兼容） */
    fun assess(spec: ExtensionSpec): List<CompatIssue>

    suspend fun install(ext: InstalledExtension): Result<Unit>
    suspend fun update(ext: InstalledExtension): Result<Unit>
    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit>
    suspend fun uninstall(id: String): Result<Unit>
}

/** 包仓库：解包目录、版本、权限授予记录，供两个适配器共用。实现放在下一步。 */
interface ExtensionRepository {
    fun list(): List<InstalledExtension>
    fun get(id: String): InstalledExtension?
    fun put(ext: InstalledExtension)
    fun remove(id: String)
    /** 该拓展的解包根目录（不保证已存在） */
    fun extensionDir(id: String): File
}
