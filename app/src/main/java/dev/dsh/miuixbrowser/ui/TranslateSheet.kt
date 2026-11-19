package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.browser.TabTranslate
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.translate.TranslateLangs
import dev.dsh.miuixbrowser.translate.TranslateManager
import dev.dsh.miuixbrowser.translate.TranslateSourceId
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 翻译源在界面上的名称（id 见 [TranslateSourceId]，顺序与 [TranslateSourceId.ALL] 一致） */
private fun sourceName(id: String) = when (id) {
    TranslateSourceId.GOOGLE -> tr("Google 翻译")
    TranslateSourceId.BING -> tr("微软翻译（Bing）")
    else -> tr("自定义（LibreTranslate）")
}

private fun sourceHint(id: String) = when (id) {
    TranslateSourceId.GOOGLE -> tr("免 Key；需要能访问 Google 的网络")
    TranslateSourceId.BING -> tr("免 Key；国内通常可直连")
    else -> tr("填写你自己的 LibreTranslate 兼容服务")
}

/**
 * 网页翻译面板（底部 Sheet）：选翻译源 / 目标语言，开始翻译，切换原文 / 译文，还原网页。
 * 设置改动立即保存；已经在翻译的页面要点「重新翻译」才会按新设置重来。
 */
@Composable
fun TranslateSheet(
    show: Boolean,
    backdrop: Backdrop?,
    tab: BrowserTab,
    settings: AppSettings,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(
        show = show,
        title = null,
        backdrop = backdrop,
        onDismissRequest = onDismiss,
        scrollContent = true,
    ) {
        val insets = LocalBottomSheetInsets.current
        val t = tab.translate
        val sourceIds = TranslateSourceId.ALL
        val custom = settings.translateSource == TranslateSourceId.CUSTOM
        val customReady = !custom || settings.translateCustomUrl.isNotBlank()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = insets.top)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = insets.bottom + 16.dp),
        ) {
            // ---- 标题 + 状态 ----
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(
                    text = tr("网页翻译"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(3.dp))
                Text(
                    text = statusText(t),
                    fontSize = 12.sp,
                    color = if (t.phase == TabTranslate.Phase.ERROR) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.size(8.dp))

            // ---- 翻译源 / 目标语言 ----
            // 不套 Card：去掉行下面的白底，让行直接浮在玻璃上；行自带 16dp 内边距，外面补 8dp 与标题的 24dp 左对齐
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                AppDropdownPreference(
                    backdrop = backdrop,
                    title = tr("翻译源"),
                    summary = sourceHint(settings.translateSource),
                    items = sourceIds.map(::sourceName),
                    selectedIndex = sourceIds.indexOf(settings.translateSource).coerceAtLeast(0),
                    onSelectedIndexChange = { i -> sourceIds.getOrNull(i)?.let { settings.updateTranslate(source = it) } },
                )
                AppDropdownPreference(
                    backdrop = backdrop,
                    title = tr("翻译为"),
                    items = TranslateLangs.ALL.map { it.name },
                    selectedIndex = TranslateLangs.ALL.indexOfFirst { it.code == settings.translateTarget }.coerceAtLeast(0),
                    onSelectedIndexChange = { i -> TranslateLangs.ALL.getOrNull(i)?.let { settings.updateTranslate(target = it.code) } },
                )
            }

            if (custom) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    var url by remember { mutableStateOf(settings.translateCustomUrl) }
                    var key by remember { mutableStateOf(settings.translateCustomKey) }
                    TextField(
                        value = url,
                        onValueChange = {
                            url = it
                            settings.updateTranslate(customUrl = it)
                            TranslateManager.reset()
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        label = tr("服务地址（例如 https://libretranslate.example.com）"),
                        singleLine = true,
                    )
                    TextField(
                        value = key,
                        onValueChange = {
                            key = it
                            settings.updateTranslate(customKey = it)
                            TranslateManager.reset()
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        label = tr("API Key（可选）"),
                        singleLine = true,
                    )
                }
            }

            Text(
                text = tr("翻译时，页面上的文字会发送到所选翻译源。"),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            )

            // ---- 始终翻译此页面（仅 http / https 页面可勾选；勾选后这个页面每次加载完成都自动翻译）----
            val pageKey = settings.translatePageKey(tab.url)
            if (pageKey != null) {
                val always = settings.isAlwaysTranslate(tab.url)
                val toggle = { settings.setAlwaysTranslate(tab.url, !always) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = toggle)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        state = if (always) ToggleableState.On else ToggleableState.Off,
                        onClick = toggle,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = tr("始终翻译此页面"),
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.size(4.dp))
            }

            // ---- 操作 ----
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppTextButton(
                    text = if (t.active) tr("重新翻译") else tr("翻译本页"),
                    primary = true,
                    enabled = customReady,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { if (t.start(settings.translateConfig())) onDismiss() },
                )
                if (t.phase == TabTranslate.Phase.ERROR) {
                    AppTextButton(
                        text = tr("重试"),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { t.retry(); onDismiss() },
                    )
                }
                if (t.active) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTextButton(
                            text = if (t.phase == TabTranslate.Phase.ORIGINAL) tr("显示译文") else tr("显示原文"),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (t.phase == TabTranslate.Phase.ORIGINAL) t.showTranslation() else t.showOriginal()
                                onDismiss()
                            },
                        )
                        AppTextButton(
                            text = tr("还原网页"),
                            modifier = Modifier.weight(1f),
                            onClick = { t.stop(); onDismiss() },
                        )
                    }
                }
            }
        }
    }
}

private fun statusText(t: TabTranslate): String = when (t.phase) {
    TabTranslate.Phase.IDLE -> tr("当前页面未翻译")
    TabTranslate.Phase.TRANSLATING -> tr("翻译中 {0}/{1}", t.done, t.total)
    TabTranslate.Phase.DONE -> tr("已翻译 · {0}", t.config?.let { sourceName(it.sourceId) }.orEmpty())
    TabTranslate.Phase.ORIGINAL -> tr("正在显示原文")
    TabTranslate.Phase.ERROR -> t.error ?: tr("翻译失败")
}
