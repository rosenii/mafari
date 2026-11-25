/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** MV3 offscreen documents hosted by a hidden, extension-origin WebView. */
class OffscreenApi(private val pages: ExtensionPageHost) {
    private val reasons = setOf(
        "AUDIO_PLAYBACK", "BLOBS", "CLIPBOARD", "DOM_PARSER", "DISPLAY_MEDIA",
        "DOM_SCRAPING", "IFRAME_SCRIPTING", "LOCAL_STORAGE", "MATCH_MEDIA",
        "TESTING", "USER_MEDIA", "WEB_RTC", "WORKERS", "BATTERY_STATUS", "GEOLOCATION",
    )

    fun register(router: ApiRouter) {
        router.register("offscreen.createDocument") { ctx, args ->
            requireMv3(ctx)
            requireExtensionPage(ctx)
            val details = args.argOrNull(0) as? JSONObject
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "createDocument 需要参数对象")
            val rawUrl = (details.opt("url") as? String)?.takeIf { it.isNotBlank() }
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "createDocument 缺少有效的 url")
            val reasonArray = details.optJSONArray("reasons")
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "reasons 必须是非空数组")
            if (reasonArray.length() == 0 || reasonArray.length() > reasons.size) {
                throw ApiException(ErrorCodes.BAD_REQUEST, "reasons 数量无效")
            }
            val parsedReasons = (0 until reasonArray.length()).map { i ->
                reasonArray.opt(i) as? String ?: throw ApiException(ErrorCodes.BAD_REQUEST, "reasons 必须是字符串数组")
            }
            if (parsedReasons.any { it !in reasons }) {
                throw ApiException(ErrorCodes.BAD_REQUEST, "包含不支持的 offscreen reason")
            }
            val justification = (details.opt("justification") as? String)?.trim().orEmpty()
            if (justification.isEmpty() || justification.length > 1000) {
                throw ApiException(ErrorCodes.BAD_REQUEST, "justification 必须为 1 到 1000 个字符")
            }
            if (rawUrl.contains(":") || rawUrl.contains("\\") || rawUrl.startsWith("/") || rawUrl.contains("?") || rawUrl.contains("#")) {
                throw ApiException(ErrorCodes.BAD_REQUEST, "offscreen url 必须是扩展包内的相对 HTML 路径")
            }
            val normalized = TabUrlPolicy.normalize(ctx.ext.spec.id, rawUrl)
            val prefix = ExtensionOrigins.originFor(ctx.ext.spec.id) + "/"
            if (!normalized.startsWith(prefix)) {
                throw ApiException(ErrorCodes.BAD_REQUEST, "offscreen url 必须指向当前扩展自身的页面")
            }
            val path = normalized.removePrefix(prefix)
            if (ExtensionPages.open(ctx.ext, path)?.mime != "text/html") {
                throw ApiException(ErrorCodes.BAD_REQUEST, "offscreen url 必须指向扩展包内存在的 HTML 文件")
            }
            val created = withContext(Dispatchers.Main.immediate) { pages.createOffscreen(ctx.ext, path) }
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "该扩展已经存在 offscreen document，或当前 WebView 无法创建")
            try {
                withTimeout(15_000L) { created.readySignal.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                withContext(Dispatchers.Main.immediate) { pages.closeOffscreen(ctx.ext.spec.id) }
                throw ApiException(ErrorCodes.TIMEOUT, "offscreen document 在 15 秒内未完成加载和握手")
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main.immediate) { pages.closeOffscreen(ctx.ext.spec.id) }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main.immediate) { pages.closeOffscreen(ctx.ext.spec.id) }
                throw ApiException(ErrorCodes.INTERNAL, "offscreen document 加载失败")
            }
            null
        }
        router.register("offscreen.closeDocument") { ctx, _ ->
            requireMv3(ctx)
            requireExtensionPage(ctx)
            withContext(Dispatchers.Main.immediate) { pages.closeOffscreen(ctx.ext.spec.id) }
            null
        }
        router.register("offscreen.hasDocument") { ctx, _ ->
            requireMv3(ctx)
            requireExtensionPage(ctx)
            withContext(Dispatchers.Main.immediate) { pages.hasOffscreen(ctx.ext.spec.id) }
        }
    }

    private fun requireMv3(ctx: CallContext) {
        if (ctx.ext.spec.manifestVersion != ManifestVersion.MV3) {
            throw ApiException(ErrorCodes.UNSUPPORTED, "offscreen API 仅支持 Manifest V3")
        }
    }

    private fun requireExtensionPage(ctx: CallContext) {
        if (ctx.sender.kind != SenderKind.EXTENSION_PAGE) {
            throw ApiException(ErrorCodes.DENIED_PERMISSION, "offscreen API 只能从扩展页面调用")
        }
    }
}
