# Mafari WebExtension API 扩展说明

本文记录 Mafari WebView 适配层额外提供或与 Firefox WebExtensions 存在差异的 API。它们不是 Firefox 标准 API 的完整替代品。

## 剪贴板

仅拓展自己的 background、popup、options 页面可访问 `browser.clipboard` / `chrome.clipboard`；普通网页和 content script 不会得到该命名空间。

在 `manifest.json` 中按需声明：

```json
{
  "manifest_version": 3,
  "permissions": ["clipboardRead", "clipboardWrite"]
}
```

读取：

```js
const text = await browser.clipboard.readText();
```

写入：

```js
await browser.clipboard.writeText('Hello from Mafari');
```

`clipboardRead` 与 `clipboardWrite` 分别控制读写。只声明 `clipboardRead` 时不会暴露 `writeText`，反之亦然。`chrome.clipboard` 提供相同方法的 callback 兼容包装。

当前仅支持纯文本：读取首个剪贴板条目，写入纯文本条目，单次文本上限 1 MiB。不支持 HTML、图片或多条目读写。Android 系统可能限制后台剪贴板读取；后台读取可能为空或被系统拒绝。需要读取剪贴板时，应在扩展页面处于前台时调用，并向用户说明读取行为。

该 API 是 Mafari 的便捷扩展接口，不应视为 Firefox/Chrome `clipboardRead` / `clipboardWrite` 对 `document.execCommand()`、`navigator.clipboard` 等 Web API 的完整兼容实现。


## Notifications（WebView）

仅扩展自己的 background / popup / options 页面可用，manifest 需声明 `notifications`。当前实现 `basic` 纯文本通知：

```javascript
const id = await browser.notifications.create('task-done', {
  type: 'basic', title: '任务完成', message: '下载已完成'
});
await browser.notifications.update(id, { message: '已保存到下载目录' });
const active = await browser.notifications.getAll();
await browser.notifications.clear(id);

browser.notifications.onClicked.addListener(id => console.log('clicked', id));
browser.notifications.onClosed.addListener((id, byUser) => console.log('closed', id, byUser));
```

也支持 `create(options)` 自动生成 ID。Chrome 风格 `chrome.notifications` 使用 callback 包装。每个扩展最多保留 64 条当前进程内通知记录；`update` 对不存在的 ID 返回 `false`，`clear` 返回是否清除了记录。优先级映射为按扩展和优先级拆分的 Android 通知渠道（共最多五个渠道），用户仍可在系统设置中覆盖渠道重要性。未知字段会明确返回 `UNSUPPORTED`，避免静默忽略。

限制：暂只支持 `basic` 文本通知、title/message/contextMessage/priority；图片、进度、列表、按钮、自定义图标与 `onButtonClicked` 尚未实现。Android 13+ 需要 Mafari 获得系统 `POST_NOTIFICATIONS` 权限；用户关闭应用通知或对应通知渠道时会拒绝创建/更新。系统通知渠道按扩展分开，但渠道重要性由 Android 用户设置控制。通知记录不跨进程持久化，进程被杀后系统通知可能仍显示，但 `getAll()` 不保证能列出它们。


## Bookmarks（WebView）

`browser.bookmarks` / `chrome.bookmarks` 复用 Mafari 自己的书签库（FavoritesStore），因此扩展创建、移动、修改和删除的书签会直接出现在 Mafari 书签 UI 中。需要在 manifest 声明 `bookmarks`；仅扩展 background / popup / options 页面可调用，content script 不开放。

```json
{ "manifest_version": 3, "permissions": ["bookmarks"] }
```

支持：

- 查询：`getTree()`、`getSubTree(id)`、`get(ids)`、`getChildren(id)`、`search(query)`
- 修改：`create(details)`、`update(id, changes)`、`move(id, destination)`、`remove(id)`、`removeTree(id)`
- 事件：`onCreated`、`onChanged`、`onMoved`、`onRemoved`

树映射沿用 Mafari Gecko/Floccus 适配的既有方案：Firefox 的 root/menu/toolbar/unfiled/mobile 四个根容器映射到 Mafari 的固定分组；Mafari 分组路径映射为嵌套文件夹。文件夹 ID 在重命名和移动时保持稳定。分隔线使用 Floccus separator URL 占位，以便同步往返。

限制：Mafari 书签模型没有完整的创建时间/修改时间字段，返回的 `dateAdded` 当前为 `0`；暂不支持 `getRecent`、`onChildrenReordered`、导入事件以及 Firefox 所有根节点元数据。`remove()` 只允许删除空文件夹；删除非空文件夹请使用 `removeTree()`。


## Downloads（部分支持）

仅拓展自己的页面可调用，需在 manifest 的 `permissions` 中声明 `downloads`。目前支持：`browser.downloads.download({ url, filename? })`、`search(query?)`、`pause(id)`、`resume(id)`、`cancel(id)`、`erase(query?)`，以及 `onCreated` / `onErased` 事件。下载任务复用 Mafari 内置下载器及其加密数据库记录；下载文件仍由 Mafari 保存到默认下载目录。

限制：目前只接受 HTTP/HTTPS URL；`filename` 只允许文件名，不接受子目录；`download()` 暂不支持 headers、POST body、saveAs、conflictAction 等选项；搜索仅支持 url、filename、id、state、mimeType 和 limit 的基础过滤；尚未实现 `onChanged`、`onDeterminingFilename`、`open`、`show`、`removeFile`、文件图标等 API。`cancel(id)` 会取消任务并移除任务记录（未完成的临时数据会清理），同时触发 `onErased`；`erase()` 按查询条件批量移除任务记录，也不会删除已完成的下载文件。


## Cookies

`browser.cookies` / `chrome.cookies` 目前仅支持 `get(details)`、`set(details)`、`remove(details)`。需要在 manifest 声明 `cookies`，并获得目标 URL 对应的 `host_permissions`；仅扩展 background / popup / options 页面可调用。

```json
{ "manifest_version": 3, "permissions": ["cookies"], "host_permissions": ["https://example.com/*"] }
```

`get` 需要 `{ url, name }`；`set` 支持 `url`、`name`、`value`、`domain`、`path`、`secure`、`httpOnly`、`sameSite`、`expirationDate`；`remove` 需要 `url`、`name`，可选 `domain`、`path`。`get` 只能返回 WebView CookieManager 可确认的 name/value/storeId，不会伪造 Cookie 属性。

限制：Android WebView 没有公开的全局 Cookie 枚举接口，因此不支持 `getAll` / `getAllCookieStores`；也没有 Cookie 变更监听 API，因此不支持 `onChanged`。删除 Cookie 需要匹配正确的 URL、domain 和 path；WebView 不提供枚举同名 Cookie 的路径信息。此 API 为 PARTIAL。


## WebRequest blocking（WebView，部分支持）

`webRequest.onBeforeRequest` 可在 `extraInfoSpec` 中声明 `blocking`，需要同时声明 `webRequest`、`webRequestBlocking` 和目标 URL 的 host permission：

```json
{
  "manifest_version": 2,
  "permissions": ["webRequest", "webRequestBlocking", "https://example.com/*"]
}
```

```javascript
browser.webRequest.onBeforeRequest.addListener(
  details => details.url.includes('/blocked') ? { cancel: true } : undefined,
  { urls: ["https://example.com/*"] },
  ["blocking"]
);
```

当前支持 `onBeforeRequest` 的 `{ cancel: true }` 与 `{ redirectUrl }`。原生网络回调在 IO 线程最多等待 350ms；拓展未回复、页面不可用或超时则放行原请求。`cancel` 通过合成 204 空响应阻止原请求，`redirectUrl` 通过合成 302 响应实现，具体重定向行为需在目标 WebView 版本真机验证。

不支持 `requestBody`、`requestHeaders` / `responseHeaders` 修改、`onBeforeSendHeaders`、`onHeadersReceived`、认证阶段和完整子资源终态。它不是 Firefox `webRequestBlocking` 的完整等价实现；超时默认放行是为了避免拓展卡死拖住网页加载。监听器在所属拓展页面销毁时由原生清理。

## Mafari 原生 Userscript 管理 API（非标准扩展 API）

WebExtension 可通过 `browser.mafari.userscripts` 管理 Mafari 自带的原生 Userscript 库。它不是 Firefox 标准 `userScripts` API；只在 background / popup / options 等拓展页面暴露，content script 不可用。必须在 manifest 显式声明 `mafariScripts`：

```json
{ "manifest_version": 3, "permissions": ["mafariScripts"] }
```

```javascript
const scripts = await browser.mafari.userscripts.getAll(); // 仅元数据
const full = await browser.mafari.userscripts.get(scripts[0].id); // 含 code
const created = await browser.mafari.userscripts.create('Example', source, 'https://example.com/*');
await browser.mafari.userscripts.setEnabled(created.id, false);
await browser.mafari.userscripts.update(created.id, 'Example v2', sourceV2, true, 'https://example.com/*');
await browser.mafari.userscripts.remove(created.id);
```

方法：`getAll()`、`get(id)`、`create(name, code, match?)`、`update(id, name, code, enabled?, match?)`、`setEnabled(id, enabled)`、`remove(id)`。脚本内容上限 1 MB；`getAll()` 不返回脚本源码，只有 `get(id)` 返回源码。变更复用 Mafari 现有脚本存储与 Compose 状态；主浏览器界面观察到脚本列表变化后会按既有同步机制更新注入集合，独立 WebApp 标签在下一次脚本同步 / 页面加载时生效。

这是高权限的 Mafari 专有接口：声明该权限的拓展可以读取、创建、修改和删除用户原生脚本，请只授予可信拓展。
