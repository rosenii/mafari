package dev.dsh.miuixbrowser

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.state.ToggleableState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.icon.glass.Delete
import java.util.Calendar
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Play
import top.yukonga.miuix.kmp.icon.glass.Pause
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll

private enum class FileKind(val iconRes: Int, val accent: Color) {
    IMAGE(R.drawable.ic_file_image, Color(0xFF26A69A)),
    VIDEO(R.drawable.ic_file_video, Color(0xFF8E54E9)),
    AUDIO(R.drawable.ic_file_audio, Color(0xFFEC407A)),
    ARCHIVE(R.drawable.ic_file_archive, Color(0xFFF59E0B)),
    APK(R.drawable.ic_file_apk, Color(0xFF3DDC84)),
    PDF(R.drawable.ic_file_pdf, Color(0xFFE5484D)),
    DOC(R.drawable.ic_file_doc, Color(0xFF2B6CD9)),
    SHEET(R.drawable.ic_file_sheet, Color(0xFF1F9D55)),
    SLIDE(R.drawable.ic_file_slide, Color(0xFFF08A24)),
    TEXT(R.drawable.ic_file_text, Color(0xFF7B8794)),
    CODE(R.drawable.ic_file_code, Color(0xFF5B6EE1)),
    OTHER(R.drawable.ic_file_other, Color(0xFF6B7A90)),
}

private fun fileKindOf(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "avif" -> FileKind.IMAGE
    "mp4", "mkv", "webm", "mov", "avi", "3gp", "ts", "m4v", "flv" -> FileKind.VIDEO
    "mp3", "m4a", "flac", "wav", "aac", "ogg", "opus" -> FileKind.AUDIO
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz" -> FileKind.ARCHIVE
    "apk", "apks", "xapk", "apkm" -> FileKind.APK
    "pdf" -> FileKind.PDF
    "doc", "docx", "rtf", "odt", "wps", "pages" -> FileKind.DOC
    "xls", "xlsx", "csv", "ods", "numbers" -> FileKind.SHEET
    "ppt", "pptx", "odp", "key" -> FileKind.SLIDE
    "txt", "md", "log", "ini", "conf" -> FileKind.TEXT
    "js", "json", "html", "htm", "xml", "css", "kt", "java", "py", "sh", "c", "cpp", "h", "yml", "yaml" -> FileKind.CODE
    else -> FileKind.OTHER
}

/** 图片 / 视频 / 音频保留封面；取不到封面时退回对应类型的图标。 */
private fun loadThumbnail(context: Context, uri: Uri, kind: FileKind): ImageBitmap? = runCatching {
    when (kind) {
        FileKind.VIDEO -> {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(0)?.asImageBitmap()
            } finally {
                runCatching { retriever.release() }
            }
        }
        FileKind.AUDIO -> {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
            } finally {
                runCatching { retriever.release() }
            }
        }
        else -> context.contentResolver.openInputStream(uri)?.use { stream ->
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
        }
    }
}.getOrNull()

@Composable
private fun FilePreview(task: DownloadTask) {
    val context = LocalContext.current
    val kind = remember(task.name) { fileKindOf(task.name) }
    val preview by produceState<ImageBitmap?>(initialValue = null, key1 = task.uri, key2 = task.state) {
        value = if (task.uri != null &&
            task.state == DownloadState.DONE &&
            (kind == FileKind.IMAGE || kind == FileKind.VIDEO || kind == FileKind.AUDIO)
        ) {
            withContext(Dispatchers.IO) { loadThumbnail(context, task.uri, kind) }
        } else {
            null
        }
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(kind.accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        val thumb = preview
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(kind.iconRes),
                contentDescription = null,
                tint = kind.accent,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    blurEnabled: Boolean = true,
    multiThread: Boolean = true,
    onMultiThreadChange: (Boolean) -> Unit = {},
    threads: Int = 4,
    onThreadsChange: (Int) -> Unit = {},
    multiThreadMinMb: Int = DOWNLOAD_MIN_MB_DEFAULT,
    onMultiThreadMinMbChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val tasks = Downloader.tasks
    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    var pendingDelete by remember { mutableStateOf<DownloadTask?>(null) }
    val deleteTask = pendingDelete
    var showClearAll by remember { mutableStateOf(false) }
    // 「同时删除本地文件」勾选项，每次打开删除弹窗都重置为不勾选
    var deleteLocal by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val groups = groupByDay(tasks.toList())
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)

    CompositionLocalProvider(LocalLiquidBackdrop provides if (glass) liquidBackdrop else null) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageGlassTopBar(
                title = "下载",
                backdrop = backdrop,
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                backButton = {
                    LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack)
                },
            )
        },
    ) { innerPadding ->
        val topInset = innerPadding.calculateTopPadding()
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .background(MiuixTheme.colorScheme.surface),
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = topInset, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                ) {
                    item {
                        SmallTitle(text = "下载配置")
                    }
                    item {
                        val threadOptions = DOWNLOAD_THREAD_OPTIONS
                        val minMbOptions = DOWNLOAD_MIN_MB_OPTIONS
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 8.dp),
                            insideMargin = PaddingValues(0.dp),
                        ) {
                            AppSwitchPreference(
                                title = "多线程下载",
                                summary = "文件超过阈值时分段并行下载；关闭后一律单线程",
                                checked = multiThread,
                                onCheckedChange = onMultiThreadChange,
                            )
                            if (multiThread) {
                                AppDropdownPreference(
                                    backdrop = if (glass) backdrop else null,
                                    title = "线程数",
                                    items = threadOptions.map { it.toString() },
                                    selectedIndex = threadOptions.indexOf(threads).coerceAtLeast(0),
                                    onSelectedIndexChange = { onThreadsChange(threadOptions[it]) },
                                )
                                AppDropdownPreference(
                                    backdrop = if (glass) backdrop else null,
                                    title = "多线程阈值",
                                    summary = "超过该阈值才使用多线程下载",
                                    items = minMbOptions.map { downloadThresholdLabel(it) },
                                    selectedIndex = minMbOptions.indexOf(multiThreadMinMb).coerceAtLeast(0),
                                    onSelectedIndexChange = { onMultiThreadMinMbChange(minMbOptions[it]) },
                                )
                            }
                        }
                    }
                    if (tasks.isEmpty()) {
                        item {
                            Text(
                                text = "还没有下载任务。网页里的下载会先弹确认框，再保存到 /sdcard/Download/mafari。",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    } else {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SmallTitle(text = "下载文件", modifier = Modifier.weight(1f))
                                // 垃圾桶图标 = 所在行标题的字高（subtitle 的字号），触控区保持 32dp
                                val titleSize = with(LocalDensity.current) {
                                    MiuixTheme.textStyles.subtitle.fontSize.toDp()
                                }
                                IconButton(
                                    onClick = {
                                        deleteLocal = false
                                        showClearAll = true
                                    },
                                    minWidth = 32.dp,
                                    minHeight = 32.dp,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Glass.Delete,
                                        contentDescription = "清空下载记录",
                                        tint = Color(0xFFE53935),
                                        modifier = Modifier.size(titleSize),
                                    )
                                }
                            }
                        }
                    }
                    groups.forEach { group ->
                    item(key = "day-${group.key}") {
                        SmallTitle(
                            text = group.label,
                            insideMargin = PaddingValues(28.dp, 6.dp, 28.dp, 4.dp),
                        )
                    }
                    items(group.tasks.size, key = { group.tasks[it].id }) { index ->
                        val task = group.tasks[index]
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 8.dp),
                            insideMargin = PaddingValues(14.dp),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 可点击打开区域：暂停/删除按钮左侧的整块区域（图标 + 文字）
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(enabled = task.state == DownloadState.DONE && task.uri != null) {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW).apply {
                                                            setDataAndType(task.uri, task.mime ?: "*/*")
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        },
                                                    )
                                                }
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                    FilePreview(task)
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = task.name,
                                            fontSize = 15.sp,
                                            color = MiuixTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            text = when (task.state) {
                                                DownloadState.DONE -> Downloader.sizeText(task.bytes) + " · 已保存"
                                                DownloadState.PAUSED -> "已暂停 · ${Downloader.progressSizeText(task.bytes, task.total)}"
                                                DownloadState.FAILED -> "失败 · ${task.error ?: "未知错误"}"
                                                DownloadState.RUNNING -> {
                                                    val percent = (Downloader.progressOf(task) * 100).toInt()
                                                    "$percent% · ${Downloader.progressSizeText(task.bytes, task.total)}"
                                                }
                                            },
                                            fontSize = 12.sp,
                                            color = if (task.state == DownloadState.FAILED) {
                                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            } else {
                                                MiuixTheme.colorScheme.primary
                                            },
                                        )
                                    }
                                    }
                                    // 操作按钮与左侧文字（15sp + 12sp 两行）协调：32dp 触控区 + 18dp 图标，
                                    // 不再用默认的 40dp / 24dp（比文字行明显大一圈）
                                    val actionBtn = 32.dp
                                    val actionIcon = Modifier.size(18.dp)
                                    when (task.state) {
                                        DownloadState.RUNNING -> IconButton(
                                            onClick = { Downloader.pause(task.id) },
                                            minWidth = actionBtn,
                                            minHeight = actionBtn,
                                        ) {
                                            Icon(imageVector = MiuixIcons.Glass.Pause, contentDescription = "暂停", modifier = actionIcon)
                                        }
                                        DownloadState.PAUSED -> IconButton(
                                            onClick = { Downloader.resume(context, task.id) },
                                            minWidth = actionBtn,
                                            minHeight = actionBtn,
                                        ) {
                                            Icon(imageVector = MiuixIcons.Glass.Play, contentDescription = "继续", modifier = actionIcon)
                                        }
                                        DownloadState.FAILED -> IconButton(
                                            onClick = { Downloader.retry(context, task.id) },
                                            minWidth = actionBtn,
                                            minHeight = actionBtn,
                                        ) {
                                            Icon(imageVector = MiuixIcons.Glass.Refresh, contentDescription = "重试", modifier = actionIcon)
                                        }
                                        DownloadState.DONE -> Unit
                                    }
                                    IconButton(
                                        onClick = {
                                            deleteLocal = false
                                            pendingDelete = task
                                        },
                                        minWidth = actionBtn,
                                        minHeight = actionBtn,
                                    ) {
                                        Icon(imageVector = MiuixIcons.Glass.Close, contentDescription = "删除", modifier = actionIcon)
                                    }
                                }
                                if (task.state == DownloadState.RUNNING || task.state == DownloadState.PAUSED) {
                                    Spacer(Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = Downloader.progressOf(task),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }
        }

        AppGlassDialog(
            show = deleteTask != null,
            title = "删除文件",
            summary = deleteTask?.let {
                if (it.state == DownloadState.DONE) it.name else it.name + "\n\n将取消下载并清除未完成的临时数据"
            },
            backdrop = if (glass) backdrop else null,
            onDismissRequest = { pendingDelete = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 只有已完成的任务才有「本地文件」可选；未完成的残缺文件一律清掉
                if (deleteTask?.state == DownloadState.DONE) {
                    DeleteLocalFileRow(checked = deleteLocal, onToggle = { deleteLocal = !deleteLocal })
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppDialogButton(
                        onClick = { pendingDelete = null },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = "取消", style = MiuixTheme.textStyles.button)
                    }
                    AppDialogButton(
                        onClick = {
                            val target = deleteTask
                            val withFile = deleteLocal
                            pendingDelete = null
                            if (target != null) Downloader.delete(context, target.id, withFile)
                        },
                        modifier = Modifier.weight(1f),
                        role = GlassDialogButtonRole.Destructive,
                    ) {
                        Text(text = "删除", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }

        AppGlassDialog(
            show = showClearAll,
            title = "清空下载记录",
            summary = "将清空全部 ${tasks.size} 条下载记录，进行中的任务会被取消",
            backdrop = if (glass) backdrop else null,
            onDismissRequest = { showClearAll = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                DeleteLocalFileRow(checked = deleteLocal, onToggle = { deleteLocal = !deleteLocal })
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppDialogButton(
                        onClick = { showClearAll = false },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = "取消", style = MiuixTheme.textStyles.button)
                    }
                    AppDialogButton(
                        onClick = {
                            val ids = tasks.map { it.id }
                            val withFile = deleteLocal
                            showClearAll = false
                            // 文件多时逐个删系统文件可能较慢，放到 IO 线程
                            scope.launch(Dispatchers.IO) {
                                ids.forEach { Downloader.delete(context, it, withFile) }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        role = GlassDialogButtonRole.Destructive,
                    ) {
                        Text(text = "清空", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }
    }
}

/** 删除弹窗里的「同时删除本地文件」勾选行（默认不勾选） */
@Composable
private fun DeleteLocalFileRow(checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = onToggle,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "同时删除本地文件",
            fontSize = 15.sp,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

private class DayGroup(val key: Int, val label: String, val tasks: List<DownloadTask>)

/**
 * 按下载日期（本地时区）分组，新的在前。任务 id = 创建时刻毫秒 × 1000 + 序号，
 * 所以 id / 1000 就是下载开始时间，不用改持久化格式，旧任务也能分组。
 */
private fun groupByDay(tasks: List<DownloadTask>): List<DayGroup> {
    fun dayKey(c: Calendar) = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    val now = Calendar.getInstance()
    val todayKey = dayKey(now)
    val yesterdayKey = dayKey(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) })
    val cal = Calendar.getInstance()
    val buckets = LinkedHashMap<Int, Pair<String, MutableList<DownloadTask>>>()
    tasks.sortedByDescending { it.id }.forEach { task ->
        val ms = task.id / 1000
        val key: Int
        val label: String
        if (ms < 946684800000L) { // 2000 年以前：旧版本留下的无效 id，没有可靠时间
            key = 0
            label = "更早"
        } else {
            cal.timeInMillis = ms
            key = dayKey(cal)
            label = when {
                key == todayKey -> "今天"
                key == yesterdayKey -> "昨天"
                cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                    "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
                else -> "${cal.get(Calendar.YEAR)}年${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
            }
        }
        buckets.getOrPut(key) { label to mutableListOf() }.second.add(task)
    }
    return buckets.map { (k, v) -> DayGroup(k, v.first, v.second) }
}
