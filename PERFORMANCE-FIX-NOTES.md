# GeckoView 滑动性能优化说明

基于已验证可正常显示网页、后台返回和扩展弹窗的 SurfaceView 修复版。

## 本次修改

1. `BrowserScreen.kt`
   - AndroidView 的 `update` 不再每次 Compose 重组都 `post { requestApplyInsets(); requestLayout() }`。
   - Window Insets 仅在内容 View 首次 attach 时请求一次。
   - View 尺寸和布局交给 AndroidView/系统 View 测量流程管理。

2. `BrowserState.kt`
   - GeckoView 的 viewport 尺寸变化不再额外调用 `requestLayout()` / `invalidate()`。
   - WebView 原有 viewport resize 通知逻辑保持不变。

3. 保留上一版 GeckoView 默认 SurfaceView 后端；没有切回 TextureView，也没有启用软件渲染、降低刷新率或定时 reload。

## 构建验证

当前执行环境无法解析 `services.gradle.org`（DNS/网络不可用），因此无法在此环境完成 `:app:assembleRelease` 编译。请通过 GitHub Actions 构建 Release APK 后进行设备实测。

## 性能判断边界

本次去除了代码中确定存在的重复布局请求，但不能仅凭源码保证所有滑动掉帧均已消失。若仍掉帧，需要 Android Studio System Trace / Perfetto 记录一次实际滚动，以区分 Gecko compositor、RenderThread、GPU 或主线程瓶颈。
