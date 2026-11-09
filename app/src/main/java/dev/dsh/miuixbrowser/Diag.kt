package dev.dsh.miuixbrowser

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简运行日志：写到 /sdcard/Android/media/<pkg>/diag.txt。
 * 默认关闭（每条日志都是一次文件追加），在 设置 → 高级 → Gecko 调试 里打开；闪退日志（crash.txt）不受影响。
 */
object Diag {

    @Volatile
    private var file: File? = null
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        val on = context.applicationContext.getSharedPreferences("mafari_gecko_debug", Context.MODE_PRIVATE)
            .getBoolean("diag", false)
        if (on) open(context)
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
            f.appendText("${time.format(Date())}  $message\n")
        }
    }
}
