package dev.dsh.miuixbrowser.ext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Narrow clipboard surface for extension pages. Content scripts never receive this namespace. */
class ClipboardApi(private val context: Context) {
    fun register(router: ApiRouter) {
        router.register("clipboard.readText") { _, _ ->
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: throw ApiException(ErrorCodes.UNSUPPORTED, "系统剪贴板不可用")
            try {
                val clip = manager.primaryClip
                if (clip == null || clip.itemCount == 0) ""
                else clip.getItemAt(0).coerceToText(context)?.toString()?.take(MAX_TEXT_CHARS) ?: ""
            } catch (_: SecurityException) {
                throw ApiException(ErrorCodes.DENIED_PERMISSION, "系统拒绝读取剪贴板；请在前台页面中重试")
            }
        }
        router.register("clipboard.writeText") { _, args ->
            val value = args.argOrNull(0) as? String
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "clipboard.writeText 需要文本参数")
            if (value.length > MAX_TEXT_CHARS) throw ApiException(ErrorCodes.BAD_REQUEST, "剪贴板文本过长")
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: throw ApiException(ErrorCodes.UNSUPPORTED, "系统剪贴板不可用")
            manager.setPrimaryClip(ClipData.newPlainText("Mafari extension", value))
            null
        }
    }

    companion object { private const val MAX_TEXT_CHARS = 1_048_576 }
}
