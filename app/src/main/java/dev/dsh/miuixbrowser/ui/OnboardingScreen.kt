package dev.dsh.miuixbrowser.ui

import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.backup.BackupAuto
import dev.dsh.miuixbrowser.browser.DefaultBrowser
import dev.dsh.miuixbrowser.browser.MenuAction
import dev.dsh.miuixbrowser.browser.MenuOrderStore
import dev.dsh.miuixbrowser.data.AppSettings
import dev.dsh.miuixbrowser.data.HOME_DOWNLOADS
import dev.dsh.miuixbrowser.data.HOME_FAVORITES
import dev.dsh.miuixbrowser.data.HOME_GROUPS
import dev.dsh.miuixbrowser.data.HOME_MODE_CUSTOM
import dev.dsh.miuixbrowser.data.HOME_PRIVACY
import dev.dsh.miuixbrowser.data.HOME_QUICK
import dev.dsh.miuixbrowser.data.HOME_RECENT
import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GeckoProtection
import dev.dsh.miuixbrowser.util.looksLikeUrl
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDefaults
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassMaterials
import top.yukonga.miuix.kmp.glass.GlassShape
import top.yukonga.miuix.kmp.glass.GlassStrokes
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 首次安装 / 升级后的用户引导页（HyperOS 4 开机引导风格）。
 *
 * 视觉：整屏动态流动色彩背景（OnboardingBackground）+ 玻璃卡片（miuix-glass 采样背景）+ 大标题 + 胶囊主按钮，
 *       右上角永远有「跳过」。按钮 / 弹窗 / 下拉 / 开关全部走 AppMaterial 的 App* 门面，
 *       所以「高级材质」的三种（柔光玻璃 / 液态玻璃 / 纯色）在引导页里都生效。
 *
 * 步骤：壁纸 → 首页 → 菜单栏 → 备份与同步 → 口令 → 隐私保护 → 默认浏览器 → 隐私政策（须读到底才能完成）。
 *
 * 引导页是盖在 BrowserScreen 之上的一层，不是路由：当前步骤存在 BrowserScreen 里，
 * 从引导里点进「备份与同步 / Firefox 帐户同步」这类二级页面时，本层暂时撤掉，回来后还停在原来的步骤。
 * 完成与跳过都调用 [AppSettings.markOnboardingDone]（由调用方处理）。
 */

internal const val ONBOARDING_STEP_COUNT = 8

private const val STEP_WALLPAPER = 0
private const val STEP_HOME = 1
private const val STEP_MENU = 2
private const val STEP_BACKUP = 3
private const val STEP_PASSWORD = 4
private const val STEP_PRIVACY = 5
private const val STEP_DEFAULT = 6
private const val STEP_POLICY = 7

private val STEP_TITLES = listOf(
    "设置壁纸", "布置首页", "整理菜单栏", "备份与同步", "设置口令", "隐私保护", "默认浏览器", "隐私政策",
)

private val STEP_SUBTITLES = listOf(
    "给主页挑一张喜欢的图片，也可以先跳过，之后在 设置 → 外观 里更换",
    "选好起始页、导航栏位置和地址栏显示，再决定首页放哪些内容",
    "长按图标拖动即可排序，拖到分割线下方就隐藏；部分按钮长按还有更多功能",
    "数据在你自己手里：多种方式备份，随时换机恢复",
    "这是 Mafari 加密备份与同步使用的唯一口令",
    "一键开启，或按需逐项打开",
    "让其他应用里的网页链接直接用 Mafari 打开",
    "请阅读到底部，然后点击「完成」",
)

@Composable
internal fun OnboardingScreen(
    settings: AppSettings,
    menuOrder: MenuOrderStore,
    step: Int,
    onStepChange: (Int) -> Unit,
    onOpenBackup: () -> Unit,
    onOpenFxa: () -> Unit,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val glass = rememberGlassActive(settings.blurEnabled)
    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    // 背景层缓存成离屏纹理：背景每帧都在流动，几块玻璃卡片 / 按钮共用同一份纹理，不各自回放一遍着色器
    SideEffect { backdrop.graphicsLayer.compositingStrategy = LayerCompositingStrategy.Offscreen }
    val bd: Backdrop? = if (glass) backdrop else null

    // 返回键：回到上一步；第一步不退出（要离开请点右上角「跳过」）
    BackHandler { if (step > 0) onStepChange(step - 1) }

    // 壁纸选择：选好后自动进入下一步
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            settings.updateWallpaper(uri.toString())
            onStepChange(STEP_HOME)
        }
    }

    // 隐私政策：必须滚到底才能完成。内容不够长、根本滚不动时（如平板横屏）稍后放行，不把人卡死在这一步
    val policyScroll = rememberScrollState()
    var policyFallback by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (step == STEP_POLICY) {
            delay(1200)
            policyFallback = true
        }
    }
    val policyReached by remember {
        derivedStateOf {
            if (policyScroll.maxValue > 0) policyScroll.value >= policyScroll.maxValue - 24 else policyFallback
        }
    }

    CompositionLocalProvider(LocalLiquidBackdrop provides if (glass) liquidBackdrop else null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 整层盖在浏览页上：吞掉触摸，免得点穿到下面的页面
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            OnboardingBackground(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .kyantLayerBackdrop(liquidBackdrop),
            )

            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                // 顶部：步骤指示 + 始终可见的「跳过」
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StepDots(step = step)
                    Spacer(Modifier.weight(1f))
                    AppDialogButton(
                        onClick = onFinish,
                        modifier = Modifier.width(76.dp),
                        cornerRadius = 18.dp,
                        minHeight = 36.dp,
                    ) {
                        Text(text = "跳过", fontSize = 14.sp, style = MiuixTheme.textStyles.button)
                    }
                }

                AnimatedContent(
                    targetState = step,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    transitionSpec = {
                        val forward = targetState > initialState
                        (
                            slideInHorizontally(tween(320)) { if (forward) it / 3 else -it / 3 } +
                                fadeIn(tween(280))
                            ) togetherWith (
                            slideOutHorizontally(tween(320)) { if (forward) -it / 3 else it / 3 } +
                                fadeOut(tween(180))
                            )
                    },
                    label = "onboardingStep",
                ) { s ->
                    StepFrame(
                        title = STEP_TITLES[s],
                        subtitle = STEP_SUBTITLES[s],
                        scroll = if (s == STEP_POLICY) policyScroll else rememberScrollState(),
                    ) {
                        when (s) {
                            STEP_WALLPAPER -> WallpaperStep(settings, bd)
                            STEP_HOME -> HomeStep(settings, bd)
                            STEP_MENU -> MenuStep(settings, menuOrder, bd)
                            STEP_BACKUP -> BackupStep(bd, onOpenBackup, onOpenFxa)
                            STEP_PASSWORD -> PasswordStep(bd)
                            STEP_PRIVACY -> PrivacyStep(settings, bd)
                            STEP_DEFAULT -> DefaultBrowserStep(bd)
                            else -> Column { PolicySections() }
                        }
                    }
                }

                // 底部操作区
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp)
                        .padding(top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val last = step == ONBOARDING_STEP_COUNT - 1
                    if (step == STEP_WALLPAPER) {
                        val hasWallpaper = settings.wallpaper.isNotBlank()
                        BigButton(
                            text = if (hasWallpaper) "继续" else "选择壁纸",
                            primary = true,
                            onClick = {
                                if (hasWallpaper) onStepChange(STEP_HOME) else picker.launch(arrayOf("image/*"))
                            },
                        )
                        BigButton(
                            text = if (hasWallpaper) "重新选择" else "跳过此步",
                            primary = false,
                            onClick = {
                                if (hasWallpaper) picker.launch(arrayOf("image/*")) else onStepChange(STEP_HOME)
                            },
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BigButton(
                                text = "上一步",
                                primary = false,
                                onClick = { onStepChange(step - 1) },
                                modifier = Modifier.weight(1f),
                            )
                            BigButton(
                                text = when {
                                    !last -> "继续"
                                    policyReached -> "完成"
                                    else -> "请先读到底部"
                                },
                                primary = true,
                                enabled = !last || policyReached,
                                onClick = { if (last) onFinish() else onStepChange(step + 1) },
                                modifier = Modifier.weight(2f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------ 公共小组件 ------------------------------ */

/** 底部大按钮：胶囊形，走材质抽象（三种材质都生效）。 */
@Composable
private fun BigButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    AppDialogButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        role = if (primary) GlassDialogButtonRole.Primary else GlassDialogButtonRole.Normal,
        cornerRadius = 28.dp,
        minHeight = 54.dp,
    ) {
        Text(text = text, fontSize = 17.sp, style = MiuixTheme.textStyles.button)
    }
}

/** 顶部步骤指示：当前步是一条长胶囊，已走过的是主题色小点。 */
@Composable
private fun StepDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(ONBOARDING_STEP_COUNT) { i ->
            val width by animateDpAsState(if (i == step) 22.dp else 6.dp, tween(260), label = "dotWidth")
            val color = if (i <= step) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.18f)
            }
            Box(modifier = Modifier.height(6.dp).width(width).clip(CircleShape).background(color))
        }
    }
}

/** 每一步的外框：大标题 + 副标题固定在上，内容区可滚动。 */
@Composable
private fun StepFrame(
    title: String,
    subtitle: String,
    scroll: androidx.compose.foundation.ScrollState,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 28.dp).padding(top = 10.dp, bottom = 16.dp)) {
            Text(
                text = title,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/**
 * 玻璃卡片：有 [backdrop] 时对流动背景做玻璃采样；关闭模糊时退回不透明的卡片底色。
 * 卡片里的下拉 / 开关 / 按钮都用 App* 门面，随「高级材质」变化。
 */
@Composable
private fun GuideCard(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = isAppDarkTheme()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .then(
                if (backdrop != null) {
                    Modifier.glassPanel(
                        backdrop = backdrop,
                        shape = GlassShape(24.dp),
                        style = GlassDefaults.style(dark),
                        material = GlassMaterials.puredThinGlass(dark),
                        stroke = GlassStrokes.forTheme(dark),
                        shading = false,
                    )
                } else {
                    Modifier.clip(RoundedCornerShape(24.dp)).background(MiuixTheme.colorScheme.surfaceContainer)
                },
            ),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp),
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** 只读信息行：标题 + 说明，可带左侧图标 / 右侧控件。 */
@Composable
private fun InfoRow(
    title: String,
    summary: String,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.5.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    )
}

/** 卡片里的操作按钮（整行宽、圆角）。 */
@Composable
private fun CardButton(text: String, onClick: () -> Unit, primary: Boolean = true) {
    AppDialogButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp, top = 4.dp),
        role = if (primary) GlassDialogButtonRole.Primary else GlassDialogButtonRole.Normal,
        cornerRadius = 20.dp,
        minHeight = 46.dp,
    ) {
        Text(text = text, fontSize = 15.sp, style = MiuixTheme.textStyles.button)
    }
}

/* ------------------------------ 1. 壁纸 ------------------------------ */

@Composable
private fun ColumnScope.WallpaperStep(settings: AppSettings, bd: Backdrop?) {
    val bitmap = rememberWallpaper(settings.wallpaper, settings.wallpaperVersion)
    // 手机外形的预览：有壁纸时显示（模糊开关实时反映），没有时是占位
    Box(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .height(300.dp)
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(30.dp))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "壁纸预览",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (settings.wallpaperBlur) Modifier.blur(18.dp) else Modifier),
            )
        } else {
            Text(
                text = "尚未选择壁纸",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
    GuideCard(bd) {
        AppSwitchPreference(
            checked = settings.wallpaperBlur,
            onCheckedChange = { settings.updateWallpaperBlur(it) },
            title = "模糊壁纸",
            summary = "主页壁纸做高斯模糊，减少对内容的干扰",
        )
    }
}

/* ------------------------------ 2. 首页 ------------------------------ */

@Composable
private fun HomeStep(settings: AppSettings, bd: Backdrop?) {
    val context = LocalContext.current
    var showUrlDialog by remember { mutableStateOf(false) }

    SectionLabel("启动与布局")
    GuideCard(bd) {
        AppDropdownPreference(
            backdrop = bd,
            title = "自定义起始页",
            summary = "启动应用、点主页按钮、新建标签页时打开什么",
            items = listOf("默认主页", "新建标签页", "指定页面"),
            selectedIndex = settings.homeMode.coerceIn(0, 2),
            // 指定页面要先填网址，确认后才切换；其它两项立即生效
            onSelectedIndexChange = { if (it == HOME_MODE_CUSTOM) showUrlDialog = true else settings.updateHomeMode(it) },
        )
        if (settings.homeMode == HOME_MODE_CUSTOM) {
            InfoRow(
                title = "指定页面地址",
                summary = settings.homeCustomUrl.ifBlank { "未设置" },
                trailing = {
                    AppTextButton(text = "修改", onClick = { showUrlDialog = true }, minWidth = 56.dp, minHeight = 32.dp)
                },
            )
        }
        AppDropdownPreference(
            backdrop = bd,
            title = "导航栏位置",
            summary = "顶部 / 底部：随页面滚动自动隐藏；固定：始终显示",
            items = listOf("顶部", "底部", "始终最小化", "固定在底部", "固定在顶部"),
            selectedIndex = settings.barPosition.coerceIn(0, 4),
            onSelectedIndexChange = { settings.updateBarPosition(it) },
        )
        AppDropdownPreference(
            backdrop = bd,
            title = "地址栏显示",
            items = listOf("网站标题", "主域名", "完整网址"),
            selectedIndex = settings.addressDisplay.coerceIn(0, 2),
            onSelectedIndexChange = { settings.updateAddressDisplay(it) },
        )
        AppDropdownPreference(
            backdrop = bd,
            title = "标签栏样式",
            items = listOf("文字显示", "卡片显示"),
            selectedIndex = settings.tabsStyle.coerceIn(0, 1),
            onSelectedIndexChange = { settings.updateTabsStyle(it) },
        )
    }

    SectionLabel("首页内容")
    GuideCard(bd) {
        AppSwitchPreference(
            checked = settings.homeShowLogo,
            onCheckedChange = { settings.updateHomeShowLogo(it) },
            title = "LOGO 标识",
        )
        listOf(
            Triple(HOME_QUICK, "快捷菜单", "常用功能一键直达"),
            Triple(HOME_FAVORITES, "个人收藏", "把常逛的网站放在首页"),
            Triple(HOME_GROUPS, "书签分组", "按文件夹快速打开书签"),
            Triple(HOME_PRIVACY, "隐私报告", "已拦截的广告与跟踪统计"),
            Triple(HOME_RECENT, "最近浏览", "无痕标签页的首页不显示"),
            Triple(HOME_DOWNLOADS, "下载项", "最近的下载任务"),
        ).forEach { (kind, title, summary) ->
            AppSwitchPreference(
                checked = settings.isHomeShown(kind),
                onCheckedChange = { settings.updateHomeShow(kind, it) },
                title = title,
                summary = summary,
            )
        }
    }
    HintText("进入首页后，长按卡片标题可上下拖动排序，卡片右上角的 × 可以把它从首页移除。")

    // 指定页面：输入网址，确认后才切换到「指定页面」
    var urlText by remember(showUrlDialog) { mutableStateOf(settings.homeCustomUrl) }
    AppGlassDialog(
        show = showUrlDialog,
        title = "指定页面",
        summary = "请输入完整网址，例如 https://www.baidu.com",
        backdrop = bd,
        onDismissRequest = { showUrlDialog = false },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = urlText,
                onValueChange = { urlText = it },
                modifier = Modifier.fillMaxWidth(),
                label = "网址",
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppDialogButton(onClick = { showUrlDialog = false }, modifier = Modifier.weight(1f)) {
                    Text(text = "取消", style = MiuixTheme.textStyles.button)
                }
                AppDialogButton(
                    onClick = {
                        val text = urlText.trim()
                        if (text.isEmpty() || !looksLikeUrl(text)) {
                            Toast.makeText(context, "请输入有效的网址", Toast.LENGTH_SHORT).show()
                        } else {
                            settings.updateHomeCustomUrl(text)
                            settings.updateHomeMode(HOME_MODE_CUSTOM)
                            showUrlDialog = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                ) {
                    Text(text = "保存", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

/* ------------------------------ 3. 菜单栏 ------------------------------ */

/** 菜单里长按（图标不动 0.65 秒）有额外功能的按钮：与 BrowserScreen 里 longPressActions 保持一致。 */
private val MENU_LONG_PRESS = setOf(
    MenuAction.ScriptMenu, MenuAction.Favorite, MenuAction.Bookmarks, MenuAction.GeckoExtensions,
)

private fun longPressSummary(action: MenuAction): String = when (action) {
    MenuAction.ScriptMenu -> "长按进入「脚本管理」，单击是当前页面的脚本菜单"
    MenuAction.Favorite -> "长按进入「书签」库，单击是收藏当前页面"
    MenuAction.Bookmarks -> "长按弹出「新建书签」，自动填入当前页的标题和网址；单击是打开书签"
    MenuAction.GeckoExtensions -> "长按进入「拓展」管理页，单击是拓展快捷菜单"
    else -> ""
}

@Composable
private fun MenuStep(settings: AppSettings, menuOrder: MenuOrderStore, bd: Backdrop?) {
    val context = LocalContext.current
    SectionLabel("试一试")
    GuideCard(bd) {
        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
            // 直接用真正的菜单宫格：这里的拖动排序 / 隐藏会立刻保存，进入浏览器后「更多」面板就是这个样子
            MenuGrid(
                labelFor = { it.label },
                order = menuOrder.order,
                hidden = menuOrder.hidden,
                onAction = { Toast.makeText(context, "这里只是预览，进入浏览器后点开「更多」即可使用", Toast.LENGTH_SHORT).show() },
                onMove = { action, toHidden, toIndex -> menuOrder.place(action, toHidden, toIndex) },
                longPressActions = MENU_LONG_PRESS,
                onLongPressAction = { action ->
                    Toast.makeText(context, longPressSummary(action), Toast.LENGTH_LONG).show()
                },
                lockedFor = { it == MenuAction.GeckoExtensions && !settings.geckoMode },
            )
        }
    }
    HintText("长按任意图标并拖动：松手前拖到分割线下方就是隐藏，再拖回上方即恢复。「设置」不能隐藏，以免找不到入口。")

    SectionLabel("长按还能做什么")
    GuideCard(bd) {
        val items = MENU_LONG_PRESS.filter { it != MenuAction.GeckoExtensions || EngineSupport.GECKO_BUNDLED }
        items.forEachIndexed { index, action ->
            if (index > 0) RowDivider()
            InfoRow(title = action.label, summary = longPressSummary(action), icon = action.icon)
        }
    }
}

/* ------------------------------ 4. 备份与同步 ------------------------------ */

@Composable
private fun BackupStep(bd: Backdrop?, onOpenBackup: () -> Unit, onOpenFxa: () -> Unit) {
    SectionLabel("备份方式")
    GuideCard(bd) {
        InfoRow("本地加密备份文件", "导出 .mfbak 加密备份，换机或重装时选择文件即可恢复")
        RowDivider()
        InfoRow("WebDAV 云备份", "备份到你自己的 WebDAV 服务器，可保留多份历史")
        RowDivider()
        InfoRow("Git 仓库备份", "推送到你自己的 Git 仓库，支持访问令牌或 Deploy key")
        RowDivider()
        InfoRow("自动推送", "设好口令和云端后，可按天 / 每 3 天 / 每周自动备份")
        CardButton(text = "打开备份与同步页", onClick = onOpenBackup, primary = false)
    }

    // 带 Firefox 同步的版本（sync_lite / gecko）才显示帐户同步；Floccus 书签同步只有 gecko 版有
    if (EngineSupport.SYNC_BUNDLED) {
        SectionLabel("Firefox 帐户同步")
        GuideCard(bd) {
            InfoRow(
                title = "和电脑、其他手机上的 Firefox 互通",
                summary = "登录 Mozilla 帐户，同步书签、历史记录、标签页和密码；也能把当前网页发送到其他设备",
            )
            if (EngineSupport.GECKO_BUNDLED) {
                RowDivider()
                InfoRow(
                    title = "Floccus 书签同步",
                    summary = "在 设置 → 拓展 里安装 Floccus，可通过 Nextcloud / WebDAV / Git 在不同浏览器之间同步书签",
                )
            }
            CardButton(text = "登录 / 管理 Firefox 帐户", onClick = onOpenFxa)
        }
    } else {
        HintText("当前是 lite 版，不含 Firefox 帐户同步；需要时可以改装 sync_lite 或 gecko 版，数据不会丢。")
    }
}

/* ------------------------------ 5. 口令 ------------------------------ */

@Composable
private fun PasswordStep(bd: Backdrop?) {
    val context = LocalContext.current
    var pwSet by remember { mutableStateOf(BackupAuto.hasPassword(context)) }
    var showDialog by remember { mutableStateOf(false) }

    GuideCard(bd) {
        InfoRow(
            title = "唯一口令",
            summary = "备份文件、WebDAV / Git 云备份和自动推送都用它加密。口令加密保存在本机，不会上传。",
        )
        RowDivider()
        InfoRow(
            title = "请务必记牢",
            summary = "忘记口令后，已加密的备份无法找回。口令至少 8 位。",
        )
        RowDivider()
        InfoRow(
            title = if (pwSet) "已设置" else "尚未设置",
            summary = if (pwSet) "备份时不再询问口令，随时可以修改" else "现在设置，或之后在 备份与同步 里设置",
        )
        CardButton(text = if (pwSet) "修改口令" else "设置口令", onClick = { showDialog = true })
    }

    DefaultPasswordDialog(
        show = showDialog,
        isSet = pwSet,
        initial = "",
        backdrop = bd,
        onDismiss = { showDialog = false },
        onSave = { pw ->
            val r = runCatching { BackupAuto.setPassword(context, pw) }
            if (r.isSuccess) {
                pwSet = true
                showDialog = false
                Toast.makeText(context, "口令已保存", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "保存失败：" + (r.exceptionOrNull()?.message ?: "未知错误"), Toast.LENGTH_LONG).show()
            }
        },
        onClear = {
            BackupAuto.clearPassword(context)
            pwSet = false
            showDialog = false
        },
    )
}

/* ------------------------------ 6. 隐私保护 ------------------------------ */

private class PrivacyItem(
    val title: String,
    val summary: String,
    val checked: Boolean,
    val set: (Boolean) -> Unit,
)

@Composable
private fun PrivacyStep(settings: AppSettings, bd: Backdrop?) {
    val context = LocalContext.current
    val gecko = settings.geckoMode
    // Gecko 的保护设置存在自己的 SharedPreferences，先读进来
    LaunchedEffect(gecko) { if (gecko) GeckoProtection.load(context) }

    // 按当前内核给出不同的子开关：WebView（lite / sync_lite，以及 gecko 版选了 WebView 内核时）/ Gecko
    val items: List<PrivacyItem> = if (gecko) {
        val p = GeckoProtection
        listOf(
            PrivacyItem("增强型跟踪保护", "拦截广告、统计与社交跟踪（标准档）", p.etp != 1) { p.updateEtp(if (it) 2 else 1) },
            PrivacyItem("清理网址跟踪参数", "去掉 utm_*、fbclid、gclid 等跟踪参数", p.stripTracking) { p.updateStripTracking(it) },
            PrivacyItem("弹跳跟踪保护", "清除经由中转站点建立的跟踪数据", p.bounceTracking) { p.updateBounceTracking(it) },
            PrivacyItem("指纹保护", "拦截已知指纹脚本，并随机化少量设备信息", p.fingerprint) { p.updateFingerprint(it) },
            PrivacyItem("全局隐私控制（GPC）", "向网站声明不同意出售 / 共享个人数据", p.gpc) { p.updateGpc(it) },
            PrivacyItem("精简 Referer", "跨站请求只带来源域名", p.trimReferrer) { p.updateTrimReferrer(it) },
            PrivacyItem("防 WebRTC 泄露 IP", "不向网页暴露内网 / 多网卡地址，视频通话仍可用", p.webrtcLeak) { p.updateWebrtcLeak(it) },
            PrivacyItem("仅 HTTPS 模式", "自动把 http 升级为 https", p.httpsOnly) { p.updateHttpsOnly(it) },
            PrivacyItem("安全 DNS（DoH）", "加密域名解析，失败时回退系统 DNS", p.dohMode > 0) { p.updateDohMode(if (it) 1 else 0) },
        )
    } else {
        listOf(
            PrivacyItem("去广告", "拦截广告与第三方统计请求，隐藏页面广告元素", AdBlock.enabled) {
                if (it) settings.updatePageEnhancements(true)
                AdBlock.updateEnabled(it)
            },
            PrivacyItem("拒绝第三方 Cookie", "减少跨站跟踪；个别「用某某账号登录」可能受影响", settings.cookieMode >= 2) {
                if (it) {
                    if (settings.cookieMode < 2) settings.updateCookieMode(2)
                } else {
                    settings.updateCookieMode(0)
                }
            },
            PrivacyItem("发送「请勿追踪」", "向网站发送 Do Not Track 请求头", settings.sendDnt) { settings.updateSendDnt(it) },
            PrivacyItem("指纹保护", "Canvas / WebGL / 音频 / 字体，让网站难以靠设备特征识别你", AdBlock.fpMaster) {
                AdBlock.setFingerprintMaster(it)
            },
            PrivacyItem("禁用 WebRTC", "防止真实 IP 泄露；视频通话 / 在线会议将不可用", settings.webrtcDisabled) {
                settings.updateWebrtcDisabled(it)
            },
        )
    }
    val onCount = items.count { it.checked }
    val allOn = onCount == items.size
    val edition = when {
        EngineSupport.GECKO_BUNDLED -> "gecko"
        EngineSupport.SYNC_BUNDLED -> "sync_lite"
        else -> "lite"
    }

    GuideCard(bd) {
        AppSwitchPreference(
            checked = allOn,
            onCheckedChange = { on -> items.forEach { it.set(on) } },
            title = "一键开启隐私保护",
            summary = if (allOn) "已开启全部 ${items.size} 项" else "已开启 $onCount / ${items.size} 项",
        )
    }

    SectionLabel("包含的保护项 · $edition 版 · " + if (gecko) "Gecko 内核" else "系统 WebView 内核")
    GuideCard(bd) {
        items.forEachIndexed { index, item ->
            if (index > 0) RowDivider()
            AppSwitchPreference(
                checked = item.checked,
                onCheckedChange = item.set,
                title = item.title,
                summary = item.summary,
            )
        }
    }
    HintText(
        if (gecko) {
            "更多保护（严格模式、强力抗指纹、第一方隔离等）在 设置 → 隐私与安全 → Gecko 高级保护 里。"
        } else {
            "指纹保护和禁用 WebRTC 可能影响个别网页 / 视频通话，可随时在 设置 → 隐私与安全 里单独调整。"
        },
    )
}

/* ------------------------------ 7. 默认浏览器 ------------------------------ */

@Composable
private fun DefaultBrowserStep(bd: Backdrop?) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(DefaultBrowser.isDefault(context)) }
    var requestAt by remember { mutableStateOf(0L) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = DefaultBrowser.isDefault(context)
        // 用户多次拒绝后系统不再弹角色窗口，会立刻返回取消：直接带去系统「默认应用」设置页
        if (!isDefault && SystemClock.elapsedRealtime() - requestAt < 500) {
            runCatching { context.startActivity(DefaultBrowser.settingsIntent()) }
        }
    }
    // 从系统设置页回来时刷新状态
    DisposableEffect(context) {
        val owner = context as? androidx.lifecycle.LifecycleOwner
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) isDefault = DefaultBrowser.isDefault(context)
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    GuideCard(bd) {
        InfoRow(
            title = if (isDefault) "已是默认浏览器" else "还不是默认浏览器",
            summary = if (isDefault) {
                "其他应用里的网页链接会直接用 Mafari 打开"
            } else {
                "设置后，聊天、邮件等应用里点开的网页链接会直接用 Mafari 打开。不想设置也可以继续，之后在 设置 → 高级 里随时设置。"
            },
        )
        if (!isDefault) {
            CardButton(
                text = "设为默认浏览器",
                onClick = {
                    val ok = runCatching {
                        requestAt = SystemClock.elapsedRealtime()
                        launcher.launch(DefaultBrowser.requestIntent(context))
                    }.isSuccess
                    if (!ok) {
                        Toast.makeText(
                            context,
                            "无法打开系统设置，请在 系统设置 → 应用 → 默认应用 里选择 Mafari",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            )
        }
    }
}
