/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.books.ui.ReaderSliderRow
import dev.dsh.miuixbrowser.tts.SpeechEngine
import dev.dsh.miuixbrowser.tts.SpeechEngineStore
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.tts.SpeechPlayer
import dev.dsh.miuixbrowser.tts.SpeechState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 听书面板：播放 / 暂停 / 停止、语速、自动下一章、选择语音引擎。
 * 「语音引擎」页可以添加自定义 HTTP 引擎（Legado 朗读引擎 / OpenAI 兼容的 AI 语音接口都能填）。
 */
@Composable
internal fun SpeechSheet(
    show: Boolean,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onDismiss: () -> Unit,
    player: SpeechPlayer,
    chapterTitle: String,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<SpeechEngine?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show) {
            page = 0
            editing = null
            adding = false
        }
    }

    AppBottomSheet(
        show = show,
        title = if (page == 0) tr("听书") else tr("语音引擎"),
        backdrop = backdrop,
        onDismissRequest = onDismiss,
        enableWindowDim = false,
        scrollContent = true,
    ) {
        val insets = LocalBottomSheetInsets.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = insets.top)
                .heightIn(max = 540.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = insets.bottom + 16.dp),
        ) {
            if (page == 0) {
                val state = player.state
                Text(
                    text = chapterTitle.ifBlank { tr("当前章节") },
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (state) {
                        SpeechState.IDLE -> tr("未开始朗读")
                        SpeechState.PREPARING -> tr("准备中…")
                        SpeechState.SPEAKING -> tr("正在朗读")
                        SpeechState.PAUSED -> tr("已暂停")
                        SpeechState.ERROR -> player.error ?: tr("朗读失败")
                    },
                    fontSize = 12.sp,
                    color = if (state == SpeechState.ERROR) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(10.dp))

                val engines = SpeechEngineStore.engines
                val selected = engines.indexOfFirst { it.id == SpeechEngineStore.selectedId }.coerceAtLeast(0)
                AppDropdownPreference(
                    onSelectedIndexChange = { i -> engines.getOrNull(i)?.let { SpeechEngineStore.select(context, it.id) } },
                    items = engines.map { it.displayName },
                    selectedIndex = selected,
                    title = tr("语音引擎"),
                    summary = if (SpeechEngineStore.current().system) tr("使用系统自带的 TTS") else SpeechEngineStore.current().url,
                    backdrop = backdrop,
                )
                AppSwitchPreference(
                    checked = SpeechEngineStore.autoNext,
                    onCheckedChange = { SpeechEngineStore.setAutoNext(context, it) },
                    title = tr("自动下一章"),
                    summary = tr("本章读完后继续读下一章"),
                )
                ReaderSliderRow(
                    name = tr("语速"),
                    value = SpeechEngineStore.speed,
                    range = 0.5f..2.5f,
                    step = 0.1f,
                    show = { tr("{0}×", String.format(java.util.Locale.US, "%.1f", it)) },
                    onCommit = { SpeechEngineStore.setSpeed(context, it) },
                )
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppDialogButton(
                        onClick = {
                            when (state) {
                                SpeechState.SPEAKING, SpeechState.PREPARING -> onPause()
                                SpeechState.PAUSED -> player.resume()
                                else -> onPlay()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        role = GlassDialogButtonRole.Primary,
                    ) {
                        Text(
                            text = when (state) {
                                SpeechState.SPEAKING, SpeechState.PREPARING -> tr("暂停")
                                SpeechState.PAUSED -> tr("继续")
                                else -> tr("开始朗读")
                            },
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                    AppDialogButton(onClick = onStop, modifier = Modifier.weight(1f), enabled = state != SpeechState.IDLE) {
                        Text(text = tr("停止"), style = MiuixTheme.textStyles.button)
                    }
                }
                Spacer(Modifier.height(6.dp))
                AppTextButton(
                    text = tr("管理语音引擎"),
                    onClick = {
                        page = 1
                        editing = null
                        adding = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                if (editing != null || adding) {
                    EngineForm(
                        engine = editing,
                        backdrop = backdrop,
                        onCancel = { editing = null; adding = false },
                        onSave = { e ->
                            SpeechEngineStore.upsert(context, e)
                            editing = null
                            adding = false
                        },
                    )
                } else {
                    Text(
                        text = tr("系统语音直接使用手机自带的 TTS；自定义引擎按 HTTP 请求取音频。"),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (e in SpeechEngineStore.engines) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).clickable {
                                    if (!e.system) {
                                        editing = e
                                    }
                                },
                            ) {
                                Text(
                                    text = e.displayName,
                                    fontSize = 15.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (e.system) tr("手机系统语音") else listOf(e.method, e.url).joinToString(" · "),
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!e.system) {
                                Text(
                                    text = tr("编辑"),
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { editing = e }.padding(horizontal = 6.dp, vertical = 6.dp),
                                )
                                Text(
                                    text = tr("删除"),
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.clickable { SpeechEngineStore.remove(context, e.id) }.padding(horizontal = 6.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppDialogButton(
                            onClick = { adding = true; editing = null },
                            modifier = Modifier.weight(1f),
                            role = GlassDialogButtonRole.Primary,
                        ) { Text(text = tr("添加引擎"), style = MiuixTheme.textStyles.button) }
                        AppDialogButton(onClick = { page = 0 }, modifier = Modifier.weight(1f)) {
                            Text(text = tr("返回"), style = MiuixTheme.textStyles.button)
                        }
                    }
                }
            }
        }
    }
}

/** 自定义语音引擎编辑表单。 */
@Composable
private fun EngineForm(
    engine: SpeechEngine?,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop?,
    onCancel: () -> Unit,
    onSave: (SpeechEngine) -> Unit,
) {
    var name by remember(engine?.id) { mutableStateOf(engine?.name.orEmpty()) }
    var url by remember(engine?.id) { mutableStateOf(engine?.url.orEmpty()) }
    var method by remember(engine?.id) { mutableStateOf(engine?.method ?: "GET") }
    var headers by remember(engine?.id) { mutableStateOf(engine?.headers.orEmpty()) }
    var body by remember(engine?.id) { mutableStateOf(engine?.body.orEmpty()) }
    var voice by remember(engine?.id) { mutableStateOf(engine?.voice.orEmpty()) }
    var model by remember(engine?.id) { mutableStateOf(engine?.model.orEmpty()) }

    fun fillOpenAi() {
        if (name.isBlank()) name = tr("AI 语音")
        url = "https://api.openai.com/v1/audio/speech"
        method = "POST"
        headers = """{"Authorization":"Bearer 你的 API Key","Content-Type":"application/json"}"""
        body = """{"model":"{{model}}","input":"{{speakTextJson}}","voice":"{{voice}}"}"""
        if (voice.isBlank()) voice = "alloy"
        if (model.isBlank()) model = "tts-1"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = tr("请求地址 / 请求体里可用：{{speakText}}、{{speakTextUrl}}、{{speakTextJson}}、{{speakSpeed}}、{{voice}}、{{model}}"),
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(6.dp))
        TextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = tr("名称"), singleLine = true)
        Spacer(Modifier.height(6.dp))
        TextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), label = tr("请求地址"), singleLine = true)
        Spacer(Modifier.height(6.dp))
        AppDropdownPreference(
            onSelectedIndexChange = { i -> method = if (i == 1) "POST" else "GET" },
            items = listOf("GET", "POST"),
            selectedIndex = if (method.equals("POST", true)) 1 else 0,
            title = tr("请求方式"),
            backdrop = backdrop,
        )
        TextField(value = headers, onValueChange = { headers = it }, modifier = Modifier.fillMaxWidth(), label = tr("请求头（JSON 或每行 Key: Value）"), singleLine = false)
        Spacer(Modifier.height(6.dp))
        TextField(value = body, onValueChange = { body = it }, modifier = Modifier.fillMaxWidth(), label = tr("请求体（POST 用）"), singleLine = false, minLines = 2)
        Spacer(Modifier.height(6.dp))
        TextField(value = voice, onValueChange = { voice = it }, modifier = Modifier.fillMaxWidth(), label = tr("音色 voice（可选）"), singleLine = true)
        Spacer(Modifier.height(6.dp))
        TextField(value = model, onValueChange = { model = it }, modifier = Modifier.fillMaxWidth(), label = tr("模型 model（可选）"), singleLine = true)
        Spacer(Modifier.height(8.dp))
        AppTextButton(text = tr("填入 OpenAI 兼容语音模板"), onClick = { fillOpenAi() }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppDialogButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
            }
            AppDialogButton(
                onClick = {
                    onSave(
                        SpeechEngine(
                            id = engine?.id.orEmpty(),
                            name = name,
                            url = url,
                            method = method,
                            headers = headers,
                            body = body,
                            voice = voice,
                            model = model,
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = name.isNotBlank() && url.isNotBlank(),
                role = GlassDialogButtonRole.Primary,
            ) { Text(text = tr("保存"), style = MiuixTheme.textStyles.button) }
        }
    }
}
