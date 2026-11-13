# Changelog

发版时在对应版本下追加条目；GitHub Release 正文读取本文件该版本小节。

## [未发布]

- 构建：拆分为 `gecko`（Gecko 版）与 `lite`（无内核版）两个 product flavor，同一份源码、一次 CI 同时产出两个安装包。lite 里 GeckoView 仅 `compileOnly`、不进 APK（`BuildConfig.GECKO_ENABLED = false`），Gecko 专属资源（内置拓展、shim）移到 `src/gecko/assets`，ABI 为 arm64-v8a / armeabi-v7a / x86_64；共用代码经 `EngineSupport` 判断，lite 下不会触碰 Gecko 类
- 发布：Release 同时附带 `Mafari-<标签>-gecko.apk` 与 `Mafari-<标签>-lite.apk`（及 `SHA256SUMS.txt`），Release 由 Gradle 直接签名；Telegram 推送两个包（超过 50MB 的只给链接）
- 优化：「检查更新」按当前安装包的变体选择同名后缀的 APK，不会把 Gecko 版升级成无内核版（反之亦然）
- 优化（无内核版）：隐藏「浏览器内核」切换、「拓展」入口与菜单项、GeckoView 版本行；「关于」页版本旁标明 Gecko 版 / 无内核版

- 修复（WebDAV）：部分服务器上传备份报「服务器不支持 WebDAV：上传备份」（HTTP 405）或只留下 0 字节文件 —— 这些服务 / 前置代理在 HTTP/2 下处理 PUT 有问题；现强制 HTTP/1.1。上传后会读回服务器上的文件大小与本地比对，不一致则删除坏文件并报错（服务器不返回大小时跳过校验）；PUT 被重定向成 GET 时明确提示，不再静默当成功；PUT 返回 405 的提示改为「服务器拒绝了上传请求」
- 新增：关于页底部新增「隐私政策」页面，说明本地存储的数据、网络请求（GitHub 更新与加速镜像、Google 图标 / 翻译、Mozilla 扩展商店、用户同步服务器等）与权限用途
- 调整：应用图标「多彩 / 立体 / 深色」三款替换为新设计（桌面前景图与设置页预览图同步更新，底色按新图调整）；图标 alias 改名为 `Icon*V2`，让启动器丢弃按旧组件名缓存的图标，修复选「多彩」仍显示旧图、选「立体」变回默认图的问题（升级后首次启动按已选图标自动重新启用）
- 依赖：OkHttp 4.12.0 → 5.5.1，新增 logging-interceptor 5.5.1（仅 debug 构建的 WebDAV 请求记录请求头，Authorization 脱敏、不记录请求体）
- 依赖：Android Gradle Plugin 9.5.0 → 9.5.1（app 与 miuix 子工程同步）；SQLCipher 4.6.1 → 4.14.1
- 新增：关于页「依赖版本」增加「本地存储」（SQLCipher）与「内核与加密」（GeckoView、BouncyCastle）两组，并在 AndroidX 组补上 SQLite；SQLCipher / SQLite 版本改由 `app/build.gradle.kts` 统一声明

- 修复（Gecko / 拓展商店）：搜索结果里拓展名 / 简介显示「null」—— AMO 某些语言的译文是 JSON null，Android 的 `optString` 把它读成字符串 "null"；现统一按缺失处理，并依次回退 zh-CN / zh-TW / en-US / 任意语言 / slug
- 修复（Gecko / 拓展商店）：已添加的拓展再次搜索仍显示「添加」—— 之前拿 slug 对已装拓展的 GUID，永远对不上；现在用 AMO 返回的 `guid` 比对（大小写不敏感）
- 新增：「捐赠」入口（设置 → 关于栏，位于「关于 Mafari」上方；关于页里也有），点开底部弹窗显示赞赏码，可一键保存到相册（Pictures/Mafari）并提示用微信「扫一扫 → 相册」识别；弹窗抽成共用的 `DonateSheet`
- 新增（Gecko / Floccus）：「检查更新」现在也能升级 Floccus —— 此前官方更新包没有书签垫片，更新会让同步失效，所以一直跳过它。现改为查 GitHub `floccusaddon/floccus` 最新正式版（不含预发布），比当前版本新才下载其 Firefox 构建包，重新打补丁后覆盖安装（同 id 升级，账号与设置保留）；权限有变化时照常弹确认框
- 修复（Gecko / Floccus）：已装着改包版 Floccus 时，再装 / 更新失败或被取消会把「放开签名校验」重新关上，导致已装的 Floccus 下次启动被禁用，且会拿没打补丁的原包重试 —— 现在这种情况直接保持原状返回失败
- 修复（Gecko / Floccus）：设置里选「本地目标文件夹」一直转圈、列不出浏览器书签（也影响后台同步读写书签）—— 根因：Floccus 是普通安装的拓展，GeckoView 在 Android 上只给内置特权拓展开放 `runtime.sendNativeMessage` / `connectNative`（普通拓展里这个 API 不存在，`geckoViewAddons` 权限也会被拒绝），所以此前垫片的 `sendNativeMessage` 直接抛 TypeError，无论后台页走扩展级还是设置页走 session 级委托都收不到消息。现改为：垫片经 `runtime.sendMessage('gm@mafari.dev')` 把书签调用交给内置拓展（0.4.0），它的后台页 `onMessageExternal` （只认 `sender.id == floccus@handmadeideas.org`、只放行书签操作）再 `sendNativeMessage('mafariBookmarks')` 转给 `BookmarksBridge`，该委托挂在内置拓展上（`GeckoGm.ensureInstalled` 就绪时挂）。同时去掉无效的 session 级委托挂载，改包时不再给 Floccus 加 `nativeMessaging` 权限；垫片调用加 30 秒超时与明确的错误信息（内置拓展没运行时会提示），`tabs.query` 的 Vivaldi 探测 3 秒兜底保留。**已装的 Floccus 里烤着旧垫片**：点「检查更新」会用同版本重新打补丁覆盖安装（账号与设置保留），或卸载后重装
- 修复（Gecko / Floccus）：Floccus 的分隔线（`type: separator`）此前会被桥当成「新文件夹」创建 —— 现存成 `separator.floccus.org` 占位书签，对 Floccus 仍以 separator 节点呈现，位置可原样往返
- 优化（Gecko / Floccus）：改包安装时去掉 `dist/node-tests/`（约 26MB 测试包）和全部 `.map`，解压后体积由 62MB 降到约 15MB，安装更快

- 优化：关于页「依赖版本」新增「云同步」分组，展示 OkHttp、JGit、JSch 的版本

- 优化：备份与同步页重排 —— 云同步（WebDAV / Git 左右切换）置顶，其后是「口令与自动推送」，最后是「本地备份与恢复」（导出到本地、从本地备份文件恢复两行，不再用 sheet）
- 新增：默认加密口令 —— 设置后（加密存本机，不进备份文件）导出 / 上传 / 推送不再逐次询问口令，可随时替换或清除；恢复仍需输入口令
- 新增：身份验证闸门（`BiometricGate.kt`，系统 BiometricPrompt）—— 查看 / 修改默认口令、以及每次从本地文件 / WebDAV / Git 恢复备份前都要先验证；优先指纹 / 人脸，失败或不想用时可退回手机锁屏密码；未设锁屏的设备无从验证，放行并提示。恢复时验证通过后先用默认口令解密，解不开再手动输入；默认口令弹窗可「显示口令」
- 新增：自动推送同步 —— 样式同「自动更新脚本」（手动 / 每天 / 每 3 天 / 每周），启动应用时到期则把加密备份推送到当前连接的 WebDAV / Git，以及服务器列表里开启「一并同步」的其他项（当前连接的行前显示绿点）；需先设默认口令并配置云同步，任一目标失败则下次启动整体重试（`BackupAuto.kt`）
- 新增：WebDAV 服务器与 Git 仓库支持保存多份配置（测试通过后加密存本机，旧的单配置自动迁移）；再次点「服务器 / 仓库」弹出 sheet，顶部列出已连接的配置（点一行编辑、右侧「使用」切换当前、可删除），下方「添加新配置」打开新增弹窗（`WebDavStore` / `GitStore`）
- 优化：弹窗统一按钮布局（取消 + 主操作，删除单独一行）、字段多时输入区可滚动、令牌 / Deploy key 改为分段切换；弹窗摘要与列表副标题精简

- 新增：备份与同步页加「WebDAV」—— 配置服务器（测试连接后保存）后，可把加密备份上传到服务器的 `Mafari` 文件夹（保留最近 5 份），或一键从服务器上最新的备份恢复；服务器只见密文，口令仍只在本机输入、不保存；服务器密码经本机密钥加密存放，不进备份文件也不进系统自动备份（`WebDav.kt`，新增 OkHttp 依赖以支持 PROPFIND / MKCOL）
- 变更：WebDAV 与 Git 都只保留最近 10 份备份（WebDAV 由 5 份改为 10 份；Git 仓库只留最近 10 个提交，推送时把更早的提交裁掉并强制推送，所以目标分支需允许强推）
- 新增：Git 同步支持 SSH Deploy key —— 仓库设置里可切换「令牌 / Deploy key」，私钥可粘贴或从文件导入（OpenSSH / PEM 格式，可带口令），私钥与口令经本机密钥加密存放；主机密钥首次连接时记下，之后变化即拒绝；引入 mwiede JSch 分支，proguard 规则整体保留 JGit / JSch（`GitSync.kt`）
- 新增：备份与同步页加「Git」—— 配置 http(s) 仓库（地址 / 用户名 / 令牌 / 分支，测试连接后保存）后，可把加密备份提交并推送到仓库（`mafari-backup.mfbak`，每次一个提交，历史即版本），或从仓库最新提交恢复；基于 JGit 纯 Java 实现，每次把目标分支克隆到缓存目录、用完即删；仓库只见密文，令牌经本机密钥加密存放，不进备份文件也不进系统自动备份（`GitSync.kt`）
- 优化：精简备份与同步页的小字说明（备份范围合并为一行、去掉与确认框重复的恢复提示、加密方案各项缩短）
- 优化（Gecko GM）：降低内存 / CPU 开销 —— shim 值层改为「只读快照引用 + 覆盖层」，不再每帧复制全量；握手惰性化（脚本没真正运行就不建通道），上限 8 → 3；队列按字节限流（4MB）；xhr 结果 60s 未取走淘汰、挂起上限 256；单值 > 64KB / 单脚本内联 > 256KB 的值不再内联进注册源码，改为按需异步拉取（`GM.getValue` 可靠，值广播对大值不带内容）；中继改为按脚本匹配范围并集动态注册（无脚本的页面不加载）；值广播改走 runtime 端口、只发给绑定了该脚本的中继，不再写 `storage.local`；原生端按脚本缓存拼好的源码（一个脚本写值不再重拼全部脚本），同步给后台页时 sig 未变的脚本不再重发 code
- 安全（Gecko GM）：中继端口须先 `bind`（令牌 + 来源框架 URL 校验）才能收发；所有下行只发给已绑定端口（此前页面可伪造端口收到所有脚本的值广播）；握手事件在捕获阶段截停，页面监听器看不到端口；回调一次性（先摘后调）；写值增加按字节限频
- 新增：下载页加「多线程下载」开关，默认开启；关闭后一律单线程（线程数、阈值随之收起）；多线程阈值默认由 10 MB 改为 200 MB（已手动设置过的保持原值）
- 变更：设置 → 布局 →「首页内容显示」更名为「定制主页」；其中「个人收藏」改为 显示1行 ~ 显示5行 / 不显示（每行 3 个，默认 4 行，旧版「隐藏」自动迁移为「不显示」）；收藏数量超出所选行数时，最后一格显示「更多」，点击进入书签页；不再限制首页最多 12 个收藏
- 修复（Gecko）：带网络请求的脚本全部失效 —— 后台页对 reqId 的校验正则不允许冒号，而运行时生成的 reqId 是「脚本id:时间戳随机串」，导致每个 GM_xmlhttpRequest 都被判为 bad request id；放宽为允许 `: .` 并把长度上限提到 200

## [99.1.7]

- 修复（Gecko）：从其他应用切回 Mafari 时网页黑几秒 —— 退到后台前抓一帧当前画面，完全停止后盖在 GeckoView 上，切回后推一下重绘，等 TextureView 重新有内容再撤掉（`GeckoViewLifecycle` / `GeckoTabEngine.captureFrameForBackground`）
- 修复（Gecko）：安装拓展时点「添加 / 取消」后一直「安装中」—— 新版 GeckoView 的安装确认（`onInstallPromptRequest`）要求回 `PermissionPromptResponse`，之前一律回 `AllowOrDeny`，内核收不到结果就挂住；现按方法的泛型返回类型回对应类型，提示框以任何方式关闭都会回结果；安装加 3 分钟超时兜底，「安装中」状态放进 finally 一定释放
- 修复（Gecko）：拓展页「更多 → 导入」点了没反应 —— 菜单收起与导入 sheet 弹出同一帧冲突，改为菜单收起后再弹；重复点同一个正在安装的拓展不再静默忽略，会提示「正在安装中」
- 修复：stable / dev 分支「检查更新」提示检查失败 —— 之前只取最近 30 条 release，gecko 分支每次构建都发一条预发布，把 stable / dev 的 release 挤出了窗口，找不到就当失败；改为每页 100 条、最多翻 6 页，找到本分支的就停；整体等待上限 8s → 15s

## [99.1.3]

- 新增（Gecko）：「网页字体跟随系统」开关（Gecko 高级保护 → 其他）—— `browser.display.use_document_fonts=0` + 各语言默认字体指向系统 sans-serif；关闭时清除用户值恢复默认（`GeckoPrefs.CLEAR`）
- 修复：拓展详情读取权限不再依赖 `MetaData.permissions`（改为反射）；`GeckoFeatureGate` 去掉多余的 `matchParentSize` import


- 新增（Gecko）：「Gecko 高级保护」页（设置 → 高级，Gecko 模式下出现）—— 增强型跟踪保护（跟随隐私设置 / 关闭 / 标准 / 严格）、清理网址跟踪参数、弹跳跟踪保护、定期清理跟踪者数据、指纹保护、强力抗指纹、GPC / DNT、精简 Referer、防 WebRTC 泄露 IP、严格第一方隔离、仅 HTTPS、安全 DNS（DoH：Cloudflare / 阿里 / DNSPod / Google / Quad9 / 自定义，优先或仅 DoH）、ECH、禁用预取 / 预连接、禁用 WebGL，支持一键恢复默认（`GeckoProtection.kt` / `GeckoProtectionScreen.kt`）
- 变更（Gecko）：`applyPrivacy` 改为经 `GeckoProtection` 与高级保护页合并后写入（ETP 档位以高级页为准；GPC / WebRTC / 指纹取并集）；Gecko 运行时创建时一并写入全部保护设置



- 新增（Gecko）：拓展管理 —— 已安装拓展卡片带启用开关；点开详情 sheet 可看版本 / 描述 / 权限，并「启用 / 停用」「选项」（moz-extension 选项页在新标签打开）「检查更新」「卸载」（二次确认）
- 新增（Gecko）：定期检查更新 —— 「自动检查更新」手动 / 每天 / 每 3 天 / 每周（默认每天），显示上次检查时间；Gecko 模式下应用在前台时启动 15 秒后检查一次、此后每 6 小时看是否到点；没用过 Gecko 时不会为检查更新去启动它。新版本需要新增权限的更新在后台会被拒绝，留待手动「全部更新」时确认


- 新增（Gecko）：「拓展」页（`GeckoExtensionsScreen.kt` / `GeckoExtensions.kt`）—— 沿用脚本页的玻璃顶栏与卡片样式；右上角「搜索」「更多」：搜索在顶栏下方展开输入框，向 Firefox 拓展商店（AMO）搜索，结果显示在列表最上方，可直接「添加」；更多菜单含「导入」（底部 sheet：从链接 / 从文件，支持商店页面链接、.xpi 直链、本地 .xpi）与「全部更新」；下方列出已安装拓展
- 新增（Gecko）：安装 / 更新 / 可选权限的确认框（动态代理 `WebExtensionController` 的提示委托，权限译成中文）

- 新增（Gecko）：拓展栏显隐 —— 切到 Gecko 内核时，设置 → 高级出现「拓展」行（展开动画），更多菜单出现「拓展」图标（排在「脚本」后）；切回 WebView 自动收起。菜单项只改内存列表不落盘，用户的排列不会因临时切换而丢失（`MenuAction.geckoOnly` / `MenuOrderStore.syncGecko`）
- 新增：路由 `AppRoute.GeckoExtensions` 与「拓展」页骨架（`GeckoExtensionsScreen.kt`），拼图图标 `AppIcons.Puzzle`

- 新增（Gecko）：功能置灰 —— 浏览器内核为 Gecko 时，设置里的「网页增强 / 去广告 / 脚本管理」、网站权限里的「网站通知 / 剪贴板读写 / 网站媒体发声」，以及更多菜单里的「脚本 / 脚本菜单」置灰且不可操作，点击弹 toast「该功能在 Gecko 下不支持或有替代方案」（`GeckoFeatureGate.kt` 的 `GeckoLocked`，`AppSettings.geckoMode`）

- 新增（Gecko）：`GeckoPrompts.kt` 实现 PromptDelegate —— JS alert / confirm / prompt、`<select>` 下拉、HTTP / 代理认证、日期时间、弹窗拦截；文件选择 / 拍照上传包装成 WebView 同款 `FileChooserParams`，复用现有选择器与相机流程
- 新增（Gecko）：隐私设置生效 —— 拦截跟踪脚本（ETP）、DNT / GPC、禁用 WebRTC、指纹保护（`GeckoPrefs.kt`，about:config 偏好，运行时创建后统一写入）
- 新增（Gecko）：代理 —— `WebViewProxy` 的规则同步到 `network.proxy.*`（含 SOCKS5 认证本机中转）；HTTP 代理认证自动提交
- 新增（Gecko）：网页重定向权限（`onLoadRequest`）；视频全屏同时收起应用顶栏 / 底栏
- 修复（Gecko）：≤ 64 MB 的 http(s) 下载直接消费 Gecko 响应体，需要登录态的下载不再失败

## [1.8.25-dev]

- 新增（Gecko）：「Gecko 高级保护」页（设置 → 高级，Gecko 模式下出现）—— 增强型跟踪保护（跟随隐私设置 / 关闭 / 标准 / 严格）、清理网址跟踪参数、弹跳跟踪保护、定期清理跟踪者数据、指纹保护、强力抗指纹、GPC / DNT、精简 Referer、防 WebRTC 泄露 IP、严格第一方隔离、仅 HTTPS、安全 DNS（DoH：Cloudflare / 阿里 / DNSPod / Google / Quad9 / 自定义，优先或仅 DoH）、ECH、禁用预取 / 预连接、禁用 WebGL，支持一键恢复默认（`GeckoProtection.kt` / `GeckoProtectionScreen.kt`）
- 变更（Gecko）：`applyPrivacy` 改为经 `GeckoProtection` 与高级保护页合并后写入（ETP 档位以高级页为准；GPC / WebRTC / 指纹取并集）；Gecko 运行时创建时一并写入全部保护设置

## [1.8.24-dev]

- 新增（Gecko）：拓展管理 —— 已安装拓展卡片带启用开关；点开详情 sheet 可看版本 / 描述 / 权限，并「启用 / 停用」「选项」（moz-extension 选项页在新标签打开）「检查更新」「卸载」（二次确认）
- 新增（Gecko）：定期检查更新 —— 「自动检查更新」手动 / 每天 / 每 3 天 / 每周（默认每天），显示上次检查时间；Gecko 模式下应用在前台时启动 15 秒后检查一次、此后每 6 小时看是否到点；没用过 Gecko 时不会为检查更新去启动它。新版本需要新增权限的更新在后台会被拒绝，留待手动「全部更新」时确认

## [1.8.23-dev]

- 新增（Gecko）：「拓展」页（`GeckoExtensionsScreen.kt` / `GeckoExtensions.kt`）—— 沿用脚本页的玻璃顶栏与卡片样式；右上角「搜索」「更多」：搜索在顶栏下方展开输入框，向 Firefox 拓展商店（AMO）搜索，结果显示在列表最上方，可直接「添加」；更多菜单含「导入」（底部 sheet：从链接 / 从文件，支持商店页面链接、.xpi 直链、本地 .xpi）与「全部更新」；下方列出已安装拓展
- 新增（Gecko）：安装 / 更新 / 可选权限的确认框（动态代理 `WebExtensionController` 的提示委托，权限译成中文）

## [1.8.22-dev]

- 新增（Gecko）：拓展栏显隐 —— 切到 Gecko 内核时，设置 → 高级出现「拓展」行（展开动画），更多菜单出现「拓展」图标（排在「脚本」后）；切回 WebView 自动收起。菜单项只改内存列表不落盘，用户的排列不会因临时切换而丢失（`MenuAction.geckoOnly` / `MenuOrderStore.syncGecko`）
- 新增：路由 `AppRoute.GeckoExtensions` 与「拓展」页骨架（`GeckoExtensionsScreen.kt`），拼图图标 `AppIcons.Puzzle`

## [1.8.21-dev]

- 新增（Gecko）：功能置灰 —— 浏览器内核为 Gecko 时，设置里的「网页增强 / 去广告 / 脚本管理」、网站权限里的「网站通知 / 剪贴板读写 / 网站媒体发声」，以及更多菜单里的「脚本 / 脚本菜单」置灰且不可操作，点击弹 toast「该功能在 Gecko 下不支持或有替代方案」（`GeckoFeatureGate.kt` 的 `GeckoLocked`，`AppSettings.geckoMode`）

## [1.8.20-dev]

- 新增（Gecko）：`GeckoPrompts.kt` 实现 PromptDelegate —— JS alert / confirm / prompt、`<select>` 下拉、HTTP / 代理认证、日期时间、弹窗拦截；文件选择 / 拍照上传包装成 WebView 同款 `FileChooserParams`，复用现有选择器与相机流程
- 新增（Gecko）：隐私设置生效 —— 拦截跟踪脚本（ETP）、DNT / GPC、禁用 WebRTC、指纹保护（`GeckoPrefs.kt`，about:config 偏好，运行时创建后统一写入）
- 新增（Gecko）：代理 —— `WebViewProxy` 的规则同步到 `network.proxy.*`（含 SOCKS5 认证本机中转）；HTTP 代理认证自动提交
- 新增（Gecko）：网页重定向权限（`onLoadRequest`）；视频全屏同时收起应用顶栏 / 底栏
- 修复（Gecko）：≤ 64 MB 的 http(s) 下载直接消费 Gecko 响应体，需要登录态的下载不再失败

## [1.8.19-dev]

- 调整：liquid glass 下弹窗 / sheet 底部按钮去掉按压放大、拖拽形变与跟指高光（只保留按下变淡）；拖拽形变改用于对话框、sheet、弹出菜单和下拉菜单的大面板（面板无跟指高光）。`GlassDragDeform` 改为公开，下拉菜单在自定义 surface 时开启形变
- 修复：弹窗 / sheet 内容在 popup host 里组合，读不到 `LocalLiquidBackdrop`，导致「首页内容显示」sheet 里的下拉菜单与弹窗内按钮退回 miuix；门面现在把两个 local 重新注入内容
- 调整：liquid glass + 壁纸模糊时，首页卡片的取样层改为模糊后的壁纸（真实下层背景），不再取未模糊原图
## [1.8.18-dev]

- 重构：新增 `AppMaterial.kt` 材质抽象层。三种材质（`MiuixGlassMaterial` / `LiquidGlassMaterial` / `FlatMaterial`）各自实现开关、按钮、弹窗、sheet、菜单、下拉；`AppGlassDialog` / `AppDialogButton` 等门面按「模糊效果」设置转发，调用处无需判断模式
- 调整：弹窗 / 下拉菜单 / sheet 边缘折射与色散加强（lens 高度与强度提高，边缘高光加宽），背景模糊度降低

## [1.8.15-dev]
- 测试液态玻璃组件
- 修复：liquid glass 模式下，所有独立开关（模糊壁纸、已安装脚本 / 订阅源 / 新建脚本弹窗、脚本菜单）改用绿色轨道的 `LiquidSwitch`（新增 `AppSwitch`）
- 修复：所有弹窗 / sheet 底部按钮（`AppDialogButton`）与文字按钮（`AppTextButton`：订阅、添加、安装、保存自定义、完成等）应用液态玻璃底；彩色（主色 / 危险色）按钮叠色不透明度 0.75 → 0.92，降低通透度
- 修改：搜索引擎 sheet「保存自定义」→「使用自定义」；UA sheet 底部「保存」→「使用」，并补上液态玻璃背景；关于页更新弹窗、书签编辑弹窗改走 `AppGlassDialog`
- 调整：弹窗 / 下拉菜单 / sheet 边缘折射与色散加强（lens 高度与强度提高，边缘高光加宽），背景模糊度降低

## [1.8.14-dev]
- 调整：顶栏按钮（返回 / 更多 / 添加）、FAB、对话框底部胶囊按钮在 miuix-glass 与关闭模糊下还原为 miuix-glass 原生组件，只有选「liquid glass」才使用 Kyant 液态玻璃（`LocalBlurMode`）
- 新增：liquid glass 模式的应用范围扩展到下拉菜单（`GlassDropdownPreference` / `GlassPopup`）、底部 sheet、对话框、开关（`LiquidSwitch`：静止为白色滑块，按下 / 拖动变为折射轨道的透明玻璃）；各页面通过 `LocalLiquidBackdrop` 提供采样层，网页态与不支持时自动回退 miuix。miuix-glass 子工程只新增了可选的 `surface` 参数（默认 null，行为不变）

## [1.8.12-dev]

- 新增：设置 → 外观 →「模糊效果」由开关改为菜单：miuix-glass / liquid glass / 关闭模糊（旧开关自动迁移）。liquid glass 模式下，首页卡片（收藏 / 隐私报告 / 最近浏览 / 下载项）与底栏改用 Kyant0/AndroidLiquidGlass 的液态玻璃（折射 + 模糊 + 提亮），取样源仍是未模糊的壁纸；其余页面暂沿用 miuix-glass

## [1.8.11-dev]

- 修复：首页顶部渐进模糊失效——它要模糊的是滚到状态栏下方的首页内容（含卡片），上一版误改成只采样壁纸，现恢复采样整页采集层
- 新增：去广告页顶部加入与指纹保护页同款的「隐私报告」矩形框（累计拦截次数、规则数、保护状态）
- 调整：首页「收藏」标题与其他卡片标题统一为 14sp；收藏磁贴内文字 16sp → 14sp

## [1.8.10-dev]

- 调整：首页所有玻璃（卡片 / 底栏 / 弹层）的取样源统一为未模糊的壁纸原图，不再受「壁纸模糊」开关影响；「壁纸模糊」只作为显示层盖在取样层上；有壁纸时底栏与弹层不再采样含卡片的整页内容，避免重复模糊采样

## [1.8.8-dev]

- 调整：设置 → 隐私与安全 → 指纹保护由底部 sheet 改为独立二级页面（`FingerprintScreen`，新增路由 `AppRoute.FingerprintPage`），与脚本 / 去广告 / 下载页同一套布局，隐私报告、保护项、高级与确认弹窗全部移入页面
- 修复：设置 → 布局 →「首页内容显示」sheet 里的菜单列表多出白底卡片、内边距过大，改为与「网站权限管理」sheet 一致的无底板紧凑列表

## [1.8.7-dev]

- 新增：根目录 `LICENSE`（Apache License 2.0）
- 修复：首页卡片（收藏 / 隐私报告 / 最近浏览 / 下载项）在无壁纸时高光描边不明显——无壁纸下补一圈对角渐变描边（浅色深线、深色亮线）
- 调整：首页「隐私报告 / 最近浏览 / 下载项」取消长按隐藏，改为长按后上下拖动排序（个人收藏按整体大矩形参与排序，长按「收藏」标题行拖动），顺序持久化；拖到屏幕边缘自动滚动
- 新增：上述三张卡片右上角加 ×，点击从首页移除（可在 设置 → 布局 → 首页内容显示 恢复）

## [1.8.5-dev]

- 新增：关于页「依赖版本」——位于「版本」与「本机 UA」之间，按「界面 / AndroidX / 构建工具链」分组，两列紧凑显示各依赖版本（miuix-glass、Backdrop、Compose Multiplatform、Activity Compose、Core KTX、WebKit、Credentials、Browser、NavigationEvent、Kotlin、AGP、Gradle）；版本号由 `app/build.gradle.kts` 统一声明并写入 `BuildConfig.DEP_VERSIONS`，不再在界面里硬编码；「版本」卡中原来手写的 miuix 一行并入此处
- 新增：关于页底部加入 Copyright 信息
- 依赖：androidx.core:core-ktx 1.17.0 → 1.19.1
- 依赖：androidx.credentials:credentials / credentials-play-services-auth 1.5.0 → 1.6.0
- 依赖：androidx.browser:browser 1.8.0 → 1.10.0
- 依赖：Android Gradle Plugin 9.4.1 → 9.5.0（app 与 miuix 子工程同步）
- 依赖：Gradle 9.8.2 → 9.9.0
- 依赖：GitHub Actions——checkout v4 → v7、setup-java v4 → v5、setup-gradle v4 → v6（缓存使用开源的 basic 实现）、cache v4 → v5、upload-artifact v4 → v7、action-gh-release v2 → v3
- 依赖：已是最新、无需变动——androidx.webkit 1.18.0、androidx.activity:activity-compose 1.13.0、androidx.navigationevent:navigationevent-compose 1.2.0、Kotlin 2.4.21、Compose Multiplatform 1.12.2、Backdrop 2.0.1

## [1.8.2-dev]

- 重构：指纹保护改为「origin-scoped document-start 注入 + 原生内联配置」——每个 origin 单独生成脚本并按 origin 注册，配置只存在于脚本闭包，页面无法再询问或枚举其他站点的配置；移除页面可调用的 `MafariAdblock.fp(host, topHost)`（去广告的 `css()` 桥不变）
- 调整：指纹保护只作用于主框架与同源 iframe，跨源 iframe 默认不保护；导航前先注册（loadUrl / 刷新 / 前进后退 / shouldOverrideUrlLoading），重定向与无回调的导航为尽力而为
- 新增：「保护豁免站点」——指纹保护有了独立的豁免列表，不再复用去广告「站点白名单」（升级时一次性复制现有白名单，之后互相独立）
- 调整：WebView 不支持 DOCUMENT_START_SCRIPT 时降级为无参 `MafariFp.fp()`，由原生维护顶层 origin；安全边界弱于主路径，见 docs/FINGERPRINT.md
- 依赖：androidx.webkit 1.14.0 → 1.18.0
- 新增：轻应用接入指纹保护（同一套按 origin 注册；loadUrl / 刷新 / 后退 / 主框架导航前先注册）
- 新增：低频可信通道 MafariTrusted（WebMessageListener）——来源由 WebView 校验，只收主框架、固定 op：指纹读取统计上报、页面请求豁免（必须用户确认，只豁免它自己）；与指纹配置下发完全解耦，不返回任何数据
- 新增：设置 → 指纹保护里显示本次启动以来被处理的读取次数
- 新增：指纹保护 sheet 重排——顶部「隐私报告」卡（自上次开启保护以来处理的指纹读取次数，持久化），「指纹保护」总开关点击展开 Canvas / WebGL / Audio / Fonts 四项（关闭时记住选择，再开启恢复），兜底策略与豁免站点收进「高级」
- 新增：首页「隐私报告」卡（玻璃 blur）：已拦截广告数、已处理指纹读取数、近 24 小时频繁请求摄像头 / 麦克风 / 定位的网站提醒（次数 ≥ 3，只记主机名与时间，无痕不记）；长按可隐藏
- 新增：设置 → 布局 →「首页内容显示」——个人收藏 / 隐私报告 / 最近浏览 / 下载项逐项显示或隐藏；最近浏览、下载项复用隐私报告同一套卡片布局（默认隐藏，无痕首页不显示最近浏览）
- 新增：指纹保护的重定向 / 跨源 iframe 缺口收窄（预注册 + 落地检测 + 明确边界）——按「站点」分组注册（裸域 + `*.站点` 同一份脚本、同一个 seed，覆盖 www ↔ m ↔ login 站内跳转；站点下有豁免条目时不使用通配）；`assets/mafari_fp_prereg.json` 预注册清单（redirects：站点 → 重定向落点；embeds：嵌入方 → 被嵌入的跨源框架，首次导航前批量注册并固定）；脚本按「规则集合 + 允许的嵌入方」校验 self.origin 与祖先链，清单外的跨源 iframe 仍不保护；onPageStarted / doUpdateVisitedHistory 检测「落在未注册 origin」，设置 → 指纹保护 →「未预注册页面的兜底」可选仅记录 / 停止并在保护下重载（默认，带一次性凭证的登录回调除外）/ 停止加载

## [1.8.1-dev]

- 修复：长按广告图片 / 链接只有保存图片、链接菜单，无法标记广告——图片菜单与链接菜单新增「标记为广告」：按长按位置取页面元素，向上找到广告位容器（浮层 / 广告类名 / iframe / ins / 紧贴的包装层），生成唯一选择器后进入「标记为广告并移除 / 仅移除」
- 新增：「脚本」「去广告」页各增加「启用范围」——「在无痕浏览中启用」「在轻应用 / 桌面快捷方式中启用」两个开关（默认开启，行为与之前一致）；去广告的范围同时作用于请求拦截与元素隐藏，不影响指纹保护
- 新增：轻应用支持去广告的元素隐藏——装上 MafariAdblock 桥并以 document-start 注入隐藏脚本（含 iframe，不支持时页面开始 / 加载完后兜底），规则、总开关、「元素隐藏」开关与站点白名单与浏览器共用
- 修复：油猴脚本在轻应用中不生效——轻应用没有安装 GM 桥与脚本注入；现在与浏览器一致：启用的脚本以 document-start 注入（含 iframe），不支持时页面加载后兜底补注入；GM_setValue / GM_xmlhttpRequest / GM_openInTab / GM_download 可用，@require / @resource 自动预取；脚本列表与浏览器共用，受「网页增强」「脚本」总开关控制（脚本改动需重新打开轻应用生效）
- 调整：主浏览器的下拉刷新指示直接复用轻应用的 RefreshSpinnerView（随下拉进度旋转 / 缩放 / 渐显，同一触发距离与触感）；移除顶部重复的拖动刷新区

## [1.8.0-stable]
- 新增：高级->代理 支持HTTP/Socks5
- 新增：高级->缓存清理
- 新增：脚本定时更新功能
- 新增：视频悬浮播放器，智能识别页面媒体流
- 新增：指纹保护，仅用于极限反追踪，不建议开启
- 新增：页面重定向权限管理
- 新增：更多菜单「添加到桌面」从桌面图标打开时进入「轻应用」模式
- 新增：脚本命令快捷菜单，快捷执行脚本指令
- 调整：主浏览器的下拉刷新指示器优化
- 调整：标签列表打开关闭回退前进跳转逻辑
- 调整：脚本页UI
- 修复：地址栏输入 ip:端口被失败为搜索内容的bug
- 修复：轻应用/发送到桌面的快捷方式 浏览不沉浸的bug
- 修复：代理选 SOCKS5 后所有网页打不开的bug
- 修复：重定向判断逻辑，同站判断错误的bug

## [1.7.17-dev]

- 调整：主浏览器的下拉刷新指示直接复用轻应用的 RefreshSpinnerView（随下拉进度旋转 / 缩放 / 渐显，同一触发距离与触感）；移除顶部重复的拖动刷新区
- 调整：更多菜单「发送到桌面」改名为「添加到桌面」，图标改为 [+]
- 修复：地址栏输入 ip:端口（如 192.168.1.1:8080）、域名:端口、localhost:端口、[IPv6]:端口、nas:5000 被当成搜索词；无协议的 IP / localhost / 局域网主机名默认走 http，其余走 https；同时修复以 http 开头的域名（如 httpbin.org）漏补协议

## [1.7.16-dev]
- 修复：轻应用/发送到桌面的快捷方式 沉浸浏览，小白条沉浸，滑动状态栏沉浸

## [1.7.15-dev]

- 新增：更多菜单「发送到桌面」——把当前网页做成桌面图标，图标取网站自己的 icon（优先页面声明的 icon / apple-touch-icon，其次 favicon 服务，全部失败则用站点首字母生成），按自适应图标安全区绘制，不会被桌面裁切
- 新增：从桌面图标打开时进入「轻应用」模式（WebAppActivity）：全屏网页，没有地址栏 / 底栏 / 标签页，在最近任务里作为独立任务显示网站标题与图标；与 Mafari 共用 Cookie 与登录态，沿用代理、去广告网络拦截与深浅色设置，状态栏底色跟随网页 theme-color / 背景色
- 轻应用行为：同站跳转留在应用内；用户亲手点击的跨站链接交给 Mafari 浏览器打开，登录 / 支付等无手势的跨站跳转原地放行；返回键先回退网页历史，到头后退出；支持文件选择、相机 / 麦克风 / 定位（仅限本站）、全屏视频、下载（交给 Mafari 下载器）；不含油猴脚本与元素隐藏
- 首次点击先申请权限：小米 / 红米 / POCO 在首次点击（或权限被拒时）先跳到应用权限页开启「桌面快捷方式」，回来再点才添加；其它系统没有这项运行时权限，由系统的「添加到桌面」确认框确认。桌面不支持固定快捷方式时只提示，不会误加
- 轻应用补齐浏览器的体验：下拉刷新（页面在顶部下拉约 4cm 松手刷新，带触感与顶部旋转指示）、沉浸浏览（状态栏初始留白，向下滚动网页顶满，回顶回落）、手势小白条沉浸（网页铺到小白条下方，仅软键盘弹出时让出底部）
- 清单：新增 INSTALL_SHORTCUT 普通权限与 WebAppActivity（documentLaunchMode=intoExisting，同一网址再点图标回到已打开的页面）

## [1.7.14-dev]

- 修复：代理选 SOCKS5 后所有网页打不开——WebView 的 SOCKS5 不支持认证，上游代理需要账号密码时握手必然失败；现在「认证」对 SOCKS5 也可用，应用在本机 127.0.0.1 随机端口起免认证 SOCKS5 中转（仅 CONNECT，域名交由代理端解析），再向上游做用户名 / 密码认证
- 修复：代理主机里误填 socks5:// 协议头、路径、账号、「:端口」或 IPv6 方括号会生成无效规则；现在自动规范化，IPv6 地址自动加方括号
- 修复：应用代理失败被静默吞掉；现在检查 WebView 是否支持代理覆盖，失败原因显示在代理对话框并写入 diag 日志；相同规则不再重复下发（避免重复重置网络栈中断加载）；HTTP 认证挑战只对 HTTP 代理自动提交账号
- 调整：指纹保护四项（Canvas / WebGL / Audio / Fonts）默认全部关闭；开启任一项时弹出确认框，说明可能引起的问题（细微像素差异、风控 / 验证码异常、检测站点提示被篡改等），确认后才生效
- 调整：WebGL 不再伪装显卡型号（固定型号与真实参数不一致，反而更独特、更易被风控标记），只保留 readPixels / 画布导出加噪
- 调整：Fonts 不再改动 DOM 的 offsetWidth / offsetHeight / getBoundingClientRect，避免影响页面布局；保留 Canvas 文字测量扰动、本机字体加载限制与 queryLocalFonts 拒绝
- 修复：开启指纹保护后，浏览器检测提示「Canvas exception（Canvas 不稳定）」——原先每次读取 Canvas 都叠加新的随机噪声，同一画布连续读两次结果不同；现改为确定性噪声（只取决于站点种子 + 像素在画布中的坐标），同一站点同一会话内重复读取、getImageData / toDataURL / toBlob 之间结果完全一致，换站点或重启应用后仍会变化
- 修复：提示「Canvas Tampering（Canvas 被篡改）」——被拦截的函数改用 Proxy 包装，name / length / 无 prototype / 不可 new 等属性与原生函数一致，Function.prototype.toString 同样改为 Proxy 并按窗口（含同源 iframe）安装，toString() 仍返回原生源码
- 优化：Canvas 加噪更克制——纯色 / 空白画布不加噪（纯色填充读回校验不再失真）；约 1/64 像素的单个颜色通道 ±1，不动 alpha；导出（toDataURL / toBlob / convertToBlob）只改写被扰动的像素，其余由 drawImage 原样拷贝，避免整幅 get/putImageData 往返造成半透明像素误差；WebGL readPixels 同样使用确定性噪声

## [1.7.11-dev]

- 新增：设置 → 隐私与安全 → 指纹保护，点开弹出 glass sheet，内含 Canvas / WebGL / Audio / Fonts 四个开关，拦截对应的浏览器指纹（独立于去广告总开关，去广告站点白名单内不处理）
- 修复：从标签页里点击链接（含长按「在新标签页打开」、target=_blank）打开的新标签，在第一页返回时会先落到该标签的主页，要再返回一次才回到来源标签；现在第一次返回就直接关闭新标签并回到来源标签

## [1.7.9-dev]

- 修复：长按链接 / 图片「在新标签页打开」立即新建并切到新标签页；以前遇到同站链接会复用已有同站标签（常常就是当前标签），表现为没开新标签或直接在当前页跳转

## [1.7.8-dev]

- 修复：同站判定改为按站点（可注册域名）而不是整个 host，m. / login. / www. 等子网站之间的跳转与新窗口不再被当成跨站拦截或弹窗
- 修复：用户亲手点击的跨站 target=_blank 链接直接新标签打开（不再受「同站链接新标签页打开」开关误伤）；开关只管同站新窗口链接
- 调整：网页重定向「总是允许」与「询问后允许」行为一致——跨站跳转在新标签页打开，原页面不动
- 修复：后台标签触发的重定向 / 唤起应用不再被悄悄拒绝并弹 Toast，改为挂起，切到该标签时再询问（超过 1 分钟自动按拒绝处理）
- 修复：网页自动唤起其他应用 / 自动跳转被拒绝（或被拦截）后，不再回退历史把页面「关掉」，始终停留在当前网页；允许跳转到新标签时原页面同样原地不动
- 修复：隐藏 iframe 里的 weixin:// 等应用协议、服务器 302 到应用协议，现在同样受「网页重定向」权限控制；intent:// 正确解析（支持 browser_fallback_url，未安装应用时提示）
- 修复：新建标签页——当前已是同模式空白标签时不再叠出空白卡片；无痕下长按「在新标签页打开」不再落到普通标签；GM_openInTab 补齐接线
- 修复：标签页层级回退——新标签记录 opener，关闭后优先回到打开它的标签，其次最近用过的标签，再其次相邻标签（优先同无痕模式）；关闭后台标签不再改变当前标签；关闭无痕标签回到最近的普通标签
- 修复：回退主页——从主页打开的页面返回到第一页后再返回即回主页，不再翻出进入主页之前的旧历史；主页上再点「主页」不再丢失被盖住的页面；主页状态下页面侧回调不再把页面「顶」出来或写入空历史
- 修复：系统重建 Activity 时不再重复打开当初的外部链接
- 脚本列表：自动识别脚本头的 @version，紧跟在脚本名后加粗显示（名字过长时只截断名字）；副标题与图标信息改为按脚本内容缓存解析
- 优化脚本图标加载：支持 @icon64 / @icon64URL、data: 图标、协议相对与相对路径地址、GitHub 网页地址转 raw；http 先试 https、手动跟随重定向、防盗链 Referer 重试、GitHub 走加速镜像并发；大图降采样；失败 10 分钟内不重复请求；最终回退到脚本所属站点的 favicon（脚本快捷菜单同步生效）
- 新增：支持网页 showSaveFilePicker（File System Access API，需 WebView 132+），弹出系统「另存为」并以页面给定的原文件名边下边存，大文件不再经 blob 占内存
- 修复：blob 下载保持页面指定的原文件名（<a download> / File.name），不再用 UUID 命名，也不再自动补扩展名或改写字符
- 新增：设置 → 网页权限 → 网页重定向（每次使用时询问 / 总是允许 / 总是拒绝），控制网页无用户操作时自动跳转到其他站点或自动唤起其他应用
- 「网页权限」更名为「网站权限管理」，说明文字加入网页重定向；设置页内容沉浸到手势小白条下方
- 网页权限各项选项文案统一为「每次使用时询问 / 总是允许 / 总是拒绝」

## [1.5.17-dev]

- 同站复用标签：已有相同 host 的标签则切换并加载，不再重复开卡
- 更多菜单：仅长按并拖动才展开「隐藏的功能」，长按不拖不进入编辑
- 更多 / 标签 sheet：关闭页面取样模糊与窗口遮罩采样，减轻卡顿（miuix 组件未改）

## [1.5.16-dev]

- 代理弹窗：文案字号统一；代理关闭时仍可配置认证；SOCKS5 时认证开关置灰
- 外部打开链接、关于页/更新页等应用内链接：自动切回浏览主界面并加载

## [1.5.15-dev]

- 修复：`GitHubAccelerate.raceFirst` 中 `ExecutorCompletionService.submit` 类型参数导致的编译失败

## [1.5.14-dev]

- 检查更新与 APK 下载：直连 + 公共 GitHub 加速镜像并发探测，连通者优先

## [1.5.13-dev]

- 修复：`onReceivedHttpAuthRequest` 参数顺序与 `HttpAuthHandler.proceed/cancel` 方法名，消除 1.5.12 编译失败

## [1.5.12-dev]

- 代理弹窗：去掉副标题说明；主机/端口同行；新增认证开关（展开用户名/密码，HTTP 挑战自动提交）

## [1.5.11-dev]

- 新增：设置 → 高级 → 代理（HTTP / SOCKS5，官方 ProxyController）
- 精简本更新日志

## [1.5.10-dev]

- 移除：DNS 管理（WebView 无法单独改写 DNS）
- 修复：`PermissionRequest` 不存在的常量导致 1.5.8+ 编译失败

## [1.5.9-dev]

- 新增：DNS 管理说明与系统私人 DNS 跳转（后于 1.5.10 移除）

## [1.5.8-dev]

- 建议安装脚本精简；标签面板定位并高亮当前页；网页权限补全（含网站通知）
- 脚本卡片副标题显示 `@description`

## [1.5.5-dev] ~ [1.5.7-dev]

- 建议安装脚本、分区改名、副标题 `@description`

## [1.5.1-dev] ~ [1.5.4-dev]

- 补齐 1.5：文件上传、拍照/录像、视频全屏、blob 下载、权限询问
- blob 分片与 createObjectURL 缓存；深浅色同步；全屏横屏

## [1.5.0-stable]

- 主线基线
