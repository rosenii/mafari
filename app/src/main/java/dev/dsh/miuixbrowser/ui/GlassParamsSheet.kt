/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ui

import top.yukonga.miuix.kmp.layout.LocalBottomSheetInsets
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.data.GlassParams
import dev.dsh.miuixbrowser.data.GlassPresets
import dev.dsh.miuixbrowser.data.LiquidParams
import dev.dsh.miuixbrowser.data.LiquidPresets
import dev.dsh.miuixbrowser.data.ParamField
import dev.dsh.miuixbrowser.data.ParamPreset
import dev.dsh.miuixbrowser.data.ParamToggle
import dev.dsh.miuixbrowser.i18n.tr
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 设置里「自定义材质参数」一行的副标题：当前模板名，或「自定义」。 */
fun glassParamsSummary(settings: AppSettings): String =
    GlassPresets.ALL.getOrNull(settings.glassPreset)?.let { tr(it.name) } ?: tr("自定义")

fun liquidParamsSummary(settings: AppSettings): String =
    LiquidPresets.ALL.getOrNull(settings.liquidPreset)?.let { tr(it.name) } ?: tr("自定义")

/**
 * 柔光玻璃自定义材质参数：模板 + 全部可调参数滑条。
 * 滑条拖动实时生效（sheet 自己就是柔光玻璃，改参数时能直接看到变化），松手后才写入存储。
 */
@Composable
fun GlassParamsSheet(
    show: Boolean,
    settings: AppSettings,
    backdrop: Backdrop?,
    onDismissRequest: () -> Unit,
) {
    MaterialParamsSheet(
        show = show,
        backdrop = backdrop,
        onDismissRequest = onDismissRequest,
        presets = GlassPresets.ALL,
        fields = GlassParams.FIELDS,
        toggles = GlassParams.TOGGLES,
        presetIndex = { settings.glassPreset },
        params = { settings.glassParams },
        onApplyPreset = { settings.applyGlassPreset(it) },
        onUpdate = { settings.updateGlassParams(it) },
        onCommit = { settings.commitGlassParams() },
        resetLabel = "恢复默认（miuix 默认）",
        resetTo = GlassPresets.INDEX_UPSTREAM,
    )
}

/**
 * 液态玻璃自定义材质参数：与柔光玻璃共用同一个弹窗（[MaterialParamsSheet]），模板 / 滑条 / 开关换成液态玻璃的。
 * 弹窗自己也是液态玻璃（设置页里的 sheet 走 LiquidGlassMaterial），拖滑条能直接看到变化。
 */
@Composable
fun LiquidParamsSheet(
    show: Boolean,
    settings: AppSettings,
    backdrop: Backdrop?,
    onDismissRequest: () -> Unit,
) {
    MaterialParamsSheet(
        show = show,
        backdrop = backdrop,
        onDismissRequest = onDismissRequest,
        presets = LiquidPresets.ALL,
        fields = LiquidParams.FIELDS,
        toggles = LiquidParams.TOGGLES,
        presetIndex = { settings.liquidPreset },
        params = { settings.liquidParams },
        onApplyPreset = { settings.applyLiquidPreset(it) },
        onUpdate = { settings.updateLiquidParams(it) },
        onCommit = { settings.commitLiquidParams() },
        resetLabel = "恢复默认（液态玻璃默认）",
        resetTo = LiquidPresets.INDEX_DEFAULT,
    )
}

/**
 * 通用「自定义材质参数」弹窗。[P] 是参数类型；读取参数一律走 lambda（[presetIndex] / [params]），
 * 保证读状态发生在弹窗内容里，拖滑条时只重组需要的行。
 */
@Composable
private fun <P> MaterialParamsSheet(
    show: Boolean,
    backdrop: Backdrop?,
    onDismissRequest: () -> Unit,
    presets: List<ParamPreset<P>>,
    fields: List<ParamField<P>>,
    toggles: List<ParamToggle<P>>,
    presetIndex: () -> Int,
    params: () -> P,
    onApplyPreset: (Int) -> Unit,
    onUpdate: (P) -> Unit,
    onCommit: () -> Unit,
    resetLabel: String,
    resetTo: Int,
) {
    AppBottomSheet(
        show = show,
        title = tr("自定义材质参数"),
        backdrop = backdrop,
        onDismissRequest = onDismissRequest,
        scrollContent = true,
    ) {
        val insets = LocalBottomSheetInsets.current
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
        // 选中「自定义」（或本来就是自定义参数）时展开参数；选模板时保持用户上次的展开状态
        var customExpanded by remember { mutableStateOf(presetIndex() == -1) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight + insets.top + insets.bottom)
                .verticalScroll(rememberScrollState())
                // 先滚动后加内边距：参数列表能滚到标题和导航栏下面再渐隐
                .padding(top = insets.top, bottom = insets.bottom + 28.dp),
        ) {
            GroupTitle(tr("模板"))
            presets.forEachIndexed { index, preset ->
                PresetRow(
                    title = tr(preset.name),
                    summary = tr(preset.desc),
                    selected = presetIndex() == index,
                    onClick = { onApplyPreset(index) },
                )
            }
            PresetRow(
                title = tr("自定义"),
                summary = tr("手动调整模糊、折射、光影、色彩等全部参数"),
                selected = presetIndex() == -1,
                onClick = { customExpanded = !customExpanded },
                trailing = if (customExpanded) tr("收起") else tr("展开"),
            )

            AnimatedVisibility(
                visible = customExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    var lastToggleGroup = ""
                    toggles.forEach { toggle ->
                        if (toggle.group != lastToggleGroup) {
                            lastToggleGroup = toggle.group
                            GroupTitle(tr(toggle.group))
                        }
                        ToggleRow(toggle = toggle, params = params, onUpdate = onUpdate, onCommit = onCommit)
                    }

                    var lastGroup = ""
                    fields.forEach { field ->
                        if (field.group != lastGroup) {
                            lastGroup = field.group
                            GroupTitle(tr(field.group))
                        }
                        FieldSlider(field = field, params = params, onUpdate = onUpdate, onCommit = onCommit)
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        text = tr(resetLabel),
                        onClick = { onApplyPreset(resetTo) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = ContentInset),
                    )
                }
            }
        }
    }
}

/** 本 sheet 内所有行 / 标题 / 滑条统一的左右内边距，保证标题与正文左对齐。 */
private val ContentInset = 20.dp

/** 分组标题。不用 miuix 的 SmallTitle：它自带 28dp 的左边距，会比 20dp 的正文行更靠右。 */
@Composable
private fun GroupTitle(text: String) {
    SmallTitle(text = text, insideMargin = PaddingValues(horizontal = ContentInset, vertical = 8.dp))
}

@Composable
private fun PresetRow(title: String, summary: String, selected: Boolean, onClick: () -> Unit, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ContentInset, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // 与 miuix 标准行（BasicComponent / 翻译弹窗的下拉行）一致：标题 headline1，副标题 body2
            Text(
                text = title,
                style = MiuixTheme.textStyles.headline1,
                color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            )
            Text(text = summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (trailing != null) {
            Text(text = trailing, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (selected) {
            Icon(
                imageVector = MiuixIcons.Basic.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun <P> ToggleRow(
    toggle: ParamToggle<P>,
    params: () -> P,
    onUpdate: (P) -> Unit,
    onCommit: () -> Unit,
) {
    val checked = toggle.get(params())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onUpdate(toggle.set(params(), !checked))
                onCommit()
            }
            .padding(horizontal = ContentInset, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = tr(toggle.name), style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.onSurface)
            Text(
                text = tr(toggle.summary),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        AppSwitch(
            checked = checked,
            onCheckedChange = {
                onUpdate(toggle.set(params(), it))
                onCommit()
            },
        )
    }
}

@Composable
private fun <P> FieldSlider(
    field: ParamField<P>,
    params: () -> P,
    onUpdate: (P) -> Unit,
    onCommit: () -> Unit,
) {
    val value = field.get(params()).coerceIn(field.min, field.max)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ContentInset, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = tr(field.name), style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(text = field.show(value), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Slider(
            value = value,
            onValueChange = { raw ->
                val snapped = ((raw / field.step).roundToInt() * field.step).coerceIn(field.min, field.max)
                if (snapped != field.get(params())) {
                    onUpdate(field.set(params(), snapped))
                }
            },
            onValueChangeFinished = onCommit,
            valueRange = field.min..field.max,
        )
    }
}
