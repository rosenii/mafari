# Changelog
## [2.1.91]
- 优化：冷启动提速。去掉启动闸门里对 WebView 异步预热的等待（实测预热回调可能始终不来，旧版因此每次冷启动固定白等 1.5 秒才放行）；WebView 预热改为只做后台线程部分，不再占首帧主线程。
- 优化：本机库密钥若仍是旧套件（ML-KEM 混合套件）封装的，库就绪之后在后台用当前本机套件重封（回读校验一致才替换），之后每次冷启动解包不再有数百毫秒的开销。
- 优化：通知权限申请从 Activity 创建时挪到首屏稳定之后；默认内核为 Gecko 时不再预读 WebView 专用的注入脚本。
- 新增：悬浮播放器（卡片态）可以移动位置：长按卡片下方的快捷菜单或视频标题区，再上下拖动，整个播放器窗口跟着移动，松手停在哪就在哪。移动范围限制在屏幕内；进入全屏 / 画中画 / 打开「投屏到」面板时按原样铺满，退回卡片后仍回到拖动后的位置。
- 新增：原生播放器全屏时支持左右滑动快进 / 快退。整屏宽度对应约 3 分钟（视频不足 3 分钟就按整段时长），滑动过程中屏幕中央显示目标时间和偏移秒数，松手后跳转；开了「拖动进度时实时预览」时边滑边预览画面。直播、时长未知、长按倍速中、弹层开着时不响应；不影响单击 / 双击 / 长按和进度条拖动。
## [2.1.90]
- 新增：系统分享菜单里也能选 Mafari 来打开 HTML / 文本文件和本地视频（原来只有「打开方式」里有）。支持HTML / MHTML / TXT / MD / LOG 。
- 调整：开启媒体嗅探后不再主动弹出「发现视频」提示，无论是否开启「自动悬浮播放」；想看本页发现的视频，点地址栏的媒体图标手动打开。「自动悬浮播放」的说明文案同步更新。
- 修复： 解决了一些已知的问题
## [2.1.88]
- 新增：Gecko调试内新增渲染方式选项，可选 SurfaceView/TextureView
- 新增：Gecko在切换至TextureView渲染时，网页浏览支持高级材质和各种模糊及动效，但性能表现不如SurfaceView
- 修复：gecko内核下使用必应搜索引擎无法加载更多页面问题
## [2.1.86]
- 优化：沉浸式浏览体验升级
- 新增：「发现视频」弹窗每一行加媒体时长（如 `MP4 · 域名 · 12.3 MB · 5:32`，直播显示「直播」）。时长来源：页面里 `<video>` 报的时长；报不出的（HLS / 没经过 `<video>` 的文件，以及 Gecko 内核）由后台探测：HLS 取清单累加 EXTINF（主清单再取第一条子清单，没有 ENDLIST 视为直播），整段文件用 MediaMetadataRetriever 读容器头，请求带当前页的 Referer / UA / Cookie；弹窗打开时才探，结果出来后这一行自动补上，探不出来就不显示。
- 调整：「自动悬浮播放」不再什么都播，只自动打开「正片」。网页里常见的预览短视频（十几秒的循环封面）和页面内嵌的动态小元素（用 mp4 / webm 当 GIF 的动图、背景循环）不再自动弹出播放器。判定：① 页面脚本给每个 `<video>` 打标记——`loop`、渲染尺寸很小（宽 < 220 或高 < 124）、不可见（display:none / visibility:hidden / 透明）的当预览 / 装饰；② 时长短于 40 秒的不自动播（先用上面的方式探时长，最多等 4 秒）；③ 时长探不出来时：HLS 放行，整段文件要已知大小 ≥ 2 MB；④ 多个候选时选已知时长最长的，同一页自动播过的不再重复拉起；⑤ 用户自己在页面里点了播放的视频不受时长限制（但仍排除页面标记为预览的）。没有合格的就什么都不弹，地址栏的媒体图标里仍能手动打开列表里的任何一条。关闭「自动悬浮播放」时行为不变（仍弹「发现视频」列表，列表里不过滤）。
- 已知限制：Gecko 内核没有页面脚本，拿不到 `<video>` 的循环 / 尺寸标记，自动悬浮播放只按时长和大小过滤。
- 调整：原生播放页卡片下方的快捷菜单 / 标题区不再固定黑色，改用材质底色并跟随「设置 → 外观」的显示模式（浅色 / 深色 / 跟随系统）和「高级材质」（关闭模糊时为实色，其余为半透明底 + 细描边）；图标、文字、分隔线、倍速 / 清晰度选项按钮的颜色随主题。卡片上的视频区与视频上叠的控件、投屏遥控层仍固定深色。浅色模式下卡片态状态栏图标改为深色。
- 调整：快捷菜单继续缩小：图标 19dp → 16dp、文字 10.5sp → 9.5sp、按钮上下内边距 5dp → 3dp，倍速 / 清晰度选项排同步压低；标题 14sp → 13sp、上下内边距 12dp → 8dp。
- 修复：原生播放页的「投屏到」弹窗背景是黑色。原因：播放页自己强制深色主题，又没有带上「高级材质」设置；现在播放页和主界面一样读取显示模式与材质设置，弹窗走同一套 AppBottomSheet 材质，浅色 / 深色随主题。
- 新增：嗅探到页面存在媒体时，地址栏最左侧显示媒体图标（媒体嗅探总开关 / 「嗅探媒体播放」关闭时不显示）；点击弹出「发现视频」弹窗，列出本页所有媒体，点一条用原生播放器打开。播放器已开着时再点另一条，会先关掉旧播放页再打开新的（切换播放，不会叠出两个播放器）。图标与翻译 / 下载 / 阅读 / 脚本图标共用最左侧同一个位置，优先级 下载 > 媒体 > 翻译 > 阅读 > 脚本；悬浮栏、固定栏、Safari 三段式三种导航栏样式都支持。换页后随本页嗅探结果清空而消失。
- 调整：设置里「嗅探网页视频」更名为「媒体嗅探与播放」，并作为单独页面放在「脚本管理」下方（设置 → 高级 → 媒体嗅探与播放）。页面内容：
  - 总开关：一键停用嗅探、悬浮媒体播放器 / M3U8 媒体下载器脚本、自动画中画、自动悬浮播放和脚本宿主能力（关闭时记住「是总开关停的」，重新打开只恢复这些脚本，不会擅自打开用户自己停用的）。
  - 嗅探媒体播放（原「嗅探网页视频」开关）、退出自动画中画（新增，按 Home / 手势返回桌面时全屏播放自动进画中画）、自动悬浮播放（新增，默认关：嗅探到视频直接用原生悬浮播放器打开，不再先弹提示）。
  - 播放器分组（新增）：渲染方式（TextureView / SurfaceView）、默认倍速、长按倍速（1.5x / 2x / 3x / 4x）、后台播放；播放页菜单里改显示方式 / 后台播放会同步保存。
  - HLS 转 MP4：从下载页移到本页。
  - 「网页增强」总开关现在同样管这个页面：WebView 内核下网页增强关闭时，本页功能整体不运行（子开关置灰，页面提示）；Gecko 内核没有网页增强开关，不受影响。
  - 已补英文 / 繁体中文文案。
- 修复：原生播放页点「投屏」没有反应。原因：「投屏到」面板用的是 miuix 玻璃弹窗，它要挂在 Scaffold 的弹窗宿主里，播放页没有这一层，面板状态已经打开却画不出来。现在点投屏时播放页临时撑满窗口并挂上 Scaffold 当弹窗宿主，用抽象层的底部弹窗（`AppBottomSheet`）显示「投屏到」，设备列表 / 屏幕镜像 / 外部打开和主界面共用同一份内容（`CastPickerBody`），收起动画结束后窗口还原成卡片。
- 新增：悬浮播放器卡片下方、标题上方加一排快捷菜单（图标 + 文字）：投屏、下载、截屏、倍速、清晰度（复制链接仍在 ⋯ 菜单里）。行高调低（图标 19dp、文字 10.5sp）。设置 → 媒体嗅探与播放 → 快捷菜单 可整排关掉，也可逐个关按钮。清晰度点开后这一排换成「自动 + 各档」，只有一档时灰掉。截屏截取当前正在播放的画面（TextureView 读纹理、SurfaceView 走 PixelCopy，按视频原始分辨率，长边最多 3840），保存到 图片/Mafari；倍速点开后这一排换成倍速选项（0.5x–3x），当前倍速会显示在按钮上；受保护内容或还没出画面时提示截屏失败。全屏 / 画中画里不显示这排菜单（⋯ 菜单里仍有投屏 / 下载 / 复制链接）。
- 新增：原生播放器接入投屏（原来只有脚本悬浮播放器能用）。播放页右上角 ⋯ 菜单新增「投屏」，弹「投屏到」面板：局域网 DLNA 设备 / 屏幕镜像 / 外部打开（复用 MediaHost 里的搜索、镜像和外部播放器逻辑，协议仍是 DlnaCast）。选 DLNA 设备后本地暂停、电视从当前进度开始播，播放页盖一层遥控（后退 / 播放·暂停 / 快进 10 秒 / 结束投屏，进度每秒向电视轮询）；结束投屏（点结束 / 电视上停止 / 播完 / 失联 / 关闭播放页）时电视停止，本地回到电视上的进度并保持暂停。拉起系统投屏 / 外部播放器前 3 秒内不自动进画中画。已知限制同脚本版：DLNA 由电视自己拉流，带不了 Referer / Cookie，防盗链源会失败，可改用「外部打开」。
- 修复：原生悬浮播放器打开时是整屏全黑的页面、播放界面只在顶部一张卡片。现在播放页是透明窗口，卡片态只占顶部卡片那么高并透出下面的网页（卡片以外的触摸交给下面的页面）；全屏 / 画中画仍是黑底铺满。
- 调整：不再把「悬浮媒体播放器」脚本列入推荐脚本（App 已自带原生嗅探和悬浮播放）；已安装的不受影响。
- 新增：原生播放器阶段四「嗅探网页视频」——网页里出现 m3u8 / mp4 等视频时弹出「发现视频」提示，点一条用原生播放器打开（带当前页的 Referer / UA / Cookie，其余地址可作为视频源切换）。WebView：`shouldInterceptRequest` 按地址判定 + 精简页面脚本补响应头 / `<video>` 地址；Gecko：内置扩展 `webRequest.onHeadersReceived` 上报。设置 → 高级 →「嗅探网页视频」可关。详见 docs/NATIVE_PLAYER.md。
- 新增：原生悬浮播放器补齐阶段三功能：
  - 画中画：播放页右下角 PiP 按钮进入；播放中按 Home / 手势返回桌面自动进入；小窗操作栏 后退 10 秒 / 播放·暂停 / 快进 10 秒；点小窗 ✕ 结束播放。
  - 后台播放：切到后台 / 锁屏继续播放（前台服务 + 媒体通知，通知栏 / 锁屏可暂停、快进退、关闭）；菜单「后台播放」可关（关闭后离开界面就暂停）。
  - 下载视频（走 App 下载器，m3u8 自动合并）、复制视频链接、视频源切换（脚本 `play` 操作可传 `sources:[{url,label,type}]`，多于一个时菜单里出现「视频源」，切换保留进度）。
  - 播放内核：加入音频焦点（来电 / 其他播放器抢占时暂停）、耳机拔出自动暂停、熄屏播放时保持网络唤醒。
- 新增：Mafari 出现在系统「打开方式」里，可以直接打开本地 HTML 文件（.html / .htm / .xhtml，来自文件管理器、聊天软件、邮件等的 content:// 或 file://）。新增无界面入口 HtmlOpenActivity：先把文件拷到应用私有目录 filesDir/opened_html（超过 7 天的旧副本自动清理，单文件上限 64MB），再在新标签里打开，所以标签恢复 / 重新加载不依赖原来的临时授权。注意：只拷这一个文件，页面里引用的同目录图片 / 样式等相对资源加载不到（单文件网页，如「另存为单个 HTML」，不受影响）。
- 安全：WebView 开启 allowFileAccess 以加载本地副本，但 file:// 一律只放行 opened_html 目录内的副本（shouldInterceptRequest / shouldOverrideUrlLoading / 地址栏加载三处校验，其余 403 / 忽略），并明确关闭 allowFileAccessFromFileURLs / allowUniversalAccessFromFileURLs；导出的 MainActivity 同样只接收这个目录里的 file://，不会被其他应用拿来读私有数据。
- 新增：液态玻璃也有「自定义材质参数」（设置 →「高级材质」选「液态玻璃」后出现）。提供 6 套模板（液态默认 / 清透 / 强折射 / 雾面 / 白亮 / 柔和）和自定义：模糊强度、折射宽度 / 强度、边缘高光、饱和度、亮度、叠色浓度滑条，以及景深折射、边缘色散、增强背景饱和度三个开关；拖动实时生效，松手保存。数值为各表面原有基准的倍率，「液态默认」与改造前外观一致；作用于弹出菜单、对话框、sheet、顶栏按钮 / 胶囊按钮、底栏和主页卡片（开关滑块不受影响）。
- 调整：柔光玻璃与液态玻璃共用同一个参数弹窗（MaterialParamsSheet），GlassField / GlassPreset 抽成通用的 ParamField / ParamPreset / ParamToggle
- 修复：网页态状态栏渐进模糊在上滑沉浸浏览时，顶部采样层随页面滚动不断变化，出现拉伸残影 / 闪烁。适用于除「固定在顶部」外的各导航栏样式（WebView；Gecko 不变）：
  - 页面自己带固定在顶部的整宽栏（新增 PageTopBar 检测，WebView 每 0.8 秒一次，带滞后）：上滑进入沉浸浏览时网页不再铺到状态栏下，状态栏区域保持原样。
  - 页面没有固定顶栏：上滑沉浸后，状态栏区域的渐进模糊改为直接采样下层网页（不镜像、不拉伸）；拉伸采样层快速淡出，不再在滚动中残留。
- 新增： 加密套件 3（对称：Keystore 主密钥 + HKDF-SHA-256 逐条派生密钥 + AES-256-GCM），本机落盘数据（库密钥、书签、密码逐条加密、同步凭据等）默认改用它：信封只多 46 字节（原混合套件 1222 字节），冷启动解开库密钥和密码库逐条解密不再做 ML-KEM 解封装、也不再加载 ML-KEM 相关类；备份文件仍用后量子混合套件 1。套件接口新增 `sealNeedsPrivateKey` / `sealWithPrivate`，套件注册表改为懒创建；旧套件数据读出后保存时自动升级。
- 注意：旧版应用读不了套件 3 的数据，降级安装前请先导出备份！！！
## [2.1.83] 
- 修复： 升级 miuix 后，所有使用柔光玻璃（折射 / 光影）的组件（对话框、弹窗、sheet、按钮等）左侧出现一条未模糊 / 边缘错位的异常带。原因：上游 blur 现会把每一侧的 padding 裁到「背景层在该侧的剩余空间」，而折射 shader 仍按四边相同的 padding 计算组件原点；新增补丁 0011，shader 改用逐边 padding
- 新增：地址栏新增搜索引擎快速切换
- 调整：添加自定义搜索引擎逻辑和UI
## [2.1.77] 
- 修复： 升级 miuix 后，所有使用柔光玻璃（折射 / 光影）的组件（对话框、弹窗、sheet、按钮等）左侧出现一条未模糊 / 边缘错位的异常带。原因：上游 blur 现会把每一侧的 padding 裁到「背景层在该侧的剩余空间」，而折射 shader 仍按四边相同的 padding 计算组件原点；新增补丁 0011，shader 改用逐边 padding
- 修复： 「外部链接打开方式 → 自定义标签页」原来无效（只是转手去调别的浏览器）。现在 Mafari 自己提供自定义标签页：新增 Custom Tabs 服务（其他应用才会把 Mafari 当作支持自定义标签页的浏览器）、无界面分流入口（http/https 链接入口由 MainActivity 移到 LinkDispatchActivity）、自定义标签页窗口（叠在调用方任务里，工具栏：× 关闭 / 标题与域名 / ↗ 在 Mafari 中打开），点链接不再跳转到 Mafari 主界面
- 新增： 输入网址时地址栏最左侧（原脚本 / 阅读 / 下载图标位置）显示当前搜索引擎图标，点击弹出引擎菜单快速切换（默认底栏与悬浮三段式都有；菜单用抽象的 AppGlassPopup，与其他弹出菜单同一套材质，键盘不收起；输入时一律向上弹，不被键盘遮挡）
- 新增： 搜索引擎弹窗末尾新增「自定义」行，点击弹出添加框（引擎名 + 引擎地址，%s 代表关键词），保存后固化为引擎列表选项并自动选中；自定义引擎可在列表里删除；旧版单个自定义模板自动迁移成列表第一项
- 修复： 顶部加载进度条被状态栏 / 顶部渐进模糊采样而糊掉；进度条移到整页采集层与模糊带之外的最上层
- 修复： 回到主页（页面被盖在同一标签下面）后媒体仍在播放：主页盖住页面期间拦住并暂停媒体（Gecko 在 onPlay 补暂停；WebView 脚本暂停 + 监听 play 事件），页面还原 / 重新加载后解除
- 修复： 悬浮三段式从网页点主页按钮回到主页后，后退按钮仍置灰；现与默认底栏一致，可点后退还原刚才的页面
- 修复： 自定义起始页为默认主页时，从正在播放媒体的网页点主页按钮 / 返回到主页，页面在下面继续播放；现在回到主页即暂停媒体
- 修复： Cloudflare 站点滑动 / 刷新时偶发的空响应 502 / 503 / 504 / 52x 不再直接弹出错误页，先静默重试最多 2 次（间隔 0.7s / 1.8s），仍失败才显示
- 调整： 状态栏渐进模糊在状态栏下沿以下再向下渐隐 14dp，盖住状态栏与网页之间的分割白块
- 修复： 悬浮三段式下沉背景（底部渐进模糊）高度向上略增 2dp，压住与网页底栏接缝处可能出现的细分割线
- 新增： 引导页第一屏加语言选择（跟随系统 / English / 简体中文 / 繁體中文），选后自动重启
- 新增： WebView 网页态状态栏背景改为与顶栏同款的 miuix 渐进模糊（自上而下由强到弱），边缘拉伸采样网页顶部边缘；显示时机不变（初始状态显示，下滑淡出），悬浮默认栏 / 悬浮三段式 / 固定底栏均适用；Gecko 不变
- 修复： 柔光玻璃（非 miuix 默认模板）下通栏 sheet 左右边缘被当成玻璃边缘折射，露出一条清晰的原页面（边缘错位）；玻璃面板改为向两侧各多铺一段到屏幕外，折射边缘落在屏外，顶部圆角仍保留
- 新增： 悬浮三段式沉到底部让出的那一行背景由纯色改为渐进模糊（direction = Bottom，贴底最强、向上渐隐），高度随让位动画伸缩，边缘拉伸采样网页底部边缘
- 新增： 历史页每条记录左侧加站点图标

- 调整：悬浮三段式导航栏与默认样式一致：下滑缩成胶囊时状态栏留白随之收起，网页铺满屏幕顶

- 优化：历史页图标按域名共用，不重复加载；未缓存的行延迟 150ms 再请求，快速滑动不排队
- 优化： `miuix-sync.sh` 导入兜底改为逐项检查，重复运行不重复添加

- 修复：上游 miuix 不再导入 `asPaddingValues`，新补丁已在构建脚本里补上
- 修复： 补全 19 条英文翻译，包括"网页增强"设置行的过期译文

## [2.1.76] 
- 安全：网页 `intent://` 交给其他应用前统一清洗（`WebIntentSanitizer`）：去掉全部 URI 授权标志、拒绝 `content:` / `file:` data、清空 clipData / component / selector。浏览器标签和轻应用两处都走它。
- 安全：移除从未使用的 FileProvider 及 `file_paths.xml`（原先把整个 files 目录都声明成可共享）。
- 安全：`allowBackup` 改为 false，新增文件不再默认进云备份 / adb 备份。
- 安全：运行日志和崩溃日志里的网址抹掉 query / fragment；无痕标签不再写运行日志（页面、错误、console）。
- 安全：应用自己访问固定端点（发布信息、AMO 扩展商店、Floccus 包、GitHub 加速探测）一律只允许 https，重定向降级到 http 也拒绝（`HttpFetcher.Options.httpsOnly` / `HttpFetcher.requireHttps`）；更新安装包只从 https 地址下载，否则退回打开发布页。
- 安全：从链接安装拓展包，公网地址必须 https，本机 / 局域网（localhost、回环、私网段、.local）仍可用 http。
- 安全：WebView 和 Gecko 的「USB 远程调试」不再跨启动保存，冷启动时自动关闭；开关说明里已注明。
- 安全：更新安装包下载完成后校验 SHA-256（`UpdateIntegrity`）。期望哈希单独**直连** api.github.com（https）取 release 资产的 digest——镜像和镜像给出的发布列表都不可信；对不上就删除安装包、报失败，不进入「可安装」状态。直连 GitHub 不通取不到期望值时照常下载，由系统安装器校验签名。
- 安全：油猴 `GM_notification` 的点击经 exported 的 MainActivity 进入，现在每条通知带随机令牌，令牌对不上的 intent（其他应用伪造的点击）一律忽略。

## [2.1.75] cold start: extensions load after the first page
- 优化：WebView 拓展不再在冷启动期间预热 / 装配，改为「首页加载成功后」晚加载（`ExtStartupGate`）：主页 → 界面出来后 600ms 且主线程空闲；网页 → 第一个网页 onPageFinished 后 300ms（最多等 5s）。开闸后后台线程预热 → 主线程给已有标签补装 → 错开拉起后台页。开闸前拓展系统在热路径上视为不存在（`webExtensionsOrNull` 返回 null），首页的请求 / 导航不经过任何拓展逻辑。前台已加载的页面若有 content script 匹配，会重载一次让拓展生效（`BrowserTab.RELOAD_AFTER_LATE_EXT` 可关）。
- 修复：首个标签在主线程等拓展预热线程持有的锁（日志里约 1.2 秒）；BrowserScreen / 对话框宿主在首次组合里提前创建拓展系统。
- 优化：每个标签创建时在主线程读 asset（`mafari_blob_cache.js`、GM relay）改为只读一次，并由启动预热线程提前读好；`TabBlob.cleanStaleFiles` 的缓存目录扫描移到后台线程。
## [2.1.75]
- 调整：页面增强现在控制范围涵盖拓展（webview实验性）
- 优化：冷启动加载流程优化，禁用项目不再预热加载
- 调整：无痕浏览开启后固化，直到手动取消
##

- **The bar-detection also recognises bottom input boxes and submit buttons** (chat composers, comment boxes, checkout "submit order" bars): a visible input / textarea / contenteditable or a submit button inside a fixed (or bottom-sticky) container that is >= 60% wide and sits at the bottom of the viewport now counts as a page bottom bar, so the split bar yields the row instead of covering it. Same logic in `PageBottomBar.kt` (WebView) and `scroll-metrics.js` (Gecko).
- **Long-press on the address pill opens the start page** (follows Settings → Customize home → Custom start page, same as the home button) instead of opening a new tab. The pill plays an elastic shrink-then-overshoot pop while it opens.
- **The compact title pill (shown while scrolling) is lower**: 32dp → 26dp, corner 16dp → 13dp.
- **WebView debug: new "Record page performance" switch** (page performance section, default on = previous behaviour). When off, FCP / LCP and new entries are no longer collected; existing records are kept.
- **WebView debug: the "Preconnect" info line now says which path is available** (queued `enqueuePreconnect`, direct `preconnect`, or neither / no multi-profile) instead of a bare "not supported". `enqueuePreconnect` only exists since androidx.webkit 1.17.0-alpha04, so a system WebView that is not new enough reports it as unsupported even when it is the latest installed one.
- **Cold start: WebView extensions no longer block the first frame / first tab.** Previously the first WebView tab built the extension system, read every extension's state + manifest, read the shim and read / bundled every content script **on the main thread** before the first page could load. Now `StartupCoordinator.prewarmExtensions` does all of that on a background thread right after process creation (`ExtensionSystem.prewarm`, `WebViewExtensionHost.prewarm`); `ExtensionInjection.build` is split into `prepare` (file reads, bundling, localisation; cached per extension, invalidated on install / enable / disable / uninstall, permission or dynamic-script change) and `finish` (per-tab string assembly with the tab id), so the main thread only concatenates. Output is byte-identical to before (`ExtensionInjectionTest.prepareThenFinishMatchesBuildForEveryTab`).
- **Cold start: extension background pages start when the main thread is idle and are staggered** (200 ms apart, 3 s idle timeout) instead of all being created in one main-thread burst 1.5 s after the first frame. The start delay itself is unchanged; a delayed start re-checks that the extension is still enabled and not already running.
- Not compiled here (no Android toolchain).

## [2.1.70]
## WebView extensions: blank popups (Dark Reader / Tampermonkey)

- **Dark Reader popup was blank.** `chrome.extension` in the page shim was the raw object, not wrapped by `cbObj`, so `chrome.extension.isAllowedFileSchemeAccess(cb)` / `isAllowedIncognitoAccess(cb)` never called their callback. Dark Reader's `collectData()` does `Promise.all([... new Promise(r => chrome.extension.isAllowedFileSchemeAccess(r)) ...])`, so on Firefox (port `getData`) the background never answered and the popup stayed empty. `chrome.extension` is now `cbObj(ext)`; promise-style `browser.extension.*` is unchanged.
- **`runtime.sendMessage` no longer rejects with `NO_RECEIVER` when a listener exists but does not respond** (Firefox semantics: resolves `undefined`). Only "no listener at all / undeliverable" is `NO_RECEIVER`. The shim now replies `{noResponse:true}` (listener present, no answer) vs `{noReceiver:true}` (no listener); `MessageBroker` resolves `undefined` when every target finished without a real reply and at least one had a listener. A real reply from any context still wins. Tampermonkey's popup sends fire-and-forget messages and then reads data over a Port; the rejection surfaced as "unable to load tree".
- Diagnostics: a `listener present but no response` line is logged for `noResponse` replies.
- Tests: `ext-shim-test` (chrome.extension callback style, noResponse reply), `MessageBrokerTest.listenerWithoutResponseResolvesUndefinedNotNoReceiver`. Not compiled for Android here. Two shim tests (`windows.create` UNSUPPORTED, API wiring list) already failed in the uploaded tree and are unchanged.

## WebView adoption, part 2: incognito Profile, WebMessageListener bridges

- **Incognito tabs use a separate WebView Profile** (`IncognitoProfile`, `MULTI_PROFILE`). The tab's WebView is bound with `setProfile` right after creation (incognito is fixed at tab creation, which is already how `createTab` uses it). Cookies, cache, localStorage / IndexedDB, service workers and site permissions are isolated from the default profile; logging in inside an incognito tab now persists for that session (the old process-wide `setAcceptCookie(false)` flip blocked all cookies). When the last bound WebView is destroyed the whole profile is deleted (retry once; leftovers from a killed process are removed on first incognito use, plus a sweep after the UI is shown). Without `MULTI_PROFILE` the old behaviour is kept.
- Cookie access that used the global `CookieManager` is now profile-aware: `applyPrivacy`, initial tab setup, `GmBridge` (GM_cookie / `GM_xmlhttpRequest` cookies) and the temp WebView of `window.open` (bound to the opener's profile). Cookie backup reads the default profile path and is unchanged; incognito downloads still send no cookies (`withCookies = !tab.incognito`).
- **One-way bridges moved to `WebMessageListener`** (`OneWayBridge`): `MafariTr` (page translation) and `MafariElement` (ad marking long-press) now only accept messages from the main frame with an http(s) origin, string payloads under a length cap. `addJavascriptInterface` remains as the fallback when `WEB_MESSAGE_LISTENER` is unsupported; the page scripts feature-detect `postMessage` vs the old method (`mafari_translate.js` and its Gecko copy kept identical; node test added).
- Not converted (they need a synchronous return value): `MafariAdblock.css`, `MafariFp.fp` (fallback only), `MafariBlob.begin` / chunk streaming. Tests: `OneWayBridgeTest`.
- Not compiled here; see the delivery notes for the device checklist.

## WebView adoption: render-process recovery, UA client hints, async startup

- **Main tabs now survive a renderer crash / OOM kill.** `TabWebViewSetup` handles `onRenderProcessGone` (returns `true`, so the app is no longer taken down with the renderer). `BrowserState.replaceCrashedTab` swaps in a fresh tab at the same position, keeping URL / title / desktop mode / opener chain / `tabs` row key. A foreground tab shows the existing "page crashed" error page (`ErrorPages.crashed`) and waits for Retry (no auto-reload, so a page that crashes on load cannot loop); a background tab becomes a pending-restore tab and loads when switched to.
- **UA client hints follow the UA string.** New `UaClientHints` derives platform / mobile from the effective UA and applies `WebSettingsCompat.setUserAgentMetadata` (`USER_AGENT_METADATA`) after every `userAgentString` change, so `Sec-CH-UA-Mobile` / `Sec-CH-UA-Platform` / `navigator.userAgentData` no longer contradict desktop mode or a custom UA. When the target UA has the same platform and mobile flag as the system default it is left to WebView (the reduced UA's Android version is frozen and less accurate). Brand list untouched. Test: `UaClientHintsTest`.
- **Async WebView start-up.** New `WebViewStartup` calls `WebViewCompat.startUpWebView` from `Application.onCreate` (main process, WebView default engine only). The cold-start content gate (`StartupCoordinator.awaitContentReady`) waits for it (<= 1.5 s, in parallel with the encrypted-DB warm-up), so the first `BrowserTab` WebView is created only after start-up finished; if it never finishes or is unsupported the old synchronous behaviour applies.
- Not compiled here; `startUpWebView` / `UserAgentMetadata` signatures are written from the androidx.webkit docs, see the device checklist in the delivery notes.

## WebView GM channel: step 1c (legacy bridge only on demand, `@connect`)
## [2.1.68]

- Isolated-world devices no longer expose `MafariGM` to page JS: `setupWebView` only creates the shared `GmBridge`; `TabScripts.ensureLegacyBridge()` attaches the `addJavascriptInterface` bridge only when the system WebView lacks isolated worlds or the channel failed to install.
- `TabScripts.injectMissing` skips late `evaluateJavascript` injection while the channel is active (such a script would have neither bridge nor relay).
- New `GmConnectPolicy` (pure JVM, same rules as the Gecko background page): `@connect` is enforced for `GM_xmlhttpRequest` (every redirect hop re-checked) and, more strictly, `GM_cookie` on the channel path. Private/LAN hosts need an explicit `@connect`.
- Script quick menu subtitle now shows the runtime environment after the running count ("N scripts running · Isolated-world channel" / "· In-page bridge", the latter with a red "!" that toasts an unsafe warning and advises updating WebView): `TabScripts.bridgeMode()` reports isolated world when the GM channel (or Gecko relay) is in use and in-page when the legacy `MafariGM` bridge is attached.
- Tests: `GmConnectPolicyTest`. Not compiled for Android here; see the device checklist in `docs/WEBVIEW_GM_CHANNEL.md`.

## androidx.webkit 1.19.0-alpha02 + true isolated worlds

- Bumped `androidx.webkit` 1.18.0 -> 1.19.0-alpha02.
- Content scripts and the message bridge now live in a per-extension `JavaScriptExecutionWorld` (needs `JS_INJECTION_IN_FRAME_AND_WORLD`; falls back to the old page-world path otherwise). `world: "MAIN"` scripts go to the page world without a bridge or extension APIs.
- `scripting.executeScript` (ISOLATED) runs in that world via `JavaScriptReplyProxy.executeJavaScript` when the extension already has a content script on the tab; otherwise it falls back to the page world.
- Shim: `mainWorld` / `isolatedWorld` modes; `browser`/`chrome` become globals only inside the isolated world.
- Not wired: `Profile.configureIsolatedSites` (experimental, opt-in) added in 1.19.0-alpha02.


## WebExtension adapter follow-up

- Added the `webRequest.onBeforeRequest` blocking decision path for `cancel` and `redirectUrl`, with a bounded 350 ms wait on WebView's request IO callback and fail-open timeout behavior.
- Bound blocking replies to the extension page context that registered a matching blocking listener. This prevents unrelated popup/options/background contexts from sending an empty reply that wins the same extension's request decision.
- Revalidated the responding extension's enabled state, `webRequest` / `webRequestBlocking` grants, original host access, and redirect target host access before applying a decision.
- Validated `webRequest` URL filters and rejected unsupported `extraInfoSpec` values instead of silently pretending to support request/response header mutation.
- Expanded the Mafari-only `browser.mafari.userscripts` / `chrome.mafari.userscripts` API with source URL install, per-script refresh, batch refresh, and auto-update mode accessors. The API is restricted to extension pages and the explicit `mafariScripts` permission.
- Updated the WebExtension compatibility matrix and added API usage documentation.


## WebExtension adapter continued implementation

- Added MV3 `declarativeNetRequest` dynamic rule storage and execution for `block`, `redirect.url`, and `allow`, with URL/resource-type/tab/method conditions, validation, a 5,000-rule cap, and persistent per-extension rules. Static rulesets and advanced conditions remain unsupported.
- Added the standard `browser.userScripts` / `chrome.userScripts` facade (`register`, `getScripts`, `update`, `unregister`) backed by the existing dynamic content-script injector; supports inline code and extension files with host-pattern validation.
- Added `webRequest.onBeforeRedirect` for Mafari-applied blocking redirects; blocked requests emit `onErrorOccurred`. Main-frame `onResponseStarted` is approximated from `onPageCommitVisible`; request/response headers and subresource response phases remain unavailable in WebView.
- Buffered early `storage.onChanged` notifications briefly so a background page that registers its listeners just after startup does not miss the first storage changes.
- Reduced the default `runtime.sendMessage` response timeout from 30 s to 10 s, added target-context timeout diagnostics, and release pending deliveries when an endpoint is destroyed.
- Updated compatibility matrix and documented the exact supported subsets and WebView limitations.


## WebExtension runtime context discovery

- Added Manifest V3 `runtime.getContexts(filter)` for querying the current extension's live background, popup, options and content-script contexts.
- Added context type, context ID, document URL/origin, frame, tab, window and incognito filtering; document IDs are Mafari host-generated identifiers rather than Chromium-native document tokens.
- Changed the unimplemented `runtime.setUninstallURL()` from a silent no-op to an explicit `UNSUPPORTED` rejection.
- Added tests for own-extension isolation, context filters and malformed filter rejection.

- Added a constrained MV2 `runtime.getBackgroundPage` bridge descriptor for non-background extension pages; MV3 correctly rejects the deprecated background-page API. The descriptor is not a cross-WebView `Window` object.

- Exposed synchronous manifest-backed `runtime.getVersion()`.

## WebExtension API continuation: offscreen, tabs capture, messaging and scripting

- Added partial MV3 `offscreen` support backed by one hidden extension-origin WebView per extension: `createDocument`, `closeDocument`, and `hasDocument`; creation waits for load plus bridge handshake, and `runtime.getContexts` reports `OFFSCREEN_DOCUMENT`.
- Restricted offscreen page shims to the `runtime` namespace and validated the declared `offscreen` permission, MV3 manifest, extension-page caller, packaged relative HTML path, reason enum and justification.
- Added `tabs.captureVisibleTab` for the active WebView viewport, returning PNG/JPEG data URLs; host permission or a current `activeTab` grant is required. Capture is bounded to 4 megapixels and uses WebView view drawing rather than compositor capture.
- Added target tab metadata to `tabs.sendMessage` sender objects, implemented single-window `tabs.onHighlighted` dispatch on tab activation, and explicitly rejected documentId-based message routing.
- Tightened scripting target validation: unsupported all-frame, non-main-frame and documentId injection requests now fail explicitly; CSS USER cascade origin is rejected rather than silently treated as AUTHOR.
- Corrected runtime message delivery so a listener that receives a message but never responds does not fabricate a successful undefined reply; explicit sync replies, returned true and Promise replies remain supported.
- Updated API compatibility notes and added unit coverage for capture options, sender metadata and unsupported scripting targets.
- Chrome offscreen reason-specific lifecycle behavior, true isolated worlds, multi-frame injection and compositor-level capture remain unsupported.
