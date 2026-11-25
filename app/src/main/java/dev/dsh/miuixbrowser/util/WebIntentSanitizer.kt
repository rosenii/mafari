/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.util

import android.content.Intent

/**
 * 网页给出的 intent://（以及自定义协议）要交给别的应用之前的统一清洗。网页不可信，所以：
 *  - 去掉所有 URI 授权标志：否则网页能拼出 data 指向本应用 content:// 的 intent，把本应用文件的读 / 写权限授给目标应用；
 *  - 拒绝 content: / file: 的 data（本应用或系统文件）；
 *  - 清空 clipData、component、selector（直指本应用或非导出组件）；
 *  - 只允许按 BROWSABLE 解析；
 *  - 目标应用包名（package）保留（支付 / 市场类 intent 常见），启动标志另加 NEW_TASK。
 * 返回 null 表示这个 intent 不该被发出去。
 */
object WebIntentSanitizer {

    private const val GRANT_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    fun sanitize(intent: Intent): Intent? {
        val scheme = intent.data?.scheme?.lowercase()
        if (scheme == "content" || scheme == "file" || scheme == "android.resource") return null
        intent.flags = intent.flags and GRANT_FLAGS.inv()
        intent.clipData = null
        intent.component = null
        intent.selector = null
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }
}
