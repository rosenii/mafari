package dev.dsh.miuixbrowser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dsh.miuixbrowser.data.BAR_POS_FIXED_BOTTOM
import dev.dsh.miuixbrowser.data.BAR_POS_FIXED_TOP
import dev.dsh.miuixbrowser.data.BAR_POS_SAFARI_BOTTOM
import dev.dsh.miuixbrowser.data.BAR_POS_TOP
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/*
 * 导航栏样式抽象层
 * ----------------
 * 「栏在哪条边」（[BarEdge]）和「栏长什么样、怎么占位」（[BarStyle]）是两件独立的事：
 *
 *   设置里的「导航栏样式」(BAR_POS_*)  --[BarStyles.resolve]-->  [BarLayout] = 边 + 样式
 *
 * 样式只回答这几个问题，其余代码（BrowserScreen / BrowserFloatingBar / BrowserBottomBar / 首页）
 * 一律向样式查询，不再自己判断「是不是固定模式」：
 *   - [BarStyle.docked]       栏是叠在网页上面（悬浮），还是停靠在屏幕边缘、网页从栏的另一侧展开
 *   - [BarStyle.metrics]      栏本体的尺寸：高度、圆角、按钮大小、内边距、地址栏大小
 *   - [BarStyle.horizontalMargin] / [BarStyle.edgeGap]   与屏幕边缘 / 系统栏的间距（悬浮才有）
 *   - [BarStyle.homePadding]  首页内容为这个栏让出的上下空间
 *   - [BarStyle.surface]      底板怎么画（胶囊玻璃 / 平铺到边缘的玻璃条 / 以后的新样式）
 *
 * 新增一种底栏样式：写一个 [BarStyle] 对象，再在 [BarStyles.resolve] 里把某个位置选项映射过去即可。
 */

/** 导航栏所在的屏幕边 */
internal enum class BarEdge { Top, Bottom }

/**
 * 栏本体的尺寸（不含系统栏）。[BottomBar] 里所有与大小有关的数字都从这里取。
 */
@Immutable
internal data class BarMetrics(
    /** 工具栏本体高度 */
    val height: Dp,
    /** 底板圆角；0 = 直角（平铺样式） */
    val corner: Dp,
    /** 后退 / 前进 / 标签页 / 更多 等按钮的点击区边长 */
    val buttonSize: Dp,
    /** 按钮行左右内边距 */
    val contentPadding: Dp,
    /** 地址栏高度 */
    val addressHeight: Dp,
    /** 地址栏圆角 */
    val addressCorner: Dp,
) {
    /** 不随地址文字变化的那部分宽度：4 个按钮 + 两侧内边距 + 地址栏自身内边距（供悬浮样式计算胶囊宽度） */
    val fixedWidth: Dp get() = buttonSize * 4 + contentPadding * 2 + 24.dp
}

/** 首页（新建标签页）内容的上 / 下内边距 */
@Immutable
internal data class BarHomePadding(val top: Dp, val bottom: Dp)

internal interface BarStyle {
    /** 稳定的样式标识（以后若要存进设置，用它，不要用类名） */
    val id: String

    /**
     * true  = 停靠：栏平铺到屏幕边缘（底板一直铺到状态栏 / 导航栏下面），网页让出栏的高度，从栏的另一侧展开；
     * false = 悬浮：栏叠在网页上面，不占网页区域。
     */
    val docked: Boolean

    val metrics: BarMetrics

    /**
     * true = 三段式（Safari）：后退 / 地址栏 / 更多各自一块玻璃，由 [SafariSplitBar] 画内容，
     * 不用 [BottomBar]（没有前进 / 主页 / 标签数按钮）。
     */
    val splitPills: Boolean get() = false

    /** 栏与屏幕左右边缘的间距（停靠样式为 0） */
    val horizontalMargin: Dp

    /** 栏与状态栏 / 导航栏之间的间距（停靠样式为 0：底板自己铺到系统栏下面） */
    fun edgeGap(edge: BarEdge): Dp

    /** 首页内容为这个栏让出的空间。停靠样式下网页区域本身已经避开了栏，所以只需要很小的呼吸空间。 */
    fun homePadding(edge: BarEdge): BarHomePadding

    /** 栏的底板。只画底板，不放内容；调用方把它放在内容层下面并铺满栏的范围。 */
    @Composable
    fun surface(
        modifier: Modifier,
        edge: BarEdge,
        backdrop: LayerBackdrop?,
        blurEnabled: Boolean,
        textureBlurEnabled: Boolean,
        liquid: com.kyant.backdrop.Backdrop?,
    ): Modifier
}

/** 一个栏的最终布局：在哪条边 + 用哪种样式。 */
@Immutable
internal data class BarLayout(val edge: BarEdge, val style: BarStyle) {
    val onTop: Boolean get() = edge == BarEdge.Top
    val docked: Boolean get() = style.docked

    /** 栏占掉网页区域顶部一段（停靠在顶部） */
    val reservesTop: Boolean get() = style.docked && edge == BarEdge.Top

    /** 栏占掉网页区域底部一段（停靠在底部） */
    val reservesBottom: Boolean get() = style.docked && edge == BarEdge.Bottom
}

internal object BarStyles {
    /** 悬浮大胶囊：叠在网页上，随地址文字伸缩宽度，可滑动收起 / 缩成圆钮 / 换边 */
    val FloatingCapsule: BarStyle = FloatingCapsuleStyle

    /** 平铺停靠：贴着屏幕边缘的整宽栏，更矮，网页从栏的另一侧展开 */
    val DockedFlat: BarStyle = DockedFlatStyle

    /** Safari 三段式：左后退 / 中地址栏 / 右更多，三块分开的玻璃件，固定在底部（网页在栏上面结束，滚到底时深入栏下） */
    val SafariSplit: BarStyle = SafariSplitStyle

    /** 把设置里的「导航栏样式」(BAR_POS_*) 翻译成「边 + 样式」。位置与样式的对应关系只在这里决定。 */
    fun resolve(position: Int): BarLayout = when (position) {
        BAR_POS_TOP -> BarLayout(BarEdge.Top, FloatingCapsule)
        BAR_POS_FIXED_TOP -> BarLayout(BarEdge.Top, DockedFlat)
        BAR_POS_FIXED_BOTTOM -> BarLayout(BarEdge.Bottom, DockedFlat)
        BAR_POS_SAFARI_BOTTOM -> BarLayout(BarEdge.Bottom, SafariSplit)
        // BAR_POS_BOTTOM / BAR_POS_MINIMIZED（始终最小化的圆钮在底部）
        else -> BarLayout(BarEdge.Bottom, FloatingCapsule)
    }
}

private object FloatingCapsuleStyle : BarStyle {
    override val id = "floating_capsule"
    override val docked = false
    override val metrics = BarMetrics(
        height = 58.dp,
        corner = 29.dp,
        buttonSize = 44.dp,
        contentPadding = 6.dp,
        addressHeight = 40.dp,
        addressCorner = 20.dp,
    )
    override val horizontalMargin = 8.dp

    // 靠上：状态栏下方留 12dp；靠下：导航栏上方留 8dp
    override fun edgeGap(edge: BarEdge): Dp = if (edge == BarEdge.Top) 12.dp else 8.dp

    // 靠上：栏浮在状态栏下 ≈ 70dp，顶部让位；靠下：底部让出栏的位置
    override fun homePadding(edge: BarEdge): BarHomePadding =
        if (edge == BarEdge.Top) BarHomePadding(top = 114.dp, bottom = 40.dp)
        else BarHomePadding(top = 44.dp, bottom = 150.dp)

    @Composable
    override fun surface(
        modifier: Modifier,
        edge: BarEdge,
        backdrop: LayerBackdrop?,
        blurEnabled: Boolean,
        textureBlurEnabled: Boolean,
        liquid: com.kyant.backdrop.Backdrop?,
    ): Modifier = modifier.glassBar(
        backdrop = backdrop,
        corner = metrics.corner,
        blurEnabled = blurEnabled,
        textureBlurEnabled = textureBlurEnabled,
        liquid = liquid,
    )
}

private object DockedFlatStyle : BarStyle {
    override val id = "docked_flat"
    override val docked = true
    override val metrics = BarMetrics(
        height = 50.dp,
        corner = 0.dp,
        buttonSize = 42.dp,
        contentPadding = 4.dp,
        addressHeight = 36.dp,
        addressCorner = 18.dp,
    )
    override val horizontalMargin = 0.dp
    override fun edgeGap(edge: BarEdge): Dp = 0.dp

    // 网页区域已经避开了栏，首页只需要普通的呼吸空间
    override fun homePadding(edge: BarEdge): BarHomePadding =
        if (edge == BarEdge.Top) BarHomePadding(top = 20.dp, bottom = 40.dp)
        else BarHomePadding(top = 44.dp, bottom = 24.dp)

    @Composable
    override fun surface(
        modifier: Modifier,
        edge: BarEdge,
        backdrop: LayerBackdrop?,
        blurEnabled: Boolean,
        textureBlurEnabled: Boolean,
        liquid: com.kyant.backdrop.Backdrop?,
    ): Modifier = modifier.flatBarSurface(
        edge = edge,
        backdrop = backdrop,
        blurEnabled = blurEnabled,
        textureBlurEnabled = textureBlurEnabled,
        liquid = liquid,
    )
}

/**
 * Safari 三段式：停靠在底部（网页让出栏的高度）。底板用高级材质（[flatBarSurface]），后退 / 地址栏 / 更多三块玻璃盖在上面；
 * 栏的左右留白、块与块的间距都由 [SafariSplitBar] 处理。向下滑动页面时底板淡出，三段式缩成只剩标题的小胶囊，网页铺满屏幕。
 */
private object SafariSplitStyle : BarStyle {
    override val id = "safari_split"
    override val docked = true
    override val splitPills = true
    override val metrics = BarMetrics(
        // 玻璃件 44dp（原 52dp 同比例 ×0.85），上下各留 5dp
        height = 54.dp,
        corner = 22.dp,
        // 后退 / 更多 圆钮直径
        buttonSize = 44.dp,
        // 栏左右与屏幕边缘的间距（调大后两侧圆钮离边缘更远，中间地址栏相应变短）
        contentPadding = 18.dp,
        addressHeight = 44.dp,
        addressCorner = 22.dp,
    )
    override val horizontalMargin = 0.dp
    override fun edgeGap(edge: BarEdge): Dp = 0.dp

    // 首页内容一直铺到屏幕底，栏悬浮在它上面：底部要让出 栏高度 + 呼吸空间（导航栏那一段由首页层另外加上）
    override fun homePadding(edge: BarEdge): BarHomePadding = BarHomePadding(top = 44.dp, bottom = 74.dp)

    @Composable
    override fun surface(
        modifier: Modifier,
        edge: BarEdge,
        backdrop: LayerBackdrop?,
        blurEnabled: Boolean,
        textureBlurEnabled: Boolean,
        liquid: com.kyant.backdrop.Backdrop?,
    ): Modifier = modifier // 不画整条底板：只有后退 / 地址栏 / 更多三块玻璃件各自悬浮在网页上
}
