package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.data.BLUR_LIQUID
import dev.dsh.miuixbrowser.data.BLUR_OFF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.DpSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.geometry.Size
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults
import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import dev.dsh.miuixbrowser.data.BLUR_MIUIX
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop as LiquidBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassBottomSheetDefaults
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonDefaults
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassDialogDefaults
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupVisuals
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ======================================================================
 * 材质抽象层
 *
 * 「高级材质」四选一（设置 → 外观 → 模糊效果）对应三份实现：
 *   - [MiuixGlassMaterial]   miuix-glass（BLUR_MIUIX）
 *   - [LiquidGlassMaterial]  Kyant 液态玻璃（BLUR_LIQUID，见 LiquidGlassComponents.kt）
 *   - [FlatMaterial]         关闭模糊（BLUR_OFF）：miuix 组件 + 不采样的实色回退底
 *
 * 业务代码一律调用下方的 App* 门面函数（AppGlassDialog / AppDialogButton / AppSwitch …），
 * 门面只做一件事：转发给 [currentMaterial]。
 *
 * 新增一种控件：① 在 [AppMaterial] 里加一个方法；② 三份材质各实现一遍；③ 在本文件加一个门面。
 * 改某种材质的外观：只改对应的那一个对象，调用处不用动。
 * ====================================================================== */

interface AppMaterial {
    @Composable
    fun DialogHost(
        onDismissRequest: () -> Unit,
        show: Boolean,
        backdrop: Backdrop?,
        modifier: Modifier,
        title: String?,
        summary: String?,
        onDismissFinished: (() -> Unit)?,
        content: @Composable () -> Unit,
        /** backdrop 已是预模糊的壁纸层时为 true：材质不再叠加实时模糊（见 [LocalWallpaperPreBlurred]）。 */
        preBlurred: Boolean = false,
    )

    @Composable
    fun SheetHost(
        onDismissRequest: () -> Unit,
        show: Boolean,
        backdrop: Backdrop?,
        modifier: Modifier,
        enableWindowDim: Boolean,
        title: String?,
        startAction: @Composable (() -> Unit)?,
        endAction: @Composable (() -> Unit)?,
        onDismissFinished: (() -> Unit)?,
        content: @Composable () -> Unit,
    )

    @Composable
    fun BoxScope.PopupHost(
        show: Boolean,
        onDismissRequest: () -> Unit,
        anchorBounds: Rect,
        backdrop: Backdrop?,
        modifier: Modifier,
        alignStart: Boolean,
        content: @Composable ColumnScope.() -> Unit,
        /** backdrop 已是预模糊的壁纸层时为 true：材质不再叠加实时模糊。 */
        preBlurred: Boolean = false,
        /** 弹窗宽度上下限（默认 200–288dp）；内容窄时想让弹窗更窄就调小 minWidth。 */
        sizing: top.yukonga.miuix.kmp.glass.GlassPopupSizing = top.yukonga.miuix.kmp.glass.GlassPopupSizing(),
        /** 内容四周的内边距；默认上下 8dp。 */
        contentPadding: PaddingValues = PaddingValues(vertical = GlassPopupDefaults.ContentPaddingVertical),
    )

    @Composable
    fun DropdownRow(
        onSelectedIndexChange: (Int) -> Unit,
        items: List<String>,
        selectedIndex: Int,
        title: String,
        backdrop: Backdrop?,
        modifier: Modifier,
        enabled: Boolean,
        showValue: Boolean,
        summary: String?,
        startAction: @Composable (() -> Unit)?,
        insideMargin: PaddingValues,
        visuals: GlassPopupVisuals,
        marqueeItems: Boolean,
    )

    @Composable
    fun SwitchRow(
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        title: String,
        modifier: Modifier,
        summary: String?,
        enabled: Boolean,
    )

    @Composable
    fun ToggleSwitch(
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        modifier: Modifier,
        enabled: Boolean,
    )

    /**
     * 弹窗 / sheet 底部的按钮，也用于页面卡片里的操作按钮。
     * [inPage] 为 true 表示按钮在页面内容里（位于液态玻璃采样层内部），液态材质下改用实色底，避免递归采样闪退。
     */
    @Composable
    fun ActionButton(
        onClick: () -> Unit,
        modifier: Modifier,
        enabled: Boolean,
        inPage: Boolean,
        role: GlassDialogButtonRole,
        cornerRadius: Dp,
        minHeight: Dp,
        content: @Composable RowScope.() -> Unit,
    )

    /** 文字按钮（订阅 / 添加 / 安装 / 使用自定义 …） */
    @Composable
    fun LabelButton(
        text: String,
        onClick: () -> Unit,
        modifier: Modifier,
        enabled: Boolean,
        primary: Boolean,
        inPage: Boolean,
        cornerRadius: Dp,
        minWidth: Dp,
        minHeight: Dp,
        insideMargin: PaddingValues,
        textStyle: TextStyle?,
    )

    /** 圆形图标按钮（顶栏按钮） */
    @Composable
    fun RoundButton(
        onClick: () -> Unit,
        backdrop: LiquidBackdrop?,
        modifier: Modifier,
        size: Dp,
        tint: Color,
        tintAlpha: Float,
        coloredTint: Boolean,
        enabled: Boolean,
        surfaceVisible: Boolean,
        content: @Composable () -> Unit,
    )

    /**
     * 小型圆形图标按钮（分组展开 / 收起、卡片「更多」等页面内的小控件）。
     * [inPage] 为 true 表示按钮在页面内容里（位于液态玻璃采样层内部），液态材质下改用实色底，避免递归采样。
     */
    @Composable
    fun SmallButton(
        onClick: () -> Unit,
        backdrop: LiquidBackdrop?,
        modifier: Modifier,
        size: Dp,
        enabled: Boolean,
        inPage: Boolean,
        content: @Composable () -> Unit,
    )

    @Composable
    fun FloatingButton(
        onClick: () -> Unit,
        backdrop: LiquidBackdrop?,
        modifier: Modifier,
        primary: Boolean,
        content: @Composable () -> Unit,
    )

    @Composable
    fun NavBackButton(backdrop: LiquidBackdrop?, surfaceVisible: Boolean, onClick: () -> Unit)

    /** sheet / 对话框底部并排的胶囊按钮 */
    @Composable
    fun PillActionButton(
        text: String,
        onClick: () -> Unit,
        backdrop: LiquidBackdrop?,
        modifier: Modifier,
        role: LiquidRole,
        enabled: Boolean,
    )
}

/**
 * 弹窗 / sheet 的内容是在 popup host 的 composition 里执行的，读不到调用处的 CompositionLocal
 * （LocalLiquidBackdrop / LocalBlurMode 会变回默认值，内部的下拉菜单、按钮就会退回 miuix 材质）。
 * 在调用处读出当前值，再在内容里重新提供一遍。
 */
@Composable
private fun rememberOverlayContent(
    content: @Composable () -> Unit,
    immersive: Boolean = false,
    sheetContentPadding: Dp = 0.dp,
    padInsets: Boolean = true,
    sheetBackdrop: Backdrop? = null,
    sheetScrim: Color = Color.Transparent,
): @Composable () -> Unit {
    val liquidBackdrop = LocalLiquidBackdrop.current
    val mode = LocalBlurMode.current
    val glassParams = LocalGlassParams.current
    val latest by rememberUpdatedState(content)
    return remember(liquidBackdrop, mode, glassParams, immersive, sheetContentPadding, padInsets, sheetBackdrop, sheetScrim) {
        val bandBase = SheetBandBase(sheetBackdrop, sheetScrim)
        val wrapped: @Composable () -> Unit = {
            CompositionLocalProvider(
                LocalLiquidBackdrop provides liquidBackdrop,
                LocalBlurMode provides mode,
                LocalGlassParams provides glassParams,
                LocalSheetBandBase provides bandBase,
            ) {
                if (immersive) SheetImmersiveBody(sheetContentPadding, padInsets) { latest() } else latest()
            }
        }
        wrapped
    }
}

/** 顶部渐进模糊带的取样底：sheet 背后的页面录制层（与玻璃面板采样同一层）+ sheet 当前的压暗色。 */
private class SheetBandBase(val backdrop: Backdrop?, val scrim: Color)

private val LocalSheetBandBase = androidx.compose.runtime.staticCompositionLocalOf<SheetBandBase?> { null }

/** 渐进模糊带比头部多出的高度（头部下沿以下）。 */
private val SheetBandExtra = 40.dp

/**
 * true：用上游 miuix progressiveTextureBlur 做顶部带（真渐进，半径渐变；需要不透明取样层）；
 * false：改为对内容本身做「清晰 → 模糊」渐变（无底色，但只有单一模糊半径）。
 */
private const val SheetBandUseMiuix = true

/** 渐进模糊从头部下沿以上多少 dp 开始向下渐隐。 */
private val SheetBandFadeLead = 8.dp

/** 头部区域内容的模糊半径。 */
private val SheetBandBlurRadius = 20.dp

/** 纯色材质（无模糊）：头部下沿以上多少 dp 开始渐隐 / 以下多少 dp 完全不透明。 */
private val SheetFadeTopLead = 16.dp
private val SheetFadeTopTail = 4.dp

/** 材质颜色层混合模式 → Compose BlendMode（PlusDarker 没有对等项，用 Multiply 近似）。 */
private fun top.yukonga.miuix.kmp.material.GlassColorBlendMode.toComposeBlendMode(): BlendMode = when (this) {
    top.yukonga.miuix.kmp.material.GlassColorBlendMode.SrcOver -> BlendMode.SrcOver
    top.yukonga.miuix.kmp.material.GlassColorBlendMode.PlusDarker -> BlendMode.Multiply
    top.yukonga.miuix.kmp.material.GlassColorBlendMode.PlusLighter -> BlendMode.Plus
    top.yukonga.miuix.kmp.material.GlassColorBlendMode.SoftLight -> BlendMode.Softlight
    top.yukonga.miuix.kmp.material.GlassColorBlendMode.HardLight -> BlendMode.Hardlight
    top.yukonga.miuix.kmp.material.GlassColorBlendMode.Overlay -> BlendMode.Overlay
    top.yukonga.miuix.kmp.material.GlassColorBlendMode.Luminosity -> BlendMode.Luminosity
    top.yukonga.miuix.kmp.material.GlassColorBlendMode.ColorDodge -> BlendMode.ColorDodge
    top.yukonga.miuix.kmp.material.GlassColorBlendMode.ColorBurn -> BlendMode.ColorBurn
}

/**
 * 滚动偏移的外部同步入口：渐进模糊带原本只靠 NestedScroll 感知滚动，
 * 程序化滚动（scrollToItem 等）不走 NestedScroll，内容已经滚到头部下面了带子却不出现。
 * 内容里的 LazyList 调用 [SyncSheetBandScroll] 即可让带子跟着列表真实位置走。
 */
private val LocalSheetBandScroll = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableFloatState?> { null }

@Composable
fun SyncSheetBandScroll(state: androidx.compose.foundation.lazy.LazyListState) {
    val target = LocalSheetBandScroll.current ?: return
    androidx.compose.runtime.LaunchedEffect(state, target) {
        androidx.compose.runtime.snapshotFlow {
            if (!state.canScrollBackward) {
                0f
            } else {
                val first = state.layoutInfo.visibleItemsInfo.firstOrNull()
                (state.firstVisibleItemScrollOffset + (first?.size ?: 0) * state.firstVisibleItemIndex).toFloat()
            }
        }.collect { target.floatValue = it }
    }
}

/** 带子底色雾化，与上游 GlassTopAppBar 渐进模糊带一致（surface 30%）。 */
private const val SheetBandTintAlpha = 0.3f

private fun sheetSmootherstep(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

private val SheetFadeStops = List(17) { it / 16f }

/** quintic 渐变蒙版（与上游 TopAppBarBandBrush 同一条曲线）：[fadeIn] = true 为 0→1，否则 1→0；[peak] 为不透明端的 alpha。 */
private fun sheetFadeBrush(startY: Float, endY: Float, fadeIn: Boolean, peak: Float = 1f): Brush =
    Brush.verticalGradient(
        *SheetFadeStops.map { t ->
            val a = sheetSmootherstep(t)
            t to Color.Black.copy(alpha = peak * (if (fadeIn) a else 1f - a))
        }.toTypedArray(),
        startY = startY,
        endY = endY,
    )

/**
 * 沉浸 sheet 的内容层（[AppBottomSheet] 统一入口）。
 *
 * 上游 `immersive` 布局下内容铺满整个 sheet，拖动条 / 标题浮在内容上面，头部高度和导航栏高度通过
 * [LocalBottomSheetInsets] 给到内容。导航栏（小白条）直接浮在内容上，底部不做任何渐隐；
 * 只有顶部在内容滚到头部下面时处理：
 * - 柔光 / 磨砂 / 液态玻璃（支持 RuntimeShader）：从 sheet 最顶端（y = 0，覆盖拖动条和标题）开始的渐进模糊带，
 *   随滚动淡入，静止时不出现，按 sheet 顶部圆角裁剪；
 * - 纯色（关闭模糊）或不支持 RuntimeShader：内容在头部下沿做 alpha 渐隐。
 *
 * @param padInsets true：内容整体按头部 / 导航栏高度加上下内边距（不滚动的内容用）；
 *   false：内容自己读 [LocalBottomSheetInsets] 并把内边距交给滚动容器，让滚动内容铺到头部和导航栏下面。
 */
@Composable
private fun SheetImmersiveBody(contentPadding: Dp, padInsets: Boolean, content: @Composable () -> Unit) {
    val insets = LocalBottomSheetInsets.current
    val blur = LocalBlurMode.current != BLUR_OFF && top.yukonga.miuix.kmp.material.isRuntimeShaderSupported()
    val inner = Modifier
        .padding(horizontal = contentPadding)
        .then(if (padInsets) Modifier.padding(top = insets.top, bottom = insets.bottom) else Modifier)
    if (!blur) {
        Box(
            modifier = Modifier
                // 离屏合成：下面的蒙版用 DstIn，只作用于内容层自己，不能影响 sheet 的玻璃面板
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val topStart = (insets.top - SheetFadeTopLead).toPx().coerceAtLeast(0f)
                    val topEnd = (insets.top + SheetFadeTopTail).toPx()
                    if (topEnd > topStart && size.width > 0f) {
                        drawRect(
                            brush = sheetFadeBrush(topStart, topEnd, fadeIn = true),
                            size = Size(size.width, topEnd.coerceAtMost(size.height)),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
                .then(inner),
        ) { content() }
        return
    }

    if (SheetBandUseMiuix) {
        SheetMiuixBandBody(inner, insets.top, content)
        return
    }

    // 不再用「垫不透明底色的取样层 + 带子盖在上面」：那样带子是一块独立的不透明面，和 sheet 自己的玻璃面板对不上。
    // 改成在内容层自己身上做渐进模糊：内容录进 srcLayer，头部附近把清晰内容按渐变擦掉（DstOut），
    // 同位置叠上同一份内容的模糊副本（同一条渐变做 DstIn）。两层都只带内容自己的 alpha，没有任何底色，
    // 玻璃面板始终是 sheet 原本的样子，只有滚到头部下面的内容被渐进模糊。
    val srcLayer = rememberGraphicsLayer()
    val blurLayer = rememberGraphicsLayer()
    var scrolled by remember { mutableFloatStateOf(0f) }
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                scrolled = if (consumed.y == 0f && available.y > 0f) {
                    0f // 向下拉但子项已无法消费：子项在顶部，修正累计漂移
                } else {
                    (scrolled - consumed.y).coerceAtLeast(0f)
                }
                return Offset.Zero
            }
        }
    }
    val rampPx = with(LocalDensity.current) { GlassTopAppBarDefaults.ProgressiveBlurRampDistance.toPx() }
    Box(modifier = Modifier.nestedScroll(connection)) {
        Box(
            modifier = Modifier
                // 离屏合成：DstOut / DstIn 只作用于内容层自己，不影响 sheet 的玻璃面板
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    val strength = (scrolled / rampPx).coerceIn(0f, 1f)
                    val bandH = (insets.top + SheetBandExtra).toPx().coerceAtMost(size.height)
                    if (strength <= 0f || bandH <= 0f || size.width <= 0f) {
                        drawContent()
                        return@drawWithContent
                    }
                    val fadeStart = (insets.top - SheetBandFadeLead).toPx().coerceIn(0f, bandH)
                    val contentSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    val bandSize = IntSize(contentSize.width, bandH.roundToInt())
                    val ramp = sheetFadeBrush(fadeStart, bandH, fadeIn = false, peak = strength)
                    val contentScope = this
                    srcLayer.record(this, layoutDirection, contentSize) { contentScope.drawContent() }
                    blurLayer.compositingStrategy = LayerCompositingStrategy.Offscreen
                    blurLayer.renderEffect = BlurEffect(SheetBandBlurRadius.toPx(), SheetBandBlurRadius.toPx(), TileMode.Clamp)
                    blurLayer.record(this, layoutDirection, bandSize) {
                        drawLayer(srcLayer)
                        drawRect(brush = ramp, size = Size(size.width, size.height), blendMode = BlendMode.DstIn)
                    }
                    // 清晰内容：头部区域按渐变擦掉
                    drawLayer(srcLayer)
                    drawRect(brush = ramp, size = Size(size.width, bandH), blendMode = BlendMode.DstOut)
                    // 模糊副本：按 sheet 顶部圆角裁剪
                    val r = CornerRadius(BottomSheetDefaults.cornerRadius.toPx())
                    val bandPath = Path().apply {
                        addRoundRect(RoundRect(0f, 0f, size.width, bandH, r, r, CornerRadius.Zero, CornerRadius.Zero))
                    }
                    clipPath(bandPath) { drawLayer(blurLayer) }
                }
                .then(inner),
        ) { content() }
    }
}

/**
 * 用上游 miuix 的 progressiveTextureBlur（半径随位置渐变的真渐进模糊）做 sheet 顶部带。
 *
 * miuix 的模糊着色器要求被采样的层不透明，所以取样层要垫底色；底色取 surface（与页面顶栏一致）。
 * 为了和半透明的玻璃面板衔接，带子自身在离屏层上从头部下沿附近渐隐到 0（DstIn），
 * 不再在底边留硬边；整体 alpha 随滚动淡入，静止时不出现。
 */
@Composable
private fun SheetMiuixBandBody(inner: Modifier, headerTop: Dp, content: @Composable () -> Unit) {
    val surface = MiuixTheme.colorScheme.surface
    val base = LocalSheetBandBase.current
    val pageBackdrop = base?.backdrop?.takeIf { top.yukonga.miuix.kmp.material.isRuntimeShaderSupported() }
    val scrim = base?.scrim ?: Color.Transparent
    var hostCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // 取样层 = 玻璃面板同源的页面录制层（先画，按本组件坐标对齐）+ 压暗 + sheet 内容，与上游 GlassTopAppBar 的做法一致。
    // 页面层先按「sheet 玻璃面板自己的模糊半径」模糊再当取样底：带子底下的背景因此和面板一致（同一份页面、同一个半径），
    // 衔接自然，也不会像直接采清晰页面那样透出 sheet 下层的文字；不用盖雾，避免和面板对不上。
    // 没有页面录制层（例如 Gecko 网页态无法采样）时才退回实色底。
    val panelMaterial = if (GlassLook.custom()) GlassLook.largeMaterial(isAppDarkTheme()) else GlassBottomSheetDefaults.material()
    val panelBlurPx = with(LocalDensity.current) { panelMaterial.blurRadius.toPx() }
    val panelLayers = remember(panelMaterial) { listOfNotNull(panelMaterial.first, panelMaterial.second, panelMaterial.third) }
    val pageLayer = rememberGraphicsLayer()
    val backdrop = rememberLayerBackdrop {
        if (pageBackdrop != null) {
            val coordinates = hostCoordinates?.takeIf { it.isAttached }
            val layerSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
            if (panelBlurPx > 0f) {
                pageLayer.compositingStrategy = LayerCompositingStrategy.Offscreen
                pageLayer.renderEffect = BlurEffect(panelBlurPx, panelBlurPx, TileMode.Clamp)
            } else {
                pageLayer.renderEffect = null
            }
            pageLayer.record(this, layoutDirection, layerSize) {
                val layerScope = this
                with(pageBackdrop) { drawBackdrop(layerScope, coordinates) }
                // 面板材质的颜色层（磨砂白雾等）也叠进取样底，和面板同一套，带子与面板才没有色差
                for (layer in panelLayers) {
                    if (layer.color.alpha > 0f) drawRect(layer.color, size = size, blendMode = layer.mode.toComposeBlendMode())
                }
            }
            drawLayer(pageLayer)
            if (scrim.alpha > 0f) drawRect(scrim, size = size, blendMode = BlendMode.SrcAtop)
        } else {
            drawRect(color = surface.copy(alpha = 1f), size = size)
        }
        drawContent()
    }
    val scrolledState = remember { mutableFloatStateOf(0f) }
    var scrolled by scrolledState
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                scrolled = if (consumed.y == 0f && available.y > 0f) {
                    0f
                } else {
                    (scrolled - consumed.y).coerceAtLeast(0f)
                }
                return Offset.Zero
            }
        }
    }
    val showBand by remember { derivedStateOf { scrolled > 0f } }
    val rampPx = with(LocalDensity.current) { GlassTopAppBarDefaults.ProgressiveBlurRampDistance.toPx() }
    val bandShape = remember {
        RoundedCornerShape(topStart = BottomSheetDefaults.cornerRadius, topEnd = BottomSheetDefaults.cornerRadius)
    }
    val blendColors = remember(surface) { listOf(BlendColorEntry(surface.copy(alpha = SheetBandTintAlpha))) }
    val blurColors = BlurDefaults.blurColors(blendColors = blendColors)
    Box(modifier = Modifier.nestedScroll(connection)) {
        Box(
            modifier = Modifier
                .onGloballyPositioned { hostCoordinates = it }
                .layerBackdrop(backdrop)
                .then(inner),
        ) {
            CompositionLocalProvider(LocalSheetBandScroll provides scrolledState) { content() }
        }
        if (showBand) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(headerTop + SheetBandExtra)
                    .graphicsLayer {
                        alpha = (scrolled / rampPx).coerceIn(0f, 1f)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    // 放在 clip 外层：clip 自己的层不是离屏，DstIn 会误伤下面的像素
                    .drawWithContent {
                        drawContent()
                        val fadeStart = (headerTop - SheetBandFadeLead).toPx().coerceIn(0f, size.height)
                        if (size.height > fadeStart) {
                            drawRect(
                                brush = sheetFadeBrush(fadeStart, size.height, fadeIn = false),
                                size = size,
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    }
                    .clip(bandShape)
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Top.copy(curve = 0.5f),
                        blurRadius = GlassTopAppBarDefaults.ProgressiveBlurRadius.value,
                        colors = blurColors,
                    ),
            )
        }
    }
}

/**
 * 弹出菜单 / 下拉的玻璃外观。柔光玻璃下用自定义材质参数的样式和材质（null = 轻模糊背景为主体），
 * 其余模式沿用上游默认。
 */
@Composable
fun appPopupVisuals(sampling: Boolean = true): GlassPopupVisuals {
    val dark = isAppDarkTheme()
    val shading = sampling && GlassLook.custom()
    return GlassPopupDefaults.visuals(
        style = GlassLook.style(dark),
        material = if (shading) GlassLook.largeMaterial(dark) else GlassPopupDefaults.material(),
    )
}

/** 按当前「模糊效果」设置取材质。 */
@Composable
fun currentMaterial(): AppMaterial = when (LocalBlurMode.current) {
    BLUR_LIQUID -> LiquidGlassMaterial
    BLUR_OFF -> FlatMaterial
    else -> MiuixGlassMaterial
}

/* ------------------------------ miuix-glass / 关闭模糊 ------------------------------ */

/**
 * miuix-glass 材质。[sampling] 为 false 时（[FlatMaterial]）强制不采样背景，面板用 miuix 的实色回退底。
 */
open class MiuixGlassMaterialBase(private val sampling: Boolean) : AppMaterial {
    private fun bd(b: Backdrop?): Backdrop? = if (sampling) b else null

    @Composable
    override fun DialogHost(
        onDismissRequest: () -> Unit, show: Boolean, backdrop: Backdrop?, modifier: Modifier,
        title: String?, summary: String?, onDismissFinished: (() -> Unit)?, content: @Composable () -> Unit,
        preBlurred: Boolean,
    ) {
        // 柔光玻璃：材质 / 样式 / 折射跟「自定义材质参数」走（上游默认的 dialogGlass 是 60dp 大模糊 + 三层白雾，会把样式盖住）
        val shading = sampling && GlassLook.custom()
        val dark = isAppDarkTheme()
        // 取样源已是预模糊壁纸：对话框材质默认 20dp 的实时模糊是在糊上加糊，半径清零即整段跳过模糊 pass
        val dialogMaterial: GlassMaterial? = if (shading) {
            GlassLook.largeMaterial(dark)
        } else {
            GlassDialogDefaults.material().let {
                if (sampling && preBlurred && backdrop != null) it.copy(blurRadius = 0.dp) else it
            }
        }
        GlassDialog(
            onDismissRequest = onDismissRequest,
            show = show,
            backdrop = bd(backdrop),
            modifier = modifier,
            title = title,
            summary = summary,
            material = dialogMaterial,
            shading = shading,
            style = GlassLook.style(dark),
            onDismissFinished = onDismissFinished,
            content = content,
        )
    }

    @Composable
    override fun SheetHost(
        onDismissRequest: () -> Unit, show: Boolean, backdrop: Backdrop?, modifier: Modifier,
        enableWindowDim: Boolean, title: String?, startAction: @Composable (() -> Unit)?,
        endAction: @Composable (() -> Unit)?, onDismissFinished: (() -> Unit)?, content: @Composable () -> Unit,
    ) {
        val shading = sampling && GlassLook.custom()
        val dark = isAppDarkTheme()
        GlassBottomSheet(
            onDismissRequest = onDismissRequest,
            show = show,
            backdrop = bd(backdrop),
            modifier = modifier,
            material = if (shading) GlassLook.largeMaterial(dark) else GlassBottomSheetDefaults.material(),
            shading = shading,
            style = GlassLook.style(dark),
            enableWindowDim = enableWindowDim,
            title = title,
            startAction = startAction,
            endAction = endAction,
            // 取消 sheet 内边距：内容层与背景面板才能铺满整个宽度（左右留白由 AppBottomSheet 加在内容上）
            insideMargin = DpSize.Zero,
            // 沉浸：内容铺满整个 sheet，拖动条 / 标题浮在上面（见上游补丁 0010）
            immersive = true,
            onDismissFinished = onDismissFinished,
            content = content,
        )
    }

    @Composable
    override fun BoxScope.PopupHost(
        show: Boolean, onDismissRequest: () -> Unit, anchorBounds: Rect, backdrop: Backdrop?,
        modifier: Modifier, alignStart: Boolean, content: @Composable ColumnScope.() -> Unit,
        preBlurred: Boolean, sizing: top.yukonga.miuix.kmp.glass.GlassPopupSizing, contentPadding: PaddingValues,
    ) {
        // 同 DialogHost：预模糊取样源上不再叠加 20dp 实时模糊
        val base = appPopupVisuals(sampling)
        val visuals = if (sampling && preBlurred && backdrop != null && !(sampling && GlassLook.custom())) {
            base.copy(material = base.material?.copy(blurRadius = 0.dp))
        } else base
        GlassPopup(
            show = show,
            onDismissRequest = onDismissRequest,
            anchorBounds = anchorBounds,
            backdrop = bd(backdrop),
            modifier = modifier,
            alignStart = alignStart,
            visuals = visuals,
            sizing = sizing,
            contentPadding = contentPadding,
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
        GlassDropdownPreference(
            onSelectedIndexChange = onSelectedIndexChange,
            items = items,
            selectedIndex = selectedIndex,
            title = title,
            backdrop = bd(backdrop),
            modifier = modifier,
            enabled = enabled,
            showValue = showValue,
            summary = summary,
            startAction = startAction,
            insideMargin = insideMargin,
            visuals = visuals,
            marqueeItems = marqueeItems,
        )
    }

    @Composable
    override fun SwitchRow(
        checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String, modifier: Modifier,
        summary: String?, enabled: Boolean,
    ) {
        SwitchPreference(
            checked = checked,
            onCheckedChange = onCheckedChange,
            title = title,
            modifier = modifier,
            summary = summary,
            enabled = enabled,
        )
    }

    @Composable
    override fun ToggleSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier, enabled: Boolean) {
        top.yukonga.miuix.kmp.basic.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    }

    @Composable
    override fun ActionButton(
        onClick: () -> Unit, modifier: Modifier, enabled: Boolean, inPage: Boolean, role: GlassDialogButtonRole,
        cornerRadius: Dp, minHeight: Dp, content: @Composable RowScope.() -> Unit,
    ) {
        GlassDialogButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            role = role,
            cornerRadius = cornerRadius,
            minHeight = minHeight,
            content = content,
        )
    }

    @Composable
    override fun LabelButton(
        text: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean, primary: Boolean,
        inPage: Boolean, cornerRadius: Dp, minWidth: Dp, minHeight: Dp, insideMargin: PaddingValues,
        textStyle: TextStyle?,
    ) {
        top.yukonga.miuix.kmp.basic.TextButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            cornerRadius = cornerRadius,
            minWidth = minWidth,
            minHeight = minHeight,
            colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
            insideMargin = insideMargin,
            textStyle = textStyle,
        )
    }

    @Composable
    override fun RoundButton(
        onClick: () -> Unit, backdrop: LiquidBackdrop?, modifier: Modifier, size: Dp, tint: Color,
        tintAlpha: Float, coloredTint: Boolean, enabled: Boolean, surfaceVisible: Boolean,
        content: @Composable () -> Unit,
    ) {
        HapticGlassIconButton(
            onClick = { if (enabled) onClick() },
            modifier = modifier.alpha(if (enabled) 1f else 0.4f),
            content = content,
        )
    }

    @Composable
    override fun SmallButton(
        onClick: () -> Unit, backdrop: LiquidBackdrop?, modifier: Modifier, size: Dp, enabled: Boolean,
        inPage: Boolean, content: @Composable () -> Unit,
    ) {
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        val dark = isAppDarkTheme()
        top.yukonga.miuix.kmp.glass.GlassIconButton(
            style = GlassLook.barStyle(dark),
            material = if (GlassLook.custom()) GlassLook.barMaterial(dark) else null,
            onClick = {
                if (enabled) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            },
            modifier = modifier.alpha(if (enabled) 1f else 0.4f),
            size = size,
            shadow = null,
            content = content,
        )
    }

    @Composable
    override fun FloatingButton(
        onClick: () -> Unit, backdrop: LiquidBackdrop?, modifier: Modifier, primary: Boolean,
        content: @Composable () -> Unit,
    ) {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier,
            containerColor = if (primary) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer,
            content = content,
        )
    }

    @Composable
    override fun NavBackButton(backdrop: LiquidBackdrop?, surfaceVisible: Boolean, onClick: () -> Unit) {
        GlassBackButton(onClick = onClick)
    }

    @Composable
    override fun PillActionButton(
        text: String, onClick: () -> Unit, backdrop: LiquidBackdrop?, modifier: Modifier,
        role: LiquidRole, enabled: Boolean,
    ) {
        GlassDialogButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            role = when (role) {
                LiquidRole.Primary -> GlassDialogButtonRole.Primary
                LiquidRole.Destructive -> GlassDialogButtonRole.Destructive
                LiquidRole.Normal -> GlassDialogButtonRole.Normal
            },
        ) {
            Text(text = text, style = MiuixTheme.textStyles.button)
        }
    }
}



object MiuixGlassMaterial : MiuixGlassMaterialBase(true)

/** 关闭模糊：与 miuix-glass 同一套组件，但不采样背景，面板与按钮都是实色回退底。 */
object FlatMaterial : MiuixGlassMaterialBase(false)

/* ------------------------------ 门面：业务代码只调用这些 ------------------------------ */

/** 对话框 */
@Composable
fun AppGlassDialog(
    onDismissRequest: () -> Unit,
    show: Boolean,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    onDismissFinished: (() -> Unit)? = null,
    /** backdrop 是预模糊的壁纸层时传 true（miuix 材质据此跳过实时模糊；液态材质不受影响）。 */
    preBlurred: Boolean = false,
    content: @Composable () -> Unit,
) {
    val material = currentMaterial()
    material.DialogHost(
        onDismissRequest, show, backdrop, modifier, title, summary, onDismissFinished,
        content = rememberOverlayContent(content),
        preBlurred = preBlurred,
    )
}

/** 底部 sheet */
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    show: Boolean,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    enableWindowDim: Boolean = true,
    title: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    endAction: @Composable (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    /**
     * 内容与标题栏按钮的左右留白。sheet 本身已取消内边距（insideMargin = 0），
     * 留白改由这里加在内容上；传 0.dp 可让内容（如列表分割线）通栏铺满。
     */
    contentHorizontalPadding: Dp = BottomSheetDefaults.insideMargin.width,
    /**
     * 内容自带滚动容器时传 true：sheet 不再给内容加上下内边距，内容自己读 [LocalBottomSheetInsets]
     * （头部高度 / 导航栏高度）并交给滚动容器做 contentPadding，列表才能铺到头部和导航栏下面再渐隐。
     * 默认 false：不滚动的内容整体被推到头部下方、导航栏上方，不用自己处理导航栏留白。
     */
    scrollContent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val material = currentMaterial()
    // 标题栏两侧按钮原先靠 sheet 内边距离开屏幕边缘，现在自己补上
    val start: (@Composable () -> Unit)? = startAction?.let { a ->
        // 左右都留白：标签页头部整行放在 startAction 里并铺满宽度，只留左边距会让右侧按钮贴边溢出
        { Box(Modifier.padding(horizontal = contentHorizontalPadding)) { a() } }
    }
    val end: (@Composable () -> Unit)? = endAction?.let { a ->
        { Box(Modifier.padding(end = contentHorizontalPadding)) { a() } }
    }
    material.SheetHost(
        onDismissRequest, show, backdrop, modifier, enableWindowDim, title, start, end, onDismissFinished,
        // 所有 sheet 统一走这里：沉浸内容层（上下边缘渐隐、头部 / 导航栏留白）
        content = rememberOverlayContent(
            content,
            immersive = true,
            sheetContentPadding = contentHorizontalPadding,
            padInsets = !scrollContent,
            sheetBackdrop = backdrop,
            sheetScrim = if (enableWindowDim) GlassBottomSheetDefaults.ScrimColor else Color.Transparent,
        ),
    )
}

/** 弹出菜单 */
@Composable
fun BoxScope.AppGlassPopup(
    show: Boolean,
    onDismissRequest: () -> Unit,
    anchorBounds: Rect,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    /** true：弹窗左边缘对齐锚点左边缘（向右展开）；默认 false：右边缘对齐锚点右边缘（向左展开）。 */
    alignStart: Boolean = false,
    /** backdrop 是预模糊的壁纸层时传 true（miuix 材质据此跳过实时模糊；液态材质不受影响）。 */
    preBlurred: Boolean = false,
    /** 弹窗宽度上下限；默认 200–288dp。 */
    sizing: top.yukonga.miuix.kmp.glass.GlassPopupSizing = top.yukonga.miuix.kmp.glass.GlassPopupSizing(),
    contentPadding: PaddingValues = PaddingValues(vertical = GlassPopupDefaults.ContentPaddingVertical),
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = currentMaterial()
    val liquidBackdrop = LocalLiquidBackdrop.current
    val mode = LocalBlurMode.current
    val glassParams = LocalGlassParams.current
    with(m) {
        PopupHost(
            show, onDismissRequest, anchorBounds, backdrop, modifier, alignStart,
            content = {
                CompositionLocalProvider(LocalLiquidBackdrop provides liquidBackdrop, LocalBlurMode provides mode, LocalGlassParams provides glassParams) {
                    content()
                }
            },
            preBlurred = preBlurred,
            sizing = sizing,
            contentPadding = contentPadding,
        )
    }
}

/** 下拉选择行 */
@Composable
fun AppDropdownPreference(
    onSelectedIndexChange: (Int) -> Unit,
    items: List<String>,
    selectedIndex: Int,
    title: String,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showValue: Boolean = true,
    summary: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    insideMargin: PaddingValues = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.InsideMargin,
    visuals: GlassPopupVisuals = appPopupVisuals(),
    marqueeItems: Boolean = false,
) = currentMaterial().DropdownRow(
    onSelectedIndexChange, items, selectedIndex, title, backdrop, modifier, enabled, showValue,
    summary, startAction, insideMargin, visuals, marqueeItems,
)

/** 带标题的开关行 */
@Composable
fun AppSwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) = currentMaterial().SwitchRow(checked, onCheckedChange, title, modifier, summary, enabled)

/** 独立开关 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = currentMaterial().ToggleSwitch(checked, onCheckedChange, modifier, enabled)

/** 弹窗 / sheet 底部按钮（取代 GlassDialogButton）；放在页面卡片里时传 inPage = true。 */
@Composable
fun AppDialogButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    inPage: Boolean = false,
    role: GlassDialogButtonRole = GlassDialogButtonRole.Normal,
    cornerRadius: Dp = GlassDialogButtonDefaults.CornerRadius,
    minHeight: Dp = GlassDialogButtonDefaults.MinHeight,
    content: @Composable RowScope.() -> Unit,
) = currentMaterial().ActionButton(onClick, modifier, enabled, inPage, role, cornerRadius, minHeight, content)

/**
 * 文字按钮（取代 miuix TextButton）。[inPage] 为 true 表示按钮在页面内容里（位于液态玻璃采样层内部），
 * 液态材质下改用实色底，避免递归采样。
 */
@Composable
fun AppTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    inPage: Boolean = false,
    cornerRadius: Dp = ButtonDefaults.CornerRadius,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight,
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin,
    textStyle: TextStyle? = null,
) = currentMaterial().LabelButton(
    text, onClick, modifier, enabled, primary, inPage, cornerRadius, minWidth, minHeight, insideMargin, textStyle,
)

/** 圆形图标按钮（顶栏按钮用 40dp） */
@Composable
fun LiquidGlassButton(
    onClick: () -> Unit,
    backdrop: LiquidBackdrop?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = MiuixTheme.colorScheme.surfaceContainer,
    tintAlpha: Float = 0.35f,
    coloredTint: Boolean = false,
    enabled: Boolean = true,
    surfaceVisible: Boolean = true,
    content: @Composable () -> Unit,
) = currentMaterial().RoundButton(
    onClick, backdrop, modifier, size, tint, tintAlpha, coloredTint, enabled, surfaceVisible, content,
)

/** 小型圆形图标按钮（默认 28dp），背景随「高级材质」变化；页面列表里的小按钮传 inPage = true。 */
@Composable
fun AppSmallButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: LiquidBackdrop? = null,
    size: Dp = 28.dp,
    enabled: Boolean = true,
    inPage: Boolean = true,
    content: @Composable () -> Unit,
) = currentMaterial().SmallButton(onClick, backdrop, modifier, size, enabled, inPage, content)

/** FAB：56dp 圆形 */
@Composable
fun LiquidGlassFab(
    onClick: () -> Unit,
    backdrop: LiquidBackdrop?,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    content: @Composable () -> Unit,
) = currentMaterial().FloatingButton(onClick, backdrop, modifier, primary, content)

/** 顶栏返回键 */
@Composable
fun LiquidGlassBackButton(backdrop: LiquidBackdrop?, surfaceVisible: Boolean, onClick: () -> Unit) =
    currentMaterial().NavBackButton(backdrop, surfaceVisible, onClick)

/** sheet / 对话框底部左右并排的胶囊按钮 */
@Composable
fun LiquidGlassPillButton(
    text: String,
    onClick: () -> Unit,
    backdrop: LiquidBackdrop?,
    modifier: Modifier = Modifier,
    role: LiquidRole = LiquidRole.Normal,
    enabled: Boolean = true,
) = currentMaterial().PillActionButton(text, onClick, backdrop, modifier, role, enabled)
