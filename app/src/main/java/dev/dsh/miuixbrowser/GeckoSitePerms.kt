package dev.dsh.miuixbrowser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * Gecko 的网站权限（对齐 Firefox「网站设置」）：
 *
 * - 全局默认：自动播放、持久存储、跨站 Cookie、受 DRM 控制的内容、设备应用和服务、本地网络设备。
 *   摄像头 / 麦克风 / 定位 / 网站通知沿用 [AppSettings] 里已有的三态（询问 / 允许 / 拒绝）。
 * - 例外网站：按站点覆盖上面任意一项（允许 / 拒绝），优先于全局默认；
 *   在权限弹窗里勾选「记住此网站的选择」时写入，也可以在设置里查看、修改、删除。
 *
 * 三态统一为 0 = 每次使用时询问，1 = 总是允许，2 = 总是拒绝。
 *
 * WebView 引擎共用同一份例外存储（按站点的设置本来就与引擎无关），但只认 [WEBVIEW_ITEMS] 里的项：
 * 摄像头 / 麦克风 / 位置 / 通知 / 剪贴板 / 媒体发声 / 网页重定向。
 */
object GeckoSitePerms {

    const val CAMERA = "camera"
    const val MIC = "mic"
    const val LOCATION = "location"
    const val NOTIFICATION = "notification"
    const val STORAGE = "persistent_storage"
    const val STORAGE_ACCESS = "storage_access"
    const val DRM = "drm"
    const val LOCAL_DEVICE = "local_device"
    const val LOCAL_NETWORK = "local_network"

    /** 以下三项只有 WebView 引擎用到 */
    const val CLIPBOARD = "clipboard"
    const val MEDIA = "media_sound"
    const val REDIRECT = "redirect"

    class Item(val key: String, val label: String)

    /** 本类自己保存全局默认的项（摄像头 / 麦克风 / 定位 / 通知在 AppSettings 里） */
    val GLOBAL_ITEMS = listOf(
        Item(STORAGE, "持久存储"),
        Item(STORAGE_ACCESS, "跨站 Cookie"),
        Item(DRM, "受 DRM 控制的内容"),
        Item(LOCAL_DEVICE, "设备应用和服务"),
        Item(LOCAL_NETWORK, "本地网络设备"),
    )

    /** 例外网站里可以按站点覆盖的全部项 */
    val EXCEPTION_ITEMS = listOf(
        Item(CAMERA, "摄像头"),
        Item(LOCATION, "位置"),
        Item(MIC, "麦克风"),
        Item(NOTIFICATION, "通知"),
    ) + GLOBAL_ITEMS

    /** WebView 引擎里可以按站点覆盖的项 */
    val WEBVIEW_ITEMS = listOf(
        Item(CAMERA, "摄像头"),
        Item(MIC, "麦克风"),
        Item(LOCATION, "位置"),
        Item(NOTIFICATION, "网站通知"),
        Item(CLIPBOARD, "剪贴板读写"),
        Item(MEDIA, "网站媒体发声"),
        Item(REDIRECT, "网页重定向"),
    )

    /** 默认值：持久存储、DRM 保持原来的「直接允许」（浏览所需），其余与 Firefox 一致，询问 */
    private val DEFAULTS = mapOf(STORAGE to 1, DRM to 1)

    /** 自动播放：0 = 允许音频和视频，1 = 仅阻止音频（Firefox 默认），2 = 阻止音频和视频 */
    var autoplay by mutableStateOf(1)
        private set

    private val modes = mutableStateMapOf<String, Int>()

    /** 站点（小写主机名）→ 权限 key → 1 允许 / 2 拒绝 */
    val exceptions = mutableStateMapOf<String, Map<String, Int>>()

    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("mafari_gecko_perms", Context.MODE_PRIVATE)
        prefs = p
        autoplay = p.getInt("autoplay", 1).coerceIn(0, 2)
        GLOBAL_ITEMS.forEach { item ->
            if (p.contains("mode_${item.key}")) modes[item.key] = p.getInt("mode_${item.key}", 0).coerceIn(0, 2)
        }
        runCatching {
            val obj = JSONObject(p.getString("exceptions", "{}").orEmpty())
            obj.keys().forEach { host ->
                val inner = obj.getJSONObject(host)
                val map = HashMap<String, Int>()
                inner.keys().forEach { k -> inner.optInt(k, 0).takeIf { it in 1..2 }?.let { map[k] = it } }
                if (map.isNotEmpty()) exceptions[host] = map
            }
        }
    }

    fun mode(key: String): Int = modes[key] ?: DEFAULTS[key] ?: 0

    fun setMode(key: String, value: Int) {
        val v = value.coerceIn(0, 2)
        modes[key] = v
        prefs?.edit()?.putInt("mode_$key", v)?.apply()
    }

    fun updateAutoplay(value: Int) {
        autoplay = value.coerceIn(0, 2)
        prefs?.edit()?.putInt("autoplay", autoplay)?.apply()
        pushAutoplay()
    }

    /** media.autoplay.default：0 全部允许 / 1 阻止有声 / 5 阻止全部（与 Firefox 桌面、Fenix 一致） */
    fun pushAutoplay() {
        GeckoPrefs.set("media.autoplay.default", when (autoplay) { 0 -> 0; 2 -> 5; else -> 1 })
    }

    // ---------------------------------------------------------------- 例外网站

    private fun norm(host: String) = host.trim().lowercase()

    /** 该站点对 [key] 的例外：1 允许 / 2 拒绝 / null 没有例外（走全局默认） */
    fun override(host: String, key: String): Int? = exceptions[norm(host)]?.get(key)

    /** 权限弹窗里勾选「记住此网站的选择」：[keys] 用逗号分隔（摄像头 + 麦克风一起问时有两项） */
    fun remember(host: String, keys: String, allow: Boolean) {
        keys.split(',').filter { it.isNotBlank() }.forEach { setException(host, it, if (allow) 1 else 2) }
    }

    /** value = null 清除该项例外；站点下一项都不剩时整站移除 */
    fun setException(host: String, key: String, value: Int?) {
        val h = norm(host)
        if (h.isEmpty()) return
        val map = HashMap(exceptions[h].orEmpty())
        if (value == null || value !in 1..2) map.remove(key) else map[key] = value
        if (map.isEmpty()) exceptions.remove(h) else exceptions[h] = map
        saveExceptions()
    }

    /** [items] = null 删除整站；否则只删这些项（另一个引擎的例外保留），一项不剩时整站移除 */
    fun removeSite(host: String, items: List<Item>? = null) {
        val h = norm(host)
        if (items == null) {
            exceptions.remove(h)
        } else {
            val map = HashMap(exceptions[h].orEmpty())
            items.forEach { map.remove(it.key) }
            if (map.isEmpty()) exceptions.remove(h) else exceptions[h] = map
        }
        saveExceptions()
    }

    fun clearExceptions(items: List<Item>? = null) {
        if (items == null) {
            exceptions.clear()
            saveExceptions()
        } else {
            exceptions.keys.toList().forEach { removeSite(it, items) }
        }
    }

    /** 在 [items] 范围内有例外的站点（已排序） */
    fun hostsFor(items: List<Item>): List<String> =
        exceptions.filter { (_, m) -> items.any { it.key in m } }.keys.sorted()

    private fun saveExceptions() {
        val obj = JSONObject()
        exceptions.forEach { (host, map) ->
            obj.put(host, JSONObject().also { o -> map.forEach { (k, v) -> o.put(k, v) } })
        }
        prefs?.edit()?.putString("exceptions", obj.toString())?.apply()
    }

    fun labelOf(key: String): String = EXCEPTION_ITEMS.firstOrNull { it.key == key }?.label ?: key

    /** 一个站点的例外摘要：「摄像头：允许 · 位置：拒绝」 */
    fun summaryOf(host: String, items: List<Item> = EXCEPTION_ITEMS): String =
        items.mapNotNull { item ->
            exceptions[host]?.get(item.key)?.let { "${item.label}：${if (it == 1) "允许" else "拒绝"}" }
        }.joinToString(" · ")
}
