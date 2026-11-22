package dev.dsh.miuixbrowser.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.dsh.miuixbrowser.ext.CompatIssue
import dev.dsh.miuixbrowser.ext.EngineKind
import dev.dsh.miuixbrowser.ext.ExtensionSpec
import dev.dsh.miuixbrowser.ext.ExtensionSystem
import dev.dsh.miuixbrowser.ext.InstalledExtension
import dev.dsh.miuixbrowser.ext.PackageUnpacker
import dev.dsh.miuixbrowser.ext.PreparedPackage
import dev.dsh.miuixbrowser.ext.SupportLevel
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.net.AmoAddon
import dev.dsh.miuixbrowser.net.AmoClient
import dev.dsh.miuixbrowser.net.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/** 菜单角标 / 高亮用：已启用、可在 WebView 上运行的拓展数（读仓库缓存，首次会读一次状态文件） */
internal fun webViewEnabledExtCount(system: ExtensionSystem): Int =
    runCatching { system.webViewExtensions().size }.getOrDefault(0)

/**
 * WebView 内核的拓展管理后端：把 [ExtensionSystem]（自研的 WebExtension 运行时）接到共用的拓展管理页上。
 *
 * - 列表 = 仓库里「可在 WebView 上运行」的拓展（[EngineKind.WEBVIEW]）；
 * - 安装：下载 / 读文件 → [dev.dsh.miuixbrowser.ext.PackageInstaller.prepare] → 用户确认权限 → commit → 启动后台页；
 * - 启停 / 卸载：交给 [ExtensionSystem.onInstalled] / [ExtensionSystem.onDisabled] / [ExtensionSystem.onUninstalled]；
 * - 任何改动后调 [onChanged]，让已打开的标签重新装配 content script（对下一次页面加载生效）。
 *
 * 快捷菜单 popup 由 WebViewExtMenuSheet 直接承载；商店搜索走 [AmoClient]；隐私浏览（无痕标签一律不装拓展）暂未支持。
 */
class WebViewExtBackend(
    private val system: ExtensionSystem,
    private val onChanged: () -> Unit = {},
) : ExtManagerBackend {

    private val list = mutableStateListOf<ExtEntry>()
    private val icons = mutableStateMapOf<String, ImageBitmap>()
    private val loadingState = mutableStateOf(false)
    private var activityRef: WeakReference<Activity>? = null

    override val items: List<ExtEntry> get() = list
    override val loading: Boolean get() = loadingState.value
    override fun icon(id: String): ImageBitmap? = icons[id]

    override val supportsStore = true
    override val supportsAutoUpdate = false
    override val supportsPrivate = false

    override val importSummary: String
        get() = tr("支持拓展商店页面链接、.zip / .crx / .xpi 直链，或本地拓展包文件")
    override val emptyHint: String
        get() = tr("还没有安装拓展。点右上角搜索，从 Firefox 拓展商店添加，或在「更多」里导入链接 / 本地 .zip / .crx / .xpi 文件。")
    override val footerHint: String
        get() = tr("拓展由 WebView 内核运行（实验性），改动对下一次页面加载生效；无痕标签不运行拓展。") + "\n" +
            tr("已接入的 API：{0}。", implementedApiSummary()) + "\n" +
            tr("未接入的方法会返回 UNSUPPORTED；不支持多窗口（windows.create）、idle 状态查询，子框架事件的 frameId 固定为 0；MV3 service worker 用隐藏页面模拟。") + "\n" +
            tr("APP 已原生支持油猴脚本，请勿再安装油猴类拓展")

    /** 按原生实际注册的 API 生成（见 [ExtensionSystem.registeredApis]），新增 / 移除 API 后文案自动跟着变 */
    private fun implementedApiSummary(): String {
        val names = system.registeredApis().map { it.substringBefore('.') }.toSortedSet()
        // action 与 browserAction 同一套实现；mafari 是 Mafari 自己的脚本接口；contentScripts 并入 scripting 说明
        val shown = names.filter { it != "browserAction" && it != "contentScripts" }.map { ns ->
            when (ns) {
                "mafari" -> tr("mafari（原生脚本）")
                "tabs" -> tr("tabs（单窗口）")
                "webNavigation" -> tr("webNavigation（仅主框架）")
                else -> ns
            }
        }
        return shown.joinToString(tr("、"))
    }

    override fun bind(context: Context) {
        activityRef = WeakReference(context.asActivity())
    }

    // ------------------------------------------------------------------ 列表

    override suspend fun refresh(context: Context) {
        loadingState.value = true
        try {
            val snapshot = withContext(Dispatchers.IO) {
                system.repo.list()
                    .filter { EngineKind.WEBVIEW in it.engines }
                    .map { Triple(it.spec.id, toEntry(it), loadIcon(it)) }
            }
            list.clear()
            list.addAll(snapshot.map { it.second })
            icons.clear()
            snapshot.forEach { (id, _, bmp) -> if (bmp != null) icons[id] = bmp }
        } finally {
            loadingState.value = false
        }
    }

    private fun toEntry(e: InstalledExtension): ExtEntry {
        val s = e.spec
        return ExtEntry(
            id = s.id,
            name = s.name,
            version = s.version,
            description = s.description,
            enabled = e.enabled,
            // 设置页 / 功能菜单由管理页里的 WebViewExtPageSheet 承载（需要拓展 bridge，不能当普通网页在标签里开）
            optionsUrl = null,
            permissions = describePermissions(e),
            notes = compatNotes(e),
            hasPopup = !system.actionApi.state(e).popup.isNullOrBlank(),
            hasOptionsPage = !s.optionsPage.isNullOrBlank(),
        )
    }

    // ------------------------------------------------------------------ 商店（Firefox 拓展商店，与 Gecko 内核同一来源）

    override suspend fun searchStore(query: String): Result<List<AmoAddon>> = AmoClient.search(query)

    override suspend fun resolveStorePage(url: String): Result<AmoAddon?> = AmoClient.resolvePage(url)

    // ------------------------------------------------------------------ 安装

    override suspend fun install(context: Context, uri: String): Result<String> = runCatching {
        val source = withContext(Dispatchers.IO) { fetch(context, uri) }
        val prepared = try {
            withContext(Dispatchers.IO) {
                system.installer.prepare(source, uri.takeIf { it.startsWith("http", ignoreCase = true) })
            }
        } finally {
            deleteIfOurs(context, source)
        }
        installPrepared(context, prepared)
    }

    /** 权限确认 → commit → 启动后台页 → 刷新界面。取消 / 失败时清掉暂存目录 */
    private suspend fun installPrepared(context: Context, p: PreparedPackage): String {
        val spec = p.spec
        var previous: InstalledExtension? = null
        try {
            if (EngineKind.WEBVIEW !in p.engines) error(tr("此拓展不能在 WebView 内核上运行"))
            previous = withContext(Dispatchers.IO) { system.repo.get(spec.id) }
            val newApi = spec.apiPermissions - (previous?.grantedApiPermissions ?: emptySet())
            val oldHosts = previous?.grantedHostPermissions?.map { it.raw }?.toSet() ?: emptySet()
            val newHosts = spec.hostPermissions.map { it.raw }.filter { it !in oldHosts }
            // 全新安装一定确认；更新只在新增权限时确认
            if (previous == null || newApi.isNotEmpty() || newHosts.isNotEmpty()) {
                val title = if (previous == null) tr("添加 {0}？", spec.name) else tr("更新 {0}？", spec.name)
                if (!confirm(title, confirmMessage(p))) error(tr("已取消安装"))
            }
        } catch (t: Throwable) {
            system.installer.discard(p)
            throw t
        }
        val ext = try {
            withContext(Dispatchers.IO) { system.installer.commit(p, spec.apiPermissions, spec.hostPermissions) }
        } catch (t: Throwable) {
            system.installer.discard(p)
            throw t
        }
        withContext(Dispatchers.Main) { system.onInstalled(ext, previous) }
        refresh(context)
        onChanged()
        return spec.name
    }

    private fun confirmMessage(p: PreparedPackage): String = buildString {
        val s = p.spec
        append(s.name).append("  ").append(s.version)
        if (s.description.isNotBlank()) append('\n').append(s.description)
        val perms = describe(s.apiPermissions, s.hostPermissions.map { it.raw } + hostsOfContentScripts(s))
        append("\n\n")
        if (perms.isEmpty()) {
            append(tr("此拓展不需要额外权限。"))
        } else {
            append(tr("此拓展将可以：\n"))
            perms.forEach { append("· ").append(it).append('\n') }
        }
        val notes = compatNotes(s.compat)
        if (notes.isNotEmpty()) {
            append('\n').append(tr("兼容性提示：")).append('\n')
            notes.forEach { append("· ").append(it).append('\n') }
        }
    }.trimEnd()

    private suspend fun confirm(title: String, message: String): Boolean = withContext(Dispatchers.Main) {
        val act = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?: error(tr("无法弹出安装确认框"))
        suspendCancellableCoroutine<Boolean> { cont ->
            val dialog = AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(tr("添加")) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton(tr("取消")) { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .create()
            cont.invokeOnCancellation { runCatching { dialog.dismiss() } }
            dialog.show()
        }
    }

    /** http(s) 链接下载到缓存；file:// 直接用。返回的文件由调用方用 [deleteIfOurs] 清理 */
    private fun fetch(context: Context, uri: String): File {
        if (uri.startsWith("file://")) {
            val path = Uri.parse(uri).path ?: error(tr("读取文件失败"))
            return File(path).takeIf { it.isFile } ?: error(tr("读取文件失败"))
        }
        if (!uri.startsWith("http://", ignoreCase = true) && !uri.startsWith("https://", ignoreCase = true)) {
            error(tr("请输入 http / https 链接"))
        }
        val res = HttpFetcher.get(
            uri,
            // 拓展是会在网页里执行的代码：公网上必须 https（防中间人篡改），本机 / 局域网自架的服务器可以用 http
            HttpFetcher.Options(
                connectTimeoutMs = 15_000,
                readTimeoutMs = 60_000,
                maxBytes = MAX_PACKAGE_BYTES,
                httpsOnly = true,
                allowLocalHttp = true,
            ),
        )
        val dir = File(context.cacheDir, "webext_import").apply { mkdirs() }
        val target = File(dir, "dl_${System.currentTimeMillis()}.pkg")
        target.writeBytes(res.bytes)
        return target
    }

    /** 只删我们自己放在缓存目录里的暂存文件，绝不动用户的原文件 */
    private fun deleteIfOurs(context: Context, f: File) {
        runCatching {
            val root = File(context.cacheDir, "webext_import").canonicalPath + File.separator
            if (f.canonicalPath.startsWith(root)) f.delete()
        }
    }

    override suspend fun stageFile(context: Context, source: Uri): String = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "webext_import").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "import_${System.currentTimeMillis()}.pkg")
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: error(tr("读取文件失败"))
        "file://" + target.absolutePath
    }

    // ------------------------------------------------------------------ 管理

    override suspend fun setEnabled(context: Context, id: String, enabled: Boolean): Result<Unit> = runCatching {
        val updated = withContext(Dispatchers.IO) {
            val ext = system.repo.get(id) ?: error("missing")
            system.repo.put(ext.copy(enabled = enabled))
            system.repo.get(id) ?: ext
        }
        withContext(Dispatchers.Main) {
            if (enabled) system.onEnabled(updated) else system.onDisabled(id)
        }
        refresh(context)
        onChanged()
    }.recoverCatching { throw IllegalStateException(if (enabled) tr("启用失败") else tr("停用失败")) }

    override suspend fun setAllowedInPrivate(context: Context, id: String, allowed: Boolean): Result<Unit> =
        Result.failure(IllegalStateException(tr("当前内核不支持")))

    override suspend fun uninstall(context: Context, id: String): Result<Unit> = runCatching {
        withContext(Dispatchers.Main) { system.onUninstalled(id) }
        refresh(context)
        onChanged()
    }.recoverCatching { throw IllegalStateException(tr("卸载失败")) }

    override suspend fun updateOne(context: Context, id: String): Result<Boolean> = runCatching {
        val ext = withContext(Dispatchers.IO) { system.repo.get(id) } ?: error(tr("检查更新失败"))
        updateFromSource(context, ext)
    }

    override suspend fun updateAll(context: Context): Pair<Int, Int> {
        val all = withContext(Dispatchers.IO) { system.repo.list().filter { EngineKind.WEBVIEW in it.engines } }
        var updated = 0
        for (ext in all) {
            if (runCatching { updateFromSource(context, ext) }.getOrDefault(false)) updated++
        }
        return updated to all.size
    }

    /** 按安装时记下的链接重新下载，版本号不同才更新。本地文件装的没有链接，无法在线更新 */
    private suspend fun updateFromSource(context: Context, ext: InstalledExtension): Boolean {
        val url = ext.sourceUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: error(tr("本地导入的拓展无法在线更新，请重新导入新版本"))
        val file = withContext(Dispatchers.IO) { fetch(context, url) }
        val prepared = try {
            withContext(Dispatchers.IO) { system.installer.prepare(file, url, forcedId = ext.spec.id) }
        } finally {
            deleteIfOurs(context, file)
        }
        if (prepared.spec.version == ext.spec.version) {
            system.installer.discard(prepared)
            return false
        }
        installPrepared(context, prepared)
        return true
    }

    // ------------------------------------------------------------------ 文案

    private fun describePermissions(e: InstalledExtension): List<String> =
        describe(e.spec.apiPermissions, e.spec.hostPermissions.map { it.raw } + hostsOfContentScripts(e.spec))

    /** content script 会在它 matches 的网站上运行，等同于对这些网站有读写权限，所以也列出来 */
    private fun hostsOfContentScripts(s: ExtensionSpec): List<String> =
        s.contentScripts.flatMap { cs -> cs.matches.map { it.raw } }

    private fun describe(apis: Set<String>, hosts: List<String>): List<String> = buildList {
        apis.forEach { add(permissionText(it)) }
        val distinct = hosts.distinct()
        val allSites = distinct.any { it == "<all_urls>" || it == "*://*/*" || it == "http://*/*" || it == "https://*/*" }
        if (allSites) {
            add(tr("访问所有网站的数据"))
        } else {
            distinct.take(5).forEach { add(tr("访问 {0}", it)) }
            if (distinct.size > 5) add(tr("以及另外 {0} 个网站", distinct.size - 5))
        }
    }

    private fun permissionText(p: String): String = when (p) {
        "storage" -> tr("保存拓展自己的数据")
        "unlimitedStorage" -> tr("不受限制地保存数据")
        "tabs" -> tr("读取标签页的网址和标题")
        "activeTab" -> tr("访问当前标签页")
        "scripting" -> tr("向网页注入脚本")
        "contextMenus", "menus" -> tr("添加右键菜单")
        "alarms" -> tr("设置定时任务")
        "notifications" -> tr("显示通知")
        "cookies" -> tr("读取和修改 Cookie")
        "webNavigation" -> tr("读取页面导航记录")
        "webRequest" -> tr("监控网页网络请求")
        "webRequestBlocking" -> tr("阻止或重定向网页网络请求")
        "mafariScripts" -> tr("读取、创建、修改和删除 Mafari 原生脚本")
        "declarativeNetRequest", "declarativeNetRequestWithHostAccess" -> tr("拦截或修改网络请求")
        "clipboardWrite" -> tr("写入剪贴板")
        "clipboardRead" -> tr("读取剪贴板")
        else -> p
    }

    private fun compatNotes(e: InstalledExtension): List<String> = compatNotes(e.spec.compat)

    private fun compatNotes(compat: List<CompatIssue>): List<String> = compat.mapNotNull { c ->
        if (c.level == SupportLevel.FULL) return@mapNotNull null
        val f = c.feature
        val state = when (c.level) {
            SupportLevel.PARTIAL -> tr("部分支持")
            SupportLevel.PLANNED -> tr("尚未实现")
            else -> tr("不支持")
        }
        when {
            f.startsWith("permission:") -> "${f.removePrefix("permission:")}：$state"
            f.startsWith("manifest:") -> "${f.removePrefix("manifest:")}：$state"
            f == "background.service_worker" -> tr("后台 service worker：用隐藏页面模拟，不会休眠")
            else -> "$f：$state"
        }
    }

    // ------------------------------------------------------------------ 图标

    /** 取最接近 96px 的 PNG / JPEG / WebP 图标并按需缩小；SVG 等解不出来就返回 null（界面画占位色块） */
    private fun loadIcon(e: InstalledExtension): ImageBitmap? = runCatching {
        val entry = e.spec.icons.entries.minByOrNull { kotlin.math.abs(it.key - 96) } ?: return@runCatching null
        val rel = PackageUnpacker.safeRelativePath(entry.value.trimStart('/')) ?: return@runCatching null
        val root = e.rootDir.canonicalFile
        val f = File(root, rel).canonicalFile
        if (!f.path.startsWith(root.path + File.separator) || !f.isFile) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ICON_PX && bounds.outHeight / (sample * 2) >= ICON_PX) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()

    private companion object {
        const val MAX_PACKAGE_BYTES = 32 * 1024 * 1024
        const val ICON_PX = 128
    }
}
