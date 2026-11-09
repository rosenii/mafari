package dev.dsh.miuixbrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/** 已安装的拓展（界面用的快照） */
data class GeckoExtItem(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val enabled: Boolean,
    val optionsUrl: String?,
    val permissions: List<String>,
    val origins: List<String>,
    val ext: WebExtension,
)

/** Firefox 拓展商店（AMO）搜索结果 */
data class AmoAddon(
    val id: Long,
    val slug: String,
    val name: String,
    val summary: String,
    val iconUrl: String,
    val xpiUrl: String,
    val version: String,
    val users: Long,
    val rating: Double,
)

/**
 * Gecko WebExtension 的应用层封装：列表 / 安装 / 更新 + AMO 搜索。
 *
 * GeckoView 的 `WebExtensionController` 在不同 nightly 之间签名有过调整（install 的参数、提示委托的拆分），
 * 所以安装与提示委托这两处按名字反射 / 动态代理，缺方法时报错而不是编译失败。
 */
object GeckoExtensionStore {

    val items = mutableStateListOf<GeckoExtItem>()
    val icons = mutableStateMapOf<String, ImageBitmap>()
    val loading = mutableStateOf(false)

    /** 弹安装 / 权限确认框需要 Activity，由「拓展」页进入时登记 */
    @Volatile
    var activityProvider: (() -> Activity?)? = null

    private var delegateInstalled = false

    private fun controller(context: Context) = GeckoRuntimeHolder.get(context).webExtensionController

    // ------------------------------------------------------------------ 列表

    suspend fun refresh(context: Context) {
        loadPrefs(context)
        loading.value = true
        try {
            val runtime = GeckoRuntimeHolder.get(context)
            ensureDelegate(runtime)
            val list = runtime.webExtensionController.list().await() ?: emptyList()
            items.clear()
            items.addAll(list.filter { !it.isBuiltIn }.map { toItem(it) }.sortedBy { it.name.lowercase() })
            items.forEach { loadIcon(it) }
        } finally {
            loading.value = false
        }
    }

    private fun toItem(ext: WebExtension): GeckoExtItem {
        val meta = ext.metaData
        return GeckoExtItem(
            id = ext.id,
            name = meta?.name?.takeIf { it.isNotBlank() } ?: ext.id,
            version = meta?.version.orEmpty(),
            description = meta?.description.orEmpty(),
            enabled = meta?.enabled ?: true,
            optionsUrl = meta?.optionsPageUrl?.takeIf { it.isNotBlank() },
            permissions = metaStrings(meta, "requiredPermissions", "permissions"),
            origins = metaStrings(meta, "requiredOrigins", "origins"),
            ext = ext,
        )
    }

    /** MetaData 的权限字段在不同 GeckoView 版本里叫法不同（permissions / requiredPermissions…），按名字反射读取 */
    private fun metaStrings(meta: Any?, vararg names: String): List<String> {
        if (meta == null) return emptyList()
        for (n in names) {
            val value = runCatching { meta.javaClass.getField(n).get(meta) }.getOrNull()
            if (value is Array<*>) return value.filterIsInstance<String>()
            if (value is Collection<*>) return value.filterIsInstance<String>()
        }
        return emptyList()
    }

    private fun loadIcon(item: GeckoExtItem) {
        if (icons.containsKey(item.id)) return
        runCatching {
            item.ext.metaData?.icon?.getBitmap(96)?.accept({ bmp ->
                if (bmp != null) icons[item.id] = bmp.asImageBitmap()
            }, { })
        }
    }

    // ------------------------------------------------------------------ 安装

    /** 安装：uri 可以是 https 的 .xpi 链接或 file:// 本地文件；确认框由提示委托弹出 */
    suspend fun install(context: Context, uri: String): Result<GeckoExtItem> = runCatching {
        val runtime = GeckoRuntimeHolder.get(context)
        ensureDelegate(runtime)
        val controller = runtime.webExtensionController
        val result = invokeInstall(controller, uri)
        val ext = result.await() ?: error("安装失败")
        refresh(context)
        items.firstOrNull { it.id == ext.id } ?: toItem(ext)
    }.recoverCatching { throw IllegalStateException(friendlyError(it)) }

    private fun invokeInstall(controller: Any, uri: String): GeckoResult<WebExtension> {
        val methods = controller.javaClass.methods.filter { it.name == "install" }
        val two = methods.firstOrNull { it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java }
        val one = methods.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
        @Suppress("UNCHECKED_CAST")
        return when {
            two != null -> two.invoke(controller, uri, null)
            one != null -> one.invoke(controller, uri)
            else -> error("当前 GeckoView 版本不支持安装拓展")
        } as GeckoResult<WebExtension>
    }

    private fun friendlyError(t: Throwable): String {
        val raw = (t.cause?.message ?: t.message).orEmpty()
        return when {
            raw.contains("userCancelled", true) || raw.contains("cancel", true) -> "已取消安装"
            raw.contains("signature", true) -> "拓展签名无效"
            raw.contains("incompatible", true) -> "此拓展与当前内核不兼容"
            raw.contains("networkFailure", true) || raw.contains("network", true) -> "下载失败，检查网络或链接"
            raw.contains("corrupt", true) || raw.contains("invalid", true) -> "不是有效的拓展文件"
            raw.isBlank() -> "安装失败"
            else -> raw
        }
    }

    /** 把用户选的本地文件拷到缓存后返回 file:// 地址（Gecko 只认文件路径） */
    suspend fun stageFile(context: Context, source: Uri): String = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "gecko_ext_import").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "import_${System.currentTimeMillis()}.xpi")
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: error("读取文件失败")
        "file://" + target.absolutePath
    }

    // ------------------------------------------------------------------ 更新

    /** 检查并更新全部拓展，返回 (已更新数, 总数)；有权限变化的更新会弹确认框 */
    suspend fun updateAll(context: Context): Pair<Int, Int> {
        loadPrefs(context)
        val controller = controller(context)
        if (items.isEmpty()) refresh(context)
        var updated = 0
        val snapshot = items.toList()
        for (item in snapshot) {
            val before = item.version
            val after = runCatching { controller.update(item.ext).await() }.getOrNull()
            val newVersion = after?.metaData?.version
            if (after != null && !newVersion.isNullOrEmpty() && newVersion != before) updated++
        }
        markChecked(context)
        refresh(context)
        return updated to snapshot.size
    }

    // ------------------------------------------------------------------ 管理：启停 / 卸载 / 单个更新

    suspend fun setEnabled(context: Context, item: GeckoExtItem, enabled: Boolean): Result<Unit> = runCatching {
        val c = controller(context)
        val r = if (enabled) c.enable(item.ext, WebExtensionController.EnableSource.USER)
        else c.disable(item.ext, WebExtensionController.EnableSource.USER)
        r.await()
        refresh(context)
    }.recoverCatching { throw IllegalStateException(if (enabled) "启用失败" else "停用失败") }

    suspend fun uninstall(context: Context, item: GeckoExtItem): Result<Unit> = runCatching {
        controller(context).uninstall(item.ext).await()
        icons.remove(item.id)
        refresh(context)
    }.recoverCatching { throw IllegalStateException("卸载失败") }

    /** 检查单个拓展的更新：true = 已更新到新版本，false = 已是最新 */
    suspend fun updateOne(context: Context, item: GeckoExtItem): Result<Boolean> = runCatching {
        val after = controller(context).update(item.ext).await()
        val newVersion = after?.metaData?.version
        val changed = after != null && !newVersion.isNullOrEmpty() && newVersion != item.version
        if (changed) refresh(context)
        changed
    }.recoverCatching { throw IllegalStateException("检查更新失败，检查网络") }

    // ------------------------------------------------------------------ 定期检查更新

    /** 自动检查间隔：0 手动 / 1 每天 / 2 每 3 天 / 3 每周 */
    val autoUpdate = mutableStateOf(1)

    /** 上次检查更新的时间（毫秒，0 = 从未） */
    val lastCheck = mutableStateOf(0L)

    private var prefsLoaded = false
    private val INTERVALS_MS = longArrayOf(0L, 24L * 3_600_000, 3 * 24L * 3_600_000, 7 * 24L * 3_600_000)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("mafari_gecko_ext", Context.MODE_PRIVATE)

    private fun loadPrefs(context: Context) {
        if (prefsLoaded) return
        prefsLoaded = true
        val p = prefs(context)
        autoUpdate.value = p.getInt("auto_update", 1).coerceIn(0, 3)
        lastCheck.value = p.getLong("last_check", 0L)
    }

    fun updateAutoUpdate(context: Context, index: Int) {
        loadPrefs(context)
        autoUpdate.value = index.coerceIn(0, 3)
        prefs(context).edit().putInt("auto_update", autoUpdate.value).apply()
    }

    private fun markChecked(context: Context) {
        lastCheck.value = System.currentTimeMillis()
        prefs(context).edit().putLong("last_check", lastCheck.value).apply()
    }

    /**
     * 到点就静默检查全部拓展的更新（应用在前台期间每隔一段时间调用一次）。
     * 新版本要求更多权限时需要确认：此时没有「拓展」页的 Activity 登记，提示会被拒绝，下次手动更新时再确认。
     * @return 更新数；没到点 / 手动模式返回 null
     */
    suspend fun maybeAutoUpdate(context: Context): Int? {
        loadPrefs(context)
        val interval = INTERVALS_MS[autoUpdate.value]
        if (interval == 0L) return null
        if (System.currentTimeMillis() - lastCheck.value < interval) return null
        if (GeckoRuntimeHolder.peek() == null) return null   // 没用过 Gecko 就不为了检查更新去启动它
        val (updated, _) = updateAll(context)
        return updated
    }

    // ------------------------------------------------------------------ 提示委托（安装 / 更新 / 可选权限确认）

    private fun ensureDelegate(runtime: GeckoRuntime) {
        if (delegateInstalled) return
        val controller = runtime.webExtensionController
        var installedAny = false
        controller.javaClass.methods
            .filter { it.name.startsWith("set") && it.name.endsWith("PromptDelegate") && it.parameterTypes.size == 1 && it.parameterTypes[0].isInterface }
            .forEach { setter ->
                runCatching {
                    val iface = setter.parameterTypes[0]
                    val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { self, method, args ->
                        when {
                            method.name == "hashCode" -> System.identityHashCode(self)
                            method.name == "equals" -> self === args?.firstOrNull()
                            method.name == "toString" -> "GeckoExtensionPrompt"
                            method.name.contains("Prompt") && GeckoResult::class.java.isAssignableFrom(method.returnType) ->
                                askPermission(method.name, args)
                            else -> null
                        }
                    }
                    setter.invoke(controller, proxy)
                    installedAny = true
                }
            }
        delegateInstalled = installedAny
    }

    private val PERMISSION_NAMES = mapOf(
        "<all_urls>" to "访问所有网站的数据",
        "tabs" to "查看标签页的标题和地址",
        "activeTab" to "访问当前标签页",
        "cookies" to "读写 Cookie",
        "webRequest" to "监控网络请求",
        "webRequestBlocking" to "拦截 / 修改网络请求",
        "declarativeNetRequest" to "按规则拦截网络请求",
        "storage" to "保存拓展自己的数据",
        "unlimitedStorage" to "无限存储",
        "downloads" to "管理下载",
        "history" to "读取浏览历史",
        "bookmarks" to "读写书签",
        "clipboardRead" to "读取剪贴板",
        "clipboardWrite" to "写入剪贴板",
        "notifications" to "显示通知",
        "nativeMessaging" to "与本机程序通信",
        "geolocation" to "获取位置",
        "contextMenus" to "添加右键菜单",
        "proxy" to "控制代理设置",
        "privacy" to "修改隐私设置",
        "management" to "管理其他拓展",
        "scripting" to "向网页注入脚本",
        "webNavigation" to "监控页面跳转",
    )

    /** 权限 / 可访问网站 → 中文说明（安装确认框与详情页共用） */
    fun describePermissions(permissions: List<String>, origins: List<String>): List<String> = buildList {
        permissions.forEach { add(PERMISSION_NAMES[it] ?: it) }
        val allSites = origins.any { it == "<all_urls>" || it == "*://*/*" || it == "http://*/*" || it == "https://*/*" }
        if (allSites) {
            if ("<all_urls>" !in permissions) add(PERMISSION_NAMES.getValue("<all_urls>"))
        } else {
            origins.take(5).forEach { add("访问 $it") }
            if (origins.size > 5) add("以及另外 ${origins.size - 5} 个网站")
        }
    }

    private fun askPermission(method: String, args: Array<Any?>?): GeckoResult<AllowOrDeny> {
        val result = GeckoResult<AllowOrDeny>()
        val ext = args?.firstOrNull { it is WebExtension } as? WebExtension
        val arrays = args?.filterIsInstance<Array<*>>()?.map { arr -> arr.filterIsInstance<String>() }.orEmpty()
        val permissions = arrays.getOrNull(0).orEmpty()
        val origins = arrays.getOrNull(1).orEmpty()
        val name = ext?.metaData?.name?.takeIf { it.isNotBlank() } ?: "此拓展"
        val lines = describePermissions(permissions, origins).map { "· $it" }
        val title = when {
            method.contains("Update") -> "更新「$name」？"
            method.contains("Optional") -> "「$name」请求更多权限"
            else -> "添加「$name」？"
        }
        val message = if (lines.isEmpty()) "此拓展不需要额外权限。" else "此拓展将可以：\n" + lines.joinToString("\n")
        val activity = activityProvider?.invoke()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            result.complete(AllowOrDeny.DENY)
            return result
        }
        activity.runOnUiThread {
            runCatching {
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(if (method.contains("Install")) "添加" else "允许") { _, _ -> result.complete(AllowOrDeny.ALLOW) }
                    .setNegativeButton("取消") { _, _ -> result.complete(AllowOrDeny.DENY) }
                    .setOnCancelListener { result.complete(AllowOrDeny.DENY) }
                    .show()
            }.onFailure { result.complete(AllowOrDeny.DENY) }
        }
        return result
    }

    // ------------------------------------------------------------------ AMO（addons.mozilla.org）

    private const val AMO_API = "https://addons.mozilla.org/api/v5/addons"

    suspend fun searchAmo(query: String): Result<List<AmoAddon>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$AMO_API/search/?app=android&type=extension&sort=users&page_size=20&lang=zh-CN&q=" +
                URLEncoder.encode(query, "UTF-8")
            val results = JSONObject(httpGet(url)).optJSONArray("results") ?: return@runCatching emptyList()
            (0 until results.length()).mapNotNull { parseAddon(results.getJSONObject(it)) }
        }.recoverCatching { throw IllegalStateException(netMessage(it)) }
    }

    /** 把 addons.mozilla.org 的商店页面链接解析成拓展信息（取 xpi 下载地址）；不是商店页面返回 null */
    suspend fun resolveAmoPage(url: String): Result<AmoAddon?> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(url)
            val host = uri.host.orEmpty()
            if (!host.endsWith("addons.mozilla.org")) return@runCatching null
            val slug = Regex("/addon/([^/?#]+)").find(uri.path.orEmpty())?.groupValues?.get(1)
                ?: return@runCatching null
            parseAddon(JSONObject(httpGet("$AMO_API/addon/${URLEncoder.encode(slug, "UTF-8")}/?lang=zh-CN")))
                ?: error("商店里找不到这个拓展")
        }.recoverCatching { throw IllegalStateException(netMessage(it)) }
    }

    private fun netMessage(t: Throwable) =
        t.message?.takeIf { it.startsWith("服务器返回") || it.startsWith("商店") } ?: "访问商店失败，检查网络"

    /** AMO 的多语言字段：带 lang 参数时是字符串，否则是 {语言: 文本} */
    private fun localized(o: JSONObject, key: String): String {
        val v = o.opt(key)
        return when (v) {
            is String -> v
            is JSONObject -> v.optString("zh-CN").ifBlank { v.optString("en-US") }.ifBlank {
                v.keys().asSequence().firstOrNull()?.let { v.optString(it) }.orEmpty()
            }
            else -> ""
        }
    }

    private fun parseAddon(o: JSONObject): AmoAddon? {
        val current = o.optJSONObject("current_version") ?: return null
        val xpi = current.optJSONObject("file")?.optString("url")?.takeIf { it.isNotBlank() }
            ?: current.optJSONArray("files")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            ?: return null
        return AmoAddon(
            id = o.optLong("id"),
            slug = o.optString("slug"),
            name = localized(o, "name").ifBlank { o.optString("slug") },
            summary = localized(o, "summary").replace(Regex("<[^>]+>"), "").trim(),
            iconUrl = o.optString("icon_url"),
            xpiUrl = xpi,
            version = current.optString("version"),
            users = o.optLong("average_daily_users"),
            rating = o.optJSONObject("ratings")?.optDouble("average", 0.0) ?: 0.0,
        )
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
            val code = c.responseCode
            if (code !in 200..299) error("服务器返回 HTTP $code")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}

/** GeckoResult → 协程；值可能为 null */
suspend fun <T> GeckoResult<T>.await(): T? = suspendCancellableCoroutine { cont ->
    accept(
        { value -> if (cont.isActive) cont.resume(value) },
        { error -> if (cont.isActive) cont.resumeWith(Result.failure(error ?: IllegalStateException("操作失败"))) },
    )
}

/** Compose 的 context 常是 ContextWrapper，剥到 Activity */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
