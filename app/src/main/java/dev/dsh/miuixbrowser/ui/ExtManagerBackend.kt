package dev.dsh.miuixbrowser.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import dev.dsh.miuixbrowser.engine.gecko.AmoAddon

/** 拓展管理页里一行拓展的展示数据（与内核无关）。[permissions] 已经是给用户看的文字。 */
class ExtEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val enabled: Boolean,
    /** 拓展自带设置页的地址；null = 没有或当前内核下无法打开 */
    val optionsUrl: String?,
    val permissions: List<String>,
    val allowedInPrivate: Boolean = false,
    val author: String = "",
    val homepage: String = "",
    /** 兼容性提示（WebView 适配器：尚未实现的 API 等），显示在「详细信息」里 */
    val notes: List<String> = emptyList(),
)

/**
 * 拓展管理页（[ExtensionsManagerScreen]）面对的后端：Gecko 走 GeckoView 的 WebExtensionController，
 * WebView 走自研的 ExtensionSystem。页面本身不再直接碰任何一个内核的类，
 * 所以无内核（lite）版也能安全地组合这个页面。
 *
 * 所有 suspend 方法在主线程协程里调用（页面用 rememberCoroutineScope），内部自行切线程。
 */
interface ExtManagerBackend {
    /** 已安装的拓展。必须是 Compose 可观察的列表（读取会触发重组） */
    val items: List<ExtEntry>
    val loading: Boolean
    fun icon(id: String): ImageBitmap?

    /** 是否有商店（搜索 / 商店链接解析）。WebView 暂无 */
    val supportsStore: Boolean
    val supportsAutoUpdate: Boolean
    val supportsPrivate: Boolean

    val importSummary: String
    val emptyHint: String
    val footerHint: String

    /** 进入页面时登记：安装确认框需要 Activity */
    fun bind(context: Context)

    suspend fun refresh(context: Context)

    /** [uri]：http(s) 链接或 file:// 本地文件。成功返回拓展名称；用户取消时失败，消息为「已取消安装」 */
    suspend fun install(context: Context, uri: String): Result<String>

    /** 把用户选的文档拷到缓存，返回可交给 [install] 的 file:// 地址 */
    suspend fun stageFile(context: Context, source: Uri): String

    suspend fun setEnabled(context: Context, id: String, enabled: Boolean): Result<Unit>
    suspend fun setAllowedInPrivate(context: Context, id: String, allowed: Boolean): Result<Unit>
    suspend fun uninstall(context: Context, id: String): Result<Unit>

    /** true = 已更新，false = 已是最新 */
    suspend fun updateOne(context: Context, id: String): Result<Boolean>

    /** 返回 (已更新数, 总数) */
    suspend fun updateAll(context: Context): Pair<Int, Int>

    suspend fun searchStore(query: String): Result<List<AmoAddon>> = Result.success(emptyList())

    /** 商店页面链接 → 可安装的条目；不是商店链接返回 null */
    suspend fun resolveStorePage(url: String): Result<AmoAddon?> = Result.success(null)

    val autoUpdate: State<Int> get() = mutableStateOf(0)
    val lastCheck: State<Long> get() = mutableStateOf(0L)
    fun updateAutoUpdate(context: Context, index: Int) {}
}

/** Compose 的 context 常是 ContextWrapper，剥到 Activity（不依赖 Gecko 包里的同名函数，无内核版也能用） */
internal fun Context.asActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
