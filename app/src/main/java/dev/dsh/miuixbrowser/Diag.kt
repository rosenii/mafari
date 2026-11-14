package dev.dsh.miuixbrowser

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简运行日志：写到 /sdcard/Android/media/<pkg>/diag.txt。
 * 仅 debug 构建开启（每条日志都是一次文件追加）；闪退日志（crash.txt）不受影响。
 */
object Diag {

    @Volatile
    private var file: File? = null
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (BuildConfig.DEBUG) open(context)
    }

    fun setEnabled(context: Context, on: Boolean) {
        if (on) open(context) else file = null
    }

    private fun open(context: Context) {
        runCatching {
            val dir = context.externalMediaDirs.firstOrNull() ?: context.filesDir
            dir.mkdirs()
            file = File(dir, "diag.txt").apply { writeText("") }
            log("diag at ${file?.absolutePath}")
        }
    }

    val enabled: Boolean get() = file != null

    fun log(message: String) {
        val f = file ?: return
        runCatching {
            val stamp = synchronized(time) { time.format(Date()) }
            f.appendText("$stamp  $message\n")
        }
    }
}
