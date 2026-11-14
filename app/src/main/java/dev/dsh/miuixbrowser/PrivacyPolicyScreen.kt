package dev.dsh.miuixbrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 隐私政策的最后更新日期；政策内容变化时同步修改。 */
private const val POLICY_UPDATED = "2026-11-13"
private const val ISSUES_URL = "https://github.com/rosenii/mafari/issues"

private data class PolicySection(val title: String, val paragraphs: List<String>)

/** 政策正文：只描述应用的实际行为，新增网络请求 / 权限 / 数据类型时需同步更新。 */
private val POLICY_SECTIONS = listOf(
    PolicySection(
        "概述",
        listOf(
            "Mafari 是一款本地优先的浏览器。应用不要求注册账号，不包含广告、统计分析或崩溃上报组件，开发者也不运营用于收集你的浏览数据的服务器。",
            "本页说明应用会在设备上保存哪些数据、会向哪些第三方发出网络请求，以及你可以如何控制它们。",
        ),
    ),
    PolicySection(
        "保存在你设备上的数据",
        listOf(
            "浏览历史、书签与收藏、下载记录、用户脚本、应用设置，以及网站产生的 Cookie 与站点数据，都只保存在你的设备上。",
            "历史、书签、下载记录、脚本等数据库使用 SQLCipher 加密保存，密钥由 Android 系统密钥库（Android Keystore）参与保护。",
            "你导入的网站密码保存在同一个加密数据库里，密码字段另外再用应用的加密套件逐条加密；查看或复制密码前需要通过系统验证（生物识别或锁屏密码）。目前密码不会被上传、同步或写入备份。",
            "这些数据不会被开发者读取或上传；卸载应用会一并删除。",
        ),
    ),
    PolicySection(
        "网络请求与第三方",
        listOf(
            "你访问的网站：网页内容、搜索关键词由你访问的网站或所选搜索引擎处理，适用其各自的隐私政策。默认搜索引擎为百度，可在设置中更换。",
            "检查与下载更新：在你主动检查更新（或开启自动检查）时，应用会访问 GitHub 获取版本信息；下载安装包时可能经由 GitHub 加速镜像（如 ghfast.top、gh-proxy.com 等），这些镜像可看到你的 IP 地址与下载地址。",
            "站点图标：创建桌面快捷方式等场景可能向 Google 图标服务（google.com/s2/favicons）请求，会发送目标网站的域名。",
            "网页翻译：使用翻译功能时，当前页面地址会发送给 Google 翻译（translate.google.com）。",
            "扩展与脚本：在扩展商店搜索或安装扩展时会访问 addons.mozilla.org；用户脚本的安装、更新及其依赖按脚本自身声明的地址（如 Greasy Fork）访问。",
            "备份与同步：只有当你自行配置 WebDAV 或 Git 同步后，备份才会上传到你指定的服务器。备份文件在设备上先加密，开发者无法读取其内容。",
        ),
    ),
    PolicySection(
        "权限说明",
        listOf(
            "网络：加载网页、检查更新、同步备份。",
            "相机、麦克风、位置：仅在网页请求并经你授权时使用，可随时在设置或系统中撤销。",
            "通知与前台服务：显示下载进度与结果。",
            "安装应用包：仅用于安装你主动下载的更新安装包。",
            "生物识别：用于应用内的生物识别验证，指纹等生物特征数据由系统处理，应用不会获取。",
            "桌面快捷方式：用于将网页添加到桌面。",
        ),
    ),
    PolicySection(
        "隐私保护功能",
        listOf(
            "应用提供跟踪保护、去广告、指纹保护、Cookie 管理等功能，均在设备本地运行。屏蔽规则的拦截统计仅保存在本地。",
        ),
    ),
    PolicySection(
        "你的选择",
        listOf(
            "你可以随时清除历史、Cookie 与缓存，删除书签与脚本，关闭自动检查更新，或不配置任何同步。",
            "备份文件与同步服务器由你自行管理，请妥善保管备份口令。",
        ),
    ),
    PolicySection(
        "政策变更与联系",
        listOf(
            "当应用的数据处理方式发生变化时，本政策会随版本更新。最后更新：$POLICY_UPDATED。",
            "如有疑问或建议，请在项目的 Issues 页面反馈。",
        ),
    ),
)

@Composable
fun PrivacyPolicyScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
    blurEnabled: Boolean = true,
) {
    val glass = rememberGlassActive(blurEnabled)
    val contentBackdrop = rememberLayerBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val surface = MiuixTheme.colorScheme.surface

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageGlassTopBar(
                title = "隐私政策",
                backdrop = contentBackdrop,
                glass = glass,
                scrollBehavior = scrollBehavior,
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(surface)
                .layerBackdrop(contentBackdrop),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .edgeHaptic()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
                    .padding(top = innerPadding.calculateTopPadding())
                    .padding(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                Text(
                    text = "最后更新：$POLICY_UPDATED",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
                POLICY_SECTIONS.forEach { section ->
                    SmallTitle(text = section.title)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        section.paragraphs.forEachIndexed { index, text ->
                            if (index > 0) Spacer(Modifier.height(10.dp))
                            Text(
                                text = text,
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 12.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenUrl(ISSUES_URL) }
                            .padding(16.dp),
                    ) {
                        Text("问题反馈", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = ISSUES_URL,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
