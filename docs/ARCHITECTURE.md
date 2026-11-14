# 架构概览（新会话 / 新成员先读这一篇）

Mafari：基于 miuix（Compose Multiplatform）的 Android 浏览器。包名 `dev.dsh.miuixbrowser`，**单模块 `app`**，所有 Kotlin 源文件平铺在同一个包里（约 100 个文件）。
内核为系统 WebView，Gecko 变体额外内嵌 GeckoView（Nightly，仅 arm64-v8a），两种内核**按标签页**选择。

> 版本号、依赖版本以 `app/build.gradle.kts` 为单一来源（同时写入 `BuildConfig.DEP_VERSIONS` 供「关于」页展示）；本文不重复抄写，避免过期。
> 本文描述「代码现在是什么样」；功能清单看 `README.md`，Gecko 适配矩阵看 `GECKO.md`，变更历史看 `CHANGELOG.md`。

## 1. 仓库布局

```
mafari-dual/
├─ app/                              唯一应用模块
│  ├─ build.gradle.kts               flavors、依赖版本、签名、BuildConfig
│  └─ src/
│     ├─ main/java/dev/dsh/miuixbrowser/   全部 Kotlin（平铺）
│     ├─ main/assets/                WebView 注入脚本：mafari_gm_runtime.js（GM 运行时）、mafari_fingerprint.js、
│     │                              mafari_desktop.js、mafari_no_webrtc.js、mafari_blob_cache.js、mafari_fp_prereg.json
│     ├─ gecko/assets/               仅 gecko 变体：内置拓展 mafari-gm-ext/（manifest / background.js / relay.js）、
│     │                              mafari_gm_gecko_shim.js、mafari-bookmarks-shim.js（Floccus 书签垫片）
│     └─ test/                       JVM 单测：CryptoEngine、BackupFormat、GitNames、GitHistory、WebDavNames
├─ miuix/                            UI 库源码（includeBuild + dependencySubstitution，Apache-2.0，自带 AGENTS.md）
│                                    业务改动不要放这里；模块：ui / preference / icons / glass / blur / nav / squircle …
├─ docs/                             ARCHITECTURE（本文）、GECKO、GECKO_GM、FINGERPRINT + 截图
├─ tools/                            node 测试：gm-ext-test（Gecko GM 链路）、userscript-test（用户脚本解析）
├─ .github/workflows/release.yml     CI 与发布（见 §10）
└─ CHANGELOG.md                      发版时在对应版本下追加；GitHub Release 正文读取该版本小节
```

## 2. 构建变体（先理解这个，否则容易写出 lite 崩溃的代码）

`flavorDimensions = engine × abi`；engine 有三个：`gecko` / `lite` / `liteSync`（= lite + Firefox 同步，只构建 `liteSyncArm64`）：

| | `lite`（无内核版） | `gecko`（Gecko 版） |
| --- | --- | --- |
| GeckoView | 仅 `compileOnly`，**不进 APK**，运行时不存在 | `implementation` |
| `BuildConfig.GECKO_ENABLED` | `false` | `true` |
| ABI | `liteArm64` / `liteArm32` / `liteX64` 各一个包 | 只有 `geckoArm64` |
| `src/gecko/assets` | 不打包 | 打包 |
| Firefox 同步库（FxA / Places） | 仅 `compileOnly`，**不进 APK** | `implementation` |
| `BuildConfig.SYNC_ENABLED` | `false`（`liteSync` 为 `true`） | `true` |

- 唯一判断入口 `EngineSupport`：`SYNC_BUNDLED`（有无 Firefox 同步，gecko / liteSync 为 true）、`SYNC_LITE`（liteSync，更新选包用）、`GECKO_BUNDLED`（编译期常量，R8 据此裁剪）、`geckoAvailable()`（打包了 **且** 设备是 arm64-v8a）、`defaultIsGecko(ctx)`（设置选了 Gecko 且可用）。
- **铁律**：共用代码凡是会碰到 `Gecko*` 类的地方，必须先过 `EngineSupport`，否则 lite 运行时 `NoClassDefFoundError`；碰 `Fxa*`（Firefox 同步）同理要先过 `EngineSupport.SYNC_BUNDLED`（`proguard-rules.pro` 里的 `-dontwarn mozilla.**` 就是为普通 lite 里缺失的同步库类准备的）。
- 构建时指定具体任务（如 `:app:assembleLiteArm64Debug`、`:app:assembleGeckoArm64Release`），不要跑 `assemble`。
- 默认两个版本同一个 applicationId（可互相覆盖升级）；`-PgeckoAppIdSuffix=.gecko` 可并存。

## 3. 运行时总览

```
MainActivity ──► BrowserScreen（Compose，约 3000 行：手势 / 弹层 / 标签面板编排，不持有业务状态）
                  ├─ NavDisplay(backStack: AppRoute) ─ Settings / About / Downloads / Library / Extensions / BackupPage …（栈底永远是 Browser）
                  ├─ HomeScreen（主页）· BrowserBottomBar（底栏 / 地址栏）· BrowserMenuPanels（更多面板 / 标签面板）
                  └─ BrowserState（标签集合 + 切换 / 恢复 / 回收）
                       └─ BrowserTab（每标签一个）──┬─ WebView ── AdBlock / FingerprintInjector / GmBridge / TrustedChannel …
                                                    └─ GeckoTabEngine（GeckoSession + GeckoView，可惰性、可回收）
                                                          └─ GeckoRuntimeHolder（进程内唯一 GeckoRuntime，首次需要才创建）
其它入口：WebAppActivity（「添加到桌面」轻应用，仅 WebView）· DownloadService（前台服务）· DownloadActionReceiver / GmNotificationReceiver
```

- `BrowserTab` 把两种内核的回调统一翻译成 Compose 状态（url / title / progress / canGoBack …），**界面层只认 `BrowserTab`**。
  Gecko 标签访问 `tab.webView` 会直接抛异常；界面用内核无关的 `contentView` / `refresh()` / `scrollOffsetY` / `evaluateJs()`。
- 路由 `AppRoute` 是纯内存返回栈（miuix-nav `NavDisplay`，预见性返回、视差、遮罩都由它负责，应用不自绘）。
- `BrowserState` 与 `BrowserScreen` 是两个最大的文件（各约 3000 行）：改动前先用 grep 定位小节，避免整文件重写。

## 4. 标签与内核生命周期（惰性引擎 + 后台回收）★

目标：标签多、内存紧时，只让「当前标签」持有 Gecko 引擎；其余标签靠库里的状态随时复原。

**三种状态**

| 状态 | 引擎 | 说明 |
| --- | --- | --- |
| 活跃 | 有 | 当前标签；`activate()` 一定 `ensureGecko()` |
| 后台 | 有 | `setActive(false)`，停止渲染 |
| 待恢复（`pendingRestore`） | **无** | 卡片 / 标题 / 缩略图还在，`url=""`、页面地址存在 `savedUrl`；重启恢复与被回收都落在这个状态 |

**流转**

```
重启 ─ restoreSavedTabs()：读 tabs 表，createTab(lazyEngine=true) + prepareRestore() → 待恢复
                           回到 last_used 最大的标签并 activate（只加载它）
切到某标签 ─ activate(i)：ensureGecko() → setEngineActive → wakeIfPending() → loadPending()
              ├─ 库里有会话状态：restoreWithState()（异步读 TabDb.state → engine.restoreState；失败退回按地址 load）
              └─ 否则 load(url)
后台回收 ─ reclaimEngines()：discardEngine() = 先把 SessionState 写库 → 销毁 GeckoSession+GeckoView → 回到待恢复
```

- 「延迟」只作用于 **Gecko 引擎的创建**；WebView 标签构造时仍会 new 一个 WebView，但页面要到被切到前台才加载。
- `createTab(lazyEngine=true)` 先拉起 `GeckoRuntimeHolder.get()`，保证 Gecko 启动失败时仍能照旧退回 WebView。
- 回收参数（`BrowserState.companion`）：`MAX_TABS=99`、检查周期 60s、闲置 20 分钟必收（`IDLE_RECLAIM_MS`）、活着的引擎上限 6（`MAX_LIVE_ENGINES`）、内存吃紧上限 2（`TRIM_LIVE_ENGINES`）。
  超上限时按「最久没看」先收，且至少闲置 60s（吃紧时 15s）。系统正在放音乐时整轮跳过。
- 触发点：周期 `reclaimTick`；`MainActivity.onStop` → `BrowserState.flushCurrent()`（存标签列表 + 会话状态 + 回收）；`onTrimMemory`（level 10 / 15 / ≥40）→ `trimCurrent()` 激进回收。
- **永不回收**：当前标签、无痕标签（状态不能落盘）、拓展 `tabs.create` 的 session（由拓展持有）、正在加载 / 全屏 / 还没有页面的标签。写库失败也不回收（宁可多占内存也不丢页面）。
- 待恢复 / 正在恢复时引擎里是空白状态，**库里那份状态才是真的**，不能用引擎状态覆盖它（`discardEngine` / `persistSessionState` 都有这个判断）。
- `GeckoViewLifecycle`（`MainActivity` 的 onPause / onStop / onResume 调用）：退后台前抓一帧盖住 GeckoView，切回重绘后撤掉，避免「网页黑几秒」。

**标签列表落盘**（`TabDb`，`tabs` 表）

- 只存普通标签；无痕标签（含缩略图）永不落盘。
- 元数据由 `TabDb.sync` 整表快照、单事务写（`BrowserScreen` 里 `snapshotFlow { tabRows() }` 变化后限频保存，退后台再立即存一次）。
- `thumb`（WebP）和 `state`/`state_url`（Gecko SessionState）**单独写**，不被元数据快照覆盖；读状态要求 `state_url == url`，页面换了旧状态作废。
- 缩略图管线 `TabThumbs`：后台单线程缩放（≤360×640、RGB_565）+ 压缩；内存最多 32 张（LRU），其余存库，卡片显示时才读回。

## 5. 数据与存储

**加密库 `history.db`（SQLCipher 整库 AES-256，`HistoryDb`）**——绝大多数结构化数据都在这一个库里：

| 表 | 负责文件 | 内容 |
| --- | --- | --- |
| `history` | `History.kt`（`HistoryStore`） | 浏览历史 |
| `bookmarks` / `bookmark_folders` / meta | `BookmarkDb`、`Favorites.kt`（`FavoritesStore`） | 书签；id 稳定不变，Floccus 同步靠它做增量对账 |
| `scripts` | `ScriptDb`、`Extensions.kt`（`ExtensionStore`） | 油猴脚本，主键为脚本 id（GM 值也按它存，所以必须稳定） |
| `gm_values` | `GmStore` | GM_setValue 数据（取代旧的 SharedPreferences `userscript_gm`），WebView 与 Gecko 共用 |
| `downloads` | `DownloadDb` | 下载任务元数据 + 分段进度 |
| `perm_events` | `PrivacyStats` | 隐私报告：权限请求记录（≤200 条 / 7 天，无痕不记） |
| `tabs` | `TabDb` | 见 §4 |

- 库密钥：随机 32 字节，经 `SecureStore`（Android Keystore 保护的 `EncryptionEngine`）封装后存 SharedPreferences `mafari_history_db`；密钥取不回来则删库重建。
- 所有写入走 **`HistoryDb.io`（单线程执行器 `mafari-db-writer`）**，按提交顺序执行；`HistoryDb.get()` 返回 null 表示库不可用，各功能降级为「不记录」而不是崩溃。
- 新增表：在 `HistoryDb.open()` 里 `CREATE TABLE IF NOT EXISTS`；旧库补列写 `migrate()`（参考 `TabDb.migrate`）。
- 其余设置在 SharedPreferences：`mafari_settings`（`AppSettings`）、`mafari`（杂项）、`mafari_ad_rules`、`mafari_cookies`、`mafari_menu`、`mafari_gecko_protect` 等。
- 诊断：`Diag`（默认关，写 `/sdcard/Android/media/<pkg>/diag.txt`）；未捕获异常写 `crash.txt`（`MainActivity.installCrashLogger`）。

## 6. 加密与备份 / 同步

三层可演进结构（详见 `CipherSuite.kt` 顶部注释）：**信封版本**（公共前缀）→ **算法套件**（默认 ML-KEM-768 + X25519 混合、HKDF、AES-256-GCM；BouncyCastle）→ **存储格式**（备份 v1/v2、密钥库）。

| 文件 | 职责 |
| --- | --- |
| `CipherSuite.kt` | 套件注册表 `CipherSuites`、`Envelope`、`KeyProvider`。**套件号发布后永不复用 / 不改格式；淘汰的套件不删，只改 `DEFAULT_ID`** |
| `CryptoEngine.kt` | `EncryptionEngine`（seal / open / reseal）、`AndroidKeyVault`（Keystore）、`SecureStore`（按 `PURPOSE_*` 加密小字符串：库密钥、WebDAV 密码、Git 令牌 / 私钥、默认口令…） |
| `Backup.kt` | `BackupFormat`（`.mfbak`，文件头记录套件与 Argon2id 参数，写最新版读所有版本）、`BackupCategory`（书签 / 历史 / 标签列表 / 设置 / 脚本 / 脚本数据 / 去广告 / Cookie）、`BackupManager`（收集与恢复） |
| `WebDav.kt` / `GitSync.kt` | 云同步：WebDAV（OkHttp，强制 HTTP/1.1，上传后读回大小校验，留 10 份）/ Git（JGit + JSch，令牌或 Deploy key，只留最近 10 个提交并强推） |
| `BackupAuto.kt` | 自动推送：启动时到期则推送到当前连接及开启「一并同步」的目标 |
| `BiometricGate.kt` | 查看 / 修改默认口令、每次恢复前的身份验证 |
| `BackupScreen.kt` | 备份页 UI（约 1800 行） |
| `WebViewCookies.kt` / `CookieControl.kt` | Cookie 导出导入（WebView 读 Chromium Cookie 库副本；Gecko 经内置拓展）与清理 |

- 标签列表（`tabs/list` 单元）：`TabDb.exportJson` 导出（含缩略图 base64 + Gecko 会话状态），`prepareRestore` 里先 `parseBackup` 校验，`applyRestore` 调 `TabDb.restoreAll` 在 `HistoryDb.io` 上单事务整表替换，并置 `TabDb.frozen` 让 `sync / saveThumb / saveState` 停写（否则界面里还活着的旧标签会把恢复的列表覆盖回去；进程随后重启）。
- 恢复**只覆盖备份里有数据的类别**，没选 / 为空的类别保持原样。
- 备份里设备上是密文的键必须存**明文**（再由备份信封加密），否则换机无法恢复（`BackupManager.DEVICE_ENCRYPTED`）；已迁到 DB 的数据走 DB 导出（`DB_BACKED`）。
- 新增要进备份的设置：加入 `BackupManager.PREFS` 并在 `categoryOf` 里归类。

## 7. 脚本（油猴）与 Gecko 拓展

一份脚本库、一份 GM 值、一份运行时 `mafari_gm_runtime.js`，两种内核各有一条通道：

| | WebView | Gecko |
| --- | --- | --- |
| 注入 | `addDocumentStartJavaScript` | 内置 WebExtension（`GeckoGm` + `assets/mafari-gm-ext`，`contentScripts.register`，MAIN 世界） |
| 原生能力 | `GmBridge`（`@JavascriptInterface`） | 私有 MessageChannel → relay.js → background.js → 原生端口（令牌 + 来源校验） |
| 值 | `GmStore`（共用） | `GmStore`（共用）+ 快照内联 + 跨标签广播 |

- 拼装：`UserscriptEngine`（解析元数据 + `buildSource`，含 `@require/@resource` 走 `ScriptResources` 缓存）；脚本存储 `ExtensionStore`；`BuiltinScripts` 首启自动装一次推荐脚本。
- Gecko 拓展：`GeckoExtensions`（`GeckoExtensionStore`：AMO 搜索 / 安装 / 更新 / 权限确认、`GeckoExtTabHost` 回调给 `BrowserState` 实现 `tabs.*`）、`GeckoExtensionsScreen`、`GeckoExtMenuSheet`。
- Floccus 书签：`FloccusPatcher` 安装前给 xpi 打补丁（注入 `mafari-bookmarks-shim.js`）→ 垫片经 `runtime.sendMessage('gm@mafari.dev')` 交给内置拓展 → `sendNativeMessage('mafariBookmarks')` → `BookmarksBridge` 读写 `BookmarkDb`。**内置拓展 id `gm@mafari.dev` 与 manifest 版本号牵一发动全身，改动 background/relay 时同步升版本。**
- 安全模型、已知限制、真机验证清单见 `GECKO_GM.md`；测试见 `tools/gm-ext-test`。

## 8. 隐私 / 去广告 / 指纹（WebView 为主，Gecko 走自带能力）

- **去广告**：`AdBlockEngine`（纯 Kotlin：解析 + 域名索引 → token 倒排 → 兜底匹配）+ `AdBlock`（单例：订阅 / 重建 / WebView 接入）；`AdBlockScreen` 为设置页。
  请求拦截 `shouldInterceptRequest` → `AdBlock.intercept`（含 `TrackerList` 第三方统计域名）；元素隐藏在 document-start 脚本里经 `MafariAdblock.css()` 取（`cssFor` LRU）。
  规则变化 → `requestRebuild()`（150ms 防抖）→ 后台 `compileNow()`，订阅按文件时间戳缓存。订阅内容不可信：选择器含 `{ } ;` 一律拒绝。
- **指纹保护（WebView）**：`FingerprintInjector` 按 origin 注册 document-start 脚本，配置由原生内联、页面不可查询；`FpPreregistry` 管豁免站点；`TrustedChannel`（`MafariTrusted`，WebMessageListener）只承载低频、需严格来源的操作。细节与降级路径见 `FINGERPRINT.md`。
- **Gecko**：`GeckoProtection`（ETP / 指纹 / DoH / 仅 HTTPS…）+ `GeckoPrefs`（`about:config` 反射写入，Gecko 未启动只记录期望值，启动后统一 flush）+ `CookieControl.pushGecko`。设置页按当前内核只显示 `PrivacyEnhanceScreen`（WebView）或 `GeckoProtectionScreen`（Gecko）之一。
- 代理：WebView 用 `WebViewProxy`（`ProxyController`，SOCKS5 认证经本机 `Socks5Relay` 中转）；Gecko 同步写 `network.proxy.*`。
- 功能置灰：`GeckoFeatureGate`（Gecko 下不支持的功能行置灰 + toast）。

## 9. 下载 / 更新 / 其它

- **下载**：`Downloader`（自研，不走系统下载器；多线程分段 + 断点续传 + 暂停 / 重试，先写缓存再入库 `/sdcard/Download/mafari`）、`DownloadService`（前台服务 + 进度通知 / 小米超级岛）、`DownloadDb`、`DownloadsScreen`。
  Gecko 下 ≤64MB 且有 Content-Length 的直接消费 Gecko 响应体（带登录态），更大的仍由 `Downloader` 重新请求（**不带 Cookie**）。
- **更新**：`UpdateChecker`（启动检查，进程内一次）+ `AboutScreen`（`UpdateChannel`：lite / gecko / canary-lite / canary-gecko；`pickApkUrl` 按变体与本机 ABI 选包；已装 liteSync 时 lite 通道只选 `sync_lite` 包，普通 lite 排除它）+ `GitHubAccelerate`（镜像并发探测）。**改 APK 命名必须同步 `pickApkUrl`。**
- **视觉**：`AppMaterial`（`MiuixGlassMaterial` / `LiquidGlassMaterial` / `FlatMaterial` 三套材质抽象，设置页统一经它取组件）、`GlassKit`、`LiquidGlassKit/Components`（Kyant backdrop）、`BlurEffects`、`WallpaperBlurCache`、`TopInset`（状态栏留白在布局阶段读取，避免每帧重组整个 `BrowserScreen`）。
  液态玻璃采样层**内部**的按钮要用实色底（`inPage`），否则递归采样闪退。
- **图标 / 快捷方式**：`AppIconManager`（5 个 `activity-alias`，名带 `V2`，切换推迟到 `onStop`）、`ShortcutHelper` + `WebAppActivity`。

## 10. CI 与发布（`.github/workflows/release.yml`）

- 触发：push `main` / `gecko` / `dual`、tag `v*`、手动。一次构建同时产出 `Mafari-<标签>-gecko.apk` 与 `Mafari-<标签>-lite-<arm64-v8a|armeabi-v7a|x86_64>.apk`（不含 Firefox 同步）与 `Mafari-<标签>-sync_lite-arm64.apk`（lite + 同步，仅 arm64）+ `SHA256SUMS.txt`。
- `v*` tag → 正式 Release；`gecko` 分支 → `gecko-<构建号>` 预发布；`dual` 分支 → 以应用版本名直接发正式 Release（同版本名重复构建会覆盖安装包，要让用户收到更新请先提高 `versionName`）。
- 签名来自环境变量 / Secrets（不配则出 debug 包）；Telegram 推送可选。Release 正文取自 `CHANGELOG.md` 对应版本小节。

## 11. 约定与易踩的坑

1. 共用代码碰 `Gecko*` 前先过 `EngineSupport`（§2）。
2. 同包顶层声明统一 `internal`；新增顶层名称前先确认包内无重名。
3. 热路径（请求拦截、滚动、主线程）禁止：遍历大集合拼字符串、读 asset / 文件、同步序列化大 JSON。
4. `BrowserTab.contentView` 读取会触发 `ensureGecko()`——后台标签要做的事（关闭 / 延迟投递）不要碰它。
5. 无痕标签：不落盘、不回收、不记隐私统计、Gecko 私有会话必须在创建时指定。
6. 数据库访问一律经 `HistoryDb.get()` / `HistoryDb.io`；拿到 null 要降级，不要抛。
7. Gecko Nightly 的 Java API 偶尔变化：建议在 `gradle.properties` 锁定 `geckoViewVersion`。
8. 改动原则（项目作者的要求）：**最小改动、最小资源占用、最大代码复用**；先复用已有组件 / 抽象（如 `AppMaterial`、`AppDropdownPreference`），不要另起一套。
9. 文档漂移提示：`GeckoGm.kt` 文件头注释仍写「GM 值在 SharedPreferences `userscript_gm`」，实际已迁到 `GmStore`（`gm_values` 表）；`BackupManager.PREFS` 里的 `userscript_gm` 仅为兼容旧数据。

## 12. 想改 X，先看 Y

| 想改 | 先看 |
| --- | --- |
| 标签切换 / 恢复 / 回收策略 | `BrowserState`（`activate` / `restoreSavedTabs` / `reclaimEngines`）、`BrowserTab`（`discardEngine` / `loadPending`）、`TabDb` |
| 地址栏 / 底栏 / 手势（导航栏位置 `AppSettings.barPosition`：顶部 / 底部 / 始终最小化；快速朝屏幕中间一扫换边） | `BrowserBottomBar`、`BrowserScreen` |
| 主页（收藏 / 隐私报告 / 排序） | `HomeScreen`、`Favorites.kt`、`BookmarkDb` |
| 更多面板 / 菜单项 | `BrowserMenuPanels`、`MenuActions` |
| 设置项 | `AppSettings`、`SettingsScreen` 及各子页（路由在 `AppRoute`） |
| Gecko 回调 / 弹层 / 权限 | `GeckoEngine`（`GeckoTabEngine`）、`GeckoPrompts` |
| Gecko 隐私 / about:config | `GeckoProtection`、`GeckoPrefs`、`GeckoDebug` |
| 油猴（WebView） | `UserscriptEngine`、`GmBridge`、`assets/mafari_gm_runtime.js` |
| 油猴（Gecko） | `GeckoGm`、`assets/mafari-gm-ext`、`mafari_gm_gecko_shim.js` |
| 去广告规则语法 / 性能 | `AdBlockEngine`、`AdBlock` |
| 备份格式 / 加密 | `Backup.kt`、`CipherSuite.kt`、`CryptoEngine.kt` |
| 云同步 | `WebDav.kt`、`GitSync.kt`、`BackupAuto.kt` |
| 下载 | `Downloader`、`DownloadService`、`DownloadDb` |
| 玻璃 / 液态玻璃材质 | `AppMaterial`、`GlassKit`、`LiquidGlass*`、`miuix/miuix-glass` |
| 发版 / 更新选包 | `release.yml`、`app/build.gradle.kts`、`AboutScreen.pickApkUrl`、`CHANGELOG.md` |

## 13. 文档地图

| 文档 | 内容 |
| --- | --- |
| `README.md` | 功能、两个版本差异、构建与发布（中英双语） |
| `docs/ARCHITECTURE.md` | 本文：代码结构与关键机制 |
| `docs/GECKO.md` | 双内核适配矩阵、Gecko 高级保护、about:config 偏好 |
| `docs/GECKO_GM.md` | Gecko 油猴（内置 WebExtension）方案、安全模型、验证清单 |
| `docs/FINGERPRINT.md` | WebView 指纹保护的安全边界与降级路径 |
| `miuix/AGENTS.md` | UI 库自身的协作约定 |
| `CHANGELOG.md` | 变更历史（发版小节同时是 Release 正文） |

> 后续建议（未做）：抽出 `EngineAdapter` 接口统一 WebView / Gecko；订阅等小数据继续往加密库迁移。
