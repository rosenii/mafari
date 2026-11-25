/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import dev.dsh.miuixbrowser.browser.ShortcutHelper
import dev.dsh.miuixbrowser.i18n.tr

/**
 * 系统里 http(s) 链接的入口（清单里的 VIEW + BROWSABLE 过滤器声明在这里，不在 MainActivity）。
 * 没有界面，按设置「外部链接打开方式」立刻分流后结束：
 * - 来自其他应用的真正 Custom Tabs 请求（带 [CustomTabsIntent.EXTRA_SESSION]）：不看设置，一律按 1 处理；
 * - 0 应用内打开：转给 [MainActivity]（singleTask，进入 Mafari 主界面）；
 * - 1 自定义标签页：在调用方的任务里启动 [CustomTabActivity]（不跳转到 Mafari 主界面）；
 * - 2 其他浏览器：弹出选择器并排除自己。
 */
class LinkDispatchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = intent
        val data = src?.data
        val scheme = data?.scheme?.lowercase()
        if (src == null || data == null || (scheme != "http" && scheme != "https")) {
            finish()
            return
        }
        // 桌面快捷方式等固定在应用内打开的入口不受设置影响
        val mode = when {
            src.getBooleanExtra(ShortcutHelper.EXTRA_FROM_SHORTCUT, false) -> 0
            // CustomTabsIntent.Builder 即使没有 session 也会写入该 key，所以只判断 key 是否存在
            isCustomTabsRequest(src) -> 1
            else -> appContainer.settings.linkOpenMode
        }
        when (mode) {
            1 -> runCatching {
                // 不加 NEW_TASK：调用方是 Activity 时，自定义标签页就叠在它的任务里，返回回到原应用
                startActivity(Intent(this, CustomTabActivity::class.java).setData(data))
            }.onFailure { forwardToMain(src) }
            2 -> runCatching {
                val view = Intent(Intent.ACTION_VIEW, data).addCategory(Intent.CATEGORY_BROWSABLE)
                startActivity(
                    Intent.createChooser(view, tr("打开链接")).apply {
                        putExtra(
                            Intent.EXTRA_EXCLUDE_COMPONENTS,
                            arrayOf(
                                ComponentName(this@LinkDispatchActivity, LinkDispatchActivity::class.java),
                                ComponentName(this@LinkDispatchActivity, MainActivity::class.java),
                            ),
                        )
                    },
                )
            }
            else -> forwardToMain(src)
        }
        finish()
    }

    private fun isCustomTabsRequest(i: Intent): Boolean = i.hasExtra(CustomTabsIntent.EXTRA_SESSION)

    private fun forwardToMain(src: Intent) {
        runCatching {
            startActivity(Intent(src).setComponent(ComponentName(this, MainActivity::class.java)))
        }
    }
}
