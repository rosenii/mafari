package dev.dsh.miuixbrowser.ui


import dev.dsh.miuixbrowser.browser.BrowserTab
import dev.dsh.miuixbrowser.browser.LoginPrompt
import dev.dsh.miuixbrowser.browser.ScriptMenuCommand
import android.net.Uri

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect


/**
 * BrowserScreen 的共享界面状态（弹层 / 对话框 / 文件选择 / 脚本菜单等）。
 * 原先是 BrowserScreen 里的一长串局部 `var x by remember { mutableStateOf(..) }`；
 * 抽成一个类后，拆出去的各个 composable 可以直接以它为 receiver 读写，不必逐个传 getter / setter。
 * BrowserScreen 里用 `var x by ui::x` 继续按原名访问，既有代码不用改。
 */
internal class BrowserUiState {
    // 首次打开：询问是否设为系统默认浏览器。无论选了设置、取消还是点外面关掉都只问这一次；已经是默认就不问
    var showDefaultBrowserPrompt by mutableStateOf(false)
    var sheet by mutableStateOf(Sheet.None)
    var linkUrl by mutableStateOf("")
    var imageUrl by mutableStateOf("")
    var elementHit by mutableStateOf<BrowserTab.ElementHit?>(null)
    var address by mutableStateOf("")
    var editing by mutableStateOf(false)
    var barHidden by mutableStateOf(false)
    var favEditing by mutableStateOf(false)
    var groupEditing by mutableStateOf(false)
    var quickEditing by mutableStateOf(false)
    var showQuickPicker by mutableStateOf(false)
    var showGroupPicker by mutableStateOf(false)
    // 书签分组弹窗：显示开关与路径 / 锚点分开存，关闭动画期间内容不会突然消失
    var showGroupPopup by mutableStateOf(false)
    var groupPopupPath by mutableStateOf("")
    var groupPopupAnchor by mutableStateOf(Rect.Zero)
    var showFavEditor by mutableStateOf(false)
    var showBookmarkCreate by mutableStateOf(false)
    var bookmarkPrefillName by mutableStateOf("")
    var bookmarkPrefillUrl by mutableStateOf("")
    var pendingScript by mutableStateOf<String?>(null)
    var scriptSheetShow by mutableStateOf(false)
    var translateSheetShow by mutableStateOf(false)
    var geckoExtSheetShow by mutableStateOf(false)
    var webViewExtSheetShow by mutableStateOf(false)
    var requestSheetShow by mutableStateOf(false)
    var scriptMenuItems by mutableStateOf<List<ScriptMenuCommand>>(emptyList())
    var runningScripts by mutableStateOf<List<RunningScript>>(emptyList())
    // 菜单面板右上角蓝标：当前页面运行的脚本数
    var scriptRunCount by mutableIntStateOf(0)
    // 菜单「同步」进行中：图标变主题色、文字变「同步中」，防止重复点击
    // 地址栏左侧脚本图标：当前标签页面运行的脚本数（加载完成后查询，见下方 LaunchedEffect）
    var addressScriptCount by mutableIntStateOf(0)
    var pendingDownload by mutableStateOf<DownloadRequest?>(null)
    var showFind by mutableStateOf(false)
    var editingFavIndex by mutableStateOf(-1)
    // 离场动画期间保留最近一次打开的弹层内容
    var lastActiveSheet by mutableStateOf(Sheet.None)
    // 弹层从打开到离场动画结束的整段时间为 true：期间把（主页）整页冻结成一张纹理
    var sheetHostActive by mutableStateOf(false)
    var showClearTabsConfirm by mutableStateOf(false)
    var showShareDialog by mutableStateOf(false)
    // 标签列表当前栏：0 = 标签页，1 = 无痕浏览页
    var tabsPage by mutableIntStateOf(0)
    // 文件选择 + 视频全屏 + 网页权限询问
    var fileChooserChoice by mutableStateOf<FileChooserChoice?>(null)
    var customView by mutableStateOf<android.view.View?>(null)
    var cameraOutputUri by mutableStateOf<Uri?>(null)
    var webPermPrompt by mutableStateOf<WebPermPrompt?>(null)
    var loginPrompt by mutableStateOf<LoginPrompt?>(null)
    var androidPermDone by mutableStateOf<(() -> Unit)?>(null)
    // 启动自动检查更新：每个进程只查一次；有新版本且未被忽略时弹底部提示
    var updateRelease by mutableStateOf<AppRelease?>(null)
    var lastUpdateRelease by mutableStateOf<AppRelease?>(null)
}
