/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.tts.SpeechEngineStore
import dev.dsh.miuixbrowser.tts.SpeechPlayer
import dev.dsh.miuixbrowser.tts.SpeechState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Earphones
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 网页听书按钮：右下角圆形玻璃按钮，点开是共用的听书面板。
 * 正文优先用阅读模式脚本提取，失败再退回页面 innerText（WebView / Gecko 都支持）。
 */
@Composable
internal fun WebSpeechButton(
    tab: BrowserTab,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    liquid: com.kyant.backdrop.Backdrop?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speech = remember { SpeechPlayer(context, scope) }
    var showSheet by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { speech.release() } }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SpeechEngineStore.load(context) } }

    fun play() {
        tab.extractReadableText { text ->
            val clean = text.trim()
            if (clean.isEmpty()) {
                Toast.makeText(context, tr("没有读到网页正文"), Toast.LENGTH_SHORT).show()
            } else {
                speech.start(SpeechEngineStore.current(), clean, SpeechEngineStore.speed) { }
            }
        }
    }

    LiquidGlassButton(
        onClick = { showSheet = true },
        backdrop = liquid,
        modifier = modifier,
        size = 46.dp,
    ) {
        Icon(
            imageVector = MiuixIcons.Glass.Earphones,
            contentDescription = tr("朗读网页"),
            modifier = Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }

    SpeechSheet(
        show = showSheet,
        backdrop = backdrop,
        onDismiss = { showSheet = false },
        player = speech,
        chapterTitle = tab.title.ifBlank { tr("网页正文") },
        onPlay = { play() },
        onPause = { speech.pause() },
        onStop = { speech.stop() },
    )
}
