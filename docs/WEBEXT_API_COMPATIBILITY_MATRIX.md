# Mafari WebExtensions API 兼容性矩阵

> 基准：Firefox WebExtensions（MDN JavaScript APIs 与 manifest permissions 文档）  
> 审查对象：从 `mafari_webext_fixed (2).zip` 起持续增量更新的 WebView 拓展适配器  
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
| `runtime` | PARTIAL | 消息与 Port 已实现；Port 只连接一个最佳接收上下文；跨扩展与外部连接能力缺失 |
| `storage` | PARTIAL | local/session CRUD 与事件已实现；sync 是独立本地持久化，不是真正跨设备同步 |
| `alarms` | PARTIAL（本轮不修改） | 有 create/get/getAll/clear/clearAll；进程被杀期间不能准点执行，启动后补发 |
| `permissions` | PARTIAL | getAll/contains/request/remove、用户确认、持久化与权限事件已实现；复杂 host pattern 子集边界仍有限 |
| `tabs` | PARTIAL | query/create/update/remove/reload/history/sendMessage/move/duplicate 与 onMoved 已实现；单窗口、主框架限制及截图等能力缺失 |
| `scripting` | PARTIAL | executeScript/insertCSS/removeCSS 与动态注册系列已实现；无真正 isolated world，frameId 仅支持 0 |
| `webNavigation` | PARTIAL | 主框架导航事件与 getFrame/getAllFrames 已接入；子框架、documentId 和完整错误阶段缺失 |
| `i18n` | FULL（需边界测试） | 已有本地化消息表和 manifest 本地化 |
| 其余 Firefox API | NONE 或 MANIFEST | 尚未接入原生 API handler / 浏览器宿主 |

## 3. Firefox JavaScript API 命名空间逐项清单

以下命名空间以 MDN 当前 Firefox WebExtensions API 索引为目录基准。状态针对本次审查的 WebView 源码快照，而不是对 GeckoView 的评价。

| API 命名空间 | 状态 | 代码证据 / 说明 | 优先级 |
|---|---|---|---|
| `action` | MANIFEST | `ManifestParser` 能解析 MV3 `action`，但没有 `browser.action` 的完整方法、事件和工具栏状态接口 | P1 |
| `alarms` | PARTIAL | `AlarmsApi` 注册 create/get/getAll/clear/clearAll；进程死亡时延后补发，不等同常驻浏览器调度 | P1 |
| `bookmarks` | NONE | 未发现 WebView API handler；不能把 Mafari 自身书签 UI 当成扩展 `bookmarks` API | P2 |
| `browserAction` | MANIFEST | 能解析 MV2 `browser_action` 配置；未形成完整 `browser.browserAction` API | P1 |
| `browserSettings` | NONE | 无扩展可调用的浏览器设置 API | P3 |
| `browsingData` | NONE | 无按扩展权限清理浏览数据 API | P3 |
| `captivePortal` | NONE | 无网络门户状态 API | P4 |
| `clipboard` | NONE | 无 WebExtensions 专用剪贴板 API | P3 |
| `commands` | NONE | 未发现快捷键注册/触发 API | P2 |
| `contentScripts` | MANIFEST | 静态 `content_scripts` 可由注入器处理；动态 API `register/unregister/update/getRegisteredContentScripts` 在 shim 有入口，但原生 router 没有 handler | P1 |
| `contextualIdentities` | NONE | 无容器身份 API；Android 标签隐私模式不等同 Firefox Containers | P4 |
| `cookies` | NONE | 无 Cookie 查询、修改和变更事件 API | P1 |
| `declarativeNetRequest` | NONE | 无 DNR 规则集/动态规则/会话规则执行引擎 | P1 |
| `devtools` | NONE | 无扩展 DevTools 页面及面板宿主 | P4 |
| `dns` | NONE | 无扩展 DNS resolve API | P2 |
| `dom` | NONE | 无 Firefox 扩展专用 DOM 扩展接口 | P4 |
| `downloads` | NONE | 无完整扩展下载 API；浏览器自身下载能力不等于该 API | P2 |
| `events` | N/A | 公共事件模型/类型；具体事件需由各 API 实现，不单独算已支持 | — |
| `extension` | NONE | 未实现 legacy `extension.*` 命名空间；部分通用能力由 `runtime` 提供 | P3 |
| `extensionTypes` | N/A | 公共类型定义，不是独立运行时服务 | — |
| `find` | NONE | 无扩展页面查找与高亮 API | P3 |
| `history` | NONE | 无扩展历史查询/修改 API | P2 |
| `i18n` | FULL* | `I18nMessages` 提供消息表和 manifest 本地化；需补齐 Firefox 全部 locale/复数/占位符边界测试 | P2 |
| `identity` | NONE | 无 OAuth 身份授权 API | P4 |
| `idle` | NONE | 无系统空闲/锁屏状态 API | P4 |
| `management` | NONE | 无安装拓展枚举、启停和管理 API | P3 |
| `menus` | NONE | 未接入浏览器菜单项注册、更新、删除和点击事件 | P1 |
| `notifications` | NONE | 未接入 Android 通知渠道和扩展通知事件 | P2 |
| `omnibox` | NONE | Android 地址栏没有扩展建议提供者接口 | P4 |
| `pageAction` | MANIFEST | 能解析 MV2 `page_action` 配置；没有完整 API 和地址栏按钮宿主 | P2 |
| `permissions` | PARTIAL | getAll/contains/request/remove 与原生授权弹窗、持久化授权、onAdded/onRemoved 已实现；复杂 match pattern 子集判断仍有限 | P1 |
| `pkcs11` | NONE | 不提供 PKCS#11 模块管理 | P4 |
| `privacy` | NONE | 无扩展隐私设置读取/修改 API | P1 |
| `proxy` | NONE | 无扩展代理配置和按请求代理事件 API | P2 |
| `publicSuffix` | NONE | 无 Public Suffix List 查询 API | P2 |
| `runtime` | PARTIAL | getManifest/getURL/getPlatformInfo/openOptionsPage/sendMessage/connect/Port 已实现；Port 只连接一个最佳接收上下文；跨扩展与外部连接不支持 | P1 |
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
| `webNavigation` | PARTIAL | 已接入主框架 beforeNavigate/committed/completed/historyStateUpdated/referenceFragmentUpdated 与 getFrame/getAllFrames；不支持子框架、documentId 和完整错误阶段 | P1 |
| `webRequest` | NONE | 无请求生命周期事件与 blocking/redirect/header 修改 API | P0 |
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
| P2 | `cookies`、`downloads`、`history`、`bookmarks` | 常见浏览器集成能力 |
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

验证：Node shim 测试 45 项通过，WebExtension 示例测试通过。Android Gradle 测试未能启动：Gradle Wrapper 需要下载 Gradle 9.9.1，但当前环境无法解析 `services.gradle.org`。


## 11. 2026-11-20 增量更新（二）

- `webNavigation`：接入主框架 `onBeforeNavigate`、`onCommitted`、`onCompleted`、`onHistoryStateUpdated`、`onReferenceFragmentUpdated`。
- `webNavigation.getFrame/getAllFrames`：仅支持主框架 `frameId=0`；其他 frameId 明确返回 UNSUPPORTED。
- 事件按 `webNavigation` API 权限与目标 URL host 权限双重过滤；无痕标签不发事件。
- WebView 无法提供完整 Firefox frame/documentId、DOMContentLoaded 与网络错误阶段语义，因此仍标记 PARTIAL。
