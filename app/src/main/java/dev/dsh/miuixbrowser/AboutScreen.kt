package dev.dsh.miuixbrowser

import android.content.Intent
import android.net.Uri
import android.webkit.WebSettings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDefaults
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassShadows
import top.yukonga.miuix.kmp.glass.GlassShape
import top.yukonga.miuix.kmp.glass.GlassStrokes
import top.yukonga.miuix.kmp.glass.glassPanel
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.kyant.backdrop.backdrops.layerBackdrop as kyantLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberKyantBackdrop

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
    blurEnabled: Boolean = true,
    updateChannel: Int = 0,
    onUpdateChannelChange: (Int) -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
) {
    val context = LocalContext.current
    // 优先交给已安装的 Telegram 客户端（不能用无包名的 ACTION_VIEW：本应用自己可能就是默认浏览器），都没有再用应用内标签打开
    val openTelegram: (String) -> Unit = { url ->
        val opened = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "org.telegram.plus")
            .any { pkg ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(pkg),
                    )
                }.isSuccess
            }
        if (!opened) onOpenUrl(url)
    }
    val scope = rememberCoroutineScope()
    val kernel = webViewVersion() ?: "未知"
    val geckoKernel = remember { geckoViewVersion() }
    val ua = remember { runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("未知") }
    val dependencyGroups = remember { parseDependencyVersions(BuildConfig.DEP_VERSIONS) }
    val cryptoGroups = remember { parseDependencyVersions(BuildConfig.CRYPTO_ALGORITHMS) }
    var checkingUpdate by remember { mutableStateOf(false) }

    val pageBackdrop = rememberLayerBackdrop()
    val liquidBackdrop = rememberKyantBackdrop()
    // 顶栏 / 返回键采样「背景 + 滚动内容」，才有渐进模糊；玻璃图标仍只采背景层（pageBackdrop），避免采到自己
    val contentBackdrop = rememberLayerBackdrop()
    val contentLiquidBackdrop = rememberKyantBackdrop()
    val glass = rememberGlassActive(blurEnabled)
    val liquid = if (glass) liquidBackdrop else null
    val scrollBehavior = MiuixScrollBehavior()
    val topScrolled = rememberTopBarScrolled(scrollBehavior)
    val dark = isAppDarkTheme()
    val surface = MiuixTheme.colorScheme.surface
    val iconShape = RoundedCornerShape(26.dp)
    var pendingUpdate by remember { mutableStateOf<AppRelease?>(null) }
    var showDonate by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLiquidBackdrop provides if (glass) liquidBackdrop else null) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageGlassTopBar(
                title = "关于",
                backdrop = contentBackdrop,
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
                backButton = {
                    LiquidGlassBackButton(backdrop = if (glass) contentLiquidBackdrop else null, surfaceVisible = topScrolled, onClick = onBack)
                },
            )
        },
    ) { innerPadding ->
        val topInset = innerPadding.calculateTopPadding()
        Box(modifier = Modifier.fillMaxSize().background(surface).layerBackdrop(contentBackdrop).kyantLayerBackdrop(contentLiquidBackdrop)) {
            // ---- 整页背景：铺满全屏的主题色渐变，供玻璃图标与顶栏采样（顶栏在录制层之外）----
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(pageBackdrop).kyantLayerBackdrop(liquidBackdrop)) {
                Box(modifier = Modifier.fillMaxSize().background(surface))
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to MiuixTheme.colorScheme.primary.copy(alpha = 0.38f),
                                0.45f to Color.Transparent,
                                1f to MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f),
                            ),
                        ),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
                    .padding(top = topInset)
                    // 沉浸：内容可滚到手势小白条下方，仅在末尾留出小白条高度
                    .padding(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                // ---- 头部：图标下移避开顶栏标题 + 版本号下检查更新 ----
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .then(
                                if (glass) {
                                    Modifier.glassPanel(
                                        backdrop = pageBackdrop,
                                        shape = GlassShape(26.dp),
                                        style = GlassDefaults.Style,
                                        stroke = GlassStrokes.forTheme(dark, GlassStrokes.MiddleLight, GlassStrokes.MiddleDark),
                                        shadow = GlassShadows.Regular,
                                        shading = true,
                                    )
                                } else {
                                    Modifier.background(MiuixTheme.colorScheme.surfaceContainerHigh, iconShape)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                            modifier = Modifier.size(58.dp),
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    Text(
                        text = "Mafari",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "版本 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )

                    Spacer(Modifier.height(16.dp))

                    AppDialogButton(
                        onClick = {
                            if (checkingUpdate) return@AppDialogButton
                            checkingUpdate = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { fetchLatestRelease(updateChannel) }
                                checkingUpdate = false
                                if (result == null) {
                                    Toast.makeText(context, "检查失败，请稍后再试", Toast.LENGTH_SHORT).show()
                                } else {
                                    val current = currentVersionLabel(updateChannel)
                                    val latest = result.version.removePrefix("v")
                                    if (!isUpdateAvailable(context, result, updateChannel)) {
                                        Toast.makeText(context, "已是最新版本 $current", Toast.LENGTH_SHORT).show()
                                    } else if (result.apkUrl.isNullOrBlank()) {
                                        // 无 APK 资产时在应用内浏览页打开发布页
                                        Toast.makeText(context, "发现新版本 $latest", Toast.LENGTH_LONG).show()
                                        onOpenUrl(result.pageUrl)
                                    } else {
                                        pendingUpdate = result
                                    }
                                }
                            }
                        },
                        role = GlassDialogButtonRole.Primary,
                        modifier = Modifier.width(200.dp),
                    ) {
                        Text(
                            text = if (checkingUpdate) "检查中…" else "检查更新",
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                }

                SmallTitle(text = "版本")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    AppDropdownPreference(
                        backdrop = if (glass) pageBackdrop else null,
                        title = "Mafari",
                        summary = BuildConfig.VERSION_NAME,
                        items = listOf("stable", "dev", "gecko"),
                        selectedIndex = updateChannel.coerceIn(0, 2),
                        onSelectedIndexChange = onUpdateChannelChange,
                    )
                    AboutRow("WebView 内核版本", kernel)
                    AboutRow("GeckoView 内核版本", geckoKernel ?: "未知")
                }

                SmallTitle(text = "加密算法")
                DependencyVersionsCard(cryptoGroups)

                SmallTitle(text = "依赖版本")
                DependencyVersionsCard(dependencyGroups)

                SmallTitle(text = "本机 UA")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Text(
                        text = ua,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                SmallTitle(text = "项目")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenUrl(PROJECT_URL) }
                            .padding(16.dp),
                    ) {
                        Text("项目地址", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "https://github.com/rosenii/mafari",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                SmallTitle(text = "交流与反馈")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    listOf(
                        Triple("Telegram 频道", TG_CHANNEL_URL, "更新公告与新版本推送"),
                        Triple("Telegram 交流群", TG_GROUP_URL, "使用交流与问题反馈"),
                    ).forEachIndexed { index, (title, url, hint) ->
                        if (index > 0) {
                            Spacer(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .height(0.5.dp)
                                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f)),
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openTelegram(url) }
                                .padding(16.dp),
                        ) {
                            Text(title, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "$hint · $url",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                SmallTitle(text = "捐赠")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDonate = true }
                            .padding(16.dp),
                    ) {
                        Text("捐赠", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = DONATE_SUMMARY,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }

                SmallTitle(text = "法律")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPrivacyPolicy() }
                            .padding(16.dp),
                    ) {
                        Text("隐私政策", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "本地存储的数据、网络请求与权限说明",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Mafari · 基于 miuix 与系统 WebView",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = COPYRIGHT,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // 应用内下载更新（不再跳浏览器）
            val update = pendingUpdate
            AppGlassDialog(
                show = update != null,
                title = "发现新版本",
                summary = update?.let { "${it.version} · 下载后可在下载页安装" },
                backdrop = if (glass) pageBackdrop else null,
                onDismissRequest = { pendingUpdate = null },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppDialogButton(
                        onClick = { pendingUpdate = null },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = "取消", style = MiuixTheme.textStyles.button)
                    }
                    AppDialogButton(
                        onClick = {
                            val rel = update ?: return@AppDialogButton
                            val apk = rel.apkUrl
                            if (apk != null) {
                                Toast.makeText(context, "正在选择下载线路…", Toast.LENGTH_SHORT).show()
                                Thread {
                                    val url = GitHubAccelerate.pickDownloadUrl(apk)
                                    android.os.Handler(context.mainLooper).post {
                                        Downloader.start(
                                            context = context,
                                            url = url,
                                            userAgent = null,
                                            fileName = "mafari-${rel.version.removePrefix("v")}.apk",
                                            mimeType = "application/vnd.android.package-archive",
                                        )
                                        Toast.makeText(context, "已开始下载，完成后可在下载页安装", Toast.LENGTH_LONG).show()
                                    }
                                }.start()
                            }
                            pendingUpdate = null
                        },
                        modifier = Modifier.weight(1f),
                        role = top.yukonga.miuix.kmp.glass.GlassDialogButtonRole.Primary,
                    ) {
                        Text(text = "下载", style = MiuixTheme.textStyles.button)
                    }
                }
            }

            // 捐赠：赞赏码 + 保存到相册
            DonateSheet(show = showDonate, backdrop = if (glass) pageBackdrop else null, onDismiss = { showDonate = false })
        }
    }
    }
}

data class AppRelease(
    val version: String,
    val pageUrl: String,
    val apkUrl: String?,
    val notes: String = "",
    /** GitHub 发布时间（毫秒）；未知为 0 */
    val publishedAt: Long = 0L,
)

private const val PROJECT_URL = "https://github.com/rosenii/mafari"
private const val TG_CHANNEL_URL = "https://t.me/mafari_a"
private const val TG_GROUP_URL = "https://t.me/Mafari_discuss"
private const val COPYRIGHT = "Copyright © 2026 rosenii. All rights reserved."
private const val RELEASES_API = "https://api.github.com/repos/rosenii/mafari/releases?per_page=100"

/** 最多翻几页：gecko 分支每次构建都发一个预发布，stable / dev 的 release 会被挤到很靠后的页里 */
private const val RELEASES_MAX_PAGES = 6

/** gecko 分支预发布的 tag：gecko-<CI 构建号> */
private val GECKO_TAG = Regex("^gecko-(\\d+)$")

/** gecko 预发布 tag 里的构建号；不是 gecko tag 返回 0 */
internal fun geckoBuildOf(tag: String): Int = GECKO_TAG.matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0

/** 预览类后缀；带这些后缀的 tag 归入 dev 分支（-dev 为主，其余预览后缀一并算 dev） */
private val PRE_TAG = Regex("(dev|alpha|beta|rc|snapshot)", RegexOption.IGNORE_CASE)

/** tag 是否属于指定分支：0=stable（-stable 或无预览后缀） 1=dev（带预览后缀） */
internal fun tagMatchesChannel(tag: String, channel: Int): Boolean {
    // gecko 分支的预发布 tag（gecko-<构建号>）只属于 gecko 渠道，不混进 stable / dev
    if (GECKO_TAG.matches(tag)) return channel == 2
    if (channel == 2) return false
    val isPre = PRE_TAG.containsMatchIn(tag)
    return if (channel == 1) isPre else !isPre
}

/**
 * 拉取指定分支（0=stable 1=dev 2=gecko 预发布）的最新 release：版本号、页面地址、APK 直链。
 * 按页（每页 100 条、最多 6 页）从新到旧找，只保留 tag 后缀匹配分支、非草稿的，再按版本号取最高者；
 * 找到匹配分支的 release 就不再往后翻（gecko 预发布刷屏时，stable 的 release 可能在第 2 页以后）。
 * 直连 + 公共 GitHub 加速镜像并发探测，连通者优先。
 */
internal fun fetchLatestRelease(channel: Int): AppRelease? {
    val fetchers: List<() -> AppRelease?> = GitHubAccelerate.MirrorPrefixes.map { prefix ->
        {
            val api = GitHubAccelerate.map(RELEASES_API, prefix)
            fetchReleaseFrom(api, channel)
        }
    }
    return GitHubAccelerate.raceFirst(15000, fetchers)
}

private fun fetchReleaseFrom(apiUrl: String, channel: Int): AppRelease? {
    var best: AppRelease? = null
    for (page in 1..RELEASES_MAX_PAGES) {
        val pageUrl = if (page == 1) apiUrl else apiUrl + (if (apiUrl.contains('?')) "&" else "?") + "page=$page"
        val array = fetchReleasePage(pageUrl)
        if (array == null) {
            // 第一页就失败 = 这条线路不通；后面的页失败就用已找到的
            return best
        }
        var matchedInPage = false
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val candidate = parseRelease(o, channel) ?: continue
            matchedInPage = true
            val current = best
            val newer = if (channel == 2) {
                current == null || geckoBuildOf(candidate.version) > geckoBuildOf(current.version)
            } else {
                current == null || isNewerVersion(candidate.version, current.version)
            }
            if (newer) best = candidate
        }
        // 已找到本分支的 release，或者已经是最后一页（不足一整页）
        if (matchedInPage || array.length() < 100) break
    }
    return best
}

private fun fetchReleasePage(url: String): org.json.JSONArray? = runCatching {
    val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 6000
        readTimeout = 6000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "mafari")
        setRequestProperty("Accept", "application/vnd.github+json")
    }
    try {
        if (connection.responseCode !in 200..299) return@runCatching null
        org.json.JSONArray(connection.inputStream.bufferedReader().readText())
    } finally {
        connection.disconnect()
    }
}.getOrNull()

/** 一条 release → AppRelease；草稿 / 不属于该分支的返回 null */
private fun parseRelease(o: org.json.JSONObject, channel: Int): AppRelease? {
    if (o.optBoolean("draft", false)) return null
    val tag = o.optString("tag_name", "")
    if (tag.isBlank() || !tagMatchesChannel(tag, channel)) return null
    // gecko 渠道：只看 GitHub 标为「预发布」的
    if (channel == 2 && !o.optBoolean("prerelease", false)) return null
    val pageUrl = o.optString("html_url", "").ifBlank { PROJECT_URL + "/releases" }
    var apkUrl: String? = null
    val assets = o.optJSONArray("assets")
    if (assets != null) {
        for (j in 0 until assets.length()) {
            val link = assets.optJSONObject(j)?.optString("browser_download_url", "").orEmpty()
            if (link.endsWith(".apk", ignoreCase = true)) {
                apkUrl = link
                break
            }
        }
    }
    return AppRelease(
        version = tag,
        pageUrl = pageUrl,
        apkUrl = apkUrl,
        notes = o.optString("body", "").trim(),
        publishedAt = runCatching { java.time.Instant.parse(o.optString("published_at", "")).toEpochMilli() }
            .getOrDefault(0L),
    )
}

@Composable
private fun AboutRow(title: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 依赖版本分组：BuildConfig.DEP_VERSIONS 里的分组标识 → 界面标题 */
private val DEP_GROUP_TITLES = mapOf(
    "ui" to "界面",
    "androidx" to "AndroidX",
    "storage" to "本地存储",
    "engine" to "内核与加密",
    "sync" to "云同步",
    "build" to "构建工具链",
    "kem" to "密钥封装（后量子混合）",
    "sym" to "对称加密",
    "kdf" to "密钥派生",
    "lib" to "实现库",
)

private class DependencyGroup(val title: String, val items: List<Pair<String, String>>)

/** 解析 "分组|名称|版本;分组|名称|版本…"（由 app/build.gradle.kts 生成），保持声明顺序 */
private fun parseDependencyVersions(raw: String): List<DependencyGroup> =
    raw.split(';')
        .mapNotNull { entry ->
            val p = entry.split('|')
            if (p.size == 3) Triple(p[0], p[1], p[2]) else null
        }
        .groupBy({ it.first }, { it.second to it.third })
        .map { (key, items) -> DependencyGroup(DEP_GROUP_TITLES[key] ?: key, items) }

/** 依赖版本：分组小标题 + 两列紧凑网格（名称在上、版本在下） */
@Composable
private fun DependencyVersionsCard(groups: List<DependencyGroup>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        groups.forEachIndexed { index, group ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            Text(
                text = group.title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(3.dp))
            group.items.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pair.forEach { (name, version) ->
                        DependencyCell(name, version, Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DependencyCell(name: String, version: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = name,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = version,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * GeckoView 版本：读库自带的 org.mozilla.geckoview.BuildConfig（MOZ_APP_VERSION + 构建号）。
 * 用反射读取，不会启动 Gecko；设备不支持（非 arm64）或读不到时返回 null。
 */
private fun geckoViewVersion(): String? = runCatching {
    val cls = Class.forName("org.mozilla.geckoview.BuildConfig")
    val ver = cls.getField("MOZ_APP_VERSION").get(null) as? String
    val build = runCatching { cls.getField("MOZ_APP_BUILDID").get(null) as? String }.getOrNull()
    if (ver.isNullOrBlank()) null else if (build.isNullOrBlank()) ver else "$ver ($build)"
}.getOrNull()
