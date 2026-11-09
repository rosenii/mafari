# GeckoView SurfaceView 黑屏修复

本次仅调整 GeckoView 渲染后端初始化：

- `GeckoEngine.kt`：移除 `session.open(runtime)` 之后显式调用的 `view.setViewBackend(GeckoView.BACKEND_SURFACE_VIEW)`，保留 `view.setSession(session)`。
- `GeckoExtMenuSheet.kt`：移除弹窗 GeckoView 上显式调用的 `setViewBackend(GeckoView.BACKEND_SURFACE_VIEW)`，保留 `setSession(popup.session)`。
- GeckoView 默认使用 SurfaceView 后端；不再主动销毁并重建内部渲染 View。
- 未改动网页加载、标签管理、扩展逻辑及前后台会话恢复逻辑。

注意：本次交付为修改后的工程源码 ZIP，不是 APK。当前构建环境无法连接 `services.gradle.org` 下载项目指定的 Gradle 9.9.0，因此未能在此环境完成 Gradle 编译验证。请通过项目 GitHub Actions 或可联网的 Android 构建环境构建后安装测试。
