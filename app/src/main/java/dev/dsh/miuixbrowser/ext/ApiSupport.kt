/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

/**
 * WebView 适配器的 API 支持矩阵（Gecko 适配器委托 GeckoView，不看这张表）。
 * 实现一个 API 就把它改成 FULL / PARTIAL；未登记的权限一律按 NONE 处理。
 */
object ApiSupport {
    private val webView: Map<String, SupportLevel> = mapOf(
        "storage" to SupportLevel.PARTIAL, // local/session API 完整；sync 为独立持久化本地存储，尚未接入账号云同步
        "runtime" to SupportLevel.PARTIAL, // 消息与 Port、MV3 getContexts 已接入；不支持跨拓展/外部连接；getContexts 的子框架与文档元数据受 WebView 限制
        "offscreen" to SupportLevel.PARTIAL, // 单个隐藏 WebView offscreen document；reason 仅用于校验，不具备 Chrome 的 reason 专属生命周期策略
        "permissions" to SupportLevel.PARTIAL, // getAll/contains/request/remove 与用户确认、权限事件已接入；match pattern 子集覆盖仍有限
        "tabs" to SupportLevel.PARTIAL, // 基础标签操作、sendMessage frameId 路由、move、duplicate、事件和活动 WebView 截图；muted 依赖 MUTE_AUDIO，audible 无 API；单窗口/完整 frame 路由等仍受限
        "activeTab" to SupportLevel.PARTIAL, // 点击拓展快捷入口授予当前标签
        "scripting" to SupportLevel.PARTIAL, // executeScript 与动态注册系列已接入；无真正隔离世界、frameId 仅支持 0
        "action" to SupportLevel.PARTIAL, // action / browserAction 共用原生状态并同步到拓展快捷菜单
        "browserAction" to SupportLevel.PARTIAL,
        "contextMenus" to SupportLevel.PARTIAL,
        "menus" to SupportLevel.PARTIAL,
        "alarms" to SupportLevel.PARTIAL, // 进程死亡期间不能准点执行，启动后补发
        "declarativeNetRequest" to SupportLevel.PARTIAL,
        "declarativeNetRequestWithHostAccess" to SupportLevel.PARTIAL, // 动态 block / redirect / allow 规则；静态 ruleset 与高级条件尚未支持
        "unlimitedStorage" to SupportLevel.FULL,
        "bookmarks" to SupportLevel.PARTIAL, // Firefox bookmarks 树 API 映射到 Mafari FavoritesStore；四个根容器与分组路径模拟文件夹
        "history" to SupportLevel.PARTIAL, // search/deleteUrl/deleteRange/deleteAll；visit 级别数据不可用
        "notifications" to SupportLevel.PARTIAL, // basic 文本通知 create/update/clear/getAll 与点击/关闭事件；Android 系统通知权限由用户在系统设置管理
        "downloads" to SupportLevel.PARTIAL, // 复用 Mafari 下载器：download/search/pause/resume/cancel/erase；选项、过滤器及事件为子集
        "webNavigation" to SupportLevel.PARTIAL, // 主框架 before/commit/complete/error 与 SPA 近似事件；无子框架 / documentId；NavigationListener 可用时 committed / DOMContentLoaded / completed 为真实回调
        "webRequest" to SupportLevel.PARTIAL, // onBeforeRequest + 主框架 completed/error；无子资源终态和响应头
        "webRequestBlocking" to SupportLevel.PARTIAL, // onBeforeRequest 同步 cancel / redirectUrl；IO 线程有限等待，超时 fail-open，不支持改请求头
        "userScripts" to SupportLevel.PARTIAL, // register/getScripts/update/unregister；WebView 注入世界仍有隔离限制
        "mafariScripts" to SupportLevel.PARTIAL, // Mafari 专有 mafari.userscripts CRUD、安装/更新与自动更新设置
        "cookies" to SupportLevel.PARTIAL, // URL-scoped get/getAll/set/remove；有 GET_COOKIE_INFO 时带真实属性；无全局枚举、storeId 和 Cookie 变更事件
        "clipboardWrite" to SupportLevel.PARTIAL, // Mafari clipboard.readText/writeText；仅拓展页面，受 Android 系统前后台剪贴板限制
        "clipboardRead" to SupportLevel.PARTIAL,
        "commands" to SupportLevel.PARTIAL, // 读取 manifest 命令；Android 不提供桌面快捷键 UI
        "pageAction" to SupportLevel.PARTIAL, // 与 action 共用状态；没有独立地址栏图标槽位
        "management" to SupportLevel.PARTIAL, // getSelf/getAll/get 只读；启停 / 卸载仍走 Mafari 管理页
        "idle" to SupportLevel.PARTIAL, // 屏幕点亮 / 锁屏状态；检测间隔不生效
        "dns" to SupportLevel.PARTIAL, // 系统解析器 A/AAAA 查询
        "topSites" to SupportLevel.PARTIAL, // 最近浏览历史按域名去重
    )

    fun forWebView(permission: String): SupportLevel = webView[permission] ?: SupportLevel.NONE
}
