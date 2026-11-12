package dev.dsh.miuixbrowser

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics

/** 切到 Gecko 内核后，依赖 WebView 专有 API 的功能行 / 菜单项置灰，点击时给出的提示 */
const val GECKO_UNSUPPORTED_TOAST = "该功能在 Gecko 下不支持或有替代方案"

/** 仅 Gecko 可用的功能（如拓展）在 WebView 下置灰，点击时给出的提示 */
const val WEBVIEW_UNSUPPORTED_TOAST = "该功能在 WebView 下不可用"

/** 置灰时的不透明度 */
const val GECKO_LOCKED_ALPHA = 0.38f

private var lastToastAt = 0L

/** 弹出「该功能在 Gecko 下不支持或有替代方案」，1.2 秒内不重复弹，避免连点叠出一串 toast */
fun showGeckoUnsupportedToast(context: Context) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastToastAt < 1_200) return
    lastToastAt = now
    Toast.makeText(context.applicationContext, GECKO_UNSUPPORTED_TOAST, Toast.LENGTH_SHORT).show()
}

/** 弹出「该功能在 WebView 下不可用」，与 [showGeckoUnsupportedToast] 共用 1.2 秒防重复 */
fun showWebViewUnsupportedToast(context: Context) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastToastAt < 1_200) return
    lastToastAt = now
    Toast.makeText(context.applicationContext, WEBVIEW_UNSUPPORTED_TOAST, Toast.LENGTH_SHORT).show()
}

