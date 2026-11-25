/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

/** 登录弹窗请求（与内核无关的纯数据 + 回调）：由 GeckoPromptHandler 构造，BrowserScreen 里的 [LoginPromptDialog] 展示。 */
internal sealed class LoginPrompt {
    /** 取消 / 点空白处 / 页面离开：按「不保存」「不填」收尾 */
    abstract val onDismiss: () -> Unit

    class Save(
        val origin: String,
        val username: String,
        /** true：该站点已存过账号，这次是更新密码 */
        val update: Boolean,
        val onSave: () -> Unit,
        override val onDismiss: () -> Unit,
    ) : LoginPrompt()

    class Select(
        val pageHost: String,
        val items: List<Item>,
        val onPick: (index: Int) -> Unit,
        override val onDismiss: () -> Unit,
    ) : LoginPrompt() {
        class Item(val origin: String, val username: String)
    }
}
