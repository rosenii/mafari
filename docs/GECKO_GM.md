# Gecko 油猴适配（内置 WebExtension）

目标：Gecko 标签与 WebView 标签共用同一套脚本系统——同一份 `mafari_gm_runtime.js`、同一个脚本库、同一份 GM 值（加密库 `history.db` 的 `gm_values` 表，见 `GmStore`；早期版本存 SharedPreferences `userscript_gm`）。

## 一、对原方案的评价

| 方案点 | 结论 | 说明 |
|---|---|---|
| `scripting.registerContentScripts` 只收文件路径 | ✅ 属实 | 所以不能用它注册内联源码 |
| MV2 `contentScripts.register({js:[{code}], world:'MAIN'})` | ✅ 可行 | `world` 与 `{code}` 都在该 API 的 schema 里（Mozilla bug 1736575，Firefox 128 起）；本项目是 nightly 152，满足。**仅查阅 Mozilla 源码 / bug 得出，未上真机验证** |
| MAIN 世界绕过页面 CSP | ✅ | Mozilla 明确「MAIN 世界执行按设计绕过 CSP」（bug 1587494 的结论）；Firefox 的内联 `<script>` 并不绕过，所以不能用 DOM 注入代替 |
| 选 MAIN 世界 | ✅ 合理 | `unsafeWindow === window`，与 WebView 的 `addDocumentStartJavaScript` 语义一致，运行时几乎不用改 |
| 每脚本内联代码 + 值快照，同步读值 | ✅ 唯一的同步方案，但有代价 | 见「已知限制」：快照有时效窗口、有体积上限、值变化要重注册 |
| 跨域请求 / cookie 走后台页 | ✅ | fetch 不受 CORS 限制；`cookies` API 比 WebView 的 CookieManager 更完整（HttpOnly、path、过期） |
| **存储走后台页** | ❌ 改了 | 若后台页自己存值，就和 WebView 标签用的 SharedPreferences 成了两份，必然分叉。现在**权威仍是原生**，后台页不存值，只做注册 / 请求 / 广播 |
| 通知 / 下载 / 菜单走 native messaging | ✅ 但改用**会话级端口** | 内容脚本经 `nativeMessagingFromContent` 直连原生，原生天然知道是哪个标签（开标签的 opener、菜单属于哪页、下载回调回到哪个框架）；后台端口只用于同步与广播 |
| （未提及）`matches` | ➕ 补充 | 不能每个脚本都注入每个页面。能转换的 `@match/@include` 转成 Gecko 的 `matches` 做预过滤，转不了（正则、`http*`…）放宽为 `*://*/*`，精确判断仍由运行时做 |
| （未提及）首次导航竞态 | ➕ 补充 | 注册是异步的，`loadUri` 前有「已同步」屏障（≤2s） |

## 二、架构

```
Kotlin GeckoGm（脚本 / 值 / 资源的唯一权威；按脚本缓存拼好的源码，只重拼变了的）
  ├─ 后台端口 mafariGmBg ──► background.js：contentScripts.register(world:MAIN) + 动态注册中继；xhr；cookie；bind 校验；值广播
  │        └─ 增量同步：sig 没变的脚本不再重发 code
  └─ 会话端口 mafariGm   ◄── relay.js(ISOLATED，仅在有脚本匹配的页面 / 框架加载)
                              ◄── 私有 MessageChannel ── shim(MAIN) ◄── mafari_gm_runtime.js
```

- shim 提供与 `GmBridge` 同名同签名的同步接口；桥对象作为**闭包变量**传入，页面拿不到。
- **惰性**：shim 在脚本真正用到桥（`ran` / 读写值 / 请求…）时才握手；匹配不上本页的脚本零成本。握手最多 3 次。
- **值**：读 = 内联快照（只读引用，不复制）+ 覆盖层 `ov`（本地写入、其它标签广播）。单值 > 64KB 或单脚本内联总量 > 256KB 的键不内联，键名列在 `S.big`：`GM.getValue` 经 `getValue` 协议异步拿到，同步 `GM_getValue` 首次返回缺失并预取。广播对大值只带 `big` 标记。
- **绑定**：通道接通后第一条消息是 `bind{sid,tok}`，中继交后台页校验（令牌 + 本框架 URL 落在该脚本匹配范围内），通过后端口才能收发；中继用绑定的凭据给每条上行盖章。**所有下行（值广播、getValue 结果、回调、菜单）只发给已绑定端口，且值广播只发给绑定了该脚本的中继**。伪造的端口既发不出请求，也收不到别的脚本的值。
- 值广播走后台页与中继之间的 runtime 端口，不再写 `storage.local`（省磁盘写与每帧的 `onChanged` 唤醒）。
- xhr：运行时本来就是 `xhrStart + xhrPoll`，shim 把结果存表；结果 60s 没被取走即淘汰，挂起请求上限 256。

运行时改动（`mafari_gm_runtime.js`，对 WebView 无影响）：① 桥取 `__mafariBridge || window.MafariGM`；② `GM_cookie.list` 优先走 `cookieListAsync`；③ 菜单变化调 `bridge.menuChanged()`；④ 命中后调 `bridge.ran(id)`（Gecko 没有 evaluateJavascript 可让原生来问）。

## 三、已实现 / 未实现

| 能力 | 状态 |
|---|---|
| GM_getValue/setValue/delete/list/Values、addValueChangeListener（含跨标签 / 跨内核） | ✅ |
| GM_xmlhttpRequest（headers、Cookie/Referer/Origin/UA 改写、anonymous、timeout、abort、redirect） | ✅ 代码 + 协议测试；改头与 Cookie 分区**待真机** |
| GM_cookie（list/set/delete，含 HttpOnly） | ✅ |
| GM_notification / GM_download / GM_openInTab / GM_setClipboard / 菜单命令 / 「本页运行脚本数」 | ✅ |
| `@require` / `@resource` | ✅ 沿用 `UserscriptEngine.buildSource` |
| `GM.openFileStream`（流式写文件）、`createBlobUrl` | ⛔ 未实现，运行时已有降级（`showSaveFilePicker`） |
| `onprogress` / 流式响应 | ⛔ fetch 版不支持 |
| 私有标签里的 GM_xmlhttpRequest / GM_cookie | ⛔ 故意禁用：后台页的 Cookie 罐不是私有会话的，会串 |
| 按标签开关脚本 | ⛔ 注册是全局的；无痕范围改由扩展的「允许在无痕中运行」控制（**待接**） |
| `redirect:'manual'` 取真实 3xx | ⚠ fetch 只给 opaqueredirect（status 0） |

## 四、已知限制

1. **值快照**：只反映注册那一刻；同一页面内靠广播 + 运行时 1s 轮询追平。大值不内联（见上），同步 `GM_getValue` 读大值首次会得到默认值——要可靠读大值请用 `GM.getValue`。值写入很频繁的脚本会触发该脚本（仅该脚本）重拼 + 重注册，已做 ≥1s 限频；写值另有按字节的限额（10s 内 16M 字符）。
2. **MAIN 世界没有页面拿不到的原生通道**：现在靠「中继先于脚本加载 + 捕获阶段截停握手事件 + bind 令牌与来源校验」兜底。**残余风险**：中继晚于 shim 加载（后台页保证先注册先执行，但这是 Gecko 内部顺序，未真机确认）时，先发出的那个 `hi` 没人截停，页面若已挂了 message 监听器，理论上能拿到端口并冒充中继收到 bind 里的令牌；令牌的权限仍受 @connect / 同站 / 限频约束，且只对该脚本有效。彻底解法是迁到 MV3 `userScripts` + `USER_SCRIPT` 世界（`configureWorld({messaging:true})`），代价是 `unsafeWindow` 要走 `wrappedJSObject` / `exportFunction`，且 `userScripts` 是仅可选权限、需要授权弹窗。
3. **MV2 的 `contentScripts` 不跨会话持久、API 已被 MV3 取代**：每次后台页启动由原生重新同步（已实现）；MV2 在 Firefox 上仍有承诺，但这是技术债。
4. 加载顺序：MAIN 脚本与中继谁先执行不保证，shim 有 `hi/ready` 握手 + 队列（两种顺序均已测）。

## 五、真机验证清单（这些我没法在无设备环境确认）

1. `ensureBuiltIn("resource://android/assets/mafari-gm-ext/", id)` 在 nightly 152 上成功；`nativeMessagingFromContent` + `session.webExtensionController.setMessageDelegate` 能收到内容脚本的 `connectNative`。
2. `contentScripts.register` 的 `world:'MAIN'` + `{code}` 在 GeckoView 上生效；`matchOriginAsFallback` 对 `about:blank` / `srcdoc` 子框架生效；大脚本（数百 KB）注册耗时。
3. webRequest 阻塞式改 `Cookie/Referer/Origin/User-Agent` 对扩展自己的 fetch 生效；后台页 fetch 带上的是不是「未分区」Cookie。
4. 首次冷启动：第一个页面是否能被脚本命中（`whenReady` 屏障是否足够）。
5. `./gradlew :app:compileDebugKotlin`：`GeckoGm.kt` 里 `ensureBuiltIn`、`MessageDelegate`、`Port` 的签名按 nightly 可能需要微调；本次新增的 `JSONObject(JSONObject, Array<String>)`（增量同步去掉 code）、`ConcurrentHashMap.merge` 也请一并编译确认。
6. 动态注册中继：`contentScripts.register({js:[{file:'relay.js'}], matchAboutBlank, matchOriginAsFallback})` 在 GeckoView 上生效；中继是否总先于同 document_start 的 MAIN 脚本执行（影响握手与上面的残余风险）。

## 六、测试

`tools/gm-ext-test/run.js`（`node run.js`；`RELAY_ORDER=late node run.js`）：用 vm 模拟 页面 ⇄ 中继 ⇄ 后台 ⇄ 原生，页面里跑真实的 `mafari_gm_runtime.js` + shim + 中继 + 后台页。覆盖：同步读快照、读己之写、值写入落原生（令牌由中继盖章）、跨标签广播与回声抑制、大值异步读取与广播、xhr、通知 / 下载回调（一次性）、HttpOnly cookie、菜单、`ran`、中继先后加载、惰性握手与握手上限、伪造端口（不 bind / 错令牌）发不出也收不到、xhr 表上限与 TTL、增量同步、无脚本时不注册中继。**不覆盖**真实 GeckoView 与 Kotlin。
