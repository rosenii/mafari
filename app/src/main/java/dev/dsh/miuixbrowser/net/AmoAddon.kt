/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.net

/** Firefox 拓展商店（AMO）搜索结果 */
data class AmoAddon(
    val id: Long,
    val slug: String,
    /** 拓展的 GUID（如 firefox@tampermonkey.net），与已安装拓展的 id 对应；判断「是否已添加」靠它 */
    val guid: String,
    val name: String,
    val summary: String,
    val iconUrl: String,
    val xpiUrl: String,
    val version: String,
    val users: Long,
    val rating: Double,
)
