/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.ImageBitmap
import dev.dsh.miuixbrowser.net.AmoAddon
import dev.dsh.miuixbrowser.net.AmoClient
import dev.dsh.miuixbrowser.engine.gecko.GeckoExtItem
import dev.dsh.miuixbrowser.engine.gecko.GeckoExtensionStore
import dev.dsh.miuixbrowser.i18n.tr

/**
 * Gecko 内核的拓展管理后端：薄薄一层转调 [GeckoExtensionStore]。
 * 只在 Gecko 内核下构造（引用了 GeckoView 的类）；无内核版运行时不存在这些类，所以绝不能在 lite 里实例化它。
 */
class GeckoExtBackend : ExtManagerBackend {
    private val store = GeckoExtensionStore

    override val items: List<ExtEntry>
        get() = store.items.map { it.toEntry() }

    override val loading: Boolean get() = store.loading.value

    override fun icon(id: String): ImageBitmap? = store.icons[id]

    override val supportsStore = true
    override val supportsAutoUpdate = true
    override val supportsPrivate = true

    override val importSummary: String get() = tr("支持拓展商店页面链接、.xpi 直链，或本地 .xpi 文件")
    override val emptyHint: String
        get() = tr("还没有安装拓展。点右上角搜索，从 Firefox 拓展商店添加，或在「更多」里导入。")
    override val footerHint: String
        get() = tr("拓展由Gecko内核运行，搜索结果来源于Firefox拓展商店，APP已原生支持油猴脚本，请勿再安装其它如**猴等油猴脚本类拓展程序")

    override fun bind(context: Context) {
        store.activityProvider = { context.asActivity() }
    }

    override suspend fun refresh(context: Context) = store.refresh(context)

    override suspend fun install(context: Context, uri: String): Result<String> =
        store.install(context, uri).map { it.name }

    override suspend fun stageFile(context: Context, source: Uri): String = store.stageFile(context, source)

    private fun find(id: String): GeckoExtItem? = store.items.firstOrNull { it.id == id }

    override suspend fun setEnabled(context: Context, id: String, enabled: Boolean): Result<Unit> =
        find(id)?.let { store.setEnabled(context, it, enabled) } ?: Result.failure(IllegalStateException(tr("操作失败")))

    override suspend fun setAllowedInPrivate(context: Context, id: String, allowed: Boolean): Result<Unit> =
        find(id)?.let { store.setAllowedInPrivate(context, it, allowed) } ?: Result.failure(IllegalStateException(tr("设置失败")))

    override suspend fun uninstall(context: Context, id: String): Result<Unit> =
        find(id)?.let { store.uninstall(context, it) } ?: Result.failure(IllegalStateException(tr("卸载失败")))

    override suspend fun updateOne(context: Context, id: String): Result<Boolean> =
        find(id)?.let { store.updateOne(context, it) } ?: Result.failure(IllegalStateException(tr("检查更新失败")))

    override suspend fun updateAll(context: Context): Pair<Int, Int> = store.updateAll(context)

    override suspend fun searchStore(query: String): Result<List<AmoAddon>> = AmoClient.search(query)

    override suspend fun resolveStorePage(url: String): Result<AmoAddon?> = AmoClient.resolvePage(url)

    override val autoUpdate: State<Int> get() = store.autoUpdate
    override val lastCheck: State<Long> get() = store.lastCheck

    override fun updateAutoUpdate(context: Context, index: Int) = store.updateAutoUpdate(context, index)

    private fun GeckoExtItem.toEntry() = ExtEntry(
        id = id,
        name = name,
        version = version,
        description = description,
        enabled = enabled,
        optionsUrl = optionsUrl,
        permissions = store.describePermissions(permissions, origins),
        allowedInPrivate = allowedInPrivate,
        author = author,
        homepage = homepage,
    )
}
