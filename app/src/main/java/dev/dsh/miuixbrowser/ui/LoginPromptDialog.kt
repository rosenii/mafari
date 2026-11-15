package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.R
import dev.dsh.miuixbrowser.browser.LoginPrompt
import dev.dsh.miuixbrowser.browser.PasswordCsv
import dev.dsh.miuixbrowser.browser.rememberFavicon
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Gecko 的登录弹层，两种形态都走应用自己的玻璃材质（跟随当前材质 / 模糊 / 深浅色）：
 * - 保存 / 更新密码：确认框（[AppGlassDialog]）。
 * - 选择要填的账号：锚点展开的下拉菜单（[AppGlassPopup]），不遮挡整个页面。
 *
 * [prompt] 为 null 时收起；收起动画期间仍显示最后一次的内容，避免标题 / 列表先消失。
 * [onClear] 把界面持有的 prompt 状态置空；每个出口（按钮、点空白处）都先 [onClear] 再调请求里的回调。
 */
@Composable
internal fun BoxScope.LoginPromptDialog(
    prompt: LoginPrompt?,
    backdrop: Backdrop?,
    onClear: () -> Unit,
) {
    val keep = remember { LastPrompt() }
    when (prompt) {
        is LoginPrompt.Save -> keep.save = prompt
        is LoginPrompt.Select -> keep.select = prompt
        null -> Unit
    }
    val save = keep.save
    val select = keep.select
    val dismiss: () -> Unit = {
        val d = prompt
        onClear()
        d?.onDismiss?.invoke()
    }

    AppGlassDialog(
        show = prompt is LoginPrompt.Save,
        title = save?.let { if (it.update) "更新密码？" else "保存密码？" },
        summary = save?.let { PasswordCsv.displayHost(it.origin) },
        backdrop = backdrop,
        onDismissRequest = dismiss,
    ) {
        if (save != null) SaveContent(save, onClear)
    }

    SelectPopup(
        show = prompt is LoginPrompt.Select,
        p = select,
        backdrop = backdrop,
        onClear = onClear,
        onDismissRequest = dismiss,
    )
}

/** 收起动画期间保留最后一次的内容 */
private class LastPrompt {
    var save: LoginPrompt.Save? = null
    var select: LoginPrompt.Select? = null
}

@Composable
private fun SaveContent(p: LoginPrompt.Save, onClear: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AccountCard(origin = p.origin, title = p.username.ifEmpty { "（无用户名）" }, subtitle = "密码  ••••••••")
        Text(
            text = if (p.update) "将替换已保存的旧密码，密码会加密保存在本机" else "密码会加密保存在本机",
            modifier = Modifier.padding(top = 10.dp, bottom = 14.dp, start = 2.dp),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppDialogButton(
                onClick = {
                    onClear()
                    p.onDismiss()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "不保存", style = MiuixTheme.textStyles.button)
            }
            AppDialogButton(
                onClick = {
                    onClear()
                    p.onSave()
                },
                modifier = Modifier.weight(1f),
                role = GlassDialogButtonRole.Primary,
            ) {
                Text(text = if (p.update) "更新" else "保存", style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/**
 * 账号下拉菜单。Gecko 的登录回调不带输入框的位置，所以锚点放在可视区域底部（键盘 / 底栏上方），
 * 向上展开、靠左对齐；账号多时菜单自己滚动。每行：用户名 + 站点域名。
 */
@Composable
private fun BoxScope.SelectPopup(
    show: Boolean,
    p: LoginPrompt.Select?,
    backdrop: Backdrop?,
    onClear: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val density = LocalDensity.current
    var host by remember { mutableStateOf(Rect.Zero) }
    val imeBottom = WindowInsets.ime.getBottom(density).toFloat()
    val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
    // 键盘开着：贴在键盘上沿；没开：让出底栏（约 84dp）+ 系统导航栏
    val reserve = maxOf(imeBottom, navBottom + with(density) { 84.dp.toPx() })
    val gap = with(density) { 8.dp.toPx() }
    val left = with(density) { 12.dp.toPx() }
    val bottom = (host.height - reserve - gap).coerceAtLeast(with(density) { 120.dp.toPx() })
    Box(modifier = Modifier.matchParentSize().onGloballyPositioned { host = it.boundsInRoot() }) {
        AppGlassPopup(
            show = show,
            onDismissRequest = onDismissRequest,
            // 向上展开：anchor.top 取页面之外的远值，GlassPopup 判定下方放不下而翻到上方，并以 anchor.bottom 为底边
            anchorBounds = Rect(left, 1e7f, left + 1f, bottom),
            backdrop = backdrop,
            alignStart = true,
            preBlurred = LocalWallpaperPreBlurred.current,
            // 宽度随内容自适应：下限放低到 112dp（默认 200dp 会让短用户名也撑出一大块空白），上限 280dp，再长的省略
            sizing = GlassPopupSizing(minWidth = 112.dp, maxWidth = 280.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            // 顶部固定文案：钥匙图标 + 加粗「密码」
            SelectHeader()
            p?.items?.forEachIndexed { index, item ->
                CompactAccountRow(
                    title = item.username.ifEmpty { "（无用户名）" },
                    summary = PasswordCsv.displayHost(item.origin),
                    onClick = {
                        onClear()
                        p.onPick(index)
                    },
                )
            }
        }
    }
}

/** 弹窗顶部固定文案：钥匙图标 + 「密码」。 */
@Composable
private fun SelectHeader() {
    val color = MiuixTheme.colorScheme.onSurfaceContainer
    Row(
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_key_plain),
            contentDescription = null,
            colorFilter = ColorFilter.tint(color),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "密码",
            modifier = Modifier.padding(start = 6.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * 紧凑的一行账号：用户名 + 站点。比 [GlassPopupItem] 的 44dp 最小高度 / 10dp 上下内边距更紧，
 * 行与行之间只留 5dp 上下内边距；按下底色与 GlassPopupItem 一致。
 */
@Composable
private fun CompactAccountRow(title: String, summary: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedColor = GlassPopupDefaults.itemPressedColor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GlassPopupDefaults.ItemPressInset)
            .clip(RoundedCornerShape(GlassPopupDefaults.ItemPressRadius))
            .background(if (pressed) pressedColor else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp - GlassPopupDefaults.ItemPressInset, vertical = 5.dp),
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = summary,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 一行账号：站点图标 + 用户名 + 副标题；底色用半透明前景色，玻璃 / 纯色材质、深浅色下都自然。 */
@Composable
private fun AccountCard(
    origin: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteAvatar(origin = origin, size = 40.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 站点图标；favicon 还没取到 / 取不到时显示域名首字母。 */
@Composable
private fun SiteAvatar(origin: String, size: Dp) {
    val icon = rememberFavicon(origin)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(size * 0.6f).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            val letter = PasswordCsv.displayHost(origin).firstOrNull { it.isLetterOrDigit() }
                ?.uppercaseChar()?.toString() ?: "#"
            Text(
                text = letter,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}
