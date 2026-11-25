/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

/** 脚本菜单命令（GM_registerMenuCommand） */
data class ScriptMenuCommand(val id: String, val title: String, val script: String)

/** 浏览器内核。标签页创建时确定，之后不变。 */
enum class EngineKind { WEBVIEW, GECKO }
