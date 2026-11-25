/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import dev.dsh.miuixbrowser.script.ExtensionStore
import dev.dsh.miuixbrowser.script.UserScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Mafari 专有 API：把浏览器原生 Userscript 库接入 WebExtension。
 * 仅拓展页面可用，且必须在 manifest permissions 中显式声明 mafariScripts。
 * 列表接口只返回元数据；脚本文本需通过 get(id) 单独读取。
 */
class NativeScriptsApi(private val storeProvider: () -> ExtensionStore?) {
    fun register(router: ApiRouter) {
        router.register("mafari.userscripts.getAll") { ctx, _ ->
            requireAvailable(ctx)
            withContext(Dispatchers.Main.immediate) {
                val store = storeOrThrow()
                JSONArray().apply { store.items.forEach { put(metadata(it)) } }
            }
        }
        router.register("mafari.userscripts.get") { ctx, args ->
            requireAvailable(ctx)
            val id = args.optString(0)
            withContext(Dispatchers.Main.immediate) {
                val script = storeOrThrow().items.firstOrNull { it.id == id }
                    ?: throw ApiException(ErrorCodes.BAD_REQUEST, "脚本不存在")
                metadata(script).put("code", script.code)
            }
        }
        router.register("mafari.userscripts.create") { ctx, args ->
            requireAvailable(ctx)
            val name = args.optString(0)
            val code = args.optString(1)
            val match = args.optString(2)
            validateCode(code)
            withContext(Dispatchers.Main.immediate) {
                val store = storeOrThrow()
                store.add(name, code, match)
                metadata(store.items.last())
            }
        }
        router.register("mafari.userscripts.update") { ctx, args ->
            requireAvailable(ctx)
            val id = args.optString(0)
            val name = args.optString(1)
            val code = args.optString(2)
            val enabled = args.optBoolean(3, true)
            val match = args.optString(4)
            validateCode(code)
            withContext(Dispatchers.Main.immediate) {
                val store = storeOrThrow()
                if (store.items.none { it.id == id }) throw ApiException(ErrorCodes.BAD_REQUEST, "脚本不存在")
                store.update(id, name, code, enabled, match)
                metadata(store.items.first { it.id == id })
            }
        }
        router.register("mafari.userscripts.setEnabled") { ctx, args ->
            requireAvailable(ctx)
            val id = args.optString(0)
            val enabled = args.optBoolean(1)
            withContext(Dispatchers.Main.immediate) {
                val store = storeOrThrow()
                if (store.items.none { it.id == id }) throw ApiException(ErrorCodes.BAD_REQUEST, "脚本不存在")
                store.setEnabled(id, enabled)
                metadata(store.items.first { it.id == id })
            }
        }
        router.register("mafari.userscripts.remove") { ctx, args ->
            requireAvailable(ctx)
            val id = args.optString(0)
            withContext(Dispatchers.Main.immediate) {
                val store = storeOrThrow()
                if (store.items.none { it.id == id }) throw ApiException(ErrorCodes.BAD_REQUEST, "脚本不存在")
                store.remove(id)
                true
            }
        }
        router.register("mafari.userscripts.install") { ctx, args ->
            requireAvailable(ctx)
            val url = validateSourceUrl(args.optString(0))
            // install() 在 IO dispatcher 下载；回到 Main 后再修改 Compose state list。
            withContext(Dispatchers.Main.immediate) {
                val script = storeOrThrow().install(url).getOrElse {
                    throw ApiException(ErrorCodes.BAD_REQUEST, it.message ?: "脚本安装失败")
                }
                metadata(script).put("code", script.code)
            }
        }
        router.register("mafari.userscripts.refresh") { ctx, args ->
            requireAvailable(ctx)
            val id = args.optString(0)
            withContext(Dispatchers.Main.immediate) {
                val store = storeOrThrow()
                val updated = store.refresh(id).getOrElse {
                    throw ApiException(ErrorCodes.BAD_REQUEST, it.message ?: "脚本更新失败")
                }
                JSONObject()
                    .put("updated", updated)
                    .put("script", store.items.firstOrNull { it.id == id }?.let(::metadata) ?: JSONObject.NULL)
            }
        }
        router.register("mafari.userscripts.refreshAll") { ctx, args ->
            requireAvailable(ctx)
            val onlyEnabled = args.optBoolean(0, false)
            withContext(Dispatchers.Main.immediate) {
                val (updated, total) = storeOrThrow().refreshAll(onlyEnabled)
                JSONObject().put("updated", updated).put("total", total)
            }
        }
        router.register("mafari.userscripts.getAutoUpdate") { ctx, _ ->
            requireAvailable(ctx)
            withContext(Dispatchers.Main.immediate) { storeOrThrow().autoUpdate }
        }
        router.register("mafari.userscripts.setAutoUpdate") { ctx, args ->
            requireAvailable(ctx)
            val mode = args.optInt(0, -1)
            if (mode !in 0..3) throw ApiException(ErrorCodes.BAD_REQUEST, "autoUpdate 仅支持 0、1、2、3")
            withContext(Dispatchers.Main.immediate) {
                storeOrThrow().updateAutoUpdate(mode)
                mode
            }
        }
    }

    private fun requireAvailable(ctx: CallContext) {
        if (ctx.sender.kind != SenderKind.EXTENSION_PAGE) {
            throw ApiException(ErrorCodes.DENIED_PERMISSION, "原生脚本管理 API 仅允许拓展页面调用")
        }
    }

    /** 只在主线程 API handler 中调用，避免提前触发加密数据库初始化。 */
    private fun storeOrThrow(): ExtensionStore = storeProvider()
        ?: throw ApiException(ErrorCodes.UNSUPPORTED, "原生脚本库未接入")

    private fun validateCode(code: String) {
        if (code.isBlank()) throw ApiException(ErrorCodes.BAD_REQUEST, "脚本内容不能为空")
        if (code.length > 1_000_000) throw ApiException(ErrorCodes.BAD_REQUEST, "脚本内容超过 1 MB")
    }

    private fun validateSourceUrl(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: throw ApiException(ErrorCodes.BAD_REQUEST, "脚本来源 URL 无效")
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw ApiException(ErrorCodes.BAD_REQUEST, "脚本来源仅支持无凭据的 HTTP(S) URL")
        }
        return uri.toASCIIString()
    }

    private fun metadata(script: UserScript) = JSONObject()
        .put("id", script.id)
        .put("name", script.name)
        .put("enabled", script.enabled)
        .put("match", script.match)
        .put("runAt", script.runAt)
        .put("icon", script.icon)
        .put("sourceUrl", script.sourceUrl)
        .put("updatedAt", script.updatedAt)
        .put("error", script.error)
}
