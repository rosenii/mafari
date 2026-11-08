package dev.dsh.miuixbrowser

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext

/**
 * Gecko 下不支持的功能入口：整体置灰（不透明度 38%），盖一层透明点击层吃掉所有点击，
 * 只弹 toast「该功能在 Gecko 下不支持……」。[disabled] = false 时原样显示，不加任何包装。
 *
 * [hint] 是替代方案提示（如「可安装 uBlock Origin 拓展替代」），为空则只说明不支持。
 */
@Composable
fun GeckoGate(disabled: Boolean, hint: String = "", content: @Composable () -> Unit) {
    if (!disabled) {
        content()
        return
    }
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.alpha(GECKO_DISABLED_ALPHA)) { content() }
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { toastGeckoUnsupported(context, hint) },
        )
    }
}

const val GECKO_DISABLED_ALPHA = 0.38f

/** 「该功能在 Gecko 下不支持」+ 可选的替代方案提示（hint 以逗号开头或为空串） */
fun toastGeckoUnsupported(context: Context, hint: String = "") {
    Toast.makeText(context, "该功能在 Gecko 下不支持" + hint, Toast.LENGTH_SHORT).show()
}
