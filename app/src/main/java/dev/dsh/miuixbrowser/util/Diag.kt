package dev.dsh.miuixbrowser.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简运行日志：写到 /sdcard/Android/media/<pkg>/diag.txt。
 * 默认关闭（每条日志都是一次文件追加），在 设置 → 高级 → Gecko 调试 / WebView 调试 里打开；闪退日志（crash.txt）不受影响。
 */
object Diag {

    @Volatile
    private var file: File? = null
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (file != null) return // Application 里已经打开过：别再清空文件，否则早期日志全丢
        val on = context.applicationContext.getSharedPreferences("mafari_gecko_debug", Context.MODE_PRIVATE)
            .getBoolean("diag", false)
        if (on) open(context)
    }

    /** 供界面观察的开关状态（Gecko / WebView 两个调试页共用同一个运行日志开关） */
    var enabledState by mutableStateOf(false)
        private set

    fun setEnabled(context: Context, on: Boolean) {
        if (on) open(context) else file = null
        enabledState = on && file != null
    }

    /** 切换并持久化（与 [init] 读取的是同一个键）；两个内核的调试页都走这里，保证状态一致 */
    fun setPersisted(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences("mafari_gecko_debug", Context.MODE_PRIVATE)
            .edit().putBoolean("diag", on).apply()
        setEnabled(context, on)
    }

    /** 日志文件（未开启或还没写过时可能不存在） */
    fun logFile(context: Context): File {
        val dir = context.externalMediaDirs.firstOrNull() ?: context.filesDir
        return File(dir, "diag.txt")
    }

    /** 清空日志内容（保持开关状态不变） */
    fun clear(context: Context) {
        runCatching { logFile(context).takeIf { it.exists() }?.writeText("") }
    }

    private fun open(context: Context) {
        runCatching {
            val dir = context.externalMediaDirs.firstOrNull() ?: context.filesDir
            dir.mkdirs()
            file = File(dir, "diag.txt").apply { writeText("") }
            enabledState = true
            log("diag at ${file?.absolutePath}")
        }
    }

    val enabled: Boolean get() = file != null

    private val urlWithQuery = Regex("""(https?://[^\s"'<>?#]+)[?#][^\s"'<>]*""", RegexOption.IGNORE_CASE)

    /** 日志 / 崩溃文件放在其他有「全部文件访问」权限的应用也读得到的目录：网址里的 query / fragment（常带 token、会话 id）一律抹掉。 */
    fun redactUrls(text: String): String = urlWithQuery.replace(text) { it.groupValues[1] + "?…" }

    fun log(message: String) {
        val f = file ?: return
        runCatching {
            val stamp = synchronized(time) { time.format(Date()) }
            f.appendText("$stamp  ${redactUrls(message)}\n")
        }
    }
}
