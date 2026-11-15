package dev.dsh.miuixbrowser.data

import dev.dsh.miuixbrowser.net.HttpFetcher
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 搜索引擎图标：网络拉取后写入 filesDir 持久化（永不过期），之后离线可用。
 * 磁盘文件名沿用旧版的 `url.hashCode()` 十六进制 + .png、目录仍是 search_icons，升级后旧缓存照常可读。
 */
object SearchIconStore {

    private val store = IconStore(
        subdir = "search_icons",
        ttlMs = null,
        maxFiles = 200,
        concurrency = 3,
        fileName = { url -> url.hashCode().toUInt().toString(16) + ".png" },
    )

    private val options = HttpFetcher.Options(connectTimeoutMs = 5000, readTimeoutMs = 5000)

    suspend fun load(context: Context, url: String): ImageBitmap? {
        if (url.isBlank()) return null
        IconStores.init(context)
        return store.get(url) {
            runCatching { HttpFetcher.get(url, options).bytes }.getOrNull()
                ?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        }
    }
}

@Composable
fun rememberSearchIcon(url: String): ImageBitmap? {
    val context = LocalContext.current
    var icon by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        icon = if (url.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { SearchIconStore.load(context, url) }
        }
    }
    return icon
}

/** 依次尝试多个图标地址，取第一个加载成功的；全部失败返回 null（调用方回退本地图标）。 */
@Composable
fun rememberFirstIcon(urls: List<String>): ImageBitmap? {
    val context = LocalContext.current
    var icon by remember(urls) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(urls) {
        icon = if (urls.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                urls.firstNotNullOfOrNull { SearchIconStore.load(context, it) }
            }
        }
    }
    return icon
}
