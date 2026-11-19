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
import dev.dsh.miuixbrowser.data.GlassField
import dev.dsh.miuixbrowser.data.GlassPresets
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
        var customExpanded by remember { mutableStateOf(settings.glassPreset == -1) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight + insets.top + insets.bottom)
                .verticalScroll(rememberScrollState())
                // 先滚动后加内边距：参数列表能滚到标题和导航栏下面再渐隐
                .padding(top = insets.top, bottom = insets.bottom + 28.dp),
        ) {
            GroupTitle(tr("模板"))
            GlassPresets.ALL.forEachIndexed { index, preset ->
                PresetRow(
                    title = tr(preset.name),
                    summary = tr(preset.desc),
                    selected = settings.glassPreset == index,
                    onClick = { settings.applyGlassPreset(index) },
                )
            }
            PresetRow(
                title = tr("自定义"),
                summary = tr("手动调整模糊、折射、光影、色彩等全部参数"),
                selected = settings.glassPreset == -1,
                onClick = { customExpanded = !customExpanded },
                trailing = if (customExpanded) tr("收起") else tr("展开"),
            )

            AnimatedVisibility(
                visible = customExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    GroupTitle(tr("材质"))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                settings.updateGlassParams(settings.glassParams.copy(frostMaterial = !settings.glassParams.frostMaterial))
                                settings.commitGlassParams()
                            }
                            .padding(horizontal = ContentInset, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = tr("叠加磨砂白色材质"), style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.onSurface)
                            Text(
                                text = tr("开启后玻璃更雾、更不透，背景模糊跟「背景模糊」滑条走"),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        AppSwitch(
                            checked = settings.glassParams.frostMaterial,
                            onCheckedChange = {
                                settings.updateGlassParams(settings.glassParams.copy(frostMaterial = it))
                                settings.commitGlassParams()
                            },
                        )
                    }

                    var lastGroup = ""
                    GlassParamsFields.forEach { field ->
                        if (field.group != lastGroup) {
                            lastGroup = field.group
                            GroupTitle(tr(field.group))
                        }
                        FieldSlider(field = field, settings = settings)
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        text = tr("恢复默认（miuix 默认）"),
                        onClick = { settings.applyGlassPreset(0) },
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

private val GlassParamsFields: List<GlassField> get() = dev.dsh.miuixbrowser.data.GlassParams.FIELDS

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
private fun FieldSlider(field: GlassField, settings: AppSettings) {
    val value = field.get(settings.glassParams).coerceIn(field.min, field.max)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ContentInset, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = tr(field.name), style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(text = field.show(value), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Slider(
            value = value,
            onValueChange = { raw ->
                val snapped = ((raw / field.step).roundToInt() * field.step).coerceIn(field.min, field.max)
                if (snapped != field.get(settings.glassParams)) {
                    settings.updateGlassParams(field.set(settings.glassParams, snapped))
                }
            },
            onValueChangeFinished = { settings.commitGlassParams() },
            valueRange = field.min..field.max,
        )
    }
}
