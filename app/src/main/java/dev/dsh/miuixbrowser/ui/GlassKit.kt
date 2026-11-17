package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.glass.GlassNavigationBarDefaults
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.EdgeStroke
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 玻璃材质是否真正生效：用户开关打开，且设备支持 RuntimeShader（minSdk 33 基本恒为 true）。
 * 关闭时所有玻璃组件传 backdrop = null，由 miuix-glass 自己退回不透明底色。
 */
@Composable
fun rememberGlassActive(userEnabled: Boolean): Boolean = userEnabled && isRuntimeShaderSupported()


/** 顶栏里的玻璃返回键。必须放在 GlassTopAppBar 的 navigationIcon 里，才会继承顶栏材质。 */
@Composable
fun GlassBackButton(onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    GlassIconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
    ) {
        Icon(
            imageVector = MiuixIcons.Glass.ChevronBackward,
            contentDescription = tr("返回"),
            modifier = Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 玻璃图标按钮 + 点击震动 */
@Composable
fun HapticGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val dark = isAppDarkTheme()
    GlassIconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = modifier,
        style = GlassLook.barStyle(dark),
        material = if (GlassLook.shading()) GlassLook.barMaterial(dark) else null,
        content = content,
    )
}


/**
 * 子页面统一的 OS4 玻璃大标题顶栏（随滚动折叠）。
 *
 * - [backdrop] 必须是「只录制页面内容」的 LayerBackdrop，顶栏本身要放在这个录制层之外
 *   （Scaffold 的 topBar 槽天然满足），否则顶栏会采样到自己，收缩重采样时 RuntimeShader 会崩溃。
 * - [glass] 为 false 时传 null backdrop，退回不透明按钮底。
 */
@Composable
fun PageGlassTopBar(
    title: String,
    backdrop: Backdrop,
    glass: Boolean,
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
    /** 自定义返回键；为 null 时用默认的 miuix 玻璃返回键 */
    backButton: (@Composable () -> Unit)? = null,
) {
    val blurActive = glass && isRuntimeShaderSupported()
    val dark = isAppDarkTheme()
    // 渐进模糊交给上游 GlassTopAppBar 自带的实现（progressiveBlur = true）：
    // - 模糊带高度含 bottomContent（分段 / 搜索栏等），不会再让底部内容露在模糊带之外；
    // - 顶栏按钮采样「已渲染的渐进层」，与标题区观感一致；
    // - 随弹性滚动（visualContentOffset）淡入，标题折叠 / 展开带 titleBlurRadius 过渡模糊；
    // - 关闭模糊时自动退回主题色渐变遮罩（默认 bandBrush），不用再自己判断。
    // backdrop 必须是「只录制页面内容」的层，顶栏在录制层之外（Scaffold topBar 槽天然满足）。
    GlassTopAppBar(
        title = title,
        backdrop = if (glass) backdrop else null,
        progressiveBlur = blurActive,
        scrollBehavior = scrollBehavior,
        // 柔光玻璃：按钮的样式 / 材质跟「自定义材质参数」走
        style = GlassLook.barStyle(dark),
        material = GlassLook.barMaterial(dark),
        titleBlurRadius = GlassTopAppBarDefaults.TitleBlurRadius,
        navigationIcon = backButton ?: { GlassBackButton(onClick = onBack) },
        actions = actions,
        bottomContent = bottomContent,
    )
}



