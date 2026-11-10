# Mafari

基于 **miuix**（Compose Multiplatform）+ **系统 WebView** 的 Android 浏览器，不内嵌 Chromium 内核。
**new：** Gecko分支内嵌gecko内核，支持双内核切换。

## 界面

| 主页 | 更多面板 | 设置 |
| --- | --- | --- |
| ![主页](docs/screenshot-home.jpg) | ![更多](docs/screenshot-menu.jpg) | ![设置](docs/screenshot-settings.jpg) |

- 单条简约悬浮底栏：支持柔光玻璃/液态玻璃效果
- 全屏沉浸浏览：状态栏初始占位留白，向下滚动顶满，回顶回落，既不影响网页顶部元素点击，也能全屏沉浸浏览。


## 底栏手势

- 网页向下累计约 100px 才隐藏；隐藏后须向上累计约 120px 才恢复（降低误触）
- 底栏上竖滑阈值提高到 56px；**左右滑动**缩小为圆形搜索钮，点按或再滑展开
- 长按地址栏复制网站

## 功能

### 去广告（设置 → 高级 → 去广告）

**去广告引擎（`AdBlockEngine.kt` / `AdBlock.kt`）：**

- **网络拦截**：WebView `shouldInterceptRequest` 按规则拦截子资源请求（`||域名^`、通配、`@@` 例外、`$third-party / $domain= / 资源类型 / $important`）
- **元素隐藏**：`##选择器`、站点规则、`~站点` 排除、`#@#` 例外；document-start 注入到所有框架
- **订阅源**：URL 导入 / 本地文件导入，支持 Adblock Plus、uBlock、AdGuard 与 hosts 格式；可启停、手动或自动更新（每天 / 3 天 / 每周）
- **自定义规则**、**站点白名单**；长按网页元素「标记为广告」会写入自定义规则
- 不支持：正则规则、`$redirect / $removeparam / $csp / $replace`、`#?#` 扩展选择器、`+js()`（解析时跳过）
- 订阅源是第三方内容，元素隐藏选择器注入前会校验，拒绝含 `{ } ;` 的选择器

### 指纹保护（设置 → 隐私与安全 → 指纹保护)

 **四项可单独开关：**
  - Canvas：`toDataURL / toBlob / getImageData / OffscreenCanvas` 读出时加入 ±1 的随机像素噪声，原画布内容不变
  - WebGL：伪装 `UNMASKED_VENDOR / RENDERER`，`readPixels` 与 WebGL 画布导出加噪声
  - Audio：`AudioBuffer`（含 OfflineAudioContext 渲染结果）与 `AnalyserNode` 输出加入 -100 dB 级别噪声
  - Fonts：干扰 `measureText`、带内联 `font-family` 的探针元素尺寸、`FontFace local()` 加载与 `queryLocalFonts`，无法枚举已安装字体
- 旧版本放在脚本列表里的去广告订阅，首次启动会自动迁移并重新下载
- 另有独立的 保护豁免站点：按 origin 注册 document-start 脚本、配置由原生内联，页面无法查询配置；只保护主框架与同源 iframe，跨源 iframe 默认不保护；不禁用 API，只在读出指纹时「农场化」。安全边界与降级路径见 [docs/FINGERPRINT.md](docs/FINGERPRINT.md)


### 脚本（油猴）
- 内置油猴脚本运行时，完美支持油猴脚本
- 支持脚本注册油猴菜单命令
- 支持从 URL/文件 导入脚本

- Gecko 下同样可用（内置 WebExtension 运行时)
- Gecko 脚本桥的安全模型：页面拿不到通道（私有 MessageChannel + 每脚本能力令牌，停用 / 删除后立即作废）；`GM_xmlhttpRequest` 按 `@connect` 限制目标主机（同站放行；未声明时放行公网、拒绝本机 / 局域网；声明后只放行所列主机，重定向逐跳检查），响应体 / 请求体有大小上限；`GM_cookie` 仅限同站或 `@connect` 明确列出的主机；私有标签页不代发请求、不读写 Cookie；弹标签 / 通知 / 下载 / 剪贴板有频率限制
- 支持一键更新和定期更新
- 支持自动识别 `.user.js` 链接并弹导入确认

### 其他

**浏览**
- 多标签（文字列表 / 卡片两种样式）、前进后退刷新、加载进度
- 地址栏可选显示：网站标题 / 主域名 / 完整域名
- 下拉刷新（4cm 触发，顶部有刷新动画）
- 页面内查找、翻译、分享、复制链接、外部浏览器打开、长按链接菜单
- 首页打开网页后，后退可回到主页

**下载**
- 内置下载器（不走系统下载器），先写缓存再入库到 `/sdcard/Download/mafari`
- 多线程分段下载（服务器支持 Range 时）、断点续传、暂停/继续、失败重试、删除记录与文件
- 前台服务 + 进度通知；按小米官方文档接入**超级岛**（`miui.focus.param`，仅在开始下载时上岛）

**其他**
- 收藏 / 历史/ 下载管理 / 脚本管理
- 设置：显示模式（跟随系统/日间/夜间）、主页壁纸、模糊效果选择、首页内容展示内容管理，搜索引擎、自定义 UA、代理、检查更新

## 部署

### 本地构建

```bash
# 需要 JDK 17+（推荐 21）、Android SDK（platforms;android-37.0、build-tools;36.0.0）
# 根工程 Gradle 9.9.0；miuix 源码在 ./miuix，由 includeBuild 参与构建
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```


### GitHub Actions 自动发布

推 tag 即自动构建并创建 Release：

```bash
git tag v3.3.0 && git push origin v3.3.0
```

可选签名 Secrets（不配也能出 debug 包）：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 your.jks` |
| `STORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASSWORD` | key 口令 |

## 许可证

本项目以 [Apache License 2.0](LICENSE) 发布；内置的 `miuix/` 子工程同为 Apache-2.0，见 [miuix/LICENSE](miuix/LICENSE)。

## 项目地址

https://github.com/rosenii/mafari
