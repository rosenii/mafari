package dev.dsh.miuixbrowser

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * 「添加到桌面」：把当前网页做成桌面图标（固定快捷方式），点开进入无底栏的轻应用 [WebAppActivity]。
 *
 * 流程：
 * 1. 小米 / 红米 / POCO 上有单独的「桌面快捷方式」权限（AppOps 10017），首次点击先引导到权限页开启，
 *    之后再点才真正添加；其它系统没有这项运行时权限，由系统自己的「添加到桌面」确认框把关。
 * 2. 图标取网站自己声明的 icon / apple-touch-icon，其次 Google favicon 服务，最后 /favicon.ico；
 *    全部失败时用站点首字母生成。图标按自适应图标安全区居中绘制在底色上，所有桌面都不会被裁坏。
 * 3. 快捷方式 intent 指向 [WebAppActivity]；桌面不支持固定快捷方式时只提示，不会误加。
 */
object ShortcutHelper {

    const val ACTION_OPEN_WEBAPP = "dev.dsh.miuixbrowser.action.OPEN_WEBAPP"
    const val EXTRA_TITLE = "webapp_title"
    const val EXTRA_ICON = "webapp_icon"

    private const val PREFS = "mafari_shortcut"
    private const val KEY_ASKED = "perm_asked"

    /** 小米 AppOps：安装桌面快捷方式 */
    private const val OP_INSTALL_SHORTCUT = 10017
    private const val MODE_ASK = 5

    private enum class PermState { Granted, Ask, Denied, Unknown }

    private fun isXiaomi(): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    private fun permissionState(context: Context): PermState {
        if (!isXiaomi()) return PermState.Unknown
        return runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            when (method.invoke(appOps, OP_INSTALL_SHORTCUT, Process.myUid(), context.packageName) as Int) {
                AppOpsManager.MODE_ALLOWED -> PermState.Granted
                MODE_ASK -> PermState.Ask
                else -> PermState.Denied
            }
        }.getOrDefault(PermState.Unknown)
    }

    /** 打开本应用的权限页（小米优先进「桌面快捷方式」所在的权限编辑页） */
    private fun openPermissionSettings(context: Context): Boolean {
        val pkg = context.packageName
        val intents = buildList {
            if (isXiaomi()) {
                add(
                    Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        .putExtra("extra_pkgname", pkg),
                )
                add(
                    Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setPackage("com.miui.securitycenter")
                        .putExtra("extra_pkgname", pkg),
                )
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }
        return intents.any { runCatching { context.startActivity(it) }.isSuccess }
    }

    private fun toast(context: Context, text: String) =
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    /** 菜单「添加到桌面」入口（主线程调用） */
    fun addToDesktop(context: Context, scope: CoroutineScope, tab: BrowserTab) {
        val url = tab.url
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        if (url.isBlank() || (scheme != "http" && scheme != "https")) {
            toast(context, "当前页面无法添加到桌面")
            return
        }

        // 第 1 步：先申请「桌面快捷方式」权限
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = permissionState(context)
        val firstTime = !prefs.getBoolean(KEY_ASKED, false)
        if (isXiaomi() && (state == PermState.Denied || (firstTime && state != PermState.Granted))) {
            prefs.edit().putBoolean(KEY_ASKED, true).apply()
            if (openPermissionSettings(context)) {
                toast(context, "请先开启「桌面快捷方式」权限，然后回来再点一次「添加到桌面」")
            } else {
                toast(context, "请在系统设置里为 Mafari 开启「桌面快捷方式」权限后再试")
            }
            return
        }
        prefs.edit().putBoolean(KEY_ASKED, true).apply()

        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            toast(context, "当前桌面不支持添加快捷方式，可在系统设置开启 Mafari 的「桌面快捷方式」权限，或更换桌面")
            return
        }

        val title = tab.title.ifBlank { hostOf(url) }
        val webView = tab.webView
        scope.launch {
            val iconHref = queryPageIcon(webView)
            val raw = loadIcon(url, iconHref)
            val id = "webapp_" + Integer.toHexString(url.hashCode())
            val bitmap = withContext(Dispatchers.Default) { composeIcon(raw, hostOf(url)) }
            withContext(Dispatchers.IO) { saveIcon(context, id, bitmap) }

            val launch = Intent(context, WebAppActivity::class.java).apply {
                action = ACTION_OPEN_WEBAPP
                data = Uri.parse(url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ICON, id)
            }
            val info = ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(shortLabel(title, url))
                .setLongLabel(title.take(60))
                .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
                .setIntent(launch)
                .build()
            val ok = runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }
                .getOrDefault(false)
            toast(
                context,
                if (ok) "正在添加到桌面，若弹出确认框请选择「添加」" else "添加失败，请检查「桌面快捷方式」权限",
            )
        }
    }

    /** 桌面图标下的名字：去掉「 - 站点名 / | 站点名」之类后缀，最多 12 个字符 */
    private fun shortLabel(title: String, url: String): String {
        val head = title.split(Regex("\\s*[-|–—_·]\\s*")).firstOrNull { it.isNotBlank() }?.trim()
        val label = head?.takeIf { it.isNotBlank() } ?: hostOf(url).removePrefix("www.")
        return label.take(12)
    }

    /** 读页面声明的最大图标（link rel=icon / apple-touch-icon），读不到返回空串 */
    private suspend fun queryPageIcon(webView: android.webkit.WebView): String {
        val js = """
            (function(){var best='',bs=-1;
            document.querySelectorAll('link[rel~="icon"],link[rel="shortcut icon"],link[rel^="apple-touch-icon"]').forEach(function(l){
              if(!l.href)return;
              var s=parseInt(((l.sizes&&l.sizes[0])||'0').split('x')[0])||0;
              if(/apple-touch/.test(l.rel))s=Math.max(s,180);
              if(/\.svg(\?|${'$'})/i.test(l.href)||/svg/.test(l.type||''))s=-1;
              if(s>=bs){bs=s;best=l.href}});
            return best})()
        """.trimIndent()
        val result = withTimeoutOrNull(1500) {
            suspendCancellableCoroutine<String?> { cont ->
                runCatching {
                    webView.evaluateJavascript(js) { value -> if (cont.isActive) cont.resume(value) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
        // evaluateJavascript 返回 JSON 字符串："\"https://...\"" / "null"
        return result?.takeIf { it != "null" }?.trim('"')?.replace("\\/", "/")?.replace("\\u0026", "&").orEmpty()
    }

    private suspend fun loadIcon(pageUrl: String, pageIconHref: String): Bitmap? = withContext(Dispatchers.IO) {
        val host = hostOf(pageUrl)
        val origin = runCatching { Uri.parse(pageUrl).let { "${it.scheme}://${it.host}" } }.getOrDefault("")
        val candidates = listOf(
            pageIconHref,
            "$origin/apple-touch-icon.png",
            "https://www.google.com/s2/favicons?sz=128&domain=$host",
        )
        for (c in candidates) {
            if (c.isBlank() || c.startsWith("/apple-touch-icon")) continue
            RemoteIconLoader.load(c, pageUrl, "")?.let { return@withContext it.asAndroidBitmap() }
        }
        FaviconLoader.load(host)?.asAndroidBitmap()
    }

    /** 画成 432×432 自适应图标：图标缩放到约 56% 居中（落在 66% 安全区内），底色随图标亮度选取 */
    private fun composeIcon(src: Bitmap?, host: String): Bitmap {
        val size = 432
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (src != null && src.width > 0 && src.height > 0) {
            val soft = src.copy(Bitmap.Config.ARGB_8888, false) ?: src
            canvas.drawColor(if (averageLuma(soft) > 0.88f) 0xFFE9ECF2.toInt() else Color.WHITE)
            val box = size * 0.56f
            val scale = minOf(box / soft.width, box / soft.height)
            val w = soft.width * scale
            val h = soft.height * scale
            canvas.drawBitmap(
                soft,
                null,
                RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f),
                paint,
            )
        } else {
            val palette = intArrayOf(0xFF3B7DFF.toInt(), 0xFF34A853.toInt(), 0xFFF29900.toInt(), 0xFF8E5CF7.toInt(), 0xFFE5484D.toInt(), 0xFF0EA5A5.toInt())
            canvas.drawColor(palette[(host.hashCode() and Int.MAX_VALUE) % palette.size])
            val letter = host.removePrefix("www.").firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "W"
            paint.color = Color.WHITE
            paint.textSize = size * 0.42f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letter, size / 2f, y, paint)
        }
        return out
    }

    /** 不透明像素的平均亮度（0..1），用来判断图标是不是几乎全白 */
    private fun averageLuma(bitmap: Bitmap): Float {
        val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 24)
        var sum = 0f
        var n = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                if (Color.alpha(p) > 40) {
                    sum += (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
                    n++
                }
                x += step
            }
            y += step
        }
        return if (n == 0) 0f else sum / n
    }

    private fun iconFile(context: Context, id: String) = File(File(context.filesDir, "webapps"), "$id.png")

    private fun saveIcon(context: Context, id: String, bitmap: Bitmap) {
        runCatching {
            val file = iconFile(context, id)
            file.parentFile?.mkdirs()
            val small = Bitmap.createScaledBitmap(bitmap, 192, 192, true)
            file.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** 轻应用在最近任务里显示的图标 */
    fun readSavedIcon(context: Context, id: String?): Bitmap? {
        if (id.isNullOrBlank()) return null
        return runCatching { android.graphics.BitmapFactory.decodeFile(iconFile(context, id).absolutePath) }.getOrNull()
    }
}
