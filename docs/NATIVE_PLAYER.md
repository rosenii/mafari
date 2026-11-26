# 原生播放器（Media3 ExoPlayer）

目标：脱离 `float-player.js`，播放内核、界面、画中画都由原生承担；页面里只留一小段 App 自带的嗅探脚本。分阶段推进。

| 阶段 | 内容 | 状态 |
|---|---|---|
| 一 | 播放内核 + 最简播放页，宿主 `play` 操作可拉起 | 已完成（未在真机验证） |
| 二 | 与脚本一致的 miuix 播放界面（只做界面：卡片 / 全屏、控件、手势、倍速 / 清晰度弹层） | 已完成（未编译、未在真机验证） |
| 三 | 原生画中画 / 后台播放 | 已完成（未在真机验证） |
| 四 | 嗅探（WebView `shouldInterceptRequest`、Gecko webRequest 上报、精简注入脚本） | 已完成（未编译、未在真机验证） |
| 五 | 开关迁到 AppSettings，下载 / 投屏 / 外部播放器直连，删除脚本 | 待做 |

## 阶段一的结构（`player/`）

- `MafariPlayer`：ExoPlayer 封装，状态用 Compose State 暴露（播放 / 缓冲 / 进度 / 倍速 / 清晰度 / 错误）。界面无关，后续悬浮窗、画中画共用同一个实例。
- `PlayerRequest`：地址 + Referer / UA / Cookie。原生请求直接带头，没有跨域问题。
- `StripImageHeaderDataSource`：包在 HTTP 数据源外，裁掉伪装成图片的 TS 分片前缀。判定规则与 `download/HlsDownload.stripFakeImageHeader` 一致（`FakeImageHeader`，纯函数，有单测）。
- `NativePlayerActivity`：最简 Compose 播放页（点按显隐控件、双击左右 10 秒、进度条、倍速、清晰度、重试）。

## 触发（临时，阶段四前用）

脚本侧（需要 `@grant GM_mafariHost`）：

```js
GM_mafariHost('play', { url, title, referer: location.href, ua: navigator.userAgent, cookie: document.cookie, type: 'hls', pos: 0 })
```

`caps` 返回里多了 `play: true`。

## 已知取舍 / 待验证

- 网络层用 `DefaultHttpDataSource`（HttpURLConnection），没引 `media3-datasource-okhttp`：项目的 OkHttp 是 5.x，该模块按 4.x 编译，不确定二进制兼容。需要 HTTP/2 / 连接复用时再评估。
- 请求头对所有请求一视同仁（含跨域的分片 / 密钥）。和脚本现状一致，但 Cookie 会被带到分片域名；阶段二可按 host 限定。
- 清晰度只列出 ExoPlayer 判定为可解码的轨道；同高度只留最高码率。
- 阶段一离开界面（`onStop`）即暂停。

## 阶段二的结构（`player/`）

- `PlayerSurface`：播放界面本体，一比一对照 `float-player.js` 的 DOM —— scrim 暗角、中部 后退10 / 播放 / 快进10、底部 时间 + 胶囊进度条、左下静音、右下 倍速 / 画中画 / 全屏、左上 ✕（全屏时 ‹）、右上 ⋯ 菜单。手势：单击显隐，双击左 / 右 40% 快退 / 快进，双击中间播放 / 暂停，长按 2x（松手恢复），全屏时左右滑动快进 / 快退（整屏宽度 ≈ 3 分钟，滑动中屏幕中央显示目标时间，松手跳转；直播不响应）。3.8 秒无操作自动收起。
- `PlayerIcons`：脚本里的 SVG 路径原样搬过来，用 `PathParser` + Canvas 绘制，不依赖 Material Icons。
- `NativePlayerActivity`：竖屏是顶部 16:9 圆角卡片（✕ 关闭）+ 下方标题；全屏钮铺满并转横屏，‹ / 返回键退出。只有一处 `PlayerSurface` 调用，切换时界面状态不丢。
- `MafariPlayer`：新增 `muted` / `toggleMute()`。

### 阶段二没做的（按约定只做界面）

- 画中画、下载视频、复制视频链接：`PlayerSurface` 留了 `onPip` / `onDownload` / `onCopyLink` 三个可空回调，传 null 时按钮照常显示但灰掉（和脚本里 disabled 的样式一致）。
- 视频源列表、页内小窗拖动、投屏、外部播放器。

### 材质（miuix-glass）

- 按钮用 `GlassIconButton`，倍速 / 更多菜单用 `Modifier.glassPanel`，采样源是视频层的 `LayerBackdrop`（只录视频层，玻璃表面都在录制层外）。
- 图标优先用 `MiuixIcons.Glass.*`；Glass 图集里没有的 4 个（后退 10 秒、快进 10 秒、画中画、退出全屏）仍用 `PlayerIcons` 自绘。
- 视频改用 `TextureView`（`MafariPlayer.attach/detach`）。原因：Compose 的背景采样录的是 View 层级里画出来的内容。WebView 开硬件层之后能被采样，TextureView 同理；`SurfaceView` 的画面由系统合成器单独送显，不在这条绘制链里，采不到（Gecko 内核不采样也是这个原因）。
- 代价：TextureView 比 SurfaceView 略耗电，没有 HDR / DRM 直通；视频播放时每帧都会重录 + 重算模糊，低端机可能掉帧。需要真机确认，必要时给玻璃加开关或降低模糊。
- 进度条仍是自绘的胶囊条（和脚本一致）；miuix 的 `Slider` 外观不同，要统一的话可以换。

### 显示方式切换 / 音量

- ⋯ 菜单最下面的「显示方式」：Texture / Surface 二选一，默认 Texture。切换时视频 View 整个重建（`key(textureMode)`），播放不中断。`MafariPlayer.attach/detach` 同时支持 `TextureView` 和 `SurfaceView`。
- Surface 模式录不到画面：按钮 / 弹层的采样源传 null，miuix 玻璃自动退回不透明底（弹层用自带的 fallback 底）。
- 左下音量钮改为点开弹层：喇叭点一下静音 / 取消静音，滑条拖动 0–100%，右边显示百分比。调的是 ExoPlayer 自身音量（`MafariPlayer.volume`），叠在系统媒体音量之上，不改系统音量。
- 显示方式不持久化，每次打开播放页回到 Texture。

### 其他和脚本不一样的地方

- 圆角：Compose 的 `clip` 对 `TextureView` 是有效的（SurfaceView 不行），卡片圆角应该能正常显示，需真机确认。
- 方向：脚本没有方向逻辑；原生页改成按钮驱动（卡片锁竖屏，全屏转横屏），不再跟随传感器。

## 阶段四：嗅探（`sniff/`、`browser/TabSniff.kt`、`engine/gecko/GeckoSniff.kt`）

打开网页后，页面里出现视频时地址栏最左侧出现媒体图标，点开「发现视频」列表，点一条用原生播放器打开（不会主动弹窗）。不再依赖脚本侧的 `GM_mafariHost('play')`。

### 判定（`MediaSniff.classify`，纯函数，有单测）

- 认：HLS 清单（`.m3u8` / `.m3u`，或 Content-Type 是 `mpegurl` 一类）和整段文件（mp4 / m4v / mov / webm / mkv / flv，或 `video/*` 的 Content-Type）。
- 不认：分片（`.ts` / `.m4s` / `video/mp2t` / `video/iso.segment`）、DASH（原生内核只带了 exoplayer + hls）、音频、图片、`blob:` / `data:`。
- 已知长度小于 256KB 的整段文件不弹（多半是广告 / 预览 / 动图）；HLS 清单不受限。
- 地址有长度上限（4096）；同页去重（不含 `#片段`），最多记 12 条（`SniffList`）。

### 两条通道

- **WebView**：`shouldInterceptRequest` 里对非主框架请求按地址判定（热路径只做字符串判断，不拷贝、不读响应）。
  补充脚本 `assets/mafari_sniff.js`（文档开始注入，只在顶层框架跑）补三种拦截看不到的：没有后缀、靠响应头判断的地址（hook `fetch` / XHR 只读 `content-type`）、`<video>` 实际在播的地址（`loadstart` / `play` 捕获）。
  报告走单向消息桥 `MafariSniff`（`OneWayBridge`，只受理主框架 http(s) 来源，长度有上限）；系统 WebView 不支持 WebMessageListener / 文档开始脚本时只剩请求拦截这一路。
  iframe 里的播放器：请求拦截覆盖（不分框架），页面脚本报告不覆盖（桥只收主框架）。
- **Gecko**：内置扩展 `mafari-gm-ext`（0.4.4）后台页用 `webRequest.onHeadersReceived`（非阻塞，只读响应头）筛出疑似视频，
  300ms 一批上报 `{t:"sniff", items:[{top,url,ct,len}]}`；206 分段响应以 `Content-Range` 的总长度为准。
  原生 `GeckoSniff` 按页面顶层 URL 存（和 `GeckoNetLog` 同样的取舍：两个标签打开同一个地址时共用一份）。
  开关关闭时后台页把监听整个卸掉（`sniffRules`，hello 时和开关变化时推送）。

### 界面

- 页面里出现**新的**视频地址 → 等 0.9 秒（让同时到的其他清晰度 / 线路一起列出）→ 开了「自动悬浮播放」就自动打开合格的正片，否则什么都不做；「发现视频」对话框（`SniffDialog`，走材质抽象层）只在点地址栏媒体图标时打开，不再主动弹出。
  同一批地址不重复打扰；关掉之后同页又发现新的才会再弹。换页 / 换标签自动收起并清空。
- 点一条 → `BrowserTab.playSniffed`：Referer = 当前页地址，UA = 当前标签的 UA，Cookie = 该标签 Profile 的 Cookie（无痕用无痕 Profile；Gecko 经后台页取，放后台线程）；
  列表里其余地址作为「视频源」一起交给播放页，可切换。
- 设置 → 高级 →「嗅探网页视频」（`AppSettings.mediaSniff`，默认开；`MediaSniff.enabled` 是热路径读的静态镜像）。关闭后 WebView 不再判定，Gecko 卸掉监听；页面脚本仍会注入，但原生丢弃它的消息。

### 已知取舍 / 待验证

- 弹窗是自动弹出的模态对话框。关掉后没有常驻入口（没有地址栏 / 菜单按钮）重新打开，只能等同页出现新的源；需要的话再加一个入口。
- 请求拦截拿不到响应头，所以 WebView 下没有后缀又没走 fetch / XHR 的地址（比如直接作 `<video src>` 的 `/stream/42`）只能靠页面脚本的 `<video>` 监听；iframe 里的这类地址抓不到。
- `<video>` 用 MSE（`blob:`）播放的站点，靠抓它背后的 m3u8 / mp4 请求；纯 MSE 分片流（没有清单）抓不到。
- 页面脚本 hook 了 `fetch` / `XHR.open`，函数的 `toString()` 不再是 `[native code]`；指纹保护 / 反检测要求高的站点可以关掉「嗅探网页视频」。
- 脚本侧的悬浮播放器（`float-player.js`）没有动，两边可能同时出现提示；阶段五删脚本时一并处理。
- 未编译、未真机验证：Kotlin 里只编译检查了 `MediaSniff`（纯逻辑），其余按现有 API 对照写；`tools/gm-ext-test` 通过，扩展里的嗅探监听用 Node 模拟过。

### X（x.com / twitter.com）视频（`sniff/XVideo.kt`、`mafari_sniff.js` 的「X」一节）

X 网页端 `<video>` 是 `blob:`（MSE），背后请求 `video.twimg.com` 的 HLS（主清单 `/pu/pl/<name>.m3u8`、子清单 `/pu/pl/<codec>/<宽x高>/…`、音频清单 `/pu/pl/mp4a/…`、fMP4 的 `/init/` 与 `.m4s` 分片）。各档 mp4 直链只在接口 JSON 里。

- **直链来源**（仅 WebView）：页面脚本 hook 的 `fetch` / XHR 在响应属于 X 自己的接口（`/i/api/graphql|2|1.1/`）、当前在状态页、响应是 JSON 且含 `"video_info"` 时才 `JSON.parse`（上限 6MB），取 `rest_id == 当前 status ID` 的那条推文的 `legacy.extended_entities.media[].video_info`（转推取被转推文）；时间线 / 回复里别的推文不上报。
  上报顺序：HLS 主清单，再按码率从高到低的各档 mp4（消息带 `d` 时长、`pv`（`animated_gif` 为 true）、`br` 码率）。同一页通用的「接口 JSON 找视频地址」和内联脚本扫描在 X 上关掉（里面是整条时间线，会把没在看的视频都报上来）。
- **站内跳转**：X 是单页应用，不会触发「页面开始加载」。接口响应到来时发现 `location.pathname` 变了（以及 `popstate`）→ 脚本重置自己的去重状态并发 `{reset:1}`，`onSniffMessage` 清空本页嗅探列表。
- **判定去噪**（`MediaSniff.classify` 先过 `XVideo.isNoise`，两个内核都生效）：`video.twimg.com` 上路径含 `/mp4a/`（音频清单）、`/init/`，或 `/pl/` 下的 `.mp4`（初始化段）不认。WebView 的请求拦截拿不到长度，挡不住初始化段，只能靠路径。
- **列表去重**（`SniffList.offer`）：同一个媒体 ID（路径里 `ext_tw_video|amplify_video|dm_video/<id>`）的主清单在列表里时，子清单不再收；主清单后到则收掉已列的子清单。列表满（12）时，新的 X 地址挤掉最早的一条 X 地址（时间线自动播放的视频会占满列表）；非 X 地址满了照旧不收。
- **标签**：`SniffedMedia.label`（`XVideo.label`：地址路径里的分辨率 + 接口给的码率，如「720×1280 · 2.2 Mbps」）显示在「发现视频」行的详情里，也进播放页「视频源」的名字。
- 取舍：Gecko 没有页面脚本，拿不到接口 JSON，只有请求监听到的 HLS 主清单（去噪 / 去重照常）；`/pl/`、`/init/` 的路径规则按已知的 X 路径布局写，X 改布局后需要跟着调；未带登录态时部分推文（敏感内容、受限账号）接口不返回 `video_info`，此时只剩播放后请求到的主清单。

## 投屏（`player/NativeCast.kt`）

入口：悬浮卡片下方的快捷菜单「投屏」（全屏 / 画中画里用 ⋯ 菜单）。面板是抽象层底部弹窗 `AppBottomSheet`，内容 `CastPickerBody` 和脚本悬浮播放器用的 `CastPickerDialog` 共用，状态在 `NativeCast.pickerUi`。
抽象层的弹窗要挂在 miuix `Scaffold` 的弹窗层上，而播放页平时是只有卡片高的窗口、没有 Scaffold：
点投屏时 `castSheetHost = true` → 窗口撑满（背景透明）并挂上透明 Scaffold 当宿主 → 弹窗滑出；
收起动画结束（`onDismissFinished`）再还原成卡片窗口。直接用 `CastPickerDialog` 在播放页里点了什么都不显示。
选项：

- **DLNA 设备**：`MediaHost.discoverDevices`（SSDP，绑 Wi-Fi 网络直连）搜索 → 选中后本地暂停，电视从当前进度 `setUriAndPlay`；
  每秒 `query` 轮询进度，播放页盖 `CastOverlay` 遥控（后退 / 播放·暂停 / 快进 10 秒 / 结束投屏）。
- **屏幕镜像**：`MediaHost.openSystemCast`（各厂商「无线显示」页，兜底系统 CAST_SETTINGS）。
- **外部打开**：`MediaHost.launchExternal`（带 UA / Referer / Cookie 的 VIEW chooser）。
- 结束（点结束 / 电视停止 / 播完 / 失联 / 关闭播放页）：电视 `Stop`；本地 seek 回电视上的进度并保持暂停。
- 搜索 / 镜像 / 外部打开三个入口在 `MediaHost` 里改成 `internal` 并加了 `beforeLaunch` 参数（脚本宿主用来 3 秒内不自动进画中画，
  播放页用自己的 `suppressPip()`），脚本侧行为不变。
- Android 17 的「附近设备」权限由 `NativePlayerActivity.withLocalNetworkPermission` 申请（和 `MainActivity` 同一套）。
- 面板打开时播放页窗口临时铺满（背景仍透明），避免面板被「只有卡片那么高」的窗口裁掉。
- 限制：DLNA 由电视自己拉流，带不了 Referer / Cookie，防盗链源会失败（25 秒没开始播放会提示）；blob: / data: 流没有可投的地址。

## 快捷菜单与截屏

悬浮卡片（非全屏 / 非画中画）下方、标题上方有一排快捷菜单（`NativePlayerActivity.QuickActions`）：投屏 / 外部播放 / 下载 / 截屏 / 倍速 / 清晰度 / 视频源。
设置 → 媒体嗅探与播放 → 快捷菜单 里总开关和各按钮开关（`PlayerPrefs.quickItems`，开页时读一次）。
- 清晰度：点开后换成「自动 + `MafariPlayer.qualities`」，只有一档时灰掉。
- 倍速：点开后这一排换成倍速选项（`PlayerPrefs.SPEEDS`），选完恢复；按钮上显示当前倍速。
- 截屏：`MafariPlayer.captureFrame` 从当前接着视频输出的 View 取帧——TextureView 用 `getBitmap(w, h)`，SurfaceView 用 `PixelCopy`；
  分辨率取视频原始大小（长边最多 3840）。保存走 MediaStore（图片/Mafari，minSdk 33 免存储权限）。
  受保护（DRM）内容拿到的是黑帧 / 失败，还没出画面时 `videoSize` 为 0，都按失败提示。

## Media3 高级选项（`player/PlayerTuning.kt`）

设置 → 媒体嗅探与播放 → 页面最下面「Media3 高级选项」。取值存在 `mafari_settings`（键 `player_adv_*`），播放器每次打开时读一次，改了下次打开生效；不动设置 = Media3 自己的默认行为（缓冲「均衡」档 = 15s / 60s / 1s / 2s）。内核已升到 Media3 1.12.0-alpha01（minSdk 33 满足其 minSdk 24 要求）。

| 分组 | 选项 | 落到哪 |
|---|---|---|
| HDR 与画质 | HDR 策略（自动 / 优先 SDR / 优先 HDR） | `MafariPlayer.applyHdrPreference`：流里 HDR / SDR 轨道并存时，给视频组加轨道子集覆盖（自适应仍有效）；用户手动选清晰度后不干预。清晰度列表里 HDR 轨道带「HDR」后缀 |
| | 最高清晰度（不限 / 2160 / 1440 / 1080 / 720 / 480p） | `DefaultTrackSelector` 参数 `setMaxVideoSize(长边, 长边)`，横竖屏都适用；手动选更高档不受限 |
| 解码 | 解码器偏好（自动 / 硬件优先 / 软件优先） | 自定义 `MediaCodecSelector`：只重排候选，不删候选 |
| | 解码器回退 | `DefaultRenderersFactory.setEnableDecoderFallback` |
| | MediaCodec 异步队列（自动 / 强制开 / 强制关） | `forceEnable/DisableMediaCodecAsynchronousQueueing` |
| | 隧道播放 | `DefaultTrackSelector.Parameters.Builder.setTunnelingEnabled`；只在打开时渲染方式为 SurfaceView 才开（TextureView 下画面出不来） |
| 缓冲与内存 | 缓冲策略（省内存 / 均衡 / 流畅优先 / 大缓冲） | `DefaultLoadControl.setBufferDurationsMs`；省内存档在大小「自动」时附带 48 MB 上限 |
| | 缓冲大小上限（自动 / 32 / 64 / 128 / 256 MB） | `setTargetBufferBytes` |
| | 后向缓冲（不保留 / 15 / 30 / 60 秒） | `setBackBuffer(ms, true)` |
| 其他 | 拖动定位（精确 / 就近关键帧） | `SeekParameters.EXACT / CLOSEST_SYNC` |
| | 网络超时（默认 / 严格 / 宽松） | `DefaultHttpDataSource` 连接 / 读取超时 |
| | 管理音频焦点、拔出耳机时暂停 | `setAudioAttributes(.., handleAudioFocus)`、`setHandleAudioBecomingNoisy` |
| | 拖动进度时实时预览（1.12 起） | `ExoPlayer.setScrubbingModeEnabled`：`SeekTrack` 拖动期间 `MafariPlayer.scrubTo`，松手 / 取消 `endScrub` 后再 `seekTo` 精确定位 |
| 缓冲与内存 | 只加载选中的轨道 | `DefaultMediaSourceFactory.setLoadOnlySelectedTracks`（1.12 起库里默认 true） |
| 稳定性与实验 | 卡死检测（默认 / 宽松 / 关闭检测） | `ExoPlayer.Builder.setStuckPlayingDetectionTimeoutMs / setStuckPlayingNotEndingTimeoutMs / setStuckBufferingDetectionTimeoutMs` + `Flags.FLAG_ENABLE_STUCK_PLAYING_DETECTION`；报错 cause 是 `StuckPlayerException`，`describeStuck` 翻成人话 |
| | Media3 灰度特性 | `Flags.setCanaryModeEnabled`（库里默认开） |
| | 加快首次起播 / 快速精确定位（实验，默认关） | `Flags.FLAG_PLAY_PAUSE_AUDIO_TRACK_ON_INIT` / `FLAG_ENABLE_MEDIACODEC_BUFFER_DECODE_ONLY`（API 34+） |

### 取舍 / 待验证

- 未编译、未真机验证（和前几个阶段一样）；1.12 新增的 API 已对照 `androidx/media` 1.12.0-alpha01 源码核对过签名；`Flags` 是进程级静态状态，每次开播放器前由 `applyMedia3Flags` 显式重设；纯逻辑（档位表、取值收敛）有单测 `PlayerTuningTest`。
- HDR 策略只对多清晰度流起作用；1.12 起 HLS `VIDEO-RANGE`（PQ / HLG）和 DASH CICP 描述会解析进 `Format.colorInfo`，`isHdr` 因此能识别这些流；单个 mp4 没得选。HDR 直出需要 SurfaceView，TextureView 下 HDR 片源可能发灰 —— 暂时没做「检测到 HDR 自动切 SurfaceView」。
- 隧道播放在播放页里临时切回 TextureView 不会重建播放器，此时需要在设置里关掉它。
- 快捷菜单 / 标题区底色不透明度见 `NativePlayerActivity.CARD_SURFACE_ALPHA`。

## 原生播放器播放时停住网页媒体（`browser/PageMediaGate.kt`）

原生播放器「想播放（含缓冲中）且没播完 / 没出错」期间，所有标签里的网页音视频一律暂停并拦住，不再和原生播放器同时出声。
暂停 / 播完 / 出错 / 关掉播放页后解除（页面媒体不会自动恢复，由用户自己点播放）。

- 触发在 `MafariPlayer`（`onPlayWhenReadyChanged` / `onPlaybackStateChanged` / `release`），由 `PageMediaGate` 记录持有者：切换媒体时新播放页先于旧页销毁，旧页释放不会把新页的状态解除。
- 拦截复用「主页盖住页面」那一套（`BrowserTab.applyMediaBlock`）：Gecko 在 `MediaSession.onPlay` 里补暂停，WebView 用脚本暂停现有媒体并在捕获阶段拦 `play` 事件。
- WebView 换文档后脚本里的拦截标记会丢，`onPageFinished` 里在播放期间重新套用；Gecko 的拦截标记在原生侧，新建引擎时直接继承。
- 限制：WebView 页面在换文档到 `onPageFinished` 之间若自动开播，会短暂出声再被暂停；未编译、未真机验证。

## 防盗链、提前嗅探、跟随换集

**防盗链请求头**：播放带 Referer / Origin / UA / Cookie。WebView 的请求拦截会记下页面加载这条地址时真正带的 Referer / Origin（`SniffedMedia.referer / origin`），
iframe 里的播放器带的是播放器页地址而不是顶层页，播放时优先用它，没有再退回顶层页地址；Origin 没有时从 Referer 推出（和浏览器对跨域媒体请求的行为一致）。
Gecko 的 `onHeadersReceived` 看不到请求头，仍用顶层页地址。Cookie 仍是该标签对「视频所在域名」的 Cookie，对所有请求（含别的域名的分片）一视同仁。
不覆盖：要求特定自定义头（如 `X-Requested-With`、签名头）、一次一密 / 限时且绑定 IP 的地址、需要页面脚本动态算出请求头的源。

**没点播放也能嗅探到**（`mafari_sniff.js` 新增，只读）：页面加载后扫三遍（解析完 / load / 再过 1.5、4 秒）：
`<video>` / `<source>` / `og:video`、苹果 CMS 的 `window.player_aaaa`（含 encrypt=1 / 2 解码）、内联脚本里的 `.m3u8 / .mp4 …` 地址（含 `\/` 转义）；
fetch / XHR 返回的小 JSON（≤200KB，最多 8 个）里的视频地址。限制：播放器是点击后才由脚本拼地址 / 请求接口的站点，仍然要点了才抓得到。

**跟随页面**（`BrowserDialogHost`）：播放器开着（或开了自动悬浮播放）时，用户在页面里手动点播放的视频改由原生播放器播。
WebView：脚本在 `<video>` 开播、fetch / XHR 拿到清单时带上 userActivation / 5 秒内触摸标记（`ua`），原生看到 `userPlayed` 就切；
Gecko 没有点击信号，只认「上次拉起播放器之后新出现」的合格正片。已经在播的地址、已经拉起过的地址不重复开。

## 画面比例 / 外部播放

- **画面比例**（⋯ 菜单 → 画面比例，`PlayerPrefs.aspectMode`，改了即存）：适应（默认，保持原比例留黑边）/ 裁剪铺满（保持比例放大到盖住画面，超出部分裁掉）/ 拉伸（不保比例拉满）/ 16:9 / 4:3（强制比例再适应窗口）。实现在 `PlayerSurface`：只改视频 View 的尺寸，容器 `clipToBounds`。
  - 待真机确认：「裁剪铺满」在 SurfaceView 渲染方式下，View 比容器大时 Compose 的裁剪对 SurfaceView 不一定生效（TextureView 可以）。
  - 截屏取的是视频 View 的画面，裁剪铺满 / 拉伸下截到的是被放大（或拉伸）后的整幅画面。
- **外部播放**（快捷菜单第二个按钮，id `external`，设置里可单独关）：`NativePlayerActivity.openExternal` 弹出系统「用其他应用打开」选择器（排除本 App）。网络地址走 `MediaHost.launchExternal`（带 UA / Referer / Cookie / 当前进度）；本地文件走 VIEW + 临时读权限。拉起前暂停本地播放，3 秒内不自动进画中画。
