/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.miuixbrowser.books.ui.ReaderSliderRow
import dev.dsh.miuixbrowser.tts.SpeechEngine
import dev.dsh.miuixbrowser.tts.SpeechEngineStore
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.net.HttpFetcher
import dev.dsh.miuixbrowser.tts.SpeechPlayer
import dev.dsh.miuixbrowser.tts.SpeechState
import dev.dsh.miuixbrowser.tts.SpeechEngineImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 语音引擎编辑表单的临时状态；底部保存按钮在滚动区外，也能读到这里的值。 */
private class EngineDraft(engine: SpeechEngine?) {
    var name by mutableStateOf(engine?.name.orEmpty())
    var url by mutableStateOf(engine?.url.orEmpty())
    var method by mutableStateOf(engine?.method ?: "GET")
    var headers by mutableStateOf(engine?.headers.orEmpty())
    var body by mutableStateOf(engine?.body.orEmpty())
    var voice by mutableStateOf(engine?.voice.orEmpty())
    var model by mutableStateOf(engine?.model.orEmpty())

    fun fillOpenAi() {
        if (name.isBlank()) name = tr("AI 语音")
        url = "https://api.openai.com/v1/audio/speech"
        method = "POST"
        headers = """{"Authorization":"Bearer 你的 API Key","Content-Type":"application/json"}"""
        body = """{"model":"{{model}}","input":"{{speakTextJson}}","voice":"{{voice}}"}"""
        if (voice.isBlank()) voice = "alloy"
        if (model.isBlank()) model = "tts-1"
    }

    fun toEngine(id: String) = SpeechEngine(
        id = id,
        name = name,
        url = url,
        method = method,
        headers = headers,
        body = body,
        voice = voice,
        model = model,
    )
}

/**
 * 听书面板：滚动区只放内容，底部播放 / 停止、添加 / 返回、保存 / 取消固定在 footer，
 * 不会被上方内容顶下去；sheet 高度随内容自适应，最高 640dp。
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
    var importShow by remember { mutableStateOf(false) }
    // 订阅文件夹的展开状态（同书签页：默认收起，只有一个时默认展开）与整组删除确认
    val groupOpen = remember { mutableStateMapOf<String, Boolean>() }
    var deleteGroup by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    fun toast(msg: String, long: Boolean = false) {
        android.widget.Toast.makeText(
            context, msg, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    /** [fileName] 不为空 = 来自本地文件；否则以 http(s) 开头按订阅地址取，其余当作粘贴的 JSON。 */
    fun importEngines(raw: String, fileName: String? = null) {
        scope.launch {
            toast(tr("正在读取语音引擎订阅…"))
            val trimmed = raw.trim()
            val isUrl = fileName == null && (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true))
            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    if (isUrl) {
                        HttpFetcher.get(trimmed, HttpFetcher.Options(maxBytes = 4 * 1024 * 1024, readTimeoutMs = 20_000)).bytes.toString(Charsets.UTF_8)
                    } else raw
                }
            }
            // 联网失败如实提示（超时 / HTTP 状态码），不要变成「订阅内容为空」
            val text = fetched.getOrElse {
                toast(it.message?.takeIf { m -> m.isNotBlank() } ?: tr("导入失败"), long = true)
                return@launch
            }
            // 同一份订阅 → 同一个文件夹：地址 / 文件名 / 粘贴内容的哈希作 key，重复导入会更新而不是新建
            val hash = Integer.toHexString(trimmed.hashCode())
            val groupKey: String
            val defaultName: String
            when {
                isUrl -> { groupKey = "url:$trimmed"; defaultName = trimmed.substringAfter("://").trimEnd('/').take(60) }
                fileName != null -> { groupKey = "file:$fileName"; defaultName = fileName }
                else -> { groupKey = "paste:$hash"; defaultName = tr("粘贴导入") + " " + hash.takeLast(4) }
            }
            runCatching { SpeechEngineImport.parse(text) }.onSuccess { res ->
                val added = SpeechEngineStore.importAll(context, res.engines, groupKey, res.title.ifBlank { defaultName })
                groupOpen[groupKey] = true
                val sb = StringBuilder(tr("已导入 {0} 个语音引擎（新增 {1} 个）", res.engines.size, added))
                if (res.skipped > 0) sb.append(tr("，跳过 {0} 个", res.skipped))
                if (res.unsupported.isNotEmpty()) {
                    sb.append("\n").append(tr("暂不支持 JS 规则：{0}", res.unsupported.take(3).joinToString("、")))
                }
                toast(sb.toString(), long = true)
                editing = null
                adding = false
            }.onFailure {
                toast(it.message ?: tr("导入失败"), long = true)
            }
        }
    }

    val engineFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (text, name) = withContext(Dispatchers.IO) {
                val body = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")
                val display = runCatching {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull()
                body to (display?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file")
            }
            if (text.isBlank()) {
                toast(tr("导入失败"))
            } else importEngines(text, name)
        }
    }
    LaunchedEffect(show) {
        if (show) {
            page = 0
            editing = null
            adding = false
        }
    }
    val formDraft = remember(page, editing?.id, adding) {
        if (page == 1 && (editing != null || adding)) EngineDraft(editing) else null
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
        val state = player.state
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = insets.top),
            ) {
                if (page == 0) {
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
                        backdrop = backdrop,
                        // 与下面的「自动下一章」和语速滑块共用 sheet 的左右留白，不要再叠加组件默认内边距
                        insideMargin = PaddingValues(0.dp),
                    )
                    SheetSwitchRow(
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
                        onCommit = {
                            SpeechEngineStore.setSpeed(context, it)
                            player.setSpeed(it)
                        },
                    )
                } else if (formDraft != null) {
                    EngineForm(formDraft, backdrop)
                } else {
                    Text(
                        text = tr("系统语音直接使用手机自带的 TTS；自定义引擎按 HTTP 请求取音频。"),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    val all = SpeechEngineStore.engines
                    val loose = all.filter { it.groupKey.isEmpty() }
                    val groups = all.filter { it.groupKey.isNotEmpty() }.groupBy { it.groupKey }
                    for (e in loose) {
                        EngineRow(
                            e,
                            indent = 0.dp,
                            onEdit = { editing = it },
                            onDelete = { SpeechEngineStore.remove(context, it.id) },
                        )
                    }
                    if (groups.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = tr("长按订阅文件夹可整组删除。"),
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    // 每份订阅导入的引擎放进各自的文件夹：点一下展开 / 收起（同书签页分组行）
                    groups.forEach { (key, list) ->
                        val gName = list.first().groupName.ifBlank { tr("语音订阅") }
                        val open = groupOpen[key] ?: (groups.size == 1)
                        EngineGroupHeader(
                            name = gName,
                            count = list.size,
                            open = open,
                            onClick = { groupOpen[key] = !open },
                            onLongClick = { deleteGroup = key to gName },
                        )
                        if (open) {
                            for (e in list) {
                                EngineRow(
                                    e,
                                    indent = 16.dp,
                                    onEdit = { editing = it },
                                    onDelete = { SpeechEngineStore.remove(context, it.id) },
                                )
                            }
                        }
                    }
                }
            }

            // ---- 固定 footer：始终贴在 sheet 底部
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = insets.bottom + 12.dp)) {
                if (page == 0) {
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
                } else if (formDraft != null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppDialogButton(
                            onClick = { editing = null; adding = false },
                            modifier = Modifier.weight(1f),
                        ) { Text(text = tr("取消"), style = MiuixTheme.textStyles.button) }
                        AppDialogButton(
                            onClick = {
                                SpeechEngineStore.upsert(context, formDraft.toEngine(editing?.id.orEmpty()))
                                editing = null
                                adding = false
                            },
                            modifier = Modifier.weight(1f),
                            enabled = formDraft.name.isNotBlank() && formDraft.url.isNotBlank(),
                            role = GlassDialogButtonRole.Primary,
                        ) { Text(text = tr("保存"), style = MiuixTheme.textStyles.button) }
                    }
                } else {
                    AppTextButton(
                        text = tr("导入语音引擎订阅"),
                        onClick = { importShow = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
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

    val pendingDelete = deleteGroup
    AppGlassDialog(
        show = pendingDelete != null,
        title = tr("删除订阅「{0}」？", pendingDelete?.second.orEmpty()),
        summary = tr("将删除这个订阅导入的全部语音引擎。"),
        backdrop = backdrop,
        onDismissRequest = { deleteGroup = null },
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppDialogButton(onClick = { deleteGroup = null }, modifier = Modifier.weight(1f)) {
                Text(text = tr("取消"), style = MiuixTheme.textStyles.button)
            }
            AppDialogButton(
                onClick = {
                    pendingDelete?.let { SpeechEngineStore.removeGroup(context, it.first) }
                    deleteGroup = null
                },
                modifier = Modifier.weight(1f),
                role = GlassDialogButtonRole.Destructive,
            ) { Text(text = tr("删除"), style = MiuixTheme.textStyles.button) }
        }
    }

    ImportDialog(
        show = importShow,
        backdrop = backdrop,
        onDismiss = { importShow = false },
        onImport = { text ->
            importShow = false
            importEngines(text)
        },
        onPickFile = {
            importShow = false
            engineFilePicker.launch(arrayOf("application/json", "text/*", "*/*"))
        },
        title = tr("导入语音引擎订阅"),
        summary = tr("粘贴 Legado 在线朗读引擎订阅 JSON，或输入订阅地址 / 选择本地文件"),
        urlLabel = tr("订阅地址或 JSON 内容"),
    )
}

/** 自定义语音引擎字段（保存 / 取消在 [SpeechSheet] 的固定 footer 里）。 */
@Composable
private fun EngineForm(draft: EngineDraft, backdrop: top.yukonga.miuix.kmp.blur.Backdrop?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = tr("请求地址 / 请求体里可用：{{speakText}}、{{speakTextUrl}}、{{speakTextUrl2}}、{{speakTextJson}}、{{speakSpeed}}、{{voice}}、{{model}}"),
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(6.dp))
        TextField(value = draft.name, onValueChange = { draft.name = it }, modifier = Modifier.fillMaxWidth(), label = tr("名称"), singleLine = true)
        Spacer(Modifier.height(6.dp))
        TextField(value = draft.url, onValueChange = { draft.url = it }, modifier = Modifier.fillMaxWidth(), label = tr("请求地址"), singleLine = true)
        Spacer(Modifier.height(6.dp))
        AppDropdownPreference(
            onSelectedIndexChange = { i -> draft.method = if (i == 1) "POST" else "GET" },
            items = listOf("GET", "POST"),
            selectedIndex = if (draft.method.equals("POST", true)) 1 else 0,
            title = tr("请求方式"),
            backdrop = backdrop,
        )
        TextField(value = draft.headers, onValueChange = { draft.headers = it }, modifier = Modifier.fillMaxWidth(), label = tr("请求头（JSON 或每行 Key: Value）"), singleLine = false)
        Spacer(Modifier.height(6.dp))
        TextField(value = draft.body, onValueChange = { draft.body = it }, modifier = Modifier.fillMaxWidth(), label = tr("请求体（POST 用）"), singleLine = false, minLines = 2)
        Spacer(Modifier.height(6.dp))
        TextField(value = draft.voice, onValueChange = { draft.voice = it }, modifier = Modifier.fillMaxWidth(), label = tr("音色 voice（可选）"), singleLine = true)
        Spacer(Modifier.height(6.dp))
        TextField(value = draft.model, onValueChange = { draft.model = it }, modifier = Modifier.fillMaxWidth(), label = tr("模型 model（可选）"), singleLine = true)
        Spacer(Modifier.height(8.dp))
        AppTextButton(text = tr("填入 OpenAI 兼容语音模板"), onClick = { draft.fillOpenAi() }, modifier = Modifier.fillMaxWidth())
    }
}

/** 管理页里的一行引擎：名称 + 请求方式 / 地址，右侧编辑、删除；订阅文件夹里的引擎用 [indent] 右移一点。 */
@Composable
private fun EngineRow(e: SpeechEngine, indent: androidx.compose.ui.unit.Dp, onEdit: (SpeechEngine) -> Unit, onDelete: (SpeechEngine) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = indent, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).clickable {
                if (!e.system) onEdit(e)
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
                modifier = Modifier.clickable { onEdit(e) }.padding(horizontal = 6.dp, vertical = 6.dp),
            )
            Text(
                text = tr("删除"),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .clickable { onDelete(e) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 订阅文件夹标题：与书签页的分组标题同款（名称 + 数量，右侧 36dp 圆形按钮 + 旋转的箭头，整行点击展开 / 收起，长按管理）。
 * 左右不再额外留白：sheet 本身已有边距，要和上面其它行对齐。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EngineGroupHeader(
    name: String,
    count: Int,
    open: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    // ChevronBackward 默认朝左：收起时转 180° 朝右（›），展开时转 270° 朝下
    val angle by androidx.compose.animation.core.animateFloatAsState(if (open) 270f else 180f, label = "engineGroupArrow")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$count",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        // 在 sheet 里（不在页面采样层内）：inPage = false
        AppSmallButton(onClick = onClick, size = 36.dp, inPage = false) {
            Icon(
                imageVector = MiuixIcons.Glass.ChevronBackward,
                contentDescription = if (open) tr("收起") else tr("展开"),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = angle },
            )
        }
    }
}

/** sheet 里的开关行：左右不加组件默认内边距，与引擎下拉行、语速滑块的左右边距一致。 */
@Composable
private fun SheetSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String, summary: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.onSurface)
            if (summary != null) {
                Text(text = summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Spacer(Modifier.width(12.dp))
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
