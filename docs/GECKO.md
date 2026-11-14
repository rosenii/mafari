# 双内核（WebView / GeckoView）

设置 → 高级 → 浏览器内核，可在 **WebView（系统）** 与 **GeckoView（Nightly，arm64-v8a）** 间切换。

- 内核**按标签页**确定：切换后只影响「之后新建」的标签，已打开的标签保持原内核直到关闭。
- 标签 / 历史 / 书签 / 地址栏 / 下载等上层逻辑只有一套，写在 `BrowserTab` / `BrowserState` 里；
  两种内核只负责把各自的回调翻译成同一组 Compose 状态（url / title / progress / canGoBack …）。
- 非 arm64-v8a 设备、或 Gecko 启动失败时，自动退回 WebView。

## 构建变体

本文档所述功能只在 **gecko 变体**里存在；**lite（无内核版）** 不打包 GeckoView（仅 `compileOnly`），共用代码用 `EngineSupport.GECKO_BUNDLED` 把 Gecko 分支挡在外面。新增共用代码若要引用 `Gecko*` 类，务必先判断它，详见 README「本地构建」。

## 代码结构

| 文件 | 作用 |
|---|---|
| `GeckoEngine.kt` | `GeckoRuntimeHolder`（进程内唯一 GeckoRuntime，惰性创建）、`GeckoTabEngine`（一个标签 = 一个 GeckoSession + GeckoView，翻译全部 Delegate） |
| `BrowserState.kt` | `BrowserTab` 持有 WebView **或** Gecko；`webView` 仅 WebView 标签可用，界面层改用 `contentView` / `refresh()` / `scrollOffsetY` / `evaluateJs()` 等与内核无关的接口 |
| `AppSettings.kt` | `browserEngine`（0 = WebView，1 = Gecko） |
| `TabDb.kt` | `tabs` 表：标签列表 / 缩略图 / Gecko 会话状态（`state`），重启恢复与后台回收共用 |

## 惰性引擎与后台回收

Gecko 标签的引擎（`GeckoSession` + `GeckoView`）**按需创建、可回收**：

- 重启后恢复出来的标签以 `lazyEngine = true` 构造，不建引擎；成为当前标签时才 `ensureGecko()` 并加载（用库里的会话状态恢复前进后退栈 / 滚动位置，没有就按地址加载）。
- 后台标签闲置 20 分钟、或活着的引擎超过 6 个（内存吃紧时 2 个）时，`discardEngine()` 先把会话状态写库再销毁引擎，标签变回「待恢复」，卡片 / 标题 / 缩略图保留。
- 永不回收：当前标签、无痕标签、拓展 `tabs.create` 的 session、正在加载 / 全屏的标签。
- 完整流程、参数与触发点见 [ARCHITECTURE.md](ARCHITECTURE.md) §4。

## 版本

`app/build.gradle.kts` 里 `geckoViewVersion` 默认 `+`（Mozilla Maven 上最新 nightly）。
nightly 的 Java API 偶尔会改，**建议在 `gradle.properties` 锁定**：`geckoViewVersion=<版本>`。
可用版本：https://maven.mozilla.org/?prefix=maven2/org/mozilla/geckoview/geckoview-nightly-arm64-v8a/

## 功能适配矩阵

状态：✅ 已实现　⚠ 部分实现　🔜 下一步　🧩 需 WebExtension（第三步「扩展」页）　⛔ Gecko 下禁用

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
| 下载（http/https） | ✅ | ≤ 64 MB 且带 Content-Length 的直接消费 Gecko 响应体（带登录态，走与 blob 相同的确认 / 保存流程）；更大或未知长度仍交给 Downloader 重新请求（**不带 Cookie**） |
| blob: / data: 下载 | ✅ | blob 直接读取 Gecko 给的响应体，不再需要 `mafari_blob_cache.js` |
| 外部协议（weixin:// intent:// …） | ✅ | 有用户手势才放行；无手势的自动唤起被拦截 |
| 定位 / 摄像头 / 麦克风权限 | ✅ | 复用现有「询问 / 允许 / 拒绝」设置与弹窗 |
| 标签缩略图 | ✅ | `GeckoView.capturePixels()` |
| 下拉刷新 / 底栏随滚动收起 | ✅ | ScrollDelegate |
| 崩溃恢复 | ✅ | onCrash / onKill 后重开会话并回到原页面 |
| 视频全屏 | ✅ | 系统栏隐藏 + 横屏，同时收起应用自己的顶栏 / 底栏（`BrowserTab.geckoFullscreen`） |
| 文件选择 / 拍照上传、alert / confirm / prompt、`<select>` 下拉、HTTP / 代理认证、日期时间、弹窗拦截 | ✅ | `GeckoPrompts.kt`（PromptDelegate）：文件 / 拍照包装成 WebView 同款 `FileChooserParams` 复用现有选择器；其余用系统对话框 |
| 网站通知 | ⛔ | 现在直接拒绝（WebView 版本来也没有） |

### 依赖 WebView 专有 API 的功能

| 功能 | 在 WebView 里怎么做 | Gecko 下 |
|---|---|---|
| 油猴脚本（GM_*、菜单命令、脚本数量） | `addDocumentStartJavaScript` + `@JavascriptInterface` | ⚠ 内置 WebExtension（`GeckoGm` + `assets/mafari-gm-ext`），与 WebView 共用同一份运行时与 GM 值；待真机验证，详见 [GECKO_GM.md](GECKO_GM.md) |
| 去广告（拦截请求 `shouldInterceptRequest` + 元素隐藏 JS） | 拦截 + 注入 | ⛔ 自带订阅 / 规则不生效。🧩 装 uBlock Origin；或先用 Gecko 内置 Enhanced Tracking Protection |
| 「标记为广告」（长按元素） | 页面 JS + `MafariElement` | ⛔ 不可用 |
| 跟踪脚本拦截 | `shouldInterceptRequest` | ✅ `ContentBlocking.setAntiTracking`（Gecko 自带 ETP 分类列表），随「拦截跟踪脚本」开关 |
| 指纹保护（Canvas / WebGL / Audio / Fonts） | 按 origin 注册 document-start 脚本 | ⚠ 四项细分开关不适用；任一开启 → `privacy.fingerprintingProtection` + `privacy.trackingprotection.fingerprinting.enabled`（不开 `resistFingerprinting`：它把时区改成 UTC，较易破坏网页） |
| 禁用 WebRTC | 注入脚本 | ✅ pref `media.peerconnection.enabled=false`（新页面生效） |
| DNT / Sec-GPC | 请求头 + JS 属性 | ✅ pref `privacy.donottrackheader.enabled` + `privacy.globalprivacycontrol.enabled` |
| 剪贴板 / 媒体静音 / 通知拒绝（JS 层覆写） | 注入脚本 | ⛔ 不适用；Gecko 走权限系统 |
| 网页重定向权限（拦截无手势的跨站跳转） | `shouldOverrideUrlLoading` + `hitTestResult` | ✅ `onLoadRequest`（`isDirectNavigation` / `hasUserGesture` / `isRedirect`）；Gecko 取不到触点下是否 `<a>`，有手势一律视为用户点击 |
| 代理（HTTP / SOCKS5 / 认证 / Socks5Relay） | `ProxyController` | ✅ `WebViewProxy` 最终采用的规则（含 SOCKS5 认证用的本机中转）同步写入 `network.proxy.*`；HTTP 代理认证在 `onAuthPrompt` 自动提交 |
| Passkey | WebView WebAuthn + Credential Manager | Gecko 自带 WebAuthn，是否弹系统 Passkey 界面需实测 |
| 缓存清理 | `WebView.clearCache` | ✅ 同时调用 `StorageController.clearData` |
| 添加到桌面（轻应用 `WebAppActivity`） | 独立 WebView 页面 | 轻应用本身仍是 WebView；Gecko 标签取不到页面图标，改按域名抓取 favicon |
| 标记 / 下载 `.user.js` | 拦截导航 | ✅ `onLoadRequest` / `onExternalResponse` 里同样拦截并交给「导入脚本」 |

## Gecko 高级保护

设置 → 高级 → 「Gecko 高级保护」（仅 Gecko 模式出现）。`GeckoProtection` 持久化全部设置，并与应用原有「隐私」开关合并：

| 项 | 实现 |
|---|---|
| 增强型跟踪保护 | `ContentBlocking.setAntiTracking`（跟随隐私设置 / 关闭 / 标准 `DEFAULT` / 严格 `STRICT`） |
| 清理跟踪参数 / 弹跳跟踪 / 跟踪者清理 | `privacy.query_stripping.*` / `privacy.bounceTrackingProtection.mode` / `privacy.purge_trackers.enabled` |
| 指纹保护 / 强力抗指纹 | `privacy.fingerprintingProtection` / `privacy.resistFingerprinting` |
| GPC / DNT | `privacy.globalprivacycontrol.enabled` + `privacy.donottrackheader.enabled`（与隐私设置取并集） |
| 精简 Referer | `network.http.referer.XOriginTrimmingPolicy=2` |
| 防 WebRTC 泄露 | `media.peerconnection.ice.default_address_only` + `ice.no_host` |
| 第一方隔离 | `privacy.firstparty.isolate` |
| 仅 HTTPS | `dom.security.https_only_mode(_pbm)` |
| 安全 DNS / ECH | `network.trr.mode`（2 优先 / 3 仅 DoH）+ `network.trr.uri`；`network.dns.echconfig.enabled` |
| 预取 / WebGL | `network.prefetch-next` 等 / `webgl.disabled` |

## about:config 偏好

没有专用 Java API 的开关统一走 `GeckoPrefs`（`GeckoPreferenceController.setGeckoPref`，反射调用，找不到只记日志不崩）。
Gecko 尚未启动时只记录期望值，运行时创建后一次性写入，所以不用 Gecko 的人没有任何开销。

## 下一步

1. **拓展工具栏按钮**：browserAction 弹窗（`ActionDelegate`）、拓展自己 `tabs.create` 的新标签（`TabDelegate`）；「拓展」页已支持商店搜索、导入、全部更新、启停 / 卸载 / 选项、定期检查更新。
2. 大文件 / 未知长度下载仍不带 Cookie：计划经 WebExtension `cookies` API 取。
