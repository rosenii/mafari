# 架构概览

包名 `dev.dsh.miuixbrowser`，单模块 `app`，UI 为 Compose（miuix Glass），内核为系统 WebView + GeckoView（可选）。

## 分层

| 层 | 主要文件 | 职责 |
| --- | --- | --- |
| 入口 / 导航 | `MainActivity`、`AppRoute`、`WebAppActivity`（轻应用） | Activity、路由栈、预见性返回 |
| 浏览主界面 | `BrowserScreen`（状态与手势编排）<br>`BrowserBottomBar`（底栏 / 地址栏 / 小部件）<br>`HomeScreen`（主页：收藏、隐私报告、拖动排序）<br>`BrowserMenuPanels`（更多面板、标签页面板） | 只做界面；状态来自 `BrowserState` |
| 标签状态 | `BrowserState`（`BrowserTab` / `BrowserState`） | 每个标签持有一个内核（WebView 或 Gecko）、导航状态、权限、注入脚本 |
| Gecko 适配 | `GeckoEngine`、`GeckoExtensions`、`GeckoPrompts`、`GeckoProtection` | GeckoSession 各类 Delegate → `BrowserTab` 的 `gecko*` 回调 |
| 去广告 | `AdBlockEngine`（纯 Kotlin：解析 + 索引匹配）<br>`AdBlock`（单例：订阅、重建、WebView 接入、指纹保护） | 子资源拦截 + 元素隐藏 CSS |
| 用户脚本 | `UserscriptEngine`（解析 + 拼装）、`Extensions`（存储）、`ScriptResources`（@require/@resource 缓存）、`GmBridge`、`assets/mafari_gm_runtime.js` | 油猴兼容运行时 |
| 隐私 / 指纹 | `FingerprintInjector`、`FpPreregistry`、`CookieControl`、`TrackerList`、`PrivacyStats` | document-start 注入、豁免站点、Cookie / 追踪域名 |
| 数据 | `AppSettings`、`History`、`Favorites`、`PrivacyStats` | 均为 SharedPreferences（JSON 字符串） |
| 下载 | `Downloader`、`DownloadService`、`DownloadsScreen` | 多线程分段下载、前台服务 |
| 视觉 | `GlassKit`、`LiquidGlassKit/Components`、`BlurEffects`、`WallpaperBlurCache` | 玻璃 / 液态玻璃与壁纸模糊缓存 |

## 关键数据流

- **请求拦截**：WebView `shouldInterceptRequest` → `AdBlock.intercept` → `TrackerList` + `AdEngine.shouldBlock`（域名索引 → token 倒排 → 兜底）。
- **元素隐藏**：document-start 脚本 → `MafariAdblock.css()` 桥 → `AdEngine.cssFor`（LRU 缓存）。
- **规则重建**：任何订阅 / 自定义规则变化 → `requestRebuild()`（150ms 防抖）→ 后台 `compileNow()`，已解析的订阅按文件时间戳缓存。
- **底栏收起**：WebView `OnScrollChangeListener` / Gecko `ScrollDelegate` → `BrowserTab.scrollSignal` → `BrowserScreen` 里的手势协程（滚动中 80ms 采样，静止时挂起，1s 兜底）。
- **隐私脚本**：`BrowserTab.applyPrivacy` 拼出 document-start 脚本；内容未变则不重复注册；asset 只读一次。

## 约定

- 同包内顶层声明统一 `internal`（拆分文件后不再用 file-private），新增顶层名称前先确认包内无重名。
- 热路径（请求拦截、滚动、主线程）禁止：遍历大集合拼接字符串、读 asset / 文件、同步序列化大 JSON。
- 后续建议：抽出 `EngineAdapter` 接口统一 WebView / Gecko；历史、订阅等迁移到 Room / DataStore。
EOF
