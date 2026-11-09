package dev.dsh.miuixbrowser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.LocalContentColor
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop as LiquidBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.effects.vibrancy
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogDefaults
import top.yukonga.miuix.kmp.glass.GlassDropdownDefaults
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.rememberGlassDragDeform
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassShape
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.GlassPopupVisuals
import top.yukonga.miuix.kmp.glass.GlassBottomSheetDefaults
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ======================================================================
 * 液态玻璃（Kyant0/AndroidLiquidGlass）应用范围
 *
 * 设置 →「高级材质」选「liquid glass」时，下面这些组件改用 Kyant 的 drawBackdrop：
 * 弹出菜单（dropdown / popup）、底部 sheet、对话框、开关（Switch）；顶栏按钮 / FAB / 胶囊按钮见 LiquidGlassKit.kt。
 * 其它模式（miuix-glass / 关闭模糊）原样走 miuix 组件。
 *
 * - [LocalBlurMode]：当前模糊模式，由 MainActivity 提供。
 * - [LocalLiquidBackdrop]：当前页面的 Kyant 采样层，由各页面在 Scaffold 外提供；为 null 时（网页态 / 不支持）退回 miuix。
 * ====================================================================== */

val LocalBlurMode = compositionLocalOf { BLUR_MIUIX }
val LocalLiquidBackdrop = compositionLocalOf<LiquidBackdrop?> { null }

/** 液态玻璃模式下返回当前页面的 Kyant 采样层，否则 null。 */
@Composable
internal fun activeLiquidBackdrop(miuixBackdrop: Backdrop?): LiquidBackdrop? =
    if (LocalBlurMode.current == BLUR_LIQUID && miuixBackdrop != null) LocalLiquidBackdrop.current else null

/** 演示项目（catalog）的容器色：浅色 #FAFAFA@60%，深色 #121212@40%。 */
private fun demoContainerColor(dark: Boolean): Color =
    if (dark) Color(0xFF121212).copy(alpha = 0.4f) else Color(0xFFFAFAFA).copy(alpha = 0.6f)

/** 弹窗 / 菜单 / sheet 的边缘高光：比默认的 Plain 略宽，配合更强的折射让边缘玻璃感更明显。 */
private fun edgeHighlight(): Highlight = Highlight.Plain.copy(width = Highlight.Plain.width * 1.4f)

/** 液态玻璃面板（下拉菜单 / 弹出菜单）：与 catalog 的玻璃卡片一致——vibrancy + 模糊 + 折射 + 纯白高光。 */
@Composable
private fun Modifier.liquidPanel(
    backdrop: LiquidBackdrop,
    shape: Shape,
    blur: Dp = 2.dp,
    refractionHeight: Dp = 22.dp,
    refractionAmount: Dp = 52.dp,
): Modifier {
    val container = demoContainerColor(isAppDarkTheme())
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            backdropBlur(blur.toPx())
            lens(refractionHeight.toPx(), refractionAmount.toPx(), depthEffect = true, chromaticAberration = true)
        },
        highlight = { edgeHighlight() },
        onDrawSurface = { drawRect(container) },
    )
}

/* ------------------------------ 液态玻璃材质 ------------------------------ */

/**
 * Kyant 液态玻璃材质。采样层缺失（网页态 / 关闭模糊 / 不支持）时，每个控件都回退到 [MiuixGlassMaterial]。
 * 外观参数（模糊 / 折射 / 高光 / 叠色）全部集中在本对象和上方的 liquidPanel、edgeHighlight 里。
 */
object LiquidGlassMaterial : AppMaterial {

    @Composable
    override fun DialogHost(
        onDismissRequest: () -> Unit, show: Boolean, backdrop: Backdrop?, modifier: Modifier,
        title: String?, summary: String?, onDismissFinished: (() -> Unit)?, content: @Composable () -> Unit,
    ) {
        val liquid = activeLiquidBackdrop(backdrop)
        if (liquid == null) {
            MiuixGlassMaterial.DialogHost(onDismissRequest, show, backdrop, modifier, title, summary, onDismissFinished, content)
            return
        }
        val containerColor = GlassDialogDefaults.containerColor()
        val dark = isAppDarkTheme()
        val panelColor = demoContainerColor(dark)
        OverlayDialog(
            show = show,
            modifier = modifier,
            title = title,
            titleColor = DialogDefaults.titleColor(),
            summary = summary,
            summaryColor = DialogDefaults.summaryColor(),
            backgroundColor = containerColor,
            enableWindowDim = true,
            onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished,
            dimColor = GlassDialogDefaults.ScrimColor,
            surface = {
                val shape = RoundedCornerShape(cornerRadius)
                val scrim = scrimColor
                // 拖拽形变（按压放大 + 跟手拉伸）；面板没有跟指高光
                val deform = rememberGlassDragDeform()
                deform.layer.drawBackdrop(
                    backdrop = liquid,
                    shape = { shape },
                    // 对话框：提亮 + 加饱和 + 模糊 + 带深度的折射 + 边缘高光
                    effects = {
                        colorControls(
                            brightness = if (dark) 0f else 0.2f,
                            saturation = 1.5f,
                        )
                        backdropBlur(if (dark) 4f.dp.toPx() else 9f.dp.toPx())
                        lens(32f.dp.toPx(), 68f.dp.toPx(), depthEffect = true, chromaticAberration = true)
                    },
                    highlight = { edgeHighlight() },
                    onDrawSurface = {
                        drawRect(scrim.value)
                        drawRect(panelColor)
                    },
                )
            },
            content = content,
        )
    }

    @Composable
    override fun SheetHost(
        onDismissRequest: () -> Unit, show: Boolean, backdrop: Backdrop?, modifier: Modifier,
        enableWindowDim: Boolean, title: String?, startAction: @Composable (() -> Unit)?,
        endAction: @Composable (() -> Unit)?, onDismissFinished: (() -> Unit)?, content: @Composable () -> Unit,
    ) {
        val liquid = activeLiquidBackdrop(backdrop)
        if (liquid == null) {
            MiuixGlassMaterial.SheetHost(
                onDismissRequest, show, backdrop, modifier, enableWindowDim, title, startAction, endAction,
                onDismissFinished, content,
            )
            return
        }
        val panelColor = demoContainerColor(isAppDarkTheme())
        OverlayBottomSheet(
            show = show,
            modifier = modifier,
            title = title,
            startAction = startAction,
            endAction = endAction,
            backgroundColor = GlassBottomSheetDefaults.containerColor(),
            enableWindowDim = enableWindowDim,
            cornerRadius = BottomSheetDefaults.cornerRadius,
            sheetMaxWidth = BottomSheetDefaults.maxWidth,
            onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished,
            outsideMargin = BottomSheetDefaults.outsideMargin,
            insideMargin = BottomSheetDefaults.insideMargin,
            dragHandleColor = BottomSheetDefaults.dragHandleColor(),
            dimColor = GlassBottomSheetDefaults.ScrimColor,
            surface = {
                val shape = if (isOverscroll) {
                    RectangleShape
                } else {
                    RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)
                }
                val scrim = scrimColor
                // overscroll 的延伸部分跟着 sheet 走，只有 sheet 本体形变
                val deform = rememberGlassDragDeform()
                (if (isOverscroll) Modifier else deform.layer).drawBackdrop(
                    backdrop = liquid,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        backdropBlur(4f.dp.toPx())
                        if (!isOverscroll) lens(24f.dp.toPx(), 52f.dp.toPx(), depthEffect = true, chromaticAberration = true)
                    },
                    highlight = { edgeHighlight() },
                    onDrawSurface = {
                        drawRect(scrim.value)
                        drawRect(panelColor)
                    },
                )
            },
            content = content,
        )
    }

    @Composable
    override fun BoxScope.PopupHost(
        show: Boolean, onDismissRequest: () -> Unit, anchorBounds: androidx.compose.ui.geometry.Rect,
        backdrop: Backdrop?, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    ) {
        val liquid = activeLiquidBackdrop(backdrop)
        GlassPopup(
            show = show,
            onDismissRequest = onDismissRequest,
            anchorBounds = anchorBounds,
            backdrop = backdrop,
            modifier = modifier,
            surface = liquid?.let { liquidPopupSurface(it) },
            content = content,
        )
    }

    @Composable
    override fun DropdownRow(
        onSelectedIndexChange: (Int) -> Unit, items: List<String>, selectedIndex: Int, title: String,
        backdrop: Backdrop?, modifier: Modifier, enabled: Boolean, showValue: Boolean, summary: String?,
        startAction: @Composable (() -> Unit)?, insideMargin: PaddingValues, visuals: GlassPopupVisuals,
        marqueeItems: Boolean,
    ) {
        val liquid = activeLiquidBackdrop(backdrop)
        GlassDropdownPreference(
            onSelectedIndexChange = onSelectedIndexChange,
            items = items,
            selectedIndex = selectedIndex,
            title = title,
            backdrop = backdrop,
            modifier = modifier,
            enabled = enabled,
            showValue = showValue,
            summary = summary,
            startAction = startAction,
            insideMargin = insideMargin,
            visuals = visuals,
            marqueeItems = marqueeItems,
            surface = liquid?.let { liquidPopupSurface(it) },
        )
    }

    @Composable
    override fun SwitchRow(
        checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String, modifier: Modifier,
        summary: String?, enabled: Boolean,
    ) {
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        BasicComponent(
            modifier = modifier,
            title = title,
            summary = summary,
            endActions = {
                LiquidSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            },
            onClick = {
                if (enabled) {
                    haptic.performHapticFeedback(
                        if (!checked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                    )
                    onCheckedChange(!checked)
                }
            },
            enabled = enabled,
        )
    }

    @Composable
    override fun ToggleSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier, enabled: Boolean) {
        LiquidSwitch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
    }

    @Composable
    override fun ActionButton(
        onClick: () -> Unit, modifier: Modifier, enabled: Boolean, role: GlassDialogButtonRole,
        cornerRadius: Dp, minHeight: Dp, content: @Composable RowScope.() -> Unit,
    ) {
        val backdrop = LocalLiquidBackdrop.current
        if (backdrop == null) {
            MiuixGlassMaterial.ActionButton(onClick, modifier, enabled, role, cornerRadius, minHeight, content)
            return
        }
        val colors = MiuixTheme.colorScheme
        val tint = when (role) {
            GlassDialogButtonRole.Primary -> colors.primary
            GlassDialogButtonRole.Destructive -> colors.error
            GlassDialogButtonRole.Normal -> colors.surfaceContainer
        }
        val textColor = if (role == GlassDialogButtonRole.Normal) colors.onSurface else Color.White
        LiquidGlassSurface(
            onClick = onClick,
            backdrop = backdrop,
            modifier = modifier.height(minHeight.coerceAtLeast(44.dp)),
            tint = tint,
            tintAlpha = 0.35f,
            coloredTint = role != GlassDialogButtonRole.Normal,
            enabled = enabled,
            deformable = false,
        ) {
            CompositionLocalProvider(LocalContentColor provides textColor) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }
    }

    @Composable
    override fun LabelButton(
        text: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean, primary: Boolean,
        inPage: Boolean, cornerRadius: Dp, minWidth: Dp, minHeight: Dp, insideMargin: PaddingValues,
        textStyle: TextStyle?,
    ) {
        val pageBackdrop = LocalLiquidBackdrop.current
        if (pageBackdrop == null) {
            MiuixGlassMaterial.LabelButton(
                text, onClick, modifier, enabled, primary, inPage, cornerRadius, minWidth, minHeight, insideMargin, textStyle,
            )
            return
        }
        val colors = MiuixTheme.colorScheme
        val textColor = if (primary) Color.White else colors.onSurface
        val labelStyle = textStyle ?: MiuixTheme.textStyles.button
        val hPad = PaddingValues(
            start = insideMargin.calculateLeftPadding(LayoutDirection.Ltr),
            end = insideMargin.calculateRightPadding(LayoutDirection.Ltr),
        )
        // 外层 Box 由「不可见的同款文字」撑出宽度（LiquidGlassSurface 的各层都是 matchParentSize，自己没有内在尺寸）
        Box(
            modifier = modifier
                .heightIn(min = minHeight.coerceAtLeast(28.dp))
                .widthIn(min = minWidth),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = text, style = labelStyle, modifier = Modifier.padding(hPad).alpha(0f))
            LiquidGlassSurface(
                onClick = onClick,
                // 页面内的按钮位于采样层内部，不能采样页面层，用实色底
                backdrop = if (inPage) null else pageBackdrop,
                modifier = Modifier.matchParentSize(),
                tint = when {
                    primary -> colors.primary
                    inPage -> colors.surfaceVariant
                    else -> colors.surfaceContainer
                },
                tintAlpha = 0.35f,
                coloredTint = primary,
                enabled = enabled,
                // 弹窗里的按钮不形变、无跟指高光；页面内的按钮保持液态形变
                deformable = inPage,
            ) {
                Text(text = text, color = textColor, style = labelStyle)
            }
        }
    }

    @Composable
    override fun RoundButton(
        onClick: () -> Unit, backdrop: LiquidBackdrop?, modifier: Modifier, size: Dp, tint: Color,
        tintAlpha: Float, coloredTint: Boolean, enabled: Boolean, surfaceVisible: Boolean,
        content: @Composable () -> Unit,
    ) {
        LiquidGlassSurface(
            onClick = onClick,
            backdrop = backdrop,
            modifier = modifier.size(size),
            tint = tint,
            tintAlpha = tintAlpha,
            coloredTint = coloredTint,
            enabled = enabled,
            surfaceVisible = surfaceVisible,
            content = content,
        )
    }

    @Composable
    override fun SmallButton(
        onClick: () -> Unit, backdrop: LiquidBackdrop?, modifier: Modifier, size: Dp, enabled: Boolean,
        inPage: Boolean, content: @Composable () -> Unit,
    ) {
        val pageBackdrop = backdrop ?: LocalLiquidBackdrop.current
        if (pageBackdrop == null) {
            MiuixGlassMaterial.SmallButton(onClick, backdrop, modifier, size, enabled, inPage, content)
            return
        }
        val colors = MiuixTheme.colorScheme
        LiquidGlassSurface(
            onClick = onClick,
            // 页面内的按钮位于采样层内部，不能采样页面层，用实色底
            backdrop = if (inPage) null else pageBackdrop,
            modifier = modifier.size(size),
            tint = if (inPage) colors.surfaceVariant else colors.surfaceContainer,
            tintAlpha = 0.35f,
            enabled = enabled,
            deformable = inPage,
            content = content,
        )
    }

    @Composable
    override fun FloatingButton(
        onClick: () -> Unit, backdrop: LiquidBackdrop?, modifier: Modifier, primary: Boolean,
        content: @Composable () -> Unit,
    ) {
        RoundButton(
            onClick = onClick,
            backdrop = backdrop,
            modifier = modifier,
            size = 56.dp,
            tint = if (primary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer,
            tintAlpha = 0.35f,
            coloredTint = primary,
            enabled = true,
            surfaceVisible = true,
            content = content,
        )
    }

    @Composable
    override fun NavBackButton(backdrop: LiquidBackdrop?, surfaceVisible: Boolean, onClick: () -> Unit) {
        RoundButton(
            onClick = onClick,
            backdrop = backdrop,
            modifier = Modifier,
            size = 40.dp,
            tint = MiuixTheme.colorScheme.surfaceContainer,
            tintAlpha = 0.35f,
            coloredTint = false,
            enabled = true,
            surfaceVisible = surfaceVisible,
        ) {
            Icon(
                imageVector = MiuixIcons.Glass.ChevronBackward,
                contentDescription = "返回",
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
    }

    @Composable
    override fun PillActionButton(
        text: String, onClick: () -> Unit, backdrop: LiquidBackdrop?, modifier: Modifier,
        role: LiquidRole, enabled: Boolean,
    ) {
        val colors = MiuixTheme.colorScheme
        val tint = when (role) {
            LiquidRole.Primary -> colors.primary
            LiquidRole.Destructive -> colors.error
            LiquidRole.Normal -> colors.surfaceContainer
        }
        val textColor = if (role == LiquidRole.Normal) colors.onSurface else Color.White
        LiquidGlassSurface(
            onClick = onClick,
            backdrop = backdrop,
            modifier = modifier.height(48.dp),
            tint = tint,
            tintAlpha = 0.35f,
            coloredTint = role != LiquidRole.Normal,
            enabled = enabled,
            deformable = false,
        ) {
            Text(text = text, color = textColor, style = MiuixTheme.textStyles.button)
        }
    }
}

private fun liquidPopupSurface(liquid: LiquidBackdrop): @Composable BoxScope.(GlassShape) -> Unit = { _ ->
    LiquidPopupSurface(liquid)
}

/** 弹出面板的背景层：面板本身已按轮廓裁剪，这里只负责填满。 */
@Composable
private fun BoxScope.LiquidPopupSurface(liquid: LiquidBackdrop) {
    Box(
        Modifier
            .matchParentSize()
            .liquidPanel(
                backdrop = liquid,
                shape = RoundedCornerShape(GlassDropdownDefaults.CornerRadius),
            ),
    )
}

/**
 * 液态玻璃开关：直接移植自 Kyant0/AndroidLiquidGlass catalog 的 LiquidToggle。
 * 静止时是实心白色滑块；按下 / 拖动时滑块放大并变成透明的液态玻璃，折射下面的轨道（和页面背景）。
 * [backdrop] 只能传开关所在内容层之外的采样层；缺省为 null，只折射轨道。
 */
@Composable
fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // 开关本身位于页面采样层里面，不能默认去采页面层（滑块会画到包含自己的图层里，导致崩溃）；只折射轨道。
    backdrop: LiquidBackdrop? = null,
) {
    val dark = isAppDarkTheme()
    val accentColor = if (dark) Color(0xFF30D158) else Color(0xFF34C759)
    val trackColor = if (dark) Color(0xFF787880).copy(0.36f) else Color(0xFF787878).copy(0.2f)

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 20f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    // 回调里要读到最新的 checked / onCheckedChange
    val latestChecked by rememberUpdatedState(checked)
    val latestOnChange by rememberUpdatedState(onCheckedChange)
    val latestHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                val before = latestChecked
                if (didDrag) {
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    latestOnChange(fraction == 1f)
                    didDrag = false
                } else {
                    fraction = if (latestChecked) 0f else 1f
                    latestOnChange(fraction == 1f)
                }
                // 状态真的变了才震动（拖回原位不震）
                if ((fraction == 1f) != before) {
                    latestHaptic.performHapticFeedback(
                        if (fraction == 1f) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                    )
                }
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) {
                    didDrag = dragAmount.x != 0f
                }
                val delta = dragAmount.x / dragWidth
                fraction =
                    if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                    else (fraction - delta).fastCoerceIn(0f, 1f)
            },
        )
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }
            .collectLatest { fraction ->
                dampedDragAnimation.updateValue(fraction)
            }
    }
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (target != fraction) {
            fraction = target
            dampedDragAnimation.animateToValue(target)
        }
    }

    val trackBackdrop = rememberKyantBackdrop()
    // 滑块折射：页面背景 + 轨道（轨道按按压进度缩放，对应 catalog 的写法）
    val trackSampler = rememberBackdrop(trackBackdrop) { drawBackdrop ->
        val progress = dampedDragAnimation.pressProgress
        val scaleX = lerp(2f / 3f, 0.75f, progress)
        val scaleY = lerp(0f, 0.75f, progress)
        scale(scaleX, scaleY) {
            drawBackdrop()
        }
    }
    val thumbBackdrop = if (backdrop != null) rememberCombinedBackdrop(backdrop, trackSampler) else trackSampler

    Box(
        modifier = modifier.alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .kyantLayerBackdrop(trackBackdrop)
                .clip(CircleShape)
                .drawBehind {
                    val f = dampedDragAnimation.value
                    drawRect(lerpColor(trackColor, accentColor, f))
                }
                .size(64f.dp, 28f.dp),
        )

        Box(
            Modifier
                .graphicsLayer {
                    val f = dampedDragAnimation.value
                    val padding = 2f.dp.toPx()
                    translationX =
                        if (isLtr) lerp(padding, padding + dragWidth, f)
                        else lerp(-padding, -(padding + dragWidth), f)
                }
                .semantics { role = Role.Switch }
                .then(if (enabled) dampedDragAnimation.modifier else Modifier)
                .drawBackdrop(
                    backdrop = thumbBackdrop,
                    shape = { CircleShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        backdropBlur(8f.dp.toPx() * (1f - progress))
                        lens(
                            5f.dp.toPx() * progress,
                            10f.dp.toPx() * progress,
                            chromaticAberration = true,
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress,
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f),
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress,
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 50f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    },
                )
                .size(40f.dp, 24f.dp),
        )
    }
}
