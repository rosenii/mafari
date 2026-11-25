/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sniff

/**
 * 「媒体嗅探与播放」页各开关的**生效状态**（纯数据，无 Android 依赖）。
 *
 * 用户在页面里存的是各自的开关值；真正起作用的值还要看两层总开关：
 *  - 本页的「总开关」[master]：关了，嗅探 / 悬浮播放 / 自动画中画 / 脚本宿主能力全部停用；
 *  - 「网页增强」[pageEnhancements]（仅 WebView 内核；Gecko 没有这个开关，[enhancementsApply] 为 false 时忽略）：
 *    关了，同样整页停用。
 * 热路径（WebView 的请求拦截、MediaHost）直接读这里的静态值。
 */
object MediaFeatures {
    @Volatile var master: Boolean = true
    @Volatile var pageEnhancements: Boolean = true
    /** 页面增强总开关是否适用（WebView 默认内核为 true，Gecko 为 false） */
    @Volatile var enhancementsApply: Boolean = true

    @Volatile var sniffSwitch: Boolean = true
    @Volatile var autoPipSwitch: Boolean = true
    @Volatile var autoFloatSwitch: Boolean = true

    /** 本页整体是否在工作（总开关 + 网页增强） */
    val active: Boolean get() = master && (!enhancementsApply || pageEnhancements)

    val sniffActive: Boolean get() = active && sniffSwitch
    val autoPipActive: Boolean get() = active && autoPipSwitch
    val autoFloatActive: Boolean get() = active && autoFloatSwitch
}
