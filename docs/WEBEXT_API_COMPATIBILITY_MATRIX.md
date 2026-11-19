# Mafari WebExtensions API 兼容性矩阵

> 基准：Firefox WebExtensions（MDN JavaScript APIs 与 manifest permissions 文档）  
> 审查对象：`dual_webext_api_update2.zip` 中的 WebView 拓展适配器  
> 审查日期：2026-11-19  
> 范围：仅评估 Mafari 自研 WebView 适配层；GeckoView 使用 Gecko 原生 WebExtensions，不计入本矩阵。

## 1. 状态定义

| 状态 | 含义 |
|---|---|
| **FULL** | 该 API 的主要方法、事件和语义已实现，并有对应测试；仍需以 Firefox 逐项行为测试确认边界。 |
| **PARTIAL** | 只有部分方法/事件/上下文可用，或存在会影响兼容性的语义差异。 |
| **MANIFEST** | 能解析 manifest 声明或注入静态 content script，但没有对应完整 JavaScript API。 |
| **NONE** | 当前 WebView 适配层没有可用实现。 |
| **N/A** | 仅为类型/事件公共定义，不是独立可调用 API。 |

**重要：** JS shim 中存在一个属性、函数或事件对象，不代表 API 已实现。必须同时确认原生 `ApiRouter` 有处理器、宿主有事件来源，并且权限和返回语义可用。未实现的调用应继续返回 `UNSUPPORTED`，不能用空结果伪装成功。

## 2. 总览

| 模块 | 当前评估 | 主要缺口 |
|---|---|---|
| `runtime` | PARTIAL | 消息与 Port 已实现；Port 可向同一拓展多个已就绪页面上下文 fan-out；跨扩展与外部连接能力缺失 |
| `storage` | PARTIAL | local/session CRUD 与事件已实现；sync 是独立本地持久化，不是真正跨设备同步 |
| `alarms` | PARTIAL（本轮不修改） | 有 create/get/getAll/clear/clearAll；进程被杀期间不能准点执行，启动后补发 |
| `permissions` | PARTIAL | getAll/contains/request/remove、用户确认、持久化与权限事件已实现；复杂 host pattern 子集边界仍有限 |
| `tabs` | PARTIAL | query/create/update/remove/reload/history/sendMessage/move/duplicate 与 onMoved 已实现；单窗口、主框架限制及截图等能力缺失 |
| `scripting` | PARTIAL | executeScript/insertCSS/removeCSS 与动态注册系列已实现；无真正 isolated world，frameId 仅支持 0 |
| `i18n` | FULL（需边界测试） | 已有本地化消息表和 manifest 本地化 |
| 其余 Firefox API | NONE 或 MANIFEST | 尚未接入原生 API handler / 浏览器宿主 |

## 3. Firefox JavaScript API 命名空间逐项清单

以下命名空间以 MDN 当前 Firefox WebExtensions API 索引为目录基准。状态针对本次审查的 WebView 源码快照，而不是对 GeckoView 的评价。

| API 命名空间 | 状态 | 代码证据 / 说明 | 优先级 |
|---|---|---|---|
| `action` | PARTIAL | `action` / `browserAction` 共用原生状态；setBadgeText/setBadgeBackgroundColor/setTitle/setPopup 已接入 Mafari 拓展快捷菜单与 popup 宿主，仍缺部分 Firefox 工具栏事件 / 行为 | P1 |
| `alarms` | PARTIAL | create/get/getAll/clear/clearAll 已接入；进程死亡时延后补发，不等同常驻浏览器调度 | P1 |
| `bookmarks` | PARTIAL | `getTree/getSubTree/get/getChildren/search/create/update/move/remove/removeTree` 接入现有 FavoritesStore；四个 Firefox 根容器映射 Mafari 分组，文件夹路径模拟嵌套树；缺少 getRecent / import 事件等语义 | P2 |
| `browserAction` | PARTIAL | 兼容入口已接入，与 `action` 共用原生状态；仍有工具栏事件 / 行为差异 | P1 |
| `browserSettings` | NONE | 无扩展可调用的浏览器设置 API | P3 |
| `browsingData` | NONE | 无按扩展权限清理浏览数据 API | P3 |
| `captivePortal` | NONE | 无网络门户状态 API | P4 |
| `clipboard` | PARTIAL（Mafari 扩展 API） | `browser.clipboard.readText/writeText` 与 `chrome.clipboard` callback 入口；仅扩展页面，读写权限分别校验；Android 前后台限制，非 Firefox 完整语义 | P2 |
| `commands` | NONE | 未发现快捷键注册/触发 API | P2 |
| `contentScripts` | MANIFEST | 静态 `content_scripts` 可由注入器处理；动态 API `register/unregister/update/getRegisteredContentScripts` 在 shim 有入口，但原生 router 没有 handler | P1 |
| `contextualIdentities` | NONE | 无容器身份 API；Android 标签隐私模式不等同 Firefox Containers | P4 |
| `cookies` | PARTIAL | URL 范围 get/set/remove；需要 cookies + 对应 host permission；WebView 不提供全局枚举和变更事件，get 只能返回 name/value 等可确认字段 | P1 |
| `declarativeNetRequest` | NONE | 无 DNR 规则集/动态规则/会话规则执行引擎 | P1 |
| `devtools` | NONE | 无扩展 DevTools 页面及面板宿主 | P4 |
| `dns` | NONE | 无扩展 DNS resolve API | P2 |
| `dom` | NONE | 无 Firefox 扩展专用 DOM 扩展接口 | P4 |
| `downloads` | PARTIAL | 支持内置下载器的 download/search/pause/resume/cancel/erase；暂不支持完整 Chrome 下载字段、过滤条件及 onChanged 等事件 | P2 |
| `events` | N/A | 公共事件模型/类型；具体事件需由各 API 实现，不单独算已支持 | — |
| `extension` | NONE | 未实现 legacy `extension.*` 命名空间；部分通用能力由 `runtime` 提供 | P3 |
| `extensionTypes` | N/A | 公共类型定义，不是独立运行时服务 | — |
| `find` | NONE | 无扩展页面查找与高亮 API | P3 |
| `history` | NONE | 无扩展历史查询/修改 API | P2 |
| `i18n` | FULL* | `I18nMessages` 提供消息表和 manifest 本地化；需补齐 Firefox 全部 locale/复数/占位符边界测试 | P2 |
| `identity` | NONE | 无 OAuth 身份授权 API | P4 |
| `idle` | NONE | 无系统空闲/锁屏状态 API | P4 |
| `management` | NONE | 无安装拓展枚举、启停和管理 API | P3 |
| `menus` | PARTIAL | contextMenus.create/update/remove/removeAll、onClicked 与常用 URL/context 过滤已接入；WebView 菜单宿主入口与 Firefox 全部上下文仍有边界 | P1 |
| `notifications` | PARTIAL | basic 文本通知 create/update/clear/getAll 与点击/关闭事件；Android 系统授权和渠道由用户控制 | P2 |
| `omnibox` | NONE | Android 地址栏没有扩展建议提供者接口 | P4 |
| `pageAction` | MANIFEST | 能解析 MV2 `page_action` 配置；没有完整 API 和地址栏按钮宿主 | P2 |
| `permissions` | PARTIAL | getAll/contains/request/remove 与原生授权弹窗、持久化授权、onAdded/onRemoved 已实现；复杂 match pattern 子集判断仍有限 | P1 |
| `pkcs11` | NONE | 不提供 PKCS#11 模块管理 | P4 |
| `privacy` | NONE | 无扩展隐私设置读取/修改 API | P1 |
| `proxy` | NONE | 无扩展代理配置和按请求代理事件 API | P2 |
| `publicSuffix` | NONE | 无 Public Suffix List 查询 API | P2 |
| `runtime` | PARTIAL | getManifest/getURL/getPlatformInfo/openOptionsPage/sendMessage/connect/Port 已实现；Port 支持同一拓展内多个已就绪页面上下文；跨扩展与外部连接不支持 | P1 |
| `scripting` | PARTIAL | executeScript/insertCSS/removeCSS 与动态 content scripts 注册/查询/更新/注销已接入；仅主框架、无真正 isolated world | P1 |
| `search` | NONE | 无扩展搜索引擎枚举/执行 API | P3 |
| `sessions` | NONE | 无最近关闭标签/窗口恢复 API | P3 |
| `sidebarAction` | NONE | 无侧边栏宿主 | P4 |
| `storage` | PARTIAL | local/sync/session CRUD、配额与 onChanged 已接入；`sync` 仅本地持久化，不是 Firefox Sync；session 默认只对受信上下文开放 | P1 |
| `tabGroups` | NONE | 无扩展标签组 API | P3 |
| `tabs` | PARTIAL | query/get/getCurrent/create/update/duplicate/move/remove/reload/goBack/goForward/sendMessage 与 onMoved 已接入；sendMessage 仅主框架；captureVisibleTab、缩放和多窗口等缺失 | P1 |
| `theme` | NONE | 无扩展主题读取/应用 API | P4 |
| `topSites` | NONE | 无常访站点 API | P3 |
| `types` | N/A | 公共类型命名空间，不是独立运行时服务 | — |
| `userScripts` | NONE | Mafari 自身 Userscript/GM 系统不等同 Firefox `browser.userScripts` API | P2 |
| `userScripts (Legacy)` | NONE | MV2 legacy userScripts API 未实现 | P4 |
| `webNavigation` | PARTIAL | 主框架 before/commit/complete/error 与 SPA History/Fragment 近似事件；子框架、DOMContentLoaded、documentId 语义不完整 | P1 |
| `webRequest` | PARTIAL | 已接入 WebView `shouldInterceptRequest` 的 `onBeforeRequest` 观察事件；blocking / response 生命周期未实现 | P0 |
| `windows` | NONE | 当前标签桥接按单窗口模型设计，无扩展窗口管理 API | P3 |

`FULL*` 表示源码已有完整主路径，但仍需更广泛的 Firefox 行为兼容测试；不代表已经证明 100% 一致。

## 4. Manifest 与运行时能力必须分开统计

| Manifest 能力 | 状态 | 说明 |
|---|---|---|
| MV2 / MV3 基础解析 | PARTIAL | `ManifestParser` 归一化部分 MV2/MV3 字段；不代表所有字段均支持 |
| `permissions` / `optional_permissions` | PARTIAL | 能拆分必需 / 可选权限；运行时 request/remove 只允许拓展页面发起，并由浏览器弹窗确认 |
| `host_permissions` / `optional_host_permissions` | PARTIAL | 可选 host 权限支持动态授予 / 撤销；复杂路径通配符覆盖判定仍采用保守策略 |
| 静态 `content_scripts` | PARTIAL | 支持匹配、排除、JS/CSS、run_at、all_frames 等部分字段；WebView frame/world 行为有边界 |
| `background.scripts` / `background.page` | PARTIAL | 使用扩展后台页宿主，生命周期与 Firefox 不完全一致 |
| `background.service_worker` | PARTIAL | 通过隐藏页面模拟，不是原生 MV3 extension service worker |
| `action` / `browser_action` / `page_action` | MANIFEST | 配置可解析，不代表完整按钮 API 已实现 |
| `options_page` / `options_ui` | PARTIAL | options 页面可被识别/打开；UI 交互和全部 Firefox 行为仍需验证 |
| `web_accessible_resources` | PARTIAL | 有资源声明模型；MV3 matches 约束及所有资源访问路径需专项测试 |
| `commands` | NONE | manifest 字段当前列为不支持 |
| `devtools_page` / `sidebar_action` / `side_panel` | NONE | 当前解析器将相关字段列为不支持 |

## 5. 当前实现代码定位

| 能力 | 主要文件 |
|---|---|
| API 注册与统一路由 | `ext/ExtensionSystem.kt`、`ext/ApiRouter.kt` |
| Manifest / 权限模型 | `ext/ManifestParser.kt`、`ext/ExtensionSpec.kt`、`ext/PermissionChecker.kt`、`ext/ApiSupport.kt` |
| JS API shim | `assets/mafari_ext_shim.js` |
| runtime / 消息 | `ext/RuntimeApi.kt`、`ext/MessageBroker.kt`、`ext/ExtensionPages.kt` |
| storage | `ext/StorageApi.kt` |
| alarms | `ext/AlarmsApi.kt`、`ext/ExtensionLifecycle.kt` |
| tabs | `ext/TabsApi.kt`、`ext/TabBridge.kt`、`ext/TabChangeTracker.kt` |
| scripting / 注入 | `ext/ScriptingApi.kt`、`ext/ExtensionInjection.kt`、`ext/WebViewExtensionHost.kt` |
| i18n | `ext/I18nMessages.kt` |
| 安装与仓库 | `ext/PackageInstaller.kt`、`ext/PackageUnpacker.kt`、`ext/FileExtensionRepository.kt` |

## 6. 兼容性边界（不是简单补 API 名称就能解决）

1. **没有真正的 isolated world。** 当前 WebView content script 运行在页面 JS 世界，闭包和桥对象隐藏只能降低冲突，不能提供 Firefox 的安全隔离保证。
2. **MV3 service worker 是隐藏页面模拟。** 生命周期、休眠/唤醒、事件持久化、启动时序与 Firefox 不等价。
3. **frame 模型不完整。** WebView 不能直接提供 Firefox 的完整 frame/documentId 语义；当前 frameId 对子框架只能粗略表示。
4. **网络拦截需要原生网络层。** `webRequest`、`webRequestBlocking`、`declarativeNetRequest` 不能仅靠 JS shim 实现。
5. **浏览器专属 UI API 需要宿主。** action、menus、sidebar、omnibox、devtools 等必须接入 Mafari 原生 UI，不是返回空对象即可兼容。
6. **权限声明不等于权限能力。** 安装器可以识别权限名，但只有 API handler、host 权限校验、授权生命周期和真实底层能力都存在，才能标记为支持。

## 7. 建议实现路线

| 顺序 | 模块 | 理由 |
|---|---|---|
| P0 | `webRequest` 基础事件 + `webRequestBlocking` 过滤决策 | 广告过滤、隐私保护和请求改写类拓展的关键能力；必须先设计原生同步拦截链 |
| P1 | `tabs.sendMessage`、`runtime.connect/Port`、`scripting.registerContentScripts` | 补齐扩展上下文之间和动态脚本生命周期的基础通信能力 |
| P1 | `action` / `browserAction`、`menus`、`commands` | 大量扩展依赖按钮、popup、右键菜单和快捷键 |
| P1 | `webNavigation` | 让扩展可靠感知导航、frame 和页面状态 |
| P1 | `permissions.contains` 复杂匹配边界 | 继续核对通配符路径及 Firefox 权限语义 |
| P2 | `cookies`、`history`、`bookmarks` | 常见浏览器集成能力 |
| P2 | `declarativeNetRequest` | 为声明式过滤拓展提供独立规则执行引擎；与 webRequest 分别评估 |
| P2 | `dns`、`publicSuffix`、`privacy`、`proxy` | 对 uBlock 等复杂拓展及隐私工具有较高价值 |
| P3 | `sessions`、`windows`、`tabGroups`、`topSites`、`search` | 完善浏览器管理类拓展 |
| P4 | `devtools`、`pkcs11`、`contextualIdentities`、`sidebarAction`、`omnibox` | Android WebView 宿主成本高或不适用，可明确标注平台限制 |

## 8. 测试要求

每个 API 至少应有：

- 正常调用测试：参数、返回值、Promise 与 callback 语义。
- 权限测试：缺权限、host 不匹配、可选权限未授予、扩展停用。
- 事件测试：注册、触发、移除、后台页重启后的行为。
- 生命周期测试：安装、更新、启用、停用、卸载、标签销毁。
- 兼容测试：使用真实 Firefox 扩展示例或 Mozilla WebExtension 示例，对照 Firefox 结果。

测试报告要区分 **API 对象存在**、**调用返回成功**、**真实副作用发生** 三个层次。只有第三层也符合预期，才可将功能标记为 FULL。

## 9. 官方基准

- [MDN — JavaScript APIs](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API)：Firefox WebExtensions JavaScript API 命名空间索引。
- [MDN — permissions](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/permissions)：manifest API 权限清单及权限语义。
- [MDN — webRequest](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/webRequest)：请求监听、host permissions 与 blocking 权限要求。
- [MDN — Intercept HTTP requests](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Intercept_HTTP_requests)：`webRequest` 与 `declarativeNetRequest` 的机制区别。
- [Firefox Source Docs — API Implementation Basics](https://firefox-source-docs.mozilla.org/toolkit/components/extensions/webextensions/basics.html)：API schema、权限和运行上下文的实现模型。

---

本矩阵是一次源码静态审查结果。修改代码后，应同步更新 `ApiSupport`、安装兼容性报告、测试用例和本文件；不得仅根据 manifest 中出现了某个 permission 就将其标为已支持。


## 10. 2026-11-20 增量更新

基于 `mafari_webext_fixed (2).zip` 的后续增量：

- `permissions.request/remove`：增加 optional API / host 权限请求、原生确认回调、持久化授权、撤销与 `onAdded/onRemoved` 事件；必需权限不能撤销。
- `tabs.duplicate/move`：新增复制当前 URL 与标签排序；排序变更通过 `tabs.onMoved` 推送。复制不会克隆完整历史栈。
- `runtime.connect`：修正无 target 时 connectInfo 参数被丢失的问题；保留单接收上下文限制。
- `scripting` 动态注册系列在本次基线中已经有原生处理器，本次保留并补充文档，不重复实现。
- `storage.sync` 仍是独立本地持久化，不具备云同步；不能标记为 Firefox Sync 完整兼容。
- `alarms` 本轮未修改。

验证：Node shim 测试 43 项通过，WebExtension 示例测试通过。Android Gradle 测试未能启动：Gradle Wrapper 需要下载 Gradle 9.9.1，但当前环境无法解析 `services.gradle.org`。


## 11. 2026-11-20 WebRequest 增量

基于 `mafari_webext_contextmenus_action_patch.zip` 的后续增量：

- 新增 `WebRequestApi`，通过 WebView `WebViewClient.shouldInterceptRequest` 接入真实请求来源，不依赖页面 JS 自报 URL。
- 支持 `webRequest.onBeforeRequest`，事件字段包含 `requestId`、`url`、`method`、`tabId`、`frameId`、`parentFrameId`、`type`、`timeStamp`。
- `onBeforeRequest.addListener(listener, filter, extraInfoSpec)` 支持多 listener 独立 token；`removeListener` 只移除对应 listener。
- filter 支持 `urls`、`types`、`tabId`、`excludedUrls`；并且逐请求执行 host permission 检查。
- 无痕标签不发送 webRequest 事件。拓展停用、更新、卸载时清理 listener filter。
- `onCompleted` / `onErrorOccurred` 现在由 WebView 主文档完成 / 失败回调驱动；保留 `onBeforeRequest` 的 requestId，并支持同一主文档请求的终态事件。
- 终态事件仅覆盖主框架；WebView 不提供完整子资源完成回调，因此不能将其视为 Firefox 对所有请求的完整生命周期。
- `blocking` / `requestBody` extraInfoSpec 明确返回 `UNSUPPORTED`；`webRequestBlocking` 在 `ApiSupport` 标为 NONE。原因是后台拓展页运行在异步 WebView 消息通道上，不能安全地在同步网络回调中等待 JS listener。
- `onHeadersReceived` 仍不注册；响应头修改与请求体读取不可用。

验证：`node tools/ext-shim-test/run.js` 48 项通过；`node tools/webext-test/run.js` 两个 WebExtension 示例通过。新增 Kotlin `WebRequestApiTest` 覆盖 URL / type / tabId / excludedUrls 过滤，但 Android Gradle 测试仍未能启动：Wrapper 需要下载 Gradle 9.9.1，当前环境无法解析 `services.gradle.org`。因此 Kotlin 编译与真机网络行为尚未验证。


## 11. 2026-11-20 Port 断开语义增量

- `runtime.Port.disconnect()` 由本端主动调用时，本端 Port 立即关闭且不触发本端 `onDisconnect`；原生层只向对端发送 `portDisconnect`，由对端触发 `onDisconnect`。
- endpoint 页面销毁时，只通知仍存活的对端；拓展整体停用/卸载时先移除该拓展全部 endpoint，再静默清理 Port，避免向即将销毁的上下文发送断开事件。
- `runtime.connect` 仍只选择一个最佳接收上下文（后台页优先），跨拓展连接和多接收上下文 fan-out 仍未实现，状态继续为 PARTIAL。
- 新增 JS shim 本端主动断开测试，以及原生 MessageBroker 双向断开测试。


## 12. 2026-11-20 WebNavigation 增量

- `onBeforeNavigate` 由 `shouldOverrideUrlLoading` 记录用户/页面发起的主框架导航；`onPageStarted` 仅作程序化导航兜底，避免重定向落点重复发送 before 事件。
- `onCommitted` 改由 `onPageCommitVisible` 触发；`onPageFinished` 负责 `onCompleted`，并在较旧 WebView 未产生 commit 回调时补齐 commit。
- 新增 `onErrorOccurred`，覆盖主框架 `onReceivedError` 和主框架 SSL 失败；HTTP 4xx/5xx 仍视为 HTTP 响应，不伪装成网络错误。
- 重定向沿用同一导航状态；完成 / 失败后结束状态；错误页后续 `onPageFinished` 不再误报成功完成。标签销毁时清理状态。
- History / Fragment 事件使用独立的 last-visited URL，避免 `onPageStarted` 已更新标签 URL 后丢失旧 URL；仅在同源、非加载期报告近似 SPA 事件。WebView 无专用 History API 回调，因此仍标记 PARTIAL。
- 未实现 `onDOMContentLoaded`、`onCreatedNavigationTarget`、子框架事件和 `documentId`；不得把主框架近似事件描述为 Firefox 完整语义。

验证：新增 webNavigation shim 错误事件分发用例；Node shim 测试结果见本轮交付说明。Android Gradle / 真机回调时序仍需设备验证。


## 13. 2026-11-20 tabs.sendMessage 增量

- `MessageEndpoint` 增加 `frameId` 元数据，默认值为 0；WebView 当前 content endpoint 仍只由 `isMainFrame=true` 的页面注册，因此不会把子框架误标为主框架。
- `MessageBroker.sendToTab` 支持可选 `frameId` 过滤；不指定时按该标签所有已注册 frame endpoint 投递，指定时只投递对应 frame。
- `tabs.sendMessage` 对 options 类型、`frameId` 整数和非负约束进行校验；`frameId=0` 可用，非零 frameId 明确返回 `UNSUPPORTED`，负值 / 非整数 / 非对象 options 返回 `BAD_REQUEST`。
- 回复语义沿用多接收端 first-response-wins：任一目标返回真实响应即完成 Promise；所有目标均无接收端 / 未注册监听器则 `NO_RECEIVER`；无响应超时返回 `TIMEOUT`。
- 新增 broker 测试覆盖指定 frame 与未指定 frame 的投递选择；Tabs API 测试覆盖 options 和 frameId 参数边界。

验证：`node tools/ext-shim-test/run.js` 49 项通过。Kotlin / Android Gradle 测试尚未执行：当前环境没有已安装的 Gradle 命令，且 Gradle Wrapper 发行版未缓存；因此本轮 Kotlin 编译和真机行为仍待验证。


## 14. 2026-11-20 runtime.Port 多上下文增量

- `runtime.connect` 不再只选择后台页或一个最佳接收上下文；现在向同一拓展内所有已注册且具备投递通道的扩展页面 endpoint 分发 `runtime.onConnect`，包括 background、popup、options 等页面。
- 同一调用端 Port 使用一个 portId；调用端 `postMessage` fan-out 到所有仍存活的接收页面。任一接收页面的 `postMessage` 均可回传到原始调用端。
- `runtime.connect` 按 `portPriority` 从高到低尝试投递（后台页优先），但不会因一个页面投递失败而阻止其他已就绪页面建立连接。若全部目标均无法投递，则返回 `NO_RECEIVER`。
- 任一参与上下文主动断开或销毁时，当前实现关闭该 portId 对应的整组连接，并只向仍存活的对端上下文发送 `onDisconnect`；主动调用方仍按上一轮语义只在本地关闭，不触发自己的 `onDisconnect`。
- WebView content endpoint 仍按每个标签主框架一个 endpoint 注册；本次多接收上下文指扩展页面上下文，不代表已支持多个 content-script frame。
- 跨扩展 `runtime.connect(extensionId)`、外部连接及真正的多 frame content-script endpoint 仍未实现，`runtime` 继续标记 PARTIAL。

验证：`node tools/ext-shim-test/run.js` 49 项通过。新增 Kotlin `MessageBrokerTest` 覆盖 background/popup/options 多目标 connect fan-out、调用端消息广播及任一接收端回复路由。Android Kotlin 单元测试未能启动：Gradle Wrapper 需要下载 Gradle 9.9.1，但当前环境无法解析 `services.gradle.org`；因此 Kotlin 编译与真机行为尚未验证。


## 15. 2026-11-20 动态 Content Scripts 生命周期增量（本轮）

- `DynamicContentScriptStore` 持久化文件增加 `schemaVersion` 与 `extensionId`；兼容读取旧版 JSON 数组并迁移为版本化对象。
- 写入使用临时文件同步落盘、旧文件备份与失败回滚；损坏 / 未知版本文件隔离，不再静默当作空注册覆盖。
- 恢复时校验 JS/CSS 路径位于拓展目录内且文件仍存在；持久化数据只恢复 `persistAcrossSessions=true` 的注册。
- 扩展更新时将动态注册状态从旧包目录迁移到新 staging 目录，不携带旧版本代码文件；更新后仍按新包文件存在性校验注册。
- 扩展停用与更新不再误清除当前进程的 session-only 注册；卸载时清除注册状态。进程重启后 session-only 注册自然消失。
- `scripting` 注入计划构建时重新按当前已授予 host permissions 过滤动态脚本匹配项；权限 request/remove 后刷新标签的 document-start handler，并使用更新后的权限快照。已执行的当前文档脚本无法被 WebView 回滚，页面重新加载后才会按新权限生效。
- 新增 Store 恢复 / 损坏 / 文件缺失 / 旧格式迁移测试、权限变化刷新测试、更新迁移测试及撤权注入测试。
- Node shim 49 项、WebExtension 示例 2 项通过。Android Gradle 测试仍需在可下载 Gradle 9.9.1 的环境运行；WebView 真机回调时序仍需设备验证。


## 16. 2026-11-20 Mafari 剪贴板 API 增量

- 新增 Mafari 扩展页面专用 `browser.clipboard.readText()` / `browser.clipboard.writeText(text)`，并提供 `chrome.clipboard` callback 风格入口。该 namespace 仅在拓展自己的 background / popup / options 页面暴露，不注入普通网页 content script。
- `clipboardRead` 与 `clipboardWrite` 分别控制读写；原生 `PermissionChecker` 对两个方法独立校验，content script 即使拥有权限也不能直接调用。
- 写入通过 Android `ClipboardManager` 设置纯文本；读取首个剪贴板条目并限制返回长度为 1 MiB。Android 系统可能限制后台读取或返回空值；系统拒绝时返回 `DENIED_PERMISSION`，不是模拟成功。
- 这是 Mafari 提供的便捷扩展 API，不声称完全等同 Firefox / Chrome 的 `document.execCommand` 剪贴板授权语义，也不实现图片 / HTML / 多条目剪贴板。
- `ApiSupport` 将 `clipboardRead` / `clipboardWrite` 从 PLANNED 调整为 PARTIAL。新增权限隔离测试，覆盖读写权限独立性及 content script 拒绝。
- 完整 Android 构建与系统剪贴板前后台行为仍需设备验证。


## 17. 2026-11-20 Notifications API 增量

- WebView 扩展页面新增 `browser.notifications` / `chrome.notifications`：`create`（支持传 ID 或自动生成 ID）、`update`、`clear`、`getAll`。
- 新增 `onClicked` / `onClosed` 事件；事件只投递给同一扩展的 background / popup / options 页面，不暴露给网页 content script。
- 仅实现 `basic` 纯文本通知，字段为 title / message / contextMessage / priority；图片、进度、列表、按钮、自定义图标及 `onButtonClicked` 明确未实现。
- Android 13+ 的 `POST_NOTIFICATIONS` 与应用通知开关由原生层检查；未授权时返回 `DENIED_PERMISSION`，不伪造成功。按扩展和 priority 创建 Android 通知渠道（每个扩展最多五个）；未知通知字段明确返回 `UNSUPPORTED`。
- 通知记录只在当前进程内维护，进程重启后系统残留通知可能仍显示，但 `getAll()` 不保证恢复；点击/关闭事件也依赖进程内扩展页面宿主已初始化。
- `ApiSupport.notifications` 从 PLANNED 调整为 PARTIAL。

验证：本轮新增 shim 事件 / API 测试；Node 测试结果见交付说明。Android Gradle / 真机通知权限与点击回调尚待可用构建环境验证。


## 18. 2026-11-20 WebView Bookmarks API 增量（本轮）

- 新增 `browser.bookmarks` / `chrome.bookmarks`，仅扩展自己的 background / popup / options 页面可见；需要 manifest 声明 `bookmarks`，原生 PermissionChecker 再次校验。content script 不允许调用。
- 实现 `getTree`、`getSubTree`、`get`、`getChildren`、`search`、`create`、`update`、`move`、`remove`、`removeTree`。
- 复用 Gecko/Floccus 适配中的 `BookmarksBridge.Ops` 树映射，直接读写 Mafari `FavoritesStore`，不经 Gecko `sendNativeMessage`，也不建立第二份书签数据库。四个 Firefox 根目录映射到「书签栏」「个人收藏」「其他书签」「移动设备书签」；应用分组路径映射成嵌套文件夹。
- separator 继续使用 Floccus separator URL 占位映射；日期字段目前为 0。书签事件支持 onCreated/onChanged/onMoved/onRemoved，事件只投递扩展页面。
- 当前为 PARTIAL：没有 `getRecent`、`onChildrenReordered`、导入事件；应用自身仅支持文件夹路径的有限树语义，日期信息不完整。Floccus 在 GeckoView 中仍走原有原生桥；本实现仅服务 WebView 扩展。


## 19. 2026-11-20 WebView Downloads API 增量（本轮）

- 新增 `browser.downloads` / `chrome.downloads`，仅扩展自己的 background / popup / options 页面可用；需声明 `downloads` 权限，content script 明确拒绝。
- 实现 `download`、`search`、`pause`、`resume`、`cancel`、`erase`，以及 `onCreated` / `onErased`。下载任务直接复用 Mafari `Downloader`、SQLCipher 下载记录与默认下载目录。
- `download()` 目前只接受 HTTP/HTTPS 和 `url` / `filename` 两个字段；搜索支持 url、filename、id、state、mimeType、limit 和有限排序；`erase()` 清除任务记录，不删除已完成文件。
- 暂不支持 headers、POST body、saveAs、conflictAction、自定义目录、`onChanged`、`onDeterminingFilename`、`open`、`show`、`removeFile`、文件图标等。`downloads` 标记 PARTIAL。

验证：Node shim 57 项、WebExtension 示例 2 项通过。Android Kotlin 编译未能启动：Gradle Wrapper 下载 Gradle 9.9.1 时无法解析 `services.gradle.org`，故 Kotlin 编译、Android 单元测试与真机下载流程尚未验证。


## 20. 2026-11-20 WebView Cookies API 增量（本轮）

- 新增 `browser.cookies` / `chrome.cookies` 的 `get(details)`、`set(details)`、`remove(details)`，仅扩展自己的 background / popup / options 页面可用；需声明 `cookies` 并获得目标 URL 对应的 host permission。content script 不开放。
- 实现基于 Android WebView `CookieManager`：get 仅能按 URL 读取浏览器返回的 Cookie 请求头；set 使用 `setCookie` 并 flush；remove 通过同 URL / domain / path 写入过期 Cookie。
- `get()` 不伪造 WebView 未公开的 path、secure、httpOnly、sameSite、expiry 等属性；只返回可确认的 name/value/storeId。set 成功时返回本次传入的属性。
- 不支持 `getAll`、`getAllCookieStores`、`getPartitionKey`、`onChanged`，也无法列举所有站点 Cookie；remove 需要调用方提供正确的 URL、domain、path，不能保证清除同名的其他路径 Cookie。标记 PARTIAL。

验证：新增 shim namespace / RPC 测试；Android Kotlin 编译及真机 Cookie 行为仍需构建环境验证。


## 15. 2026-11-20 WebRequest 主框架终态事件增量

- 新增 `webRequest.onCompleted` / `webRequest.onErrorOccurred` listener 注册与 shim 分发。
- `TabWebViewSetup` 在主文档 `onPageFinished` / `onReceivedError` / SSL 失败回调时通知 WebRequestApi。
- 对可关联的主文档请求沿用 `onBeforeRequest` 的 requestId；每个标签只保留当前主文档请求，避免无限累积。
- 失败后若 WebView 再回调页面完成，不会重新生成一条虚假的完成事件；无对应 before 记录的终态回调直接忽略。
- 仍然不支持子资源 `onCompleted/onErrorOccurred`、`onHeadersReceived`、`onBeforeSendHeaders`、`onSendHeaders`、`requestBody` 和 blocking；`webRequest` 继续为 PARTIAL。
- 这不是完整 Firefox 生命周期兼容：WebView 的主文档回调与网络请求级回调并不一一对应，重定向和错误页行为需真机验证。
