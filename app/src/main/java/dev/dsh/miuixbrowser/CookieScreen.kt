package dev.dsh.miuixbrowser

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val COOKIE_MODE_NAMES = listOf("允许全部", "拒绝跟踪者", "拒绝第三方", "拒绝全部")

private enum class CookieClear(val title: String, val ask: String) {
    SITE("清除当前网站 Cookie", "将清除当前网站的 Cookie，该站点需要重新登录"),
    COOKIES("清除全部 Cookie", "将清除所有网站的 Cookie，所有登录状态会失效"),
    SESSION("清除会话 Cookie", "将清除所有没有过期时间的 Cookie（关闭浏览器本该失效的那些）"),
    STORAGE("清除网站存储", "将清除 localStorage / IndexedDB 等网站本地数据，不含 Cookie"),
    EVERYTHING("清除 Cookie 与网站存储", "将清除所有网站的 Cookie 和本地数据，所有登录状态会失效"),
}

/**
 * 设置 → 隐私与安全 → Cookie 管理：操作都走 WebView 官方 API（见 [CookieControl]）。
 */
@Composable
fun CookieScreen(settings: AppSettings, currentUrl: String, onBack: () -> Unit, blurEnabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val backdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)
    val bd = if (glass) backdrop else null

    val site = CookieControl.siteOf(currentUrl)
    var siteCount by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentUrl, refresh) {
        siteCount = if (site != null) CookieControl.siteCookieCount(currentUrl) else null
    }
    var pending by remember { mutableStateOf<CookieClear?>(null) }

    fun run(action: CookieClear) {
        scope.launch {
            val ok = when (action) {
                CookieClear.SITE -> CookieControl.clearSite(currentUrl)
                CookieClear.COOKIES -> CookieControl.clearCookies()
                CookieClear.SESSION -> CookieControl.clearSessionCookies()
                CookieClear.STORAGE -> CookieControl.clearSiteStorage()
                CookieClear.EVERYTHING -> CookieControl.clearEverything()
            }
            refresh++
            Toast.makeText(context, if (ok) "已清除" else "没有可清除的内容或操作失败", Toast.LENGTH_SHORT).show()
        }
    }

    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                PageGlassTopBar(
                    title = "Cookie 管理",
                    backdrop = backdrop,
                    glass = glass,
                    scrollBehavior = scrollBehavior,
                    onBack = onBack,
                    backButton = {
                        LiquidGlassBackButton(backdrop = liquid, surfaceVisible = topScrolled, onClick = onBack)
                    },
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop)
                        .kyantLayerBackdrop(liquidBackdrop)
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState())
                        .padding(top = innerPadding.calculateTopPadding())
                        .padding(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                ) {
                    SmallTitle(text = "接受策略")
                    CookieCard {
                        AppDropdownPreference(
                            backdrop = bd,
                            title = "Cookie 接受策略",
                            summary = "官方 CookieManager；无跟踪者名单，「拒绝跟踪者」按拒绝第三方处理",
                            items = COOKIE_MODE_NAMES,
                            selectedIndex = settings.cookieMode,
                            onSelectedIndexChange = { settings.updateCookieMode(it) },
                        )
                    }

                    SmallTitle(text = "自动清理")
                    CookieCard {
                        AppDropdownPreference(
                            backdrop = bd,
                            title = "启动时清理",
                            summary = "每次冷启动时清理；无痕标签页本身不保存 Cookie",
                            items = listOf("不清理", "清除 Cookie", "清除 Cookie 与网站存储"),
                            selectedIndex = settings.cookieClearOnLaunch,
                            onSelectedIndexChange = { settings.updateCookieClearOnLaunch(it) },
                        )
                    }

                    SmallTitle(text = "清除数据")
                    CookieCard {
                        if (site != null) {
                            ArrowPreference(
                                title = CookieClear.SITE.title,
                                summary = site + (siteCount?.let { " · 当前 $it 个 Cookie" } ?: ""),
                                onClick = { pending = CookieClear.SITE },
                            )
                        }
                        ArrowPreference(
                            title = CookieClear.COOKIES.title,
                            summary = "所有网站，登录状态会失效",
                            onClick = { pending = CookieClear.COOKIES },
                        )
                        ArrowPreference(
                            title = CookieClear.SESSION.title,
                            summary = "只清没有过期时间的 Cookie，保留「记住我」的登录",
                            onClick = { pending = CookieClear.SESSION },
                        )
                        ArrowPreference(
                            title = CookieClear.STORAGE.title,
                            summary = "localStorage · IndexedDB 等，不含 Cookie",
                            onClick = { pending = CookieClear.STORAGE },
                        )
                        ArrowPreference(
                            title = CookieClear.EVERYTHING.title,
                            summary = "一次清掉上面两类数据",
                            onClick = { pending = CookieClear.EVERYTHING },
                        )
                    }
                }

                val ask = pending
                AppGlassDialog(
                    show = ask != null,
                    title = ask?.title ?: "",
                    summary = ask?.ask,
                    backdrop = bd,
                    onDismissRequest = { pending = null },
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppDialogButton(onClick = { pending = null }, modifier = Modifier.weight(1f)) {
                            Text(text = "取消", style = MiuixTheme.textStyles.button)
                        }
                        AppDialogButton(
                            onClick = {
                                pending = null
                                ask?.let { run(it) }
                            },
                            modifier = Modifier.weight(1f),
                            role = GlassDialogButtonRole.Destructive,
                        ) {
                            Text(text = "清除", style = MiuixTheme.textStyles.button)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CookieCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        insideMargin = PaddingValues(0.dp),
    ) { Column { content() } }
}
