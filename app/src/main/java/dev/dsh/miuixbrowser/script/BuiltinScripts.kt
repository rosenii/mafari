/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.script

/**
 * 已移除内置「推荐脚本」：不再在首次启动时自动安装，脚本页也不再展示推荐项（已安装的脚本不受影响）。
 * 这里只留「媒体嗅探与播放」总开关用来认出已安装媒体脚本（M3U8 媒体下载器）的来源地址。
 */
val MEDIA_SCRIPT_URLS: Set<String> = setOf(
    "https://update.greasyfork.org/scripts/584352/M3U8%E5%AA%92%E4%BD%93%E4%B8%8B%E8%BD%BD%E5%99%A8%20%28%E6%94%AF%E6%8C%81AES-128%E8%A7%A3%E5%AF%86%29.user.js",
)
