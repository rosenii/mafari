# 脚本宿主能力：系统画中画 / DLNA 投屏 / 外部播放器

WebView 和 Gecko 都没有系统画中画，也没有 Remote Playback（Cast），所以「悬浮媒体播放器」这类脚本原来只能退化成页内小窗，
投屏只能靠 `intent://` 碰运气。运行时因此多提供一个 GM 函数：

```js
// @grant GM_mafariHost   （GM4 写法：GM.mafariHost）
GM_mafariHost(op, args, onEvent) -> Promise<结果 | null>   // null = 宿主不支持，脚本退回原行为
```

| op | 说明 |
|---|---|
| `caps` | 能力探测 → `{api:1, engine, pip, cast, ext}`；不是主界面（如桌面轻应用 WebAppActivity）回 `{api:0}` |
| `sub` | 订阅宿主事件（传 `onEvent`，常驻）：`{ev:'pip',on}` / `{ev:'act',a:'play'｜'pause'｜'back'｜'fwd'}` / `{ev:'cast',state,...}` |
| `pip` `{w,h,playing}` | 进入系统画中画。**调用前页面必须已经全屏**，小窗里才只剩画面 |
| `arm` `{on,w,h}` | 上报「全屏且正在播放」；为 true 时按 Home / 手势返回桌面自动进画中画（Android 12+ 走 `setAutoEnterEnabled`） |
| `pipState` `{playing}` | 刷新小窗操作栏的播放 / 暂停图标 |
| `cast` `{url,type,title,referer,ua,cookie,pos}` | 弹原生面板：DLNA 设备 / 屏幕镜像（系统投屏）/ 外部播放器。状态经 `cast` 事件回来 |
| `castCtl` `{a:'play'｜'pause'｜'seek'｜'stop',pos}` | 控制正在投的设备 |
| `external` `{…同 cast}` | 外部播放器选择器，带 `headers`（UA / Referer / Cookie）、`title`、`position` extras |
| `release` | 播放器关闭 |

## 通道

- WebView：`MafariGM.host(op, json, cbId)`（`GmBridge.host`）→ 结果 / 事件经 `evaluateJavascript` 调 `window.__mafariHostCallback_<cbId>(kind, json)`。
- Gecko：shim `bridge.host` → 中继（`NATIVE` 白名单加了 `host`）→ `GeckoGm` 的 `"host"` 消息 → 回包 `{t:'cb'}`。
  `__mafariHostCallback_*` 是**常驻**回调（订阅），不像下载 / 通知回调那样调用一次就摘掉。
- 两条通道共用 `script/MediaHost.kt`；DLNA 协议在 `script/DlnaCast.kt`（纯 JDK，无 Android 依赖）。

## 画中画怎么工作

Android 的画中画是整个 Activity 缩成小窗。播放器先 `host.requestFullscreen()`（WebView 走 `onShowCustomView` 全屏容器，
Gecko 走 `onFullScreen`，两者都会盖住应用自己的界面），再 `pip`。小窗里脚本给 wrapper 加 `iosgp-pip`，隐藏全部自绘控件，
改由系统操作栏（后退 10 秒 / 播放·暂停 / 快进 10 秒）经 `act` 事件驱动 `<video>`。

和已有逻辑的关系：
- `MainActivity.onStop` 会暂停所有媒体（Gecko 还有 `pause-hidden.js`）。进画中画只走 `onPause`，页面仍可见，不受影响；
  关闭小窗（✕）走真正的 `onStop`，照常暂停。`keepPlayingOnStop()` 只兜 700ms 内的过渡性 `onStop`（个别系统会这样）。
- 拉起外部播放器 / 系统投屏前 3 秒内不会自动进画中画（`startActivity` 本身会触发 `onUserLeaveHint`）。

## 投屏

SSDP 组播搜索（`MulticastLock` + 绑到 Wi-Fi 网络，开着 VPN 也能搜到）→ 读设备描述 → AVTransport SOAP。
播放中每秒轮询进度，经 `cast` 事件回传，脚本把进度条切到电视的进度，播放 / 快进 / 拖动 / 停止都转成 `castCtl`。

已知限制：
- DLNA 由电视自己去拉流，**带不了 Referer / Cookie**。防盗链源会失败（25 秒没开始播放会提示）；这类源用「外部播放器」。
- blob: / data: 流（MSE）没有可投的地址。
- 没有 H.265 / 某些封装的支持取决于电视。

## 测试

- `tools/userscript-test/media-host.test.js`：在 Chromium 里用模拟宿主跑脚本，覆盖画中画 / 投屏 / 外部播放器 / 无宿主退化。
- `tools/dlna-test/DlnaFake.kt`：用假 SSDP + 假 AVTransport 设备测 `DlnaCast`（`kotlinc` 编译，见文件头）。

## 事件隔离（防页面的隐形广告层）

播放器是页面里的普通元素，点它的按钮 / 进度条时，页面挂在 `window` / `document` / `body` 上的全局点击监听（尤其是捕获阶段的）
也会收到事件，不少站点借此在任意一次点击时弹隐形广告、重定向到垃圾站。脚本在 document-start 先于页面脚本运行，因此：
- 包装 `EventTarget.addEventListener / removeEventListener`：页面在全局目标上注册的 click / mousedown / pointerdown / touchstart 等监听，
  遇到来自播放器 / 小球的事件直接跳过；`document.onclick = …` 这类属性写法同样包装。
- host / 小球自己在冒泡阶段 `stopPropagation`。
只隔离来自脚本元素的事件，页面其它位置的点击不受影响。测试：`tools/userscript-test/float-player-isolation.test.js`。
