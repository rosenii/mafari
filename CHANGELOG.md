# Changelog
## [2.1.84-canary]
- 修复：custom tab 异常跳转问题

## [2.1.83] 
- 修复： 升级 miuix 后，所有使用柔光玻璃（折射 / 光影）的组件（对话框、弹窗、sheet、按钮等）左侧出现一条未模糊 / 边缘错位的异常带。原因：上游 blur 现会把每一侧的 padding 裁到「背景层在该侧的剩余空间」，而折射 shader 仍按四边相同的 padding 计算组件原点；新增补丁 0011，shader 改用逐边 padding
- 新增：地址栏新增搜索引擎快速切换
- 调整：添加自定义搜索引擎逻辑和UI
## [2.1.77] 
- 修复： 升级 miuix 后，所有使用柔光玻璃（折射 / 光影）的组件（对话框、弹窗、sheet、按钮等）左侧出现一条未模糊 / 边缘错位的异常带。原因：上游 blur 现会把每一侧的 padding 裁到「背景层在该侧的剩余空间」，而折射 shader 仍按四边相同的 padding 计算组件原点；新增补丁 0011，shader 改用逐边 padding
- 修复： 「外部链接打开方式 → 自定义标签页」原来无效（只是转手去调别的浏览器）。现在 Mafari 自己提供自定义标签页：新增 Custom Tabs 服务（其他应用才会把 Mafari 当作支持自定义标签页的浏览器）、无界面分流入口（http/https 链接入口由 MainActivity 移到 LinkDispatchActivity）、自定义标签页窗口（叠在调用方任务里，工具栏：× 关闭 / 标题与域名 / ↗ 在 Mafari 中打开），点链接不再跳转到 Mafari 主界面
- 新增： 输入网址时地址栏最左侧（原脚本 / 阅读 / 下载图标位置）显示当前搜索引擎图标，点击弹出引擎菜单快速切换（默认底栏与悬浮三段式都有；菜单用抽象的 AppGlassPopup，与其他弹出菜单同一套材质，键盘不收起；输入时一律向上弹，不被键盘遮挡）
- 新增： 搜索引擎弹窗末尾新增「自定义」行，点击弹出添加框（引擎名 + 引擎地址，%s 代表关键词），保存后固化为引擎列表选项并自动选中；自定义引擎可在列表里删除；旧版单个自定义模板自动迁移成列表第一项
- 修复： 顶部加载进度条被状态栏 / 顶部渐进模糊采样而糊掉；进度条移到整页采集层与模糊带之外的最上层
- 修复： 回到主页（页面被盖在同一标签下面）后媒体仍在播放：主页盖住页面期间拦住并暂停媒体（Gecko 在 onPlay 补暂停；WebView 脚本暂停 + 监听 play 事件），页面还原 / 重新加载后解除
- 修复： 悬浮三段式从网页点主页按钮回到主页后，后退按钮仍置灰；现与默认底栏一致，可点后退还原刚才的页面
- 修复： 自定义起始页为默认主页时，从正在播放媒体的网页点主页按钮 / 返回到主页，页面在下面继续播放；现在回到主页即暂停媒体
- 修复： Cloudflare 站点滑动 / 刷新时偶发的空响应 502 / 503 / 504 / 52x 不再直接弹出错误页，先静默重试最多 2 次（间隔 0.7s / 1.8s），仍失败才显示
- 调整： 状态栏渐进模糊在状态栏下沿以下再向下渐隐 14dp，盖住状态栏与网页之间的分割白块
- 修复： 悬浮三段式下沉背景（底部渐进模糊）高度向上略增 2dp，压住与网页底栏接缝处可能出现的细分割线
- 新增： 引导页第一屏加语言选择（跟随系统 / English / 简体中文 / 繁體中文），选后自动重启
- 新增： WebView 网页态状态栏背景改为与顶栏同款的 miuix 渐进模糊（自上而下由强到弱），边缘拉伸采样网页顶部边缘；显示时机不变（初始状态显示，下滑淡出），悬浮默认栏 / 悬浮三段式 / 固定底栏均适用；Gecko 不变
- 修复： 柔光玻璃（非 miuix 默认模板）下通栏 sheet 左右边缘被当成玻璃边缘折射，露出一条清晰的原页面（边缘错位）；玻璃面板改为向两侧各多铺一段到屏幕外，折射边缘落在屏外，顶部圆角仍保留
- 新增： 悬浮三段式沉到底部让出的那一行背景由纯色改为渐进模糊（direction = Bottom，贴底最强、向上渐隐），高度随让位动画伸缩，边缘拉伸采样网页底部边缘
- 新增： 历史页每条记录左侧加站点图标

- 调整：悬浮三段式导航栏与默认样式一致：下滑缩成胶囊时状态栏留白随之收起，网页铺满屏幕顶

- 优化：历史页图标按域名共用，不重复加载；未缓存的行延迟 150ms 再请求，快速滑动不排队
- 优化： `miuix-sync.sh` 导入兜底改为逐项检查，重复运行不重复添加

- 修复：上游 miuix 不再导入 `asPaddingValues`，新补丁已在构建脚本里补上
- 修复： 补全 19 条英文翻译，包括"网页增强"设置行的过期译文

## [2.1.76] 
- 安全：网页 `intent://` 交给其他应用前统一清洗（`WebIntentSanitizer`）：去掉全部 URI 授权标志、拒绝 `content:` / `file:` data、清空 clipData / component / selector。浏览器标签和轻应用两处都走它。
- 安全：移除从未使用的 FileProvider 及 `file_paths.xml`（原先把整个 files 目录都声明成可共享）。
- 安全：`allowBackup` 改为 false，新增文件不再默认进云备份 / adb 备份。
- 安全：运行日志和崩溃日志里的网址抹掉 query / fragment；无痕标签不再写运行日志（页面、错误、console）。
- 安全：应用自己访问固定端点（发布信息、AMO 扩展商店、Floccus 包、GitHub 加速探测）一律只允许 https，重定向降级到 http 也拒绝（`HttpFetcher.Options.httpsOnly` / `HttpFetcher.requireHttps`）；更新安装包只从 https 地址下载，否则退回打开发布页。
- 安全：从链接安装拓展包，公网地址必须 https，本机 / 局域网（localhost、回环、私网段、.local）仍可用 http。
- 安全：WebView 和 Gecko 的「USB 远程调试」不再跨启动保存，冷启动时自动关闭；开关说明里已注明。
- 安全：更新安装包下载完成后校验 SHA-256（`UpdateIntegrity`）。期望哈希单独**直连** api.github.com（https）取 release 资产的 digest——镜像和镜像给出的发布列表都不可信；对不上就删除安装包、报失败，不进入「可安装」状态。直连 GitHub 不通取不到期望值时照常下载，由系统安装器校验签名。
- 安全：油猴 `GM_notification` 的点击经 exported 的 MainActivity 进入，现在每条通知带随机令牌，令牌对不上的 intent（其他应用伪造的点击）一律忽略。

## [2.1.75] cold start: extensions load after the first page
- 优化：WebView 拓展不再在冷启动期间预热 / 装配，改为「首页加载成功后」晚加载（`ExtStartupGate`）：主页 → 界面出来后 600ms 且主线程空闲；网页 → 第一个网页 onPageFinished 后 300ms（最多等 5s）。开闸后后台线程预热 → 主线程给已有标签补装 → 错开拉起后台页。开闸前拓展系统在热路径上视为不存在（`webExtensionsOrNull` 返回 null），首页的请求 / 导航不经过任何拓展逻辑。前台已加载的页面若有 content script 匹配，会重载一次让拓展生效（`BrowserTab.RELOAD_AFTER_LATE_EXT` 可关）。
- 修复：首个标签在主线程等拓展预热线程持有的锁（日志里约 1.2 秒）；BrowserScreen / 对话框宿主在首次组合里提前创建拓展系统。
- 优化：每个标签创建时在主线程读 asset（`mafari_blob_cache.js`、GM relay）改为只读一次，并由启动预热线程提前读好；`TabBlob.cleanStaleFiles` 的缓存目录扫描移到后台线程。
## [2.1.75]
- 调整：页面增强现在控制范围涵盖拓展（webview实验性）
- 优化：冷启动加载流程优化，禁用项目不再预热加载
- 调整：无痕浏览开启后固化，直到手动取消
##

- **The bar-detection also recognises bottom input boxes and submit buttons** (chat composers, comment boxes, checkout "submit order" bars): a visible input / textarea / contenteditable or a submit button inside a fixed (or bottom-sticky) container that is >= 60% wide and sits at the bottom of the viewport now counts as a page bottom bar, so the split bar yields the row instead of covering it. Same logic in `PageBottomBar.kt` (WebView) and `scroll-metrics.js` (Gecko).
- **Long-press on the address pill opens the start page** (follows Settings → Customize home → Custom start page, same as the home button) instead of opening a new tab. The pill plays an elastic shrink-then-overshoot pop while it opens.
- **The compact title pill (shown while scrolling) is lower**: 32dp → 26dp, corner 16dp → 13dp.
- **WebView debug: new "Record page performance" switch** (page performance section, default on = previous behaviour). When off, FCP / LCP and new entries are no longer collected; existing records are kept.
- **WebView debug: the "Preconnect" info line now says which path is available** (queued `enqueuePreconnect`, direct `preconnect`, or neither / no multi-profile) instead of a bare "not supported". `enqueuePreconnect` only exists since androidx.webkit 1.17.0-alpha04, so a system WebView that is not new enough reports it as unsupported even when it is the latest installed one.
- **Cold start: WebView extensions no longer block the first frame / first tab.** Previously the first WebView tab built the extension system, read every extension's state + manifest, read the shim and read / bundled every content script **on the main thread** before the first page could load. Now `StartupCoordinator.prewarmExtensions` does all of that on a background thread right after process creation (`ExtensionSystem.prewarm`, `WebViewExtensionHost.prewarm`); `ExtensionInjection.build` is split into `prepare` (file reads, bundling, localisation; cached per extension, invalidated on install / enable / disable / uninstall, permission or dynamic-script change) and `finish` (per-tab string assembly with the tab id), so the main thread only concatenates. Output is byte-identical to before (`ExtensionInjectionTest.prepareThenFinishMatchesBuildForEveryTab`).
- **Cold start: extension background pages start when the main thread is idle and are staggered** (200 ms apart, 3 s idle timeout) instead of all being created in one main-thread burst 1.5 s after the first frame. The start delay itself is unchanged; a delayed start re-checks that the extension is still enabled and not already running.
- Not compiled here (no Android toolchain).

## [2.1.70]
## WebView extensions: blank popups (Dark Reader / Tampermonkey)

- **Dark Reader popup was blank.** `chrome.extension` in the page shim was the raw object, not wrapped by `cbObj`, so `chrome.extension.isAllowedFileSchemeAccess(cb)` / `isAllowedIncognitoAccess(cb)` never called their callback. Dark Reader's `collectData()` does `Promise.all([... new Promise(r => chrome.extension.isAllowedFileSchemeAccess(r)) ...])`, so on Firefox (port `getData`) the background never answered and the popup stayed empty. `chrome.extension` is now `cbObj(ext)`; promise-style `browser.extension.*` is unchanged.
- **`runtime.sendMessage` no longer rejects with `NO_RECEIVER` when a listener exists but does not respond** (Firefox semantics: resolves `undefined`). Only "no listener at all / undeliverable" is `NO_RECEIVER`. The shim now replies `{noResponse:true}` (listener present, no answer) vs `{noReceiver:true}` (no listener); `MessageBroker` resolves `undefined` when every target finished without a real reply and at least one had a listener. A real reply from any context still wins. Tampermonkey's popup sends fire-and-forget messages and then reads data over a Port; the rejection surfaced as "unable to load tree".
- Diagnostics: a `listener present but no response` line is logged for `noResponse` replies.
- Tests: `ext-shim-test` (chrome.extension callback style, noResponse reply), `MessageBrokerTest.listenerWithoutResponseResolvesUndefinedNotNoReceiver`. Not compiled for Android here. Two shim tests (`windows.create` UNSUPPORTED, API wiring list) already failed in the uploaded tree and are unchanged.

## WebView adoption, part 2: incognito Profile, WebMessageListener bridges

- **Incognito tabs use a separate WebView Profile** (`IncognitoProfile`, `MULTI_PROFILE`). The tab's WebView is bound with `setProfile` right after creation (incognito is fixed at tab creation, which is already how `createTab` uses it). Cookies, cache, localStorage / IndexedDB, service workers and site permissions are isolated from the default profile; logging in inside an incognito tab now persists for that session (the old process-wide `setAcceptCookie(false)` flip blocked all cookies). When the last bound WebView is destroyed the whole profile is deleted (retry once; leftovers from a killed process are removed on first incognito use, plus a sweep after the UI is shown). Without `MULTI_PROFILE` the old behaviour is kept.
- Cookie access that used the global `CookieManager` is now profile-aware: `applyPrivacy`, initial tab setup, `GmBridge` (GM_cookie / `GM_xmlhttpRequest` cookies) and the temp WebView of `window.open` (bound to the opener's profile). Cookie backup reads the default profile path and is unchanged; incognito downloads still send no cookies (`withCookies = !tab.incognito`).
- **One-way bridges moved to `WebMessageListener`** (`OneWayBridge`): `MafariTr` (page translation) and `MafariElement` (ad marking long-press) now only accept messages from the main frame with an http(s) origin, string payloads under a length cap. `addJavascriptInterface` remains as the fallback when `WEB_MESSAGE_LISTENER` is unsupported; the page scripts feature-detect `postMessage` vs the old method (`mafari_translate.js` and its Gecko copy kept identical; node test added).
- Not converted (they need a synchronous return value): `MafariAdblock.css`, `MafariFp.fp` (fallback only), `MafariBlob.begin` / chunk streaming. Tests: `OneWayBridgeTest`.
- Not compiled here; see the delivery notes for the device checklist.

## WebView adoption: render-process recovery, UA client hints, async startup

- **Main tabs now survive a renderer crash / OOM kill.** `TabWebViewSetup` handles `onRenderProcessGone` (returns `true`, so the app is no longer taken down with the renderer). `BrowserState.replaceCrashedTab` swaps in a fresh tab at the same position, keeping URL / title / desktop mode / opener chain / `tabs` row key. A foreground tab shows the existing "page crashed" error page (`ErrorPages.crashed`) and waits for Retry (no auto-reload, so a page that crashes on load cannot loop); a background tab becomes a pending-restore tab and loads when switched to.
- **UA client hints follow the UA string.** New `UaClientHints` derives platform / mobile from the effective UA and applies `WebSettingsCompat.setUserAgentMetadata` (`USER_AGENT_METADATA`) after every `userAgentString` change, so `Sec-CH-UA-Mobile` / `Sec-CH-UA-Platform` / `navigator.userAgentData` no longer contradict desktop mode or a custom UA. When the target UA has the same platform and mobile flag as the system default it is left to WebView (the reduced UA's Android version is frozen and less accurate). Brand list untouched. Test: `UaClientHintsTest`.
- **Async WebView start-up.** New `WebViewStartup` calls `WebViewCompat.startUpWebView` from `Application.onCreate` (main process, WebView default engine only). The cold-start content gate (`StartupCoordinator.awaitContentReady`) waits for it (<= 1.5 s, in parallel with the encrypted-DB warm-up), so the first `BrowserTab` WebView is created only after start-up finished; if it never finishes or is unsupported the old synchronous behaviour applies.
- Not compiled here; `startUpWebView` / `UserAgentMetadata` signatures are written from the androidx.webkit docs, see the device checklist in the delivery notes.

## WebView GM channel: step 1c (legacy bridge only on demand, `@connect`)
## [2.1.68]

- Isolated-world devices no longer expose `MafariGM` to page JS: `setupWebView` only creates the shared `GmBridge`; `TabScripts.ensureLegacyBridge()` attaches the `addJavascriptInterface` bridge only when the system WebView lacks isolated worlds or the channel failed to install.
- `TabScripts.injectMissing` skips late `evaluateJavascript` injection while the channel is active (such a script would have neither bridge nor relay).
- New `GmConnectPolicy` (pure JVM, same rules as the Gecko background page): `@connect` is enforced for `GM_xmlhttpRequest` (every redirect hop re-checked) and, more strictly, `GM_cookie` on the channel path. Private/LAN hosts need an explicit `@connect`.
- Script quick menu subtitle now shows the runtime environment after the running count ("N scripts running · Isolated-world channel" / "· In-page bridge", the latter with a red "!" that toasts an unsafe warning and advises updating WebView): `TabScripts.bridgeMode()` reports isolated world when the GM channel (or Gecko relay) is in use and in-page when the legacy `MafariGM` bridge is attached.
- Tests: `GmConnectPolicyTest`. Not compiled for Android here; see the device checklist in `docs/WEBVIEW_GM_CHANNEL.md`.

## androidx.webkit 1.19.0-alpha02 + true isolated worlds

- Bumped `androidx.webkit` 1.18.0 -> 1.19.0-alpha02.
- Content scripts and the message bridge now live in a per-extension `JavaScriptExecutionWorld` (needs `JS_INJECTION_IN_FRAME_AND_WORLD`; falls back to the old page-world path otherwise). `world: "MAIN"` scripts go to the page world without a bridge or extension APIs.
- `scripting.executeScript` (ISOLATED) runs in that world via `JavaScriptReplyProxy.executeJavaScript` when the extension already has a content script on the tab; otherwise it falls back to the page world.
- Shim: `mainWorld` / `isolatedWorld` modes; `browser`/`chrome` become globals only inside the isolated world.
- Not wired: `Profile.configureIsolatedSites` (experimental, opt-in) added in 1.19.0-alpha02.


## WebExtension adapter follow-up

- Added the `webRequest.onBeforeRequest` blocking decision path for `cancel` and `redirectUrl`, with a bounded 350 ms wait on WebView's request IO callback and fail-open timeout behavior.
- Bound blocking replies to the extension page context that registered a matching blocking listener. This prevents unrelated popup/options/background contexts from sending an empty reply that wins the same extension's request decision.
- Revalidated the responding extension's enabled state, `webRequest` / `webRequestBlocking` grants, original host access, and redirect target host access before applying a decision.
- Validated `webRequest` URL filters and rejected unsupported `extraInfoSpec` values instead of silently pretending to support request/response header mutation.
- Expanded the Mafari-only `browser.mafari.userscripts` / `chrome.mafari.userscripts` API with source URL install, per-script refresh, batch refresh, and auto-update mode accessors. The API is restricted to extension pages and the explicit `mafariScripts` permission.
- Updated the WebExtension compatibility matrix and added API usage documentation.


## WebExtension adapter continued implementation

- Added MV3 `declarativeNetRequest` dynamic rule storage and execution for `block`, `redirect.url`, and `allow`, with URL/resource-type/tab/method conditions, validation, a 5,000-rule cap, and persistent per-extension rules. Static rulesets and advanced conditions remain unsupported.
- Added the standard `browser.userScripts` / `chrome.userScripts` facade (`register`, `getScripts`, `update`, `unregister`) backed by the existing dynamic content-script injector; supports inline code and extension files with host-pattern validation.
- Added `webRequest.onBeforeRedirect` for Mafari-applied blocking redirects; blocked requests emit `onErrorOccurred`. Main-frame `onResponseStarted` is approximated from `onPageCommitVisible`; request/response headers and subresource response phases remain unavailable in WebView.
- Buffered early `storage.onChanged` notifications briefly so a background page that registers its listeners just after startup does not miss the first storage changes.
- Reduced the default `runtime.sendMessage` response timeout from 30 s to 10 s, added target-context timeout diagnostics, and release pending deliveries when an endpoint is destroyed.
- Updated compatibility matrix and documented the exact supported subsets and WebView limitations.


## WebExtension runtime context discovery

- Added Manifest V3 `runtime.getContexts(filter)` for querying the current extension's live background, popup, options and content-script contexts.
- Added context type, context ID, document URL/origin, frame, tab, window and incognito filtering; document IDs are Mafari host-generated identifiers rather than Chromium-native document tokens.
- Changed the unimplemented `runtime.setUninstallURL()` from a silent no-op to an explicit `UNSUPPORTED` rejection.
- Added tests for own-extension isolation, context filters and malformed filter rejection.

- Added a constrained MV2 `runtime.getBackgroundPage` bridge descriptor for non-background extension pages; MV3 correctly rejects the deprecated background-page API. The descriptor is not a cross-WebView `Window` object.

- Exposed synchronous manifest-backed `runtime.getVersion()`.

## WebExtension API continuation: offscreen, tabs capture, messaging and scripting

- Added partial MV3 `offscreen` support backed by one hidden extension-origin WebView per extension: `createDocument`, `closeDocument`, and `hasDocument`; creation waits for load plus bridge handshake, and `runtime.getContexts` reports `OFFSCREEN_DOCUMENT`.
- Restricted offscreen page shims to the `runtime` namespace and validated the declared `offscreen` permission, MV3 manifest, extension-page caller, packaged relative HTML path, reason enum and justification.
- Added `tabs.captureVisibleTab` for the active WebView viewport, returning PNG/JPEG data URLs; host permission or a current `activeTab` grant is required. Capture is bounded to 4 megapixels and uses WebView view drawing rather than compositor capture.
- Added target tab metadata to `tabs.sendMessage` sender objects, implemented single-window `tabs.onHighlighted` dispatch on tab activation, and explicitly rejected documentId-based message routing.
- Tightened scripting target validation: unsupported all-frame, non-main-frame and documentId injection requests now fail explicitly; CSS USER cascade origin is rejected rather than silently treated as AUTHOR.
- Corrected runtime message delivery so a listener that receives a message but never responds does not fabricate a successful undefined reply; explicit sync replies, returned true and Promise replies remain supported.
- Updated API compatibility notes and added unit coverage for capture options, sender metadata and unsupported scripting targets.
- Chrome offscreen reason-specific lifecycle behavior, true isolated worlds, multi-frame injection and compositor-level capture remain unsupported.
