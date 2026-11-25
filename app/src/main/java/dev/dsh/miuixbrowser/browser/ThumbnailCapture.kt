/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.compose.ui.graphics.asImageBitmap
import dev.dsh.miuixbrowser.data.TabThumbs

/**
 * 标签缩略图的截取与状态（从 BrowserTab 拆出）。
 * 对界面可见的 `thumbnail` 仍是 [BrowserTab] 上的 Compose 状态，这里只持有截图节流 / 去重用的内部状态。
 */
internal class ThumbnailCapture(private val tab: BrowserTab) {

    private var lastThumbAt = 0L

    /** 当前这次截图开始的时间（uptime），0 = 没有进行中的截图；Gecko 异步截图超过 3 秒没回调就当丢失 */
    private var capturingSince = 0L

    /** 缩略图对应的页面状态：地址、滚动位置都没变且已有缩略图时，不重复截图 */
    private var thumbUrl = ""
    private var thumbScrollY = -1
    private var pendingThumbUrl = ""
    private var pendingThumbScrollY = -1

    /**
     * 按需截取标签缩略图（主线程发起）：只在「切走这个标签」和「打开标签页面板」时调用，
     * 页面加载完成不再自动截图。1 秒内重复调用、上一张还没处理完、页面和滚动位置都没变，都直接跳过。
     */
    fun captureNow() {
        if (tab.closed || tab.destroyed || tab.parked) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastThumbAt < 1000L) return
        if (capturingSince != 0L && now - capturingSince < 3000L) return
        val scrollY = tab.scrollOffsetY
        if (tab.thumbnail != null && thumbUrl == tab.url && thumbScrollY == scrollY && !tab.loading) return
        lastThumbAt = now
        pendingThumbUrl = tab.url
        pendingThumbScrollY = scrollY
        if (tab.isGecko) captureGecko() else captureWebView(tab.webView)
    }

    private fun captureGecko() {
        val g = tab.gecko ?: return
        val url = tab.url
        if (tab.closed || tab.destroyed || url.isBlank() || tab.isBlankPage(url) || tab.parked) return
        // 全局最多同时 N 张在途（Gecko 的整屏原图每张约 10MB），超了这次就不拍
        if (!TabThumbs.tryBegin(tab)) return
        capturingSince = android.os.SystemClock.uptimeMillis()
        // Gecko 的截图本身是异步的；拿到整屏原图后，缩小 / 压缩 / 落盘都交给 TabThumbs 的后台线程，主线程不做图像处理
        g.capture { bmp ->
            if (tab.closed || tab.destroyed) {
                capturingSince = 0L
                TabThumbs.end(tab)
                return@capture
            }
            TabThumbs.submit(tab, bmp, ownsSource = true)
        }
    }

    /**
     * 截取当前页面作为标签缩略图。WebView 的 View.draw 只能在主线程，所以这里直接画到最终大小的小图
     * （最长 [TabThumbs.MAX_W]×[TabThumbs.MAX_H]、RGB_565），压缩 / 落盘仍在后台。
     */
    private fun captureWebView(view: WebView) {
        val url = tab.url
        if (tab.closed || tab.destroyed || url.isBlank() || url == "about:blank") return
        runCatching {
            if (view.width <= 0 || view.height <= 0) return
            if (!TabThumbs.tryBegin(tab)) return
            val scale = minOf(TabThumbs.MAX_W.toFloat() / view.width, TabThumbs.MAX_H.toFloat() / view.height, 1f)
            val bitmap = android.graphics.Bitmap.createBitmap(
                (view.width * scale).toInt().coerceAtLeast(1),
                (view.height * scale).toInt().coerceAtLeast(1),
                android.graphics.Bitmap.Config.RGB_565,
            )
            val canvas = android.graphics.Canvas(bitmap)
            canvas.scale(scale, scale)
            view.draw(canvas)
            capturingSince = android.os.SystemClock.uptimeMillis()
            TabThumbs.submit(tab, bitmap, ownsSource = false)
        }.onFailure { TabThumbs.end(tab) }
    }

    /** [TabThumbs] 回到主线程：一次截图处理结束（成功或失败） */
    fun captureDone() {
        capturingSince = 0L
        TabThumbs.end(tab)
    }

    /** 新截的缩略图 */
    fun apply(bitmap: Bitmap) {
        tab.thumbnail = bitmap.asImageBitmap()
        thumbUrl = pendingThumbUrl
        thumbScrollY = pendingThumbScrollY
    }

    /** 从库里读回的缩略图（不代表当前页面状态，所以不更新 thumbUrl） */
    fun applyFromDb(bitmap: Bitmap) {
        tab.thumbnail = bitmap.asImageBitmap()
    }

    /** 释放内存里的缩略图（LRU 淘汰 / 标签关闭） */
    fun drop() {
        tab.thumbnail = null
        thumbUrl = ""
        thumbScrollY = -1
    }
}
