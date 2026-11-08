# 双内核（WebView / GeckoView）

设置 → 高级 → 浏览器内核，可在 **WebView（系统）** 与 **GeckoView（Nightly，arm64-v8a）** 间切换。

- 内核**按标签页**确定：切换后只影响「之后新建」的标签，已打开的标签保持原内核直到关闭。
- 标签 / 历史 / 书签 / 地址栏 / 下载等上层逻辑只有一套，写在 `BrowserTab` / `BrowserState` 里；
  两种内核只负责把各自的回调翻译成同一组 Compose 状态（url / title / progress / canGoBack …）。
- 非 arm64-v8a 设备、或 Gecko 启动失败时，自动退回 WebView。

## 代码结构

| 文件 | 作用 |
|---|---|
| `GeckoEngine.kt` | `GeckoRuntimeHolder`（进程内唯一 GeckoRuntime，惰性创建）、`GeckoTabEngine`（一个标签 = 一个 GeckoSession + GeckoView，翻译全部 Delegate） |
| `BrowserState.kt` | `BrowserTab` 持有 WebView **或** Gecko；`webView` 仅 WebView 标签可用，界面层改用 `contentView` / `refresh()` / `scrollOffsetY` / `evaluateJs()` 等与内核无关的接口 |
| `AppSettings.kt` | `browserEngine`（0 = WebView，1 = Gecko） |

## 版本

`app/build.gradle.kts` 里 `geckoViewVersion` 默认 `+`（Mozilla Maven 上最新 nightly）。
nightly 的 Java API 偶尔会改，**建议在 `gradle.properties` 锁定**：`geckoViewVersion=<版本>`。
可用版本：https://maven.mozilla.org/?prefix=maven2/org/mozilla/geckoview/geckoview-nightly-arm64-v8a/

## 功能适配矩阵

状态：✅ 第一步已实现　🔜 下一步　🧩 需 WebExtension（第三步「扩展」页）　⛔ Gecko 下禁用

### 浏览器基础（两内核同一套 UI）

| 功能 | Gecko 状态 | 说明 |
|---|---|---|
| 加载 / 刷新 / 停止 / 前进 / 后退 | ✅ | 回退到「从主页进入的第一页」再回主页，逻辑与 WebView 一致（用 HistoryDelegate 的下标） |
| 新建标签 / 切换 / 关闭 / 无痕 | ✅ | 无痕 = Gecko 私有会话；后台标签 `setActive(false)` |
| 标题 / 进度 / 地址同步 | ✅ | |
| 历史记录 | ✅ | 页面加载成功后写入同一个 `HistoryStore` |
| 书签 / 收藏 / 地址栏 / 搜索 | ✅ | 与内核无关 |
| 新窗口（target=_blank / window.open） | ✅ | 截获 URL 后开新标签，复用同站 / 点击 / 重定向权限规则；`window.opener` 关系丢失 |
| 长按链接 / 图片菜单 | ✅ | ContentDelegate.onContextMenu |
| 页内查找 | ✅ | SessionFinder |
| 桌面模式 / 自定义 UA | ✅ | GeckoSessionSettings（不再依赖 `mafari_desktop.js`） |
| 深浅色 | ✅ | 运行时 `preferredColorScheme` |
| 第三方 Cookie 开关 | ✅ | ContentBlocking.cookieBehavior（运行时级别） |
| 下载（http/https） | ✅* | *Downloader 重新请求链接，**不带 Gecko 的 Cookie**：需要登录态的下载会失败（见「下一步」） |
| blob: / data: 下载 | ✅ | blob 直接读取 Gecko 给的响应体，不再需要 `mafari_blob_cache.js` |
| 外部协议（weixin:// intent:// …） | ✅ | 有用户手势才放行；无手势的自动唤起被拦截 |
| 定位 / 摄像头 / 麦克风权限 | ✅ | 复用现有「询问 / 允许 / 拒绝」设置与弹窗 |
| 标签缩略图 | ✅ | `GeckoView.capturePixels()` |
| 下拉刷新 / 底栏随滚动收起 | ✅ | ScrollDelegate |
| 崩溃恢复 | ✅ | onCrash / onKill 后重开会话并回到原页面 |
| 视频全屏 | 🔜 | 目前只做系统栏隐藏 + 横屏，应用自己的顶栏 / 底栏还在 |
| 文件选择 / 拍照上传、alert / confirm / prompt、HTTP 认证 | 🔜 | PromptDelegate，**第二步** |
| 网站通知 | ⛔ | 现在直接拒绝（WebView 版本来也没有） |

### 依赖 WebView 专有 API 的功能

| 功能 | 在 WebView 里怎么做 | Gecko 下 |
|---|---|---|
| 油猴脚本（GM_*、菜单命令、脚本数量） | `addDocumentStartJavaScript` + `@JavascriptInterface` | ⛔ 先禁用（`syncUserScripts` 直接返回，菜单显示 0 个脚本）。🧩 之后用 WebExtension（Tampermonkey / Violentmonkey）代替 |
| 去广告（拦截请求 `shouldInterceptRequest` + 元素隐藏 JS） | 拦截 + 注入 | ⛔ 自带订阅 / 规则不生效。🧩 装 uBlock Origin；或先用 Gecko 内置 Enhanced Tracking Protection |
| 「标记为广告」（长按元素） | 页面 JS + `MafariElement` | ⛔ 不可用 |
| 跟踪脚本拦截 | `shouldInterceptRequest` | 🔜 改用 `ContentBlocking`（Gecko 自带分类列表） |
| 指纹保护（Canvas / WebGL / Audio / Fonts） | 按 origin 注册 document-start 脚本 | ✅ 已映射到 Gecko 的 Fingerprinting Protection（`GeckoRuntimeSettings.setFingerprintingProtection*`）：overrides 以 `-AllTargets` 清空默认集合，再按四个开关打开对应目标（Canvas→`CanvasRandomization`；WebGL→`WebGLRenderCapability`/`WebGLRenderInfo`；Audio→`AudioSampleRate`/`AudioContext`；Fonts→`FontVisibility*`）。站点白名单与检测报告仍只对 WebView 有效 |
| 禁用 WebRTC | 注入脚本 | ✅ pref `media.peerconnection.enabled=false`（关闭时清除用户值） |
| DNT / Sec-GPC | 请求头 + JS 属性 | ✅ pref `privacy.donottrackheader.enabled` + `GeckoRuntimeSettings.setGlobalPrivacyControl` |
| 剪贴板 / 媒体静音 / 通知拒绝（JS 层覆写） | 注入脚本 | ⛔ 不适用；Gecko 走权限系统 |
| 网页重定向权限（拦截无手势的跨站跳转） | `shouldOverrideUrlLoading` + `hitTestResult` | 🔜 `onLoadRequest`（`hasUserGesture` / `isRedirect`）可以近似实现；目前只对外部协议和新窗口生效 |
| 代理（HTTP / SOCKS5 / 认证 / Socks5Relay） | `ProxyController` | ✅ `network.proxy.*` pref；HTTP 认证走 `PromptDelegate.onAuthPrompt`（只接管来自代理的提示）；SOCKS5 认证与 WebView 共用 `Socks5Relay`，Gecko 连本机中转 |
| Passkey | WebView WebAuthn + Credential Manager | Gecko 自带 WebAuthn，是否弹系统 Passkey 界面需实测 |
| 缓存清理 | `WebView.clearCache` | ✅ 同时调用 `StorageController.clearData` |
| 添加到桌面（轻应用 `WebAppActivity`） | 独立 WebView 页面 | 轻应用本身仍是 WebView；Gecko 标签取不到页面图标，改按域名抓取 favicon |
| 标记 / 下载 `.user.js` | 拦截导航 | ✅ `onLoadRequest` / `onExternalResponse` 里同样拦截并交给「导入脚本」 |

## Gecko 专属 UI（拓展 / 置灰）

**判定依据是当前标签的内核**（`tab.isGecko`），不是「浏览器内核」设置：设置只决定新建标签用哪个内核，已打开的标签保持原内核。所以在设置里切到 Gecko 后，要在 Gecko 标签里才会看到下面的变化。

- **置灰**：`GeckoGate(disabled, hint) { … }` 把内容不透明度降到 38%，并盖一层吞掉点击的透明层，点击只弹 toast「该功能在 Gecko 下不支持」+ 替代方案。菜单格子由 `MenuAction.geckoUnsupportedHint` 决定（非 null = 置灰）；设置页行由 `SettingsScreen(geckoActive = …)` 包 `GeckoGate`。新增一个 Gecko 下不可用的功能时，只需要加这两处之一。
- **拓展入口**：`MenuAction.GeckoExtensions`（`geckoOnly`）只在 Gecko 标签的菜单里出现，打开菜单时淡入；WebView 标签下被过滤掉。拖动排序用 `MenuOrderStore.place(…, skipped)` 换算序号。
- **拓展页** `GeckoExtensionsScreen`：搜索走 AMO 公开 API（`/api/v5/addons/search/?app=android&type=extension`），安装时把 `current_version.file.url`（xpi 直链）交给 `WebExtensionController.install`，由 Gecko 下载并校验签名。导入：链接（AMO 页面会先解析成 xpi 直链）/ 本地文件（复制到缓存目录后以 `file://` 安装）。
- **权限确认**：`GeckoExtensionStore` 实现 `WebExtensionController.PromptDelegate`（安装、更新新增权限、可选权限），把请求交给拓展页弹窗；拓展页不在前台时一律拒绝，避免回调悬挂。
- **定期检查更新**：设置项存在 `mafari_gecko_ext`（`auto_update` / `last_check`）。检查时机是「有 Gecko 标签时的应用启动」和「打开拓展页」，不为了检查更新而拉起 Gecko；应用在后台时不检查（没有引入 WorkManager）。
- **拓展设置页 / `tabs.create`**：通过 `TabDelegate` 转成应用自己的新标签；`moz-extension://` 只有 Gecko 能加载，当前内核设置不是 Gecko 时会提示先切换。

### 隐私 / 代理（`GeckoPrivacy.kt`）

指纹保护、禁止跟踪、禁用 WebRTC、代理四项与 WebView **共用同一套开关和界面**，不置灰。`GeckoPrivacy.apply` 在 Gecko 运行时创建后立即执行一次（第一个页面加载前就位），之后由 BrowserScreen 在设置变化时调用 `applyIfRunning`（Gecko 没启动则跳过）。偏好写在用户分支，关闭时清除用户值回到 Gecko 默认，不写死。这些偏好由 `GeckoPreferenceController`（GeckoView 标注为 experimental）写入，nightly 升级后若签名变化改这一个文件即可。

## 下一步

1. **（第二步）PromptDelegate**：文件选择 / 拍照、JS 对话框、HTTP 认证、地址栏下拉自动补全以外的弹层。
2. 下载带 Cookie：Gecko 没有 Cookie 公开 API，计划二选一：直接消费 `onExternalResponse` 的 `body` 流并落盘；或经 WebExtension `cookies` API 取。
3. **（第三步，已完成大半）Gecko 专属「拓展」页**：安装 / 搜索 / 导入 / 更新 / 管理 / 定期检查已做（见上节）。剩 browserAction 弹窗（工具栏按钮）与 `onNewTab` 的真实 GeckoSession 对接；用户脚本与去广告暂由 Tampermonkey / uBlock Origin 拓展承接。
4. 视频全屏时让应用自己的顶栏 / 底栏一并隐藏。
