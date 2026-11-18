package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.download.DownloadState
import dev.dsh.miuixbrowser.download.Downloader
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshotFlow
import top.yukonga.miuix.kmp.icon.glass.Download

import android.app.Activity
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.drag
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.CoroutineStart
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import androidx.compose.ui.text.drawText
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.basic.Check
import dev.dsh.miuixbrowser.ui.icons.Settings
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.Phone
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.HorizontalSplit
import top.yukonga.miuix.kmp.icon.glass.Hide
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** MIUIX Glass 底板：主页用 glassPanel 真玻璃；无采样/关模糊时回退到渐变底板。 */
@Composable
internal fun Modifier.glassBar(
    backdrop: LayerBackdrop?,
    corner: Dp,
    blurEnabled: Boolean = true,
    textureBlurEnabled: Boolean = true,
    liquid: com.kyant.backdrop.Backdrop? = null,
): Modifier {
    val dark = isAppDarkTheme()
    if (liquid != null && blurEnabled && textureBlurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val liquidShape = RoundedCornerShape(corner)
        return this.drawBackdrop(
            backdrop = liquid,
            shape = { liquidShape },
            effects = {
                vibrancy()
                backdropBlur(1.5f.dp.toPx())
                lens(18f.dp.toPx(), 40f.dp.toPx(), depthEffect = true, chromaticAberration = true)
            },
            onDrawSurface = { drawRect(if (dark) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.14f)) },
        )
    }
    val glassShape = GlassShape(corner)
    val rounded = RoundedCornerShape(corner)
    val canUseGlass = blurEnabled && textureBlurEnabled && backdrop != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    // 关闭模糊时使用接近不透明的纯色底板；网页态（无采样）提高不透明度，避免内容透出干扰。
    val tint = when {
        !blurEnabled -> Brush.verticalGradient(
            listOf(
                MiuixTheme.colorScheme.surface.copy(alpha = 0.97f),
                MiuixTheme.colorScheme.surface.copy(alpha = 0.97f),
            ),
        )
        dark -> Brush.verticalGradient(listOf(Color(0xF02B2B31), Color(0xE81A1A20)))
        else -> Brush.verticalGradient(listOf(Color(0xF0FFFFFF), Color(0xE8E7EAF0)))
    }

    return this.glassPanel(
        backdrop = if (canUseGlass) backdrop else null,
        shape = glassShape,
        style = GlassLook.style(dark),
        material = if (canUseGlass) GlassLook.material(dark) else null,
        stroke = if (blurEnabled) GlassEdgeStrokes.forTheme(dark) else null,
        shading = GlassLook.shading(),
        fallback = Modifier
            .clip(rounded)
            .background(tint, rounded)
            .border(
                width = 1.dp,
                color = if (dark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.9f),
                shape = rounded,
            ),
    )
}

@Composable
internal fun BottomBar(
    liquid: com.kyant.backdrop.Backdrop? = null,
    blurEnabled: Boolean = true,
    textureBlurEnabled: Boolean = true,
    backdrop: LayerBackdrop,
    address: String,
    loading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onHome: () -> Unit,
    tabCount: Int,
    onChange: (String) -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onShare: () -> Unit,
    onTabs: () -> Unit,
    onNewTab: () -> Unit,
    onMore: () -> Unit,
    onRefresh: () -> Unit,
    onCopyAddress: () -> Unit,
    onAddressTap: () -> Unit = {},
    onFocusLost: () -> Unit = {},
    editing: Boolean = false,
    /** 当前页面能进入（或正处于）阅读模式：地址栏最左侧显示阅读图标 */
    readerVisible: Boolean = false,
    /** 正处于阅读模式：图标点亮 */
    readerActive: Boolean = false,
    onReader: () -> Unit = {},
    /** 当前页面有正在运行的脚本：在阅读图标的位置显示脚本图标（阅读图标优先，可阅读时由它占位） */
    scriptsRunning: Boolean = false,
    onScripts: () -> Unit = {},
    /** 下载图标状态：有下载在进行 / 有已完成未查看的下载时非 null；优先级 下载 > 阅读 > 脚本 */
    download: AddressDownloadState? = null,
    onDownload: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(29.dp)
    val showRefresh = shouldShowAddressRefresh(address = address, loading = loading, editing = editing)
    val downloadVisible = download?.visible == true
    val showDownload = shouldShowAddressDownload(downloadVisible = downloadVisible, editing = editing)
    val showReader = shouldShowAddressReader(readerVisible = readerVisible, editing = editing, downloadVisible = downloadVisible)
    val showScripts = shouldShowAddressScripts(
        readerVisible = readerVisible,
        scriptsRunning = scriptsRunning,
        editing = editing,
        downloadVisible = downloadVisible,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
    ) {
        // 空玻璃底板与按钮内容分层，避免 backdrop 采集层递归包含自身。
        Box(
            modifier = Modifier
                .matchParentSize()
                .glassBar(
                    backdrop = backdrop,
                    corner = 29.dp,
                    blurEnabled = blurEnabled,
                    textureBlurEnabled = textureBlurEnabled,
                    liquid = liquid,
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                // 不裁剪内容层：按钮的柔光投影需要向按钮边缘外自然扩散。
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PressIcon(icon = MiuixIcons.Glass.Back, description = tr("后退"), enabled = canGoBack, onClick = onBack)
            if (canGoForward) {
                PressIcon(
                    icon = MiuixIcons.Glass.Back,
                    description = tr("前进"),
                    enabled = true,
                    flip = true,
                    onClick = onForward,
                )
            } else {
                PressIcon(
                    icon = MiuixIcons.Home,
                    description = tr("主页"),
                    enabled = true,
                    onClick = onHome,
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.6f))
                    // 编辑中不挂点击 / 长按复制：长按要留给输入框（放大镜、选词、拖动光标），而不是复制网址
                    .then(
                        if (editing) Modifier else Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onAddressTap()
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onCopyAddress()
                            },
                        ),
                    )
                    // 左右两侧的图标（阅读 / 脚本 / 刷新）尽量贴边；文字与图标的间距由下面的 Spacer 负责
                    .padding(start = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 阅读模式图标：地址栏最左侧（比加载小圆点还靠左）
                if (showDownload && download != null) {
                    // 下载图标优先级最高：与阅读 / 脚本图标同一位置、同一尺寸
                    AddressDownloadButton(state = download, onClick = onDownload)
                    Spacer(Modifier.width(6.dp))
                } else if (showReader) {
                    AddressReaderButton(active = readerActive, onClick = onReader)
                    Spacer(Modifier.width(6.dp))
                } else if (showScripts) {
                    // 与阅读图标同一位置、同一尺寸；可阅读时上面的分支优先，这里不显示
                    AddressScriptButton(onClick = onScripts)
                    Spacer(Modifier.width(6.dp))
                }
                if (loading) {
                    LoadingDot()
                    Spacer(Modifier.width(8.dp))
                }
                AddressField(
                    value = address,
                    onChange = onChange,
                    onGo = onGo,
                    editing = editing,
                    onStartEdit = onAddressTap,
                    onFocusLost = onFocusLost,
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCopyAddress()
                    },
                    modifier = Modifier.weight(1f),
                )
                // 网页加载完毕（非加载中、非输入中）时，地址栏最右端显示刷新图标
                if (showRefresh) {
                    Spacer(Modifier.width(6.dp))
                    AddressRefreshButton(onClick = onRefresh)
                }
            }

            TabIcon(count = tabCount, onClick = onTabs, onLongClick = onNewTab)
            PressIcon(icon = MiuixIcons.Glass.More, description = tr("更多"), onClick = onMore)
        }
    }
}

/**
 * 图标轮廓重叠柔影：在原始矢量图标下方叠放两层轻微错位、模糊的同形图标。
 * 阴影跟随实际图标路径，不绘制圆形底影或按钮外轮廓。
 */
@Composable
internal fun IconWithContourShadow(
    imageVector: ImageVector,
    description: String,
    tint: Color,
    scale: Float,
    flip: Boolean = false,
    boxSize: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val shadowAlpha = if (tint.alpha < 0.5f) 0.12f else 0.28f
    Box(contentAlignment = Alignment.Center) {
        // 暗色投影：向右下轻微错位，模糊后沿图标边缘自然扩散。
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.Black.copy(alpha = shadowAlpha),
            modifier = Modifier
                .size(boxSize)
                .offset(x = 0.8.dp, y = 1.0.dp)
                .blur(2.2.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    if (flip) rotationZ = 180f
                },
        )
        // 反向浅色柔影与暗影交叠，形成玻璃质感的轮廓层次。
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.White.copy(alpha = if (tint.alpha < 0.5f) 0.10f else 0.24f),
            modifier = Modifier
                .size(boxSize)
                .offset(x = (-0.6).dp, y = (-0.6).dp)
                .blur(1.6.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    if (flip) rotationZ = 180f
                },
        )
        // 清晰图标置顶，避免阴影影响图标辨识度。
        Icon(
            imageVector = imageVector,
            contentDescription = description,
            tint = tint,
            modifier = Modifier
                .size(boxSize - 3.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    if (flip) rotationZ = 180f
                },
        )
    }
}

/** 右上角蓝色数字标（与标签页图标上的数字标同款） */
@Composable
internal fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(15.dp)
            .widthIn(min = 15.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.primary)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )
    }
}

@Composable
internal fun TabIcon(count: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "tabPress",
    )
    val color = MiuixTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(31.dp), contentAlignment = Alignment.Center) {
            IconWithContourShadow(
                imageVector = AppIcons.Tabs,
                description = tr("标签页"),
                tint = color,
                scale = scale,
                boxSize = 30.dp,
            )
            if (count > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .height(13.dp)
                        .widthIn(min = 13.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MiuixTheme.colorScheme.primary)
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = count.toString(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PressIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    flip: Boolean = false,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val color = if (enabled) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = {
                    if (enabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconWithContourShadow(
            imageVector = icon,
            description = description,
            tint = color,
            scale = scale,
            flip = flip,
        )
    }
}

/** 刷新图标显示条件：已打开网页（地址非空）、加载完毕、且不在输入状态。 */
internal fun shouldShowAddressRefresh(address: String, loading: Boolean, editing: Boolean): Boolean =
    address.isNotEmpty() && !loading && !editing

/** 地址栏内刷新图标占用的宽度（按钮 24dp + 左侧间距 2dp），用于底栏宽度计算。 */
internal val AddressRefreshWidth = 26.dp

/** 阅读图标显示条件：页面可进入 / 正处于阅读模式，且不在输入状态（输入时让位给文字编辑）。 */
internal fun shouldShowAddressReader(readerVisible: Boolean, editing: Boolean, downloadVisible: Boolean = false): Boolean =
    readerVisible && !editing && !downloadVisible

/** 下载图标显示条件：有下载在进行 / 有已完成未查看的下载，且不在输入状态。优先级最高（下载 > 阅读 > 脚本）。 */
internal fun shouldShowAddressDownload(downloadVisible: Boolean, editing: Boolean): Boolean =
    downloadVisible && !editing

/** 地址栏内阅读图标占用的宽度（按钮 24dp + 右侧间距 2dp），用于底栏宽度计算。 */
internal val AddressReaderWidth = 26.dp

/**
 * 脚本图标显示条件：当前页面有正在运行的脚本，且不在输入状态；
 * 页面可阅读 / 正处于阅读模式时阅读图标优先，脚本图标让位（两者共用同一个位置）。
 */
internal fun shouldShowAddressScripts(
    readerVisible: Boolean,
    scriptsRunning: Boolean,
    editing: Boolean,
    downloadVisible: Boolean = false,
): Boolean = scriptsRunning && !readerVisible && !downloadVisible && !editing

/**
 * 地址栏下载图标的状态（只统计本次运行期间产生 / 变化的任务，冷启动时库里的旧记录不触发）：
 * - [running]：有任务正在下载 → 主题色进度环（总大小未知时为旋转的不定进度弧）
 * - [paused]：没有进行中的任务，但有暂停的任务 → 灰色进度环；暂停满 3 分钟仍未继续则自动隐藏
 * - [unseenFailed]：有任务失败 → 图标右上角红点；1 分钟内没点击则自动隐藏
 * - [unseenDone]：有任务下载完成 → 图标右上角绿点；1 分钟内没点击则自动隐藏；红点优先于绿点
 * 点击图标（打开下载页）后调用 [acknowledge]：已完成 / 失败的小圆点消失。
 */
@Stable
internal class AddressDownloadState {
    var running by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    /** 当前环显示的进度百分比 0..100；-1 = 总大小未知 */
    var progressPct by mutableIntStateOf(-1)
        private set
    var unseenDone by mutableStateOf(false)
        private set
    var unseenFailed by mutableStateOf(false)
        private set

    val visible: Boolean get() = running || paused || unseenDone || unseenFailed

    private var initialIds: Set<Long>? = null
    private var watched = emptySet<Long>()
    private var acked = emptySet<Long>()
    private val pausedSince = HashMap<Long, Long>()
    private val failedSince = HashMap<Long, Long>()
    private val doneSince = HashMap<Long, Long>()
    private var last: Snapshot? = null

    fun acknowledge() {
        val s = last
        acked = acked + watched + (s?.failedIds ?: emptySet()) + (s?.doneIds ?: emptySet())
        recompute(System.currentTimeMillis())
    }

    private class Snapshot(
        val runningIds: Set<Long>,
        val pausedIds: Set<Long>,
        val failedIds: Set<Long>,
        val doneIds: Set<Long>,
        val runPct: Int,
        val pausedPct: Int,
    )

    private fun pctOf(tasks: List<dev.dsh.miuixbrowser.download.DownloadTask>): Int {
        if (tasks.isEmpty() || !tasks.all { it.total > 0 }) return -1
        val total = tasks.sumOf { it.total }
        val bytes = tasks.sumOf { it.bytes }
        return ((bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100)
    }

    private fun fresh(id: Long) = id in watched || (initialIds?.contains(id) == false)

    private fun recompute(now: Long) {
        val s = last ?: return
        running = s.runningIds.isNotEmpty()
        paused = pausedSince.any { now - it.value < PAUSED_HIDE_MS }
        progressPct = if (running) s.runPct else s.pausedPct
        unseenFailed = failedSince.any { it.key !in acked && now - it.value < FAILED_HIDE_MS }
        unseenDone = doneSince.any { it.key !in acked && now - it.value < DONE_HIDE_MS }
    }

    /** 在 LaunchedEffect 里调用。进度按 1% 量化，避免每个数据块都触发重组。 */
    suspend fun track() = kotlinx.coroutines.coroutineScope {
        // 暂停 / 失败的自动隐藏靠定时重算
        launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (pausedSince.isNotEmpty() || failedSince.isNotEmpty() || doneSince.isNotEmpty()) recompute(System.currentTimeMillis())
            }
        }
        snapshotFlow {
            val tasks = Downloader.tasks.toList()
            val run = tasks.filter { it.state == DownloadState.RUNNING }
            val pau = tasks.filter { it.state == DownloadState.PAUSED }
            Snapshot(
                runningIds = run.map { it.id }.toSet(),
                pausedIds = pau.map { it.id }.toSet(),
                failedIds = tasks.filter { it.state == DownloadState.FAILED }.map { it.id }.toSet(),
                doneIds = tasks.filter { it.state == DownloadState.DONE }.map { it.id }.toSet(),
                runPct = pctOf(run),
                pausedPct = pctOf(pau),
            )
        }.collect { s ->
            val now = System.currentTimeMillis()
            if (initialIds == null) {
                initialIds = s.runningIds + s.pausedIds + s.failedIds + s.doneIds
            }
            watched = watched + s.runningIds
            // 重新开始下载的任务：之前的「已查看」作废，再失败 / 完成时重新提示
            acked = acked - s.runningIds
            pausedSince.keys.retainAll(s.pausedIds)
            s.pausedIds.forEach { id -> if (fresh(id) && id !in pausedSince) pausedSince[id] = now }
            failedSince.keys.retainAll(s.failedIds)
            s.failedIds.forEach { id -> if (fresh(id) && id !in failedSince) failedSince[id] = now }
            doneSince.keys.retainAll(s.doneIds)
            s.doneIds.forEach { id -> if (fresh(id) && id !in doneSince) doneSince[id] = now }
            last = s
            recompute(now)
        }
    }

    private companion object {
        const val PAUSED_HIDE_MS = 3 * 60 * 1000L
        const val FAILED_HIDE_MS = 60 * 1000L
        const val DONE_HIDE_MS = 60 * 1000L
    }
}

/**
 * 地址栏最左侧的下载按钮，位置和大小与阅读 / 脚本图标一致：
 * 下载中 = 主题色图标 + 进度环；暂停 = 灰色图标 + 灰色进度环；失败 = 右上角红点；完成 = 右上角绿点。
 * 任何时候点击都进入下载页。
 */
@Composable
internal fun AddressDownloadButton(state: AddressDownloadState, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val primary = MiuixTheme.colorScheme.primary
    val gray = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val ringStroke = with(LocalDensity.current) { 2.dp.toPx() }
    val dotRadius = with(LocalDensity.current) { 3.5.dp.toPx() }
    val dotBorder = with(LocalDensity.current) { 1.dp.toPx() }
    val surface = MiuixTheme.colorScheme.surface
    val running = state.running
    val showRing = running || state.paused
    val ringColor = if (running) primary else gray
    val pct = state.progressPct
    val determinate = pct >= 0
    val sweep by animateFloatAsState(
        targetValue = if (determinate) pct / 100f * 360f else 90f,
        animationSpec = tween(200),
        label = "dlSweep",
    )
    // 总大小未知：弧段匀速旋转（仅在下载中且不定进度时才创建动画）
    val spin = if (running && !determinate) {
        val t = rememberInfiniteTransition(label = "dlSpin")
        t.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1000, easing = androidx.compose.animation.core.LinearEasing)),
            label = "dlSpinAngle",
        ).value
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            if (showRing) {
                val inset = ringStroke / 2f
                val arcSize = Size(size.width - ringStroke, size.height - ringStroke)
                val topLeft = Offset(inset, inset)
                // 底环：淡色轨道
                drawArc(
                    color = ringColor.copy(alpha = 0.22f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = ringStroke),
                )
                // 进度弧：从 12 点钟方向顺时针；暂停且总大小未知时只画轨道
                if (running || determinate) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f + spin,
                        sweepAngle = sweep.coerceAtLeast(4f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = ringStroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
            // 右上角小圆点：失败（红）优先于完成（绿）
            if (state.unseenFailed || state.unseenDone) {
                val c = Offset(size.width - dotRadius, dotRadius)
                // 外圈用面板底色描边，把圆点和图标 / 进度环分开
                drawCircle(color = surface, radius = dotRadius + dotBorder, center = c)
                drawCircle(
                    color = if (state.unseenFailed) Color(0xFFFF3B30) else Color(0xFF34C759),
                    radius = dotRadius,
                    center = c,
                )
            }
        }
        val iconSize = with(LocalDensity.current) { 13.sp.toDp() }
        Icon(
            imageVector = MiuixIcons.Glass.Download,
            contentDescription = tr("下载"),
            // 只有暂停（没有进行中的任务）时图标变灰
            tint = if (!running && state.paused) gray else primary,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 地址栏最左侧的阅读模式按钮：未进入时是灰色线条，进入后图标点亮（主题色 + 淡色底）。 */
@Composable
internal fun AddressReaderButton(active: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val primary = MiuixTheme.colorScheme.primary
    val tint = if (active) primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (active) primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = with(LocalDensity.current) { 15.sp.toDp() }
        Icon(
            imageVector = AppIcons.Reader,
            contentDescription = if (active) tr("退出阅读模式") else tr("阅读模式"),
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 地址栏最左侧的脚本按钮：位置和大小与 [AddressReaderButton] 一致；有脚本在运行，所以图标用主题色。点击打开脚本菜单。 */
@Composable
internal fun AddressScriptButton(onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = with(LocalDensity.current) { 15.sp.toDp() }
        Icon(
            imageVector = AppIcons.Script,
            contentDescription = tr("正在运行的脚本"),
            tint = MiuixTheme.colorScheme.primary,
            // 视觉上略微下移（只移图标，点击区域不变）
            modifier = Modifier.offset(y = 1.dp).size(iconSize),
        )
    }
}

/** 地址栏最右端的刷新按钮 */
@Composable
internal fun AddressRefreshButton(onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 图标尺寸与地址栏文字（15.sp）一致
        val iconSize = with(LocalDensity.current) { 15.sp.toDp() }
        Icon(
            imageVector = AppIcons.Refresh,
            contentDescription = tr("刷新"),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            // 视觉上略微下移（只移图标，点击区域不变）
            modifier = Modifier.offset(y = 1.dp).size(iconSize),
        )
    }
}

/** 加载时地址栏里的小转点 */
@Composable
internal fun LoadingDot() {
    val transition = rememberInfiniteTransition(label = "load")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot",
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.primary.copy(alpha = alpha)),
    )
}

/* ------------------------------ 小部件 ------------------------------ */

@Composable
internal fun AddressField(
    value: String,
    onChange: (String) -> Unit,
    onGo: () -> Unit,
    editing: Boolean = true,
    onStartEdit: () -> Unit = {},
    onFocusLost: () -> Unit = {},
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val color = MiuixTheme.colorScheme.onSurface
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    // 用 TextFieldValue 才能控制选区：外部（String）是文字的唯一来源，这里只额外记住光标 / 选区
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // 外部文字变了（切换显示标题 / 网址、页面跳转）而选区还是旧文字的：光标放到末尾
    // 未编辑时光标放在开头：输入框会把光标滚进可视区，光标在末尾的话，底栏收起时（地址栏很窄）文字被滚到末尾，
    // 之后滑出变宽，滚动位置不会回到开头，网址头部就被遮住一截
    val idleCursor = if (editing) value.length else 0
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(idleCursor))
    LaunchedEffect(value) {
        if (field.text != value) field = TextFieldValue(value, TextRange(idleCursor))
    }
    // 进入编辑态（点击地址栏 / 圆形搜索钮）：先全选当前网址，再拿焦点，用户可以直接输入替换或粘贴；
    // 退出编辑态时选区收起。失焦时通知外部退出编辑态。
    LaunchedEffect(editing) {
        if (editing) {
            field = TextFieldValue(value, TextRange(0, value.length))
            runCatching { focusRequester.requestFocus() }
        } else {
            field = TextFieldValue(value, TextRange(0))
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) {
            Text(
                text = tr("搜索或输入网址"),
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                // 回主页时地址栏宽度还在弹簧动画里：占位文字必须单行不换行，否则会先折成多行再展开
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
        BasicTextField(
            value = shown,
            onValueChange = { v ->
                field = v
                // 只有文字真的变了才通知外部：光标 / 选区移动不算输入（外部据此判断「用户是否改过地址」）
                if (v.text != value) onChange(v.text)
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = color),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { st ->
                    if (st.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        hadFocus = false
                        onFocusLost()
                    }
                },
        )
        if (!editing) {
            // 未编辑时盖一层透明点击区，点一下立刻切到完整网址；长按复制网址。
            // 这一层会吞掉触摸，所以长按必须在这里处理，外层 Row 的 onLongClick 收不到事件。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onStartEdit,
                        onLongClick = onLongPress,
                    ),
            )
        }
    }
}

@Composable
internal fun BarButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    flip: Boolean = false,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val color = if (enabled) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                onClick = {
                    if (enabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = color,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { if (flip) rotationZ = 180f },
        )
    }
}


/**
 * 页面加载进度条。[progress] 是 lambda：进度每变一次（一次加载最多上百次）只在绘制阶段读取，
 * 不会让调用它的整个界面重组，也不会触发重新测量。
 */
@Composable
internal fun ProgressLine(progress: () -> Float, modifier: Modifier = Modifier) {
    val primary = MiuixTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.5.dp)
            .background(primary.copy(alpha = 0.15f))
            .drawBehind {
                drawRect(primary, size = Size(size.width * progress().coerceIn(0.02f, 1f), size.height))
            },
    )
}

/* ------------------------------ 弹层行 ------------------------------ */

@Composable
internal fun SheetRow(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (highlight) {
                    Modifier.border(2.dp, MiuixTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** 按选择器移除页面元素（长按菜单用） */
internal fun removeElementJs(selector: String): String =
    "(function(){try{var el=document.querySelector(" + org.json.JSONObject.quote(selector) +
        ");if(el){el.style.setProperty('display','none','important');el.remove();}}catch(e){}})();"

