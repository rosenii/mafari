# WebView WebExtensions 适配进度

本文对应 Mafari `dual.zip` 当前代码。它描述的是 WebView 适配器，不代表 GeckoView 的原生 WebExtensions 能力。

## 当前实现链路

```text
manifest.json
  └─ ManifestParser / ExtensionSpec
       └─ PackageInstaller / FileExtensionRepository
            ├─ ExtensionInjection → addDocumentStartJavaScript
            ├─ WebViewExtensionHost → WebMessageListener → ExtensionRuntime
            └─ ExtensionPageHost → 后台页 / popup / options
                 └─ mafari_ext_shim.js → browser.* / chrome.*
```

- `BrowserTab.applyWebExtensions()` 在首次网页加载前装配 content script；无痕标签不装载。
- 每个扩展有独立 WebMessageListener 名称；来源 origin、主框架标记来自 WebView 回调，不接受消息体自报。
- API 由 `ApiRouter` 注册，`PermissionChecker` 对扩展启用状态、来源和权限统一校验。
- 扩展数据放在独立目录；后台页生命周期、`runtime.onStartup` / `onInstalled`、闹钟补发已有管理。
- shim 同时提供 Promise 风格 `browser.*` 和回调风格 `chrome.*`，并实现 `chrome.runtime.lastError` 的回调期语义。

## API 进度

| API / 能力 | 当前状态 | 说明 |
| --- | --- | --- |
| `runtime.getManifest/getURL/getPlatformInfo` | 已实现 | 本地同步或 Promise 语义 |
| `runtime.sendMessage/onMessage` | 部分实现 | 扩展页面与 content script 的一次性消息；多接收端回复按首个有效回复处理 |
| `runtime.openOptionsPage` | 已实现 | 打开 manifest 声明的 options 页面 |
| `runtime.connect/onConnect` | 部分实现 | 已接入双向 Port 消息、异步连接建立、断开通知；每个 Port 当前只连接一个扩展页面，优先后台页，不支持跨扩展连接及 `onConnectExternal` |
| `storage.local/sync/session` | 部分实现 | 三个存储区的 CRUD、配额和事件已实现；`sync` 当前是独立本地持久化区，不是账号云同步 |
| `storage.onChanged` | 已实现 | 全局与分区事件；session 事件仅发往受信拓展页面 |
| `permissions.getAll/contains/request/remove` | 已实现（有边界） | optional permissions / optional host permissions 持久化授权与撤销；原生弹窗确认；required 权限不可撤销 |
| `permissions.onAdded/onRemoved` | 已实现 | 权限状态变化后向拓展上下文推送事件 |
| `alarms.*` | 已实现 | 单计时器调度；进程被杀期间顺延至下次启动补发 |
| `tabs.query/get/getCurrent/create/update/duplicate/move/remove/reload/goBack/goForward` | 部分实现 | 单窗口；URL / 标题等字段按权限裁剪；duplicate 复制当前 URL，不复制完整会话历史 |
| `tabs.sendMessage` | 部分实现 | 按扩展 + 标签路由到已注入的主框架 content script；暂不支持子框架 frameId / documentId |
| `tabs.onMoved` | 已实现 | 标签排序变化时推送 fromIndex / toIndex |
| `tabs.captureVisibleTab` 等 | 未实现 | 截图、缩放、分组和多窗口等宿主能力仍未接线 |
| `scripting.executeScript/insertCSS/removeCSS` | 部分实现 | 仅主框架；执行在页面世界，不是真正隔离世界 |
| `scripting.registerContentScripts` 系列 | 部分实现 | 支持注册、查询、更新、注销及持久化；只支持扩展包内 file，不支持内联 code；动态注册 / 更新 / 注销会刷新受影响标签的 document-start handler，当前已加载文档不会回溯执行 |
| `i18n.*` | 已实现 | 本地化消息表在注入前生成 |
| `contextMenus/menus` | 未实现 | 需接入 Mafari 长按菜单与菜单命令 UI |
| `notifications` | 未实现 | 需接入 Android 通知渠道、权限与点击回调 |
| `cookies` | 未实现 | 需设计跨站点 Cookie 权限与安全边界 |
| `webNavigation` | 部分实现 | 主框架 beforeNavigate/committed/completed/historyStateUpdated/referenceFragmentUpdated 事件与 getFrame/getAllFrames 已接入；仅 frameId=0，不支持子框架、documentId 与完整错误阶段 |
| `declarativeNetRequest` | 未实现 | 不能直接等同现有广告拦截器，需规则模型与权限适配 |
| `action/browserAction` | 部分实现 | 底栏「更多」→「拓展」打开运行列表；点击声明 `default_popup` 的拓展可在 Sheet 内打开 popup。扩展按钮、徽标、title 更新与 `onClicked` 事件尚未实现 |

### Port 当前行为

- `runtime.connect()` 在 JS 侧同步返回 Port；原生连接异步建立，握手期间调用 `postMessage()` 的消息暂存在本地队列。
- `runtime.onConnect` 接收端会得到 `name`、`sender`、`onMessage`、`onDisconnect`、`postMessage()` 和 `disconnect()`。
- 每条 Port 在原生保存扩展 ID、两端 contextId 与 Port ID；每次发送和断开都验证调用方属于连接两端之一。
- 当前按优先级选择一个接收上下文：后台页优先，其次 popup / options。不是 Firefox 的多 frame / 多接收端完整语义。
- 页面宿主销毁或标签 endpoint 注销时，关联 Port 会关闭，并向仍在线的一端发送 `portDisconnect`。


## WebView 拓展快捷菜单

- 底栏「更多」菜单中单击「拓展」会打开 WebView 拓展快捷 Sheet；长按仍进入拓展管理页。Gecko 的原有快捷菜单不变。
- 列表包含所有安装到 WebView 引擎的拓展，启用项视为运行中；可单独启停，也可使用右上角总开关。
- 拓展声明 `action.default_popup`（MV2 `browser_action.default_popup`）时，点击列表项会在同一 Sheet 中加载其 popup 页面。
- 尚未声明 popup 的拓展显示「没有快捷菜单」；由于 `action.onClicked` / `browserAction.onClicked` 尚未实现，纯后台型 action 暂不能从此菜单触发。
- 无痕标签不运行 WebView 拓展，快捷菜单会显示对应提示。

## 必须明确的 WebView 限制

1. **隔离世界（已部分解决）**：系统 WebView 支持 `JS_INJECTION_IN_FRAME_AND_WORLD`（androidx.webkit ≥ 1.16）时，每个拓展有独立 `JavaScriptExecutionWorld`，桥与 ISOLATED content script 只在该世界内，`world:"MAIN"` 注入页面世界且无拓展 API；`scripting.executeScript` 在该标签已有该拓展 content script 时进入隔离世界，否则降级页面世界。不支持时回到下述旧行为：当前 content script 的局部变量和 API 通过闭包隐藏，桥对象在启动时捕获后从 `window` 删除，但脚本仍运行在页面 JavaScript 世界。`world: "ISOLATED"` 与 `"MAIN"` 目前不能保证浏览器级隔离。
2. **`all_frames` 是尽力实现**。WebView document-start 脚本按 origin 注入，再由脚本自身判断 frame；`frameId` 也不能像 Chromium 一样提供真实、稳定的子框架编号。
3. **MV3 service worker 是隐藏扩展页模拟**，不是 Android WebView 原生 Service Worker 扩展宿主。休眠、唤醒、事件持久化和 Chrome 行为不能完全等同。
4. **页面世界执行的 `scripting` 有安全边界**：只能在获得 host 权限的页面主框架执行；页面导航期间会二次校验 origin，避免把脚本发到已变化的站点。
5. `storage.sync` 目前只是本地存储区，不应向扩展宣称已实现 Chrome Sync。

## 下一步建议顺序

1. 完善 Port：支持 `tabs.connect`、多接收端 / 多 frame 语义与页面导航清理。
2. 完善动态脚本：补充 Firefox 可表达的注入时机与匹配选项；隔离世界仍受 WebView 平台限制。
3. 完善 `tabs`：截图、语言检测、缩放和更多标签事件；多窗口能力在单窗口产品模型下保持明确不支持。
4. `storage.sync`：需要账号同步后端与冲突合并策略，不能仅靠本地文件宣称云同步。
5. `permissions.contains`：继续完善复杂通配符路径的子集判定与 Firefox 边界行为。
6. `action` / `contextMenus`：补齐按钮点击、badge、title 与网页长按菜单项。
7. `webNavigation` 已接入主框架事件；下一步完善导航错误 / 子框架边界，再接入 `notifications`、`cookies`。

不要为了兼容检测而把未实现的 API 返回空成功；保持 `UNSUPPORTED`，并在扩展安装兼容性报告里显示 PARTIAL / NONE。


### webNavigation 主框架支持

- 支持 `getFrame({tabId, frameId: 0})`、`getAllFrames({tabId})`。
- 支持 `onBeforeNavigate`、`onCommitted`、`onCompleted`、`onHistoryStateUpdated`、`onReferenceFragmentUpdated`。
- 事件仅发送给声明 `webNavigation` 且拥有目标 URL host 权限的拓展；无痕标签不发事件。
- 当前 `frameId` 固定为 0，`parentFrameId` 固定为 -1；子框架、documentId、DOMContentLoaded、onErrorOccurred 和完整 transition 语义尚未实现。
