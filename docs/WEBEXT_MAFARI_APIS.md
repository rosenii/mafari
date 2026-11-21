# Mafari WebExtension 专有能力

这些能力只由 Mafari 的 WebView Extension adapter 提供，不属于 Firefox / Chromium 标准 WebExtension API。

## 1. 原生 Userscript 库

在 manifest 中声明并由用户授予 `mafariScripts`：

```json
{
  "manifest_version": 3,
  "name": "Userscript manager integration",
  "version": "1.0",
  "permissions": ["mafariScripts"]
}
```

仅拓展自己的 background、popup、options 页面可以访问 `browser.mafari.userscripts` 或 `chrome.mafari.userscripts`。content script 不可调用。

```js
const api = browser.mafari.userscripts;

// 列表只返回元数据；读取单个脚本时才返回 code。
const scripts = await api.getAll();
const script = scripts.length ? await api.get(scripts[0].id) : null;

// 安装 / 更新来源 URL（仅 HTTP(S)，禁止 URL 内嵌用户名密码）。
const installed = await api.install("https://example.org/my-script.user.js");
await api.setEnabled(installed.id, true);
const refreshResult = await api.refresh(installed.id);
const batchResult = await api.refreshAll(true); // true = 只更新启用的脚本

// 创建、编辑、删除。
const created = await api.create("Example", "// ==UserScript==\\n// @match https://example.org/*\\n// ==/UserScript==\\nconsole.log('hello');", "https://example.org/*");
await api.update(created.id, "Example v2", "// ==UserScript==\\n// ==/UserScript==\\nconsole.log('v2');", true, "https://example.org/*");
await api.remove(created.id);

// 自动更新模式：0 手动，1 每天，2 每 3 天，3 每周。
const mode = await api.getAutoUpdate();
await api.setAutoUpdate(2);
```

API 方法：`getAll`、`get`、`create`、`update`、`setEnabled`、`remove`、`install`、`refresh`、`refreshAll`、`getAutoUpdate`、`setAutoUpdate`。

脚本保存在 Mafari 现有加密 Userscript 库中；实际注入由原生 `UserscriptEngine` / `TabScripts` 执行，不会把脚本代码提升为拓展的原生权限。脚本列表更新会通过共享 Compose state 传播到浏览器界面；document-start 脚本变更通常从下一次页面加载开始完整生效。

该 API 不等于 Firefox 标准 `browser.userScripts`，也不提供脚本隔离世界。

## 2. webRequestBlocking 子集

```js
browser.webRequest.onBeforeRequest.addListener(
  details => {
    if (details.url.includes("/tracking/")) return { cancel: true };
    if (details.url.includes("/old-path/")) {
      return { redirectUrl: details.url.replace("/old-path/", "/new-path/") };
    }
    return {};
  },
  { urls: ["https://example.org/*"], types: ["script", "xmlhttprequest"] },
  ["blocking"]
);
```

必须同时声明并获授 `webRequest`、`webRequestBlocking`，并对原始请求 URL 有 host permission。重定向目标也要在响应拓展的已授予 host access 范围内。listener 必须同步返回 `{cancel:true}` 或 `{redirectUrl}`；不支持 Promise / 异步回调作为 blocking 决策。

WebView `shouldInterceptRequest` 只提供有限的请求发出前拦截点：原生 IO 线程最多等待 350ms，超时或宿主无响应时默认放行；取消通过空 204 响应近似实现，重定向通过 302 + Location 近似实现。请求头 / 响应头修改、requestBody、extraHeaders、响应阶段 blocking 与完整子资源终态均不支持。不能将其视为 Firefox 网络栈的完整等价实现。


## 3. MV3 runtime.getContexts 子集

```js
const contexts = await chrome.runtime.getContexts({
  contextTypes: ["BACKGROUND", "POPUP", "TAB"],
  documentUrls: ["https://example.org/dashboard.html"]
});
```

支持 `contextTypes`、`contextIds`、`documentIds`、`documentOrigins`、`documentUrls`、`frameIds`、`tabIds`、`windowIds` 和 `incognito` 过滤；`documentUrls` 使用完整 URL 精确匹配，`contextTypes` 按 Chrome 定义的类型枚举校验。只返回当前拓展自己的在线上下文；`documentId` 是 Mafari 为当前宿主文档分配的标识，不是 Chromium 原生 document token。当前只注册主框架 content-script endpoint，不支持隐身上下文和完整 Chrome Service Worker 生命周期。未关联浏览器标签 / 窗口的上下文使用 `tabId: -1` / `windowId: -1`。

## MV3 Offscreen API (partial)

WebView extensions declaring the `offscreen` permission can use `browser.offscreen` / `chrome.offscreen`:

- `createDocument({ url, reasons, justification })`
- `closeDocument()`
- `hasDocument()`

The document is hosted in a hidden, extension-origin WebView. The URL must be a relative path to a packaged extension HTML file. One offscreen document is allowed per extension. `createDocument()` waits for the initial page load and Mafari bridge handshake; a 15-second timeout closes the document and rejects the call. `runtime.getContexts({contextTypes:['OFFSCREEN_DOCUMENT']})` can discover it.

Important differences from Chrome: the hidden WebView is not a Chromium offscreen-document implementation. `reasons` are validated but do not enforce Chrome's reason-specific lifecycle (including the 30-second silent-audio close rule). The offscreen page exposes only the `runtime` namespace, but browser capabilities and background execution throttling remain Android WebView-dependent. Device testing is required for audio, media capture, workers, and clipboard use.

## `tabs.captureVisibleTab` (partial)

`tabs.captureVisibleTab([windowId], [options])` returns a PNG or JPEG data URL for the active WebView viewport. The caller must have host access to the active page or a valid `activeTab` grant. JPEG `quality` is accepted from 0 to 100. Mafari currently captures by drawing the active WebView into a bounded bitmap (maximum 4 megapixels); this is not a compositor-level capture and may differ for video, protected surfaces, or hardware overlays.

## Runtime messaging and scripting limitations

- `runtime.onMessage` replies are accepted from synchronous `sendResponse`, a listener returning `true`, or a returned Promise. A listener that receives but does not answer no longer produces a fabricated successful `undefined` response.
- `tabs.sendMessage` sender metadata now includes the target tab ID, Mafari's single window ID, and main-frame ID. In the single-window/single-highlighted-tab model, activation also emits `tabs.onHighlighted`. Routing by `documentId` / `documentIds` remains unsupported.
- `scripting.executeScript` rejects `allFrames:true`, non-zero `frameIds`, and `documentIds` rather than silently running only in the main frame. CSS `origin:'USER'` is rejected; only the normal author-style insertion path is available.
- WebView still does not provide a Chrome-equivalent isolated JavaScript world through the current adapter. `ISOLATED` must not be treated as a security boundary; scripts run through the page WebView execution surface.
