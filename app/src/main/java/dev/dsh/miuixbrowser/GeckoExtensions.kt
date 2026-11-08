package dev.dsh.miuixbrowser

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gecko 专属「拓展」：对 GeckoView `WebExtensionController` 的封装。
 *
 * - 安装 / 卸载 / 启用 / 停用 / 更新 / 无痕允许 都走 WebExtensionController；
 * - 搜索走 Firefox 附加组件商店（AMO）公开 API，安装时把商店给的 xpi 直链交给 Gecko 下载并校验签名；
 * - 「安装 / 权限」确认由 [GeckoExtensionStore.prompt] 暴露给界面，界面决定后回调给 Gecko；
 * - 定期检查更新：按 [autoUpdate] 的间隔，在 Gecko 已经启动时于启动 / 打开拓展页时检查一次。
 *
 * 所有对 Gecko 的调用都必须在主线程发起（GeckoResult 的回调才会回到主线程）。
 */

/** 已安装拓展的只读快照（界面用）。真正的 [WebExtension] 对象留在 store 内部按 id 索引。 */
data class GeckoAddon(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val enabled: Boolean,
    val allowedInPrivate: Boolean,
    val optionsUrl: String,
    val homepageUrl: String,
    val amoUrl: String,
    val creator: String,
    val permissions: List<String>,
    val origins: List<String>,
    val icon: ImageBitmap? = null,
)

/** AMO 搜索结果 */
data class AmoResult(
    val guid: String,
    val name: String,
    val summary: String,
    val iconUrl: String,
    val xpiUrl: String,
    val version: String,
    val author: String,
    val users: Long,
    val pageUrl: String,
)

/** 安装 / 更新时需要用户确认的权限请求 */
class GeckoExtPrompt(
    val name: String,
    val permissions: List<String>,
    val origins: List<String>,
    val dataCollection: List<String>,
    /** true = 更新带来了新权限；false = 首次安装 */
    val isUpdate: Boolean,
    private val onDecide: (granted: Boolean, privateAllowed: Boolean) -> Unit,
) {
    fun allow(privateAllowed: Boolean) = onDecide(true, privateAllowed)
    fun deny() = onDecide(false, false)
}

class GeckoExtensionStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("mafari_gecko_ext", Context.MODE_PRIVATE)

    /** 已安装的拓展（不含内置拓展） */
    val items = mutableStateListOf<GeckoAddon>()

    /** 正在等待用户确认的权限请求（界面弹窗） */
    var prompt by mutableStateOf<GeckoExtPrompt?>(null)
        private set

    /** 拓展页是否正在显示：不可见时的「更新带来新权限」直接拒绝，避免回调永远悬挂 */
    var pageVisible = false

    /** 拓展要求打开页面（设置页 / tabs.create）时调用，由 BrowserScreen 接到「新建标签」 */
    var openUrl: ((String) -> Unit)? = null

    /** 自动检查更新：0 手动 1 每天 2 每 3 天 3 每周（与脚本 / 去广告一致） */
    var autoUpdate by mutableIntStateOf(prefs.getInt(KEY_AUTO, 1).coerceIn(0, 3))
        private set

    /** 上次检查更新的时间（毫秒），0 = 从未 */
    var lastCheck by mutableLongStateOf(prefs.getLong(KEY_LAST, 0L))
        private set

    var loading by mutableStateOf(false)
        private set

    private val extensions = HashMap<String, WebExtension>()
    private var attached = false

    fun updateAutoUpdate(mode: Int) {
        autoUpdate = mode.coerceIn(0, 3)
        prefs.edit().putInt(KEY_AUTO, autoUpdate).apply()
    }

    /** Gecko 已经启动过（有 Gecko 标签）才算「可用」；不为了看一眼而启动 Gecko */
    val runtimeReady: Boolean get() = GeckoRuntimeHolder.peek() != null

    private fun controller(): WebExtensionController {
        val controller = GeckoRuntimeHolder.get(app).webExtensionController
        if (!attached) {
            attached = true
            controller.setPromptDelegate(promptDelegate)
        }
        return controller
    }

    /* ------------------------------------------------------------------ */
    /* 列表                                                                */
    /* ------------------------------------------------------------------ */

    /** 重新读取已安装拓展 */
    suspend fun refresh() {
        loading = true
        try {
            val list = controller().list().await().orEmpty().filter { !it.isBuiltIn }
            extensions.clear()
            list.forEach { ext ->
                extensions[ext.id] = ext
                bindDelegates(ext)
            }
            val old = items.associateBy { it.id }
            val snapshot = list.map { ext -> snapshotOf(ext, old[ext.id]?.icon) }
            items.clear()
            items.addAll(snapshot.sortedBy { it.name.lowercase() })
            // 图标异步补上
            list.forEach { ext -> if (old[ext.id]?.icon == null) loadIcon(ext) }
        } finally {
            loading = false
        }
    }

    private fun snapshotOf(ext: WebExtension, icon: ImageBitmap?): GeckoAddon {
        val m = ext.metaData
        return GeckoAddon(
            id = ext.id,
            name = m.name?.takeIf { it.isNotBlank() } ?: ext.id,
            version = m.version,
            description = m.description.orEmpty(),
            enabled = m.enabled,
            allowedInPrivate = m.allowedInPrivateBrowsing,
            optionsUrl = m.optionsPageUrl.orEmpty(),
            homepageUrl = m.homepageUrl.orEmpty(),
            amoUrl = m.amoListingUrl.orEmpty(),
            creator = m.creatorName.orEmpty(),
            permissions = m.requiredPermissions.toList(),
            origins = m.requiredOrigins.toList(),
            icon = icon,
        )
    }

    private fun loadIcon(ext: WebExtension) {
        runCatching {
            ext.metaData.icon.getBitmap(96).accept({ bitmap ->
                if (bitmap == null) return@accept
                val index = items.indexOfFirst { it.id == ext.id }
                if (index >= 0) items[index] = items[index].copy(icon = bitmap.asImageBitmap())
            }, { })
        }
    }

    /** 拓展自己要求开设置页 / 新标签：接到应用自己的标签页逻辑 */
    private fun bindDelegates(ext: WebExtension) {
        ext.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onNewTab(
                source: WebExtension,
                createDetails: WebExtension.CreateTabDetails,
            ): GeckoResult<org.mozilla.geckoview.GeckoSession>? {
                val url = createDetails.url
                if (!url.isNullOrBlank()) openUrl?.invoke(url)
                return null
            }

            override fun onOpenOptionsPage(source: WebExtension) {
                val url = source.metaData.optionsPageUrl
                if (!url.isNullOrBlank()) openUrl?.invoke(url)
            }
        })
    }

    /** 打开某个拓展的设置页（没有设置页返回 false） */
    fun openOptions(addon: GeckoAddon): Boolean {
        if (addon.optionsUrl.isBlank()) return false
        openUrl?.invoke(addon.optionsUrl)
        return addon.optionsUrl.isNotBlank()
    }

    /* ------------------------------------------------------------------ */
    /* 安装 / 卸载 / 开关                                                   */
    /* ------------------------------------------------------------------ */

    /** 从链接安装：AMO 页面 / xpi 直链都行（页面链接会先解析成 xpi 直链）。成功返回拓展名。 */
    suspend fun installFromUrl(raw: String): Result<String> {
        val url = raw.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true) && !url.startsWith("file:", true)) {
            return Result.failure(IllegalArgumentException("请输入 http / https 链接"))
        }
        return runCatching {
            val xpi = resolveXpiUrl(url)
            val ext = controller().install(xpi).await() ?: error("安装失败")
            refresh()
            ext.metaData.name ?: ext.id
        }.recoverCatching { throw IllegalStateException(friendly(it)) }
    }

    /** 从本地 .xpi 文件安装：先复制到缓存目录，再交给 Gecko（content:// 它读不到） */
    suspend fun installFromFile(uri: Uri): Result<String> {
        val file = File(app.cacheDir, "mafari_ext_${System.currentTimeMillis()}.xpi")
        val copied = withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { out -> input.copyTo(out) }
                } ?: error("读取文件失败")
            }
        }
        copied.exceptionOrNull()?.let { return Result.failure(IllegalStateException("读取文件失败")) }
        return try {
            runCatching {
                val ext = controller().install(Uri.fromFile(file).toString()).await() ?: error("安装失败")
                refresh()
                ext.metaData.name ?: ext.id
            }.recoverCatching { throw IllegalStateException(friendly(it)) }
        } finally {
            runCatching { file.delete() }
        }
    }

    suspend fun uninstall(addon: GeckoAddon): Result<Unit> = runCatching {
        val ext = extensions[addon.id] ?: error("拓展不存在")
        controller().uninstall(ext).await()
        refresh()
    }.recoverCatching { throw IllegalStateException(friendly(it)) }

    suspend fun setEnabled(addon: GeckoAddon, enabled: Boolean): Result<Unit> = runCatching {
        val ext = extensions[addon.id] ?: error("拓展不存在")
        val c = controller()
        if (enabled) {
            c.enable(ext, WebExtensionController.EnableSource.USER).await()
        } else {
            c.disable(ext, WebExtensionController.EnableSource.USER).await()
        }
        refresh()
    }.recoverCatching { throw IllegalStateException(friendly(it)) }

    suspend fun setAllowedInPrivate(addon: GeckoAddon, allowed: Boolean): Result<Unit> = runCatching {
        val ext = extensions[addon.id] ?: error("拓展不存在")
        controller().setAllowedInPrivateBrowsing(ext, allowed).await()
        refresh()
    }.recoverCatching { throw IllegalStateException(friendly(it)) }

    /* ------------------------------------------------------------------ */
    /* 更新                                                                */
    /* ------------------------------------------------------------------ */

    /** 检查并更新单个拓展。返回 true = 有新版本并已更新，false = 已是最新。 */
    suspend fun update(addon: GeckoAddon): Result<Boolean> = runCatching {
        val ext = extensions[addon.id] ?: error("拓展不存在")
        val before = ext.metaData.version
        val updated = controller().update(ext).await()
        refresh()
        updated != null && updated.metaData.version != before
    }.recoverCatching { throw IllegalStateException(friendly(it)) }

    /** 全部更新，返回 (有更新的数量, 检查成功的数量, 总数)；同时记下检查时间 */
    suspend fun updateAll(): Triple<Int, Int, Int> {
        var updated = 0
        var checked = 0
        val targets = items.toList()
        for (addon in targets) {
            val r = update(addon)
            if (r.isSuccess) {
                checked++
                if (r.getOrDefault(false)) updated++
            }
        }
        markChecked()
        return Triple(updated, checked, targets.size)
    }

    private fun markChecked() {
        lastCheck = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST, lastCheck).apply()
    }

    /** 按设置的间隔检查更新；只在 Gecko 已经启动时进行（不为了检查更新而拉起 Gecko） */
    suspend fun autoUpdateIfDue() {
        val days = when (autoUpdate) {
            1 -> 1
            2 -> 3
            3 -> 7
            else -> return
        }
        if (!runtimeReady) return
        if (System.currentTimeMillis() - lastCheck < days * 86_400_000L) return
        runCatching {
            refresh()
            if (items.isNotEmpty()) updateAll() else markChecked()
        }
    }

    /* ------------------------------------------------------------------ */
    /* AMO（Firefox 附加组件商店）                                          */
    /* ------------------------------------------------------------------ */

    /** 搜索商店里支持 Firefox for Android 的拓展 */
    suspend fun search(query: String): Result<List<AmoResult>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query.trim().take(100), "UTF-8")
            val text = httpGet("https://addons.mozilla.org/api/v5/addons/search/?q=$q&app=android&type=extension&sort=users&page_size=20&lang=zh-CN")
            val results = JSONObject(text).optJSONArray("results") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until results.length()) {
                    val o = results.optJSONObject(i) ?: continue
                    val xpi = o.optJSONObject("current_version")?.optJSONObject("file")?.optString("url").orEmpty()
                    val guid = o.optString("guid")
                    if (xpi.isBlank() || guid.isBlank()) continue
                    add(
                        AmoResult(
                            guid = guid,
                            name = localized(o.opt("name")).ifBlank { guid },
                            summary = localized(o.opt("summary")),
                            iconUrl = o.optString("icon_url"),
                            xpiUrl = xpi,
                            version = o.optJSONObject("current_version")?.optString("version").orEmpty(),
                            author = o.optJSONArray("authors")?.optJSONObject(0)?.optString("name").orEmpty(),
                            users = o.optLong("average_daily_users"),
                            pageUrl = o.optString("url"),
                        ),
                    )
                }
            }
        }.recoverCatching { throw IllegalStateException("搜索失败，检查网络（需要能访问 addons.mozilla.org）") }
    }

    /** 从商店结果安装 */
    suspend fun installFromAmo(result: AmoResult): Result<String> = runCatching {
        val ext = controller().install(result.xpiUrl).await() ?: error("安装失败")
        refresh()
        ext.metaData.name ?: result.name
    }.recoverCatching { throw IllegalStateException(friendly(it)) }

    /** AMO 详情页链接 → 用 API 解析出 xpi 直链；其它链接原样返回 */
    private suspend fun resolveXpiUrl(url: String): String {
        val lower = url.lowercase()
        if (!lower.contains("addons.mozilla.org") || lower.endsWith(".xpi") || lower.contains("/downloads/")) return url
        // https://addons.mozilla.org/zh-CN/firefox/addon/<slug>/
        val slug = Regex("/addon/([^/?#]+)").find(url)?.groupValues?.get(1) ?: return url
        return withContext(Dispatchers.IO) {
            runCatching {
                val text = httpGet("https://addons.mozilla.org/api/v5/addons/addon/$slug/")
                JSONObject(text).optJSONObject("current_version")?.optJSONObject("file")?.optString("url")
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
        }
    }

    private fun httpGet(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 mafari")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** AMO 的多语言字段：带 lang 时是字符串；不带时是 {locale: text} 对象 */
    private fun localized(v: Any?): String = when (v) {
        is String -> v
        is JSONObject -> v.optString("zh-CN").ifBlank { v.optString("en-US") }
            .ifBlank { v.keys().asSequence().firstNotNullOfOrNull { k -> v.optString(k).takeIf { s -> s.isNotBlank() } }.orEmpty() }
        else -> ""
    }

    /* ------------------------------------------------------------------ */
    /* 权限确认                                                            */
    /* ------------------------------------------------------------------ */

    private val promptDelegate = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>,
        ): GeckoResult<WebExtension.PermissionPromptResponse>? {
            val result = GeckoResult<WebExtension.PermissionPromptResponse>()
            val shown = showPrompt(
                GeckoExtPrompt(
                    name = extension.metaData.name ?: extension.id,
                    permissions = permissions.toList(),
                    origins = origins.toList(),
                    dataCollection = dataCollectionPermissions.toList(),
                    isUpdate = false,
                    onDecide = { granted, privateAllowed ->
                        prompt = null
                        result.complete(WebExtension.PermissionPromptResponse(granted, privateAllowed, false))
                    },
                ),
            )
            if (!shown) result.complete(WebExtension.PermissionPromptResponse(false, false, false))
            return result
        }

        override fun onUpdatePrompt(
            extension: WebExtension,
            newPermissions: Array<String>,
            newOrigins: Array<String>,
            newDataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny>? {
            val result = GeckoResult<AllowOrDeny>()
            val shown = showPrompt(
                GeckoExtPrompt(
                    name = extension.metaData.name ?: extension.id,
                    permissions = newPermissions.toList(),
                    origins = newOrigins.toList(),
                    dataCollection = newDataCollectionPermissions.toList(),
                    isUpdate = true,
                    onDecide = { granted, _ ->
                        prompt = null
                        result.complete(if (granted) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                    },
                ),
            )
            if (!shown) result.complete(AllowOrDeny.DENY)
            return result
        }

        override fun onOptionalPrompt(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny>? {
            // 拓展运行中申请的「可选权限」：同样交给用户确认
            val result = GeckoResult<AllowOrDeny>()
            val shown = showPrompt(
                GeckoExtPrompt(
                    name = extension.metaData.name ?: extension.id,
                    permissions = permissions.toList(),
                    origins = origins.toList(),
                    dataCollection = dataCollectionPermissions.toList(),
                    isUpdate = true,
                    onDecide = { granted, _ ->
                        prompt = null
                        result.complete(if (granted) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                    },
                ),
            )
            if (!shown) result.complete(AllowOrDeny.DENY)
            return result
        }
    }

    /** 拓展页不在前台、或已有一个确认弹窗时返回 false（调用方按「拒绝」处理） */
    private fun showPrompt(p: GeckoExtPrompt): Boolean {
        if (!pageVisible || prompt != null) return false
        prompt = p
        return true
    }

    /** 报错信息本地化：不把 Gecko 的英文异常直接甩给用户 */
    private fun friendly(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            msg.contains("CANCELED", true) || msg.contains("cancel", true) -> "已取消安装"
            msg.contains("NETWORK", true) || msg.contains("Unable to resolve", true) -> "下载失败，检查网络"
            msg.contains("SIGNEDSTATE", true) || msg.contains("signature", true) -> "拓展未通过签名校验"
            msg.contains("INCOMPATIBLE", true) -> "该拓展与当前 Gecko 版本不兼容"
            msg.contains("BLOCKLISTED", true) -> "该拓展已被 Mozilla 屏蔽"
            msg.contains("CORRUPT", true) || msg.contains("INVALID", true) -> "不是有效的拓展文件（.xpi）"
            msg.isNotBlank() && msg.any { it.code > 127 } -> msg // 已经是中文提示
            else -> "操作失败" + if (msg.isNotBlank()) "：" + msg.take(60) else ""
        }
    }

    private companion object {
        const val KEY_AUTO = "auto_update"
        const val KEY_LAST = "last_check"
    }
}

/** GeckoResult → 协程挂起；必须在带 Looper 的线程（主线程）调用 */
private suspend fun <T> GeckoResult<T>.await(): T? = suspendCancellableCoroutine { cont ->
    accept({ value -> if (cont.isActive) cont.resume(value) }, { error ->
        if (cont.isActive) cont.resumeWithException(error ?: RuntimeException("unknown"))
    })
}

/** 权限名 → 中文说明（没有收录的直接显示原名） */
fun describePermission(name: String): String = when (name) {
    "activeTab" -> "访问当前打开的标签页"
    "tabs" -> "读取标签页的网址和标题"
    "tabHide" -> "隐藏和显示标签页"
    "bookmarks" -> "读取和修改书签"
    "history" -> "读取浏览历史"
    "cookies" -> "读取和修改 Cookie"
    "storage", "unlimitedStorage" -> "保存拓展自己的数据"
    "downloads" -> "管理下载"
    "downloads.open" -> "下载并打开文件"
    "clipboardRead" -> "读取剪贴板"
    "clipboardWrite" -> "写入剪贴板"
    "notifications" -> "显示通知"
    "webRequest", "webRequestBlocking" -> "监视并拦截网络请求"
    "webNavigation" -> "访问浏览过程中的导航信息"
    "contextMenus", "menus" -> "添加长按菜单项"
    "nativeMessaging" -> "与设备上的其它程序通信"
    "geolocation" -> "获取你的位置"
    "privacy" -> "读取和修改隐私设置"
    "proxy" -> "控制浏览器的代理设置"
    "management" -> "管理其它拓展"
    "search" -> "使用搜索引擎"
    "topSites" -> "读取常用网站"
    "find" -> "在页面中查找内容"
    "scripting" -> "在网页中运行脚本"
    "declarativeNetRequest", "declarativeNetRequestWithHostAccess", "declarativeNetRequestFeedback" -> "拦截和修改网络请求"
    "dns" -> "解析域名"
    "identity" -> "登录账号（OAuth）"
    "alarms" -> "定时运行"
    "idle" -> "检测设备是否空闲"
    "sessions" -> "读取最近关闭的标签页"
    "theme" -> "修改浏览器主题"
    "browserSettings" -> "修改浏览器设置"
    "captivePortal" -> "检测网络登录门户"
    "technicalAndInteraction" -> "收集技术和交互数据"
    "locationInfo" -> "收集位置信息"
    "browsingActivity" -> "收集浏览活动"
    "searchTerms" -> "收集搜索词"
    "websiteContent" -> "收集网站内容"
    "websiteActivity" -> "收集网站活动"
    "personallyIdentifyingInfo" -> "收集个人身份信息"
    "healthInfo" -> "收集健康信息"
    "financialAndPaymentInfo" -> "收集财务和支付信息"
    "authenticationInfo" -> "收集身份验证信息"
    "personalCommunications" -> "收集个人通讯内容"
    else -> name
}

/** 站点匹配 → 中文说明 */
fun describeOrigin(origin: String): String = when {
    origin == "<all_urls>" || origin == "*://*/*" || origin == "http://*/*" || origin == "https://*/*" ||
        origin == "*://*/" -> "访问你在所有网站上的数据"
    else -> "访问 " + origin.removePrefix("*://").removePrefix("https://").removePrefix("http://")
        .removeSuffix("/*").removeSuffix("/") + " 的数据"
}
