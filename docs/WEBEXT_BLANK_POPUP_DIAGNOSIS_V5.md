# WebView 拓展 popup 白屏 / 「插件未能连接当前页面」诊断（V5）

## 现象

| 拓展 | 现象 |
| --- | --- |
| 篡改猴 | popup 能画出来（转圈动画在跑），但一直转圈 |
| uBlock Origin | popup 只画出一块占位色块，后续内容不出现 |
| 沉浸式翻译 | popup 完整渲染，但提示「插件未能连接当前页面，请刷新页面后重试」 |

三者的 popup 页面本身都已加载并执行了 JS（V4 的渲染诊断已排除资源 404 / 视口 0×0），所以问题不在「页面没画出来」，而在「页面等的回应永远没来」。
三个拓展的 manifest 都声明了 `webRequest` + `webRequestBlocking`，且都是 Firefox 版。

## 证据等级（先说清楚哪些是验证过的）

- **已验证**：旧 shim 里 `webRequest.onHeadersReceived` 等对象不存在（测试用 Firefox 风格的后台页启动代码在旧 shim 上复现了 TypeError）；`sender.url` 缺失，Port 的 `sender.url` 只有 origin。
- **未验证（凭对这些拓展源码的记忆）**：三个拓展的后台页具体在启动时调用了哪些 API、uBO 如何判定 popup 特权。本环境没有这三个拓展的源码，也没有设备日志。所以下面的「根因」是**最可能的解释**，不是已确认的结论。确认方法见文末「怎么确认」。
- **未编译**：Kotlin 改动只做了人工检查与单元测试编写，没有在本环境编译或运行（没有 Gradle 依赖与设备）。

## 根因（按把握程度排序）

### 1. 后台页启动时在缺失的 API 上抛 TypeError（最可能）

shim 原来只有 3 个 `webRequest` 事件。Firefox 版拓展的后台页常无条件执行
`webRequest.onHeadersReceived.addListener(...)`、`browserAction.setIcon(...)`、`windows.getCurrent()`、`storage.managed.get()`、
`webNavigation.onCreatedNavigationTarget.addListener(...)` 等。任何一个是 `undefined` 都会在后台页启动途中抛 TypeError，
之后的 `runtime.onMessage / onConnect` 永远没注册，popup / content script 发来的消息得不到回应：popup 转圈或空白，
popup 向 content script 发消息得到 `Receiving end does not exist`，即「插件未能连接当前页面」（对沉浸式翻译这一条是推测）。

修复分三层：

1. 补齐已知缺失的命名空间 / 事件 / 方法（见兼容矩阵第 16 节）。
2. **兜底**：每个命名空间对象包一层 Proxy，不存在的 `onXxx` 一律给惰性事件。逐个补事件补不完，这一层让「没听说过的事件」也不会把后台页带死。方法与命名空间不兜，保持特性检测可用。
3. 后台页起来后输出一行 `[mafari] shim ready: ...`，诊断日志里用它确认新 shim 已加载。

### 2. `sender.url` 缺失 / 只有 origin（缺失是事实；uBO 判定是记忆）

`MessageBroker.senderJson()` 原来不带 `url`；Port 的 `sender.url` 只是 origin（无结尾斜杠）。Chrome / Firefox 给的是完整网址。
uBO 我印象里用 `port.sender.url.startsWith(vAPI.getURL(''))` 判断 popup 特权，少一个 `/` 就判定失败，请求被静默忽略。

修复：`Sender.url` 由宿主在主线程从自己持有的 WebView 取（主框架）或退化为 `origin/`（子框架），不信消息体；`sender.tab` 补 `windowId / incognito / url`。

### 3. 后台页还没握手时的第一条消息被立刻拒绝

恢复标签 / 刚启动时，content script 的第一条 `runtime.sendMessage` 可能比后台页握手早到，立刻得到 NO_RECEIVER；Firefox 会等后台页。
修复：后台页开始加载后 5 秒内，找不到接收端的 `runtime.sendMessage` 先等待（`expectBackground` / `backgroundReady`）。超时后不再等。
`runtime.connect` 是同步建立的，不等；uBO 这类 popup 在后台页完全就绪后才打开，影响很小。

### 4. 已打开的标签要重新导航一次

`addWebMessageListener / addDocumentStartJavaScript` 只对之后的导航生效。新装 / 启用拓展后，已经打开的标签必须刷新一次，content script 才存在。

## 怎么确认（设置 → 高级 → WebView 调试 → 运行日志，复现一次后看 diag.txt）

| 日志 | 含义 |
| --- | --- |
| `ext <id> background console.log: [mafari] shim ready: ...` | 新 shim 已加载，后台页脚本开始执行。**没有这一行 = 装的还是旧包 / 后台页根本没起来** |
| `ext <id> background console.error: Uncaught TypeError ...` | 后台页启动抛错（根因 1）。看它出在哪个 API——把这行发给我 |
| `ext <id> background console.error: ... WebView 暂不支持 webRequestBlocking` | **预期内**：拓展用 `['blocking']` 注册 webRequest，native 明确拒绝。这不是启动崩溃 |
| `ext <id> background console.warning: [mafari] webRequest.onHeadersReceived: 监听器已注册，但 ...` | 预期内：该事件不可观测，对应功能受限 |
| `ext <id> background bridge-ready` 缺失 | 后台页根本没加载，查 `load error` / `render process gone` |
| `ext <id> runtime.sendMessage from ...: no extension page online` | 后台页没起来 / 没握手（等满 5 秒后仍无） |
| `ext <id>: tab N content scripts installed ...` + `content script connected tab=N` | content script 在这个标签里跑起来并握手了 |
| `ext <id> tabs.sendMessage tab=N: no content-script endpoint (online: ...)` | 这个标签里没有 content script（页面早于拓展加载，或 content script 没跑起来） |
| `ext <id>: tab N 没有可注入的 content script` | manifest 没有 content_scripts，或脚本文件读不到 |

## 这次修复**不**解决的

- `webRequestBlocking`：uBO 的拦截、篡改猴 `GM_xmlhttpRequest` 改请求头、沉浸式翻译的头部改写都不会生效。popup 能打开不等于功能完整；uBO 现阶段只能当「能打开的壳」，拦截仍要靠 Mafari 自带的去广告。需要原生网络层。
- `tabs.executeScript / insertCSS`（MV2 注入接口）：现在明确返回 `UNSUPPORTED`（原来是 TypeError），uBO / 篡改猴对已打开标签的补注入因此不工作。
- 没有真正的 isolated world。

## 测试

- `node tools/ext-shim-test/run.js`：75/75（相对原版新增 14 项，其中针对「缺 API 抛 TypeError」的在旧 shim 上失败）。
- `node tools/webext-test/run.js`：通过。
- `MessageBrokerTest` 新增 4 项（sender.url / sender.tab 回归、后台页等待 / 不等待）。**Kotlin 未在本环境编译或运行。**
