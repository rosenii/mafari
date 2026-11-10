package dev.dsh.miuixbrowser

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
import top.yukonga.miuix.kmp.icon.glass.Settings
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.Refresh
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
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDefaults
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassMaterials
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassShape
import top.yukonga.miuix.kmp.glass.GlassStrokes
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
        style = GlassDefaults.style(dark),
        material = if (canUseGlass) GlassMaterials.puredThinGlass(dark) else null,
        stroke = if (blurEnabled) GlassStrokes.forTheme(dark) else null,
        shading = false,
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
    onMore: () -> Unit,
    onCopyAddress: () -> Unit,
    onAddressTap: () -> Unit = {},
    onFocusLost: () -> Unit = {},
    editing: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(29.dp)
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
            PressIcon(icon = MiuixIcons.Glass.Back, description = "后退", enabled = canGoBack, onClick = onBack)
            if (canGoForward) {
                PressIcon(
                    icon = MiuixIcons.Glass.Back,
                    description = "前进",
                    enabled = true,
                    flip = true,
                    onClick = onForward,
                )
            } else {
                PressIcon(
                    icon = MiuixIcons.Home,
                    description = "主页",
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
                    .combinedClickable(
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
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                    modifier = Modifier.weight(1f),
                )
            }

            TabIcon(count = tabCount, onClick = onTabs, onLongClick = onMore)
            PressIcon(icon = MiuixIcons.Glass.More, description = "更多", onClick = onMore)
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
                description = "标签页",
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
    modifier: Modifier = Modifier,
) {
    val color = MiuixTheme.colorScheme.onSurface
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    // 进入编辑态时主动拿焦点（圆形搜索钮 / 点击地址栏），失焦时通知外部退出编辑态
    LaunchedEffect(editing) {
        if (editing) runCatching { focusRequester.requestFocus() }
    }
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) {
            Text(
                text = "搜索或输入网址",
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                // 回主页时地址栏宽度还在弹簧动画里：占位文字必须单行不换行，否则会先折成多行再展开
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
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
            // 未编辑时盖一层透明点击区，点一下立刻切到完整网址
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onStartEdit,
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

