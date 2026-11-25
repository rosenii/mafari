/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import dev.dsh.miuixbrowser.player.PlayerRequest

/**
 * 系统「打开方式」/ 分享菜单里的本地视频入口（清单里 VIEW + content / file + 视频 MIME 等过滤器，以及 SEND + 视频 MIME，声明在这里）。
 * 无界面：直接把地址交给原生播放页全屏播放（Media3 的 DefaultDataSource 能读 content:// / file://，不拷贝文件），随即结束。
 * 读权限靠 ClipData + FLAG_GRANT_READ_URI_PERMISSION 转给播放页；独立 taskAffinity，返回直接回到调用方应用。
 */
class VideoOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = intent
        // 打开方式：地址在 data 里；分享菜单：地址在 EXTRA_STREAM（有的应用只放在 ClipData 里）
        val uri = if (src == null) null else when (src.action) {
            Intent.ACTION_VIEW -> src.data
            Intent.ACTION_SEND -> src.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?: src.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            else -> null
        }
        val scheme = uri?.scheme?.lowercase()
        if (uri != null && (scheme == "content" || scheme == "file")) {
            val req = PlayerRequest(url = uri.toString(), title = displayName(uri), standalone = true)
            runCatching {
                startActivity(
                    req.toIntent(this).apply {
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            }
        }
        finish()
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            name = runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment
        return name?.substringAfterLast('/').orEmpty()
    }
}
