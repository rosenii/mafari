# WebView 油猴桥：隔离世界通道

## 背景

WebView 标签的 GM 桥原先是 `addJavascriptInterface(GmBridge, "MafariGM")`：对该标签里**所有框架、所有 origin**可见，
与有没有脚本、脚本匹配不匹配当前页无关；`scriptId` 由调用方自报，没有任何校验。页面 JS 理论上可以直接调
`MafariGM.cookies(url)`（含 HttpOnly）、`xhrStart`（默认带 Cookie 的跨域请求）、`getValue(scriptId, key)` 等。

方案：复用 Gecko 已有的「中继 + 私有通道」架构（见 `GECKO_GM.md`），脚本和运行时仍在页面世界（`unsafeWindow`、`@grant none`
的老脚本行为不变），只把**原生通道**挪进 AndroidX WebKit 的隔离世界，页面世界里没有任何原生入口：

```
Kotlin GmChannel（原生权威：值 / xhr / cookie / 下载 / 通知 / 文件流；复用 GmBridge 的实现）
  └─ addWebMessageListener("mafariGmRelay", originRules, world=GM 世界)      ← 回调带浏览器给的 sourceOrigin / isMainFrame
       ◄── mafari_gm_relay_wv.js（隔离世界，addJavaScriptOnEvent DOCUMENT_START）
            ◄── 私有 MessageChannel ── mafari_gm_shim.js（页面世界）◄── mafari_gm_runtime.js（页面世界，语义不变）
```

shim 与 Gecko 共用（`mafari_gm_shim.js`，原 `mafari_gm_gecko_shim.js`），中继协议与 Gecko 的 `relay.js` 一致，区别只在上游：
这里没有后台页，绑定校验与分发都在 Kotlin。

## 状态

| 步骤 | 内容 | 状态 |
|---|---|---|
| 1a | shim 挪到 main 并加异步文件流；WebView 中继；运行时 `openFileStream` 支持同步 / 异步桥；协议测试 | ✅ 已完成（`tools/gm-wv-test`，Node 里跑通） |
| 1b | Kotlin `GmChannel`：安装、校验、分发、广播；`UserscriptEngine` 内联值快照；脚本源码包装 | ✅ 代码已写（`script/GmChannel.kt` `GmOrigins.kt` `GmGuard.kt` `GmSnapshot.kt`，由 `TabScripts.sync` 接入）；**未在 Android 环境编译 / 真机验证** |
| 1c | 支持隔离世界的 WebView 上不再注入 `MafariGM`；`@connect` 校验 | ✅ 代码已写（`script/GmConnectPolicy.kt`；`TabScripts.ensureLegacyBridge`）；**未在 Android 环境编译 / 真机验证**；策略类已在 JVM 上单独编译并跑过断言（`GmConnectPolicyTest`） |

**旧桥保留，但按需才挂**：系统 WebView 不支持 `JS_INJECTION_IN_FRAME_AND_WORLD`、或通道装到一半失败退回旧路径时，才由
`TabScripts.ensureLegacyBridge()`（幂等）挂上 `addJavascriptInterface(GmBridge, "MafariGM")`，脚本源码不变（运行时取 `W.MafariGM`）。
支持隔离世界的设备上，`setupWebView` 只创建 `GmBridge` 实例（供通道复用），**页面世界里没有 `MafariGM`**。
旧路径的暴露问题依旧存在，只是范围缩到旧设备（`addJavascriptInterface` 对下一次页面加载生效）。

另外：通道接管时 `TabScripts.injectMissing`（页面加载完后用 `evaluateJavascript` 补注入）直接跳过——补注入的源码没有 `MafariGM`、
也没有中继（中继只随 document-start 注入），脚本半残运行没有意义；漏掉的脚本在下次导航由 document-start handler 注入。
`WebAppActivity`（轻应用）不走通道，仍沿用 `MafariGM`。

## 一、线路协议（均为 JSON 字符串）

### 上行：中继 → 原生（`mafariGmRelay.postMessage`）

中继给每条消息盖章 `pid`（本框架内的端口号）、`sid`、`tok`（取自该端口已绑定的值，覆盖消息里自带的），并只放行下表里的 `t`。

| `t` | 字段 | 原生回复 |
|---|---|---|
| `bind` | `pid, sid, tok` | `{t:'bound', pid, ok}`；不通过中继会关闭该端口 |
| `set` / `del` | `k, v, src` | 广播 `val`（见下行） |
| `getValue` | `k` | `{t:'valRes', pid, id, k, raw}` |
| `xhr` | `reqId, opts, body` | `{t:'xhrRes', pid, reqId, json}`（`json` 形状同旧 `xhrPoll`） |
| `xhrAbort` | `reqId` | — |
| `cookieList` | `url, cbId` | `{t:'cookieRes', pid, cbId, arr}` |
| `cookieSet` | `opts` | — |
| `toast` / `copy` / `copyHtml` | `msg` / `text` / `html` | — |
| `openTab` / `closeTab` | `id, url, opts` / `id` | — |
| `download` | `url, name, cbId?` | `{t:'cb', pid, fn:'__mafariDlCallback_<cbId>', args:[ok, err]}` |
| `notify` | `json, cbId` | `{t:'cb', pid, fn:'__mafariNtfCallback_<cbId>', args:[kind]}` |
| `host` | `op, json, cbId` | `{t:'cb', pid, fn:'__mafariHostCallback_<cbId>', args:[kind, json]}`（常驻回调） |
| `menu` | `list` | —（仅主框架） |
| `ran` | `id` | —（仅主框架） |
| `stream` | `op:'open'\|'write'\|'close'\|'abort', cbId, name,mime \| id,b64 \| id` | `{t:'streamRes', pid, cbId, res}`；`res` 与同步版 `GmBridge.stream*` 的返回串相同 |

### 下行：原生 → 中继（`JavaScriptReplyProxy.postMessage`）

| 形式 | 投递 |
|---|---|
| 带 `pid` | 只发给该端口（回调、`valRes`、`xhrRes`、`cookieRes`、`streamRes`）；`pid` 在到达脚本前被剥掉 |
| 带 `id`（`{t:'val', id, k, raw\|big, src}`） | 发给本框架里绑定了脚本 `id` 的端口；大值（> 64KB）只带 `big:true`，不带内容 |
| 都没有（`{t:'menuRun', cid, n}`） | 发给本框架所有已绑定端口 |

## 二、安全模型

- 页面世界没有原生入口：`mafariGmRelay` 只存在于隔离世界；握手端口由中继在捕获阶段截停，页面监听器看不到。
- 绑定：`bind{sid,tok}` 必须先通过，之后中继才转发。原生校验两项：令牌（原生为每个脚本生成）与浏览器给出的 `sourceOrigin`
  在该脚本匹配范围内。**偷到令牌也不能在别的 origin 使用。**
- 中继强制覆盖消息里的 `sid/tok/pid`；非白名单的 `t`（包括绑定后再发 `bind`）直接丢弃。
- 下行回调名只接受 `__mafari(Dl|Ntf|Host)Callback_<id>`，一次性回调先摘后调（宿主订阅除外）。
- 残余风险（与 Gecko 相同）：页面世界与页面共用，若中继晚于 shim 加载，先发出的那个握手没人截停，页面若已挂好 `message`
  监听器，理论上能拿到端口并读到 `bind` 里的令牌；令牌的权限仍受 origin 范围约束，且只对该脚本有效。

## 三、1b 规格：Kotlin `GmChannel`

1. **安装顺序**（每个标签 WebView；支持 `JS_INJECTION_IN_FRAME_AND_WORLD` 时）：
   `getExecutionWorld(webView, "mafari-gm")` → `addWebMessageListener(webView, "mafariGmRelay", originRules, world, listener)`
   → `addJavaScriptOnEvent(中继源码, DOCUMENT_START, originRules, world)` → 最后才注册各脚本的页面世界 handler。
   监听器必须先于中继、中继必须先于脚本；顺序见下文真机清单第 1 条。
2. **`originRules`**：所有已启用脚本 `@match` / `@include` 的 origin 并集（可复用 `ext/MatchPattern.kt` 的 `toOriginRules()`）；
   表达不了的（正则、`http*`、路径）放宽成 `*`，精确判断由第 3 条完成。没有任何脚本启用时不装通道。
3. **每条消息都校验**：`tok == 原生持有的该脚本令牌`（常量时间比较）且 `sourceOrigin` 命中该脚本匹配范围（可按 `(sid, origin)` 缓存）；
   `menu` / `ran` 还要求 `isMainFrame`。不通过的静默丢弃（`bind` 回 `ok:false`）。
4. **帧注册表**：`JavaScriptReplyProxy → 已绑定的 sid 集合`，`bind` 成功时登记；值广播遍历持有该 `sid` 的帧；
   `postMessage` 抛异常或帧已销毁时清掉。不要假设同一帧每次回调给的是同一个 `ReplyProxy` 实例——校验不依赖它，注册表只用来广播。
5. **回复一律带 `pid`**（取自请求），`val` 与 `menuRun` 不带。回调（下载 / 通知 / 宿主）不再用 `evaluateJavascript` 推到主框架，
   因此 iframe 里的脚本也能收到结果。
6. **值快照**：`UserscriptEngine.buildSource` 产出
   `(function(__mafariBridge){ 运行时 })( 填好的 shim )`，快照为 `{id, tok, values, big, caps:['stream']}`；单值 > 64KB 或单脚本内联总量 > 256KB
   的键不内联，键名进 `big`。值变化后重建该脚本的 handler（限频 ≥ 1s，签名里带值的哈希），同 Gecko。
7. **复用现有实现**：把 `GmBridge` 的 xhr / cookie / 下载 / 通知 / 文件流抽成不带 `@JavascriptInterface`、以 `(scriptId, 参数)` 为入参的内部方法，
   新通道与旧桥共用一套；旧桥保留作为降级。
8. **令牌**：原生内存里为每个脚本随机生成（128 位以上），只出现在注入源码的快照里，不落盘、不进日志。
9. **降级**：不支持隔离世界时沿用旧路径，脚本源码不变。

## 三·补、`@connect` 目标主机策略（1c）

与 Gecko 侧 `background.js` 的 `connectAllows / cookieAllows` 同一口径，实现在 `GmConnectPolicy`（纯 JVM）：

| | 规则 |
|---|---|
| `GM_xmlhttpRequest` | 同站（目标 = 页面主机或其子域）始终放行；`@connect` 列出的主机（含子域）放行；本机 / 局域网 / 链路本地 / 单标签内网名必须显式 `@connect`（`*` 不算）；没声明：放行公网；声明了就按声明，`*` = 任意公网主机 |
| `GM_cookie` | 更严：只给同站或 `@connect` 明确列出的主机（`*` 与「没声明」都不算）；不允许时 `cookieList` 回空数组，`cookieSet` 丢弃 |
| 重定向 | `GmBridge.runXhr` 每一跳（含首跳）都重新过策略，不通过以 `blocked by @connect policy: <host>` 结束；只对通道版生效（`allowHost` 非空），旧桥传 null，行为不变 |
| 页面主机 | 取浏览器给出的 `sourceOrigin`；`about:blank` / `srcdoc` 等不透明 origin（`null`）的子框架没有主机，退回 `webView.url`（对应 Gecko 的「退回标签地址」） |

策略对象随令牌一起登记，脚本停用 / 删除 / 通道关闭时一并作废。已知限制：解析到内网的公网域名（DNS rebinding）看不出来，与 Gecko 相同。

## 四、真机验证清单（Node 里无法确认）

1. 同一个 document-start 里，隔离世界中继与页面世界脚本谁先执行，含 `about:blank` / `srcdoc` 子框架（文档不保证跨世界顺序；协议靠 `hi/ready` 握手兜底）。
2. 隔离世界里 `window.addEventListener('message')` 能收到页面世界 `window.postMessage` 的事件，`ev.source === window` 成立，`ev.ports[0]` 可用。
3. `addWebMessageListener` 的 `originRules` 不匹配的框架里确实没有 `mafariGmRelay` 对象。
4. `JavaScriptReplyProxy` 在框架销毁 / 后退缓存恢复后的行为（抛异常还是静默忽略）。
5. 大脚本、大值快照对页面启动耗时的影响；`bind` 到首个值读取的延迟。
6. 隔离世界设备上确认页面世界 `typeof MafariGM === "undefined"`；人为让通道安装失败（或在不支持的系统 WebView 上）确认退回旧桥后脚本仍能用，且第一次导航之后 `MafariGM` 才出现。
7. `@connect`：不透明 origin（`about:blank` / `srcdoc`）子框架里发 xhr 是否按 `webView.url` 的主机判同站；`sourceOrigin` 带端口时主机解析是否正确。
8. 两个变体都要重新编译一次（`GeckoGm.kt` 这次只改了 shim 的资源名，但 shim 换了目录）。

## 五、测试

`tools/gm-wv-test/run.js`（`node run.js`；`RELAY_ORDER=late node run.js`）：Node `vm` 模拟 页面 ⇄ 中继 ⇄ 原生，页面里跑真实的
`mafari_gm_runtime.js` + `mafari_gm_shim.js` + `mafari_gm_relay_wv.js`；原生是 `GmChannel` 的 JS 替身（令牌 + origin 校验、广播、回调按 `pid` 投递）。
覆盖：同步读快照 / 读己之写、xhr 推送、通知 / 下载一次性回调、HttpOnly cookie、菜单、值广播与回声抑制、脚本间值隔离、大值异步读取与广播、
异步文件流、页面世界看不到通道对象 / 握手端口、未绑定 / 错令牌 / 偷令牌换 origin 的伪造端口、`sid/tok/pid` 覆盖、白名单、按 `pid` 精确投递、
握手上限、惰性、中继重复注入、无通道对象时中继不动；以及旧同步桥的 `openFileStream` 回归。
不覆盖：真实 WebView、Kotlin 本身。
