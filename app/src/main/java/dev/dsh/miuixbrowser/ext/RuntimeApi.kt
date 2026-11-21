package dev.dsh.miuixbrowser.ext

import org.json.JSONObject
import java.io.File

/**
 * runtime.sendMessage 的投递：把消息送到拓展的其它上下文（后台页、popup、其它标签里的 content script）并等回复。
 * 由后台宿主与注入层提供消息 / Port 路由，并通过同一接口查询当前拓展的运行上下文。
 * 参数约定见 [ApiRequest]：JS 垫片已把重载归一化，到这里 [extensionId] 为 null 表示发给自己。
 */
fun interface RuntimeMessenger {
    suspend fun sendMessage(ctx: CallContext, extensionId: String?, message: Any?): Any?
    fun connect(ctx: CallContext, name: String): String = throw ApiException(ErrorCodes.UNSUPPORTED, "runtime.connect 尚未实现")
    fun postPortMessage(ctx: CallContext, portId: String, message: Any?): Unit {
        throw ApiException(ErrorCodes.UNSUPPORTED, "Port 尚未实现")
    }
    fun disconnectPort(ctx: CallContext, portId: String): Unit {
        throw ApiException(ErrorCodes.UNSUPPORTED, "Port 尚未实现")
    }
    fun getContexts(ctx: CallContext, filter: JSONObject): Any? =
        throw ApiException(ErrorCodes.UNSUPPORTED, "runtime.getContexts 尚未实现")
    fun getBackgroundPage(ctx: CallContext): Any? =
        throw ApiException(ErrorCodes.UNSUPPORTED, "MV2 background page reference is unavailable")
}

class RuntimeApi(
    private val messenger: RuntimeMessenger,
    /** 当前浏览器标签桥接；运行时初始化时可先传闭包，真正调用时再读取已 attach 的桥接。 */
    private val tabBridge: () -> TabBridge? = { null },
) {
    fun register(router: ApiRouter) {
        router.register("runtime.getManifest") { ctx, _ ->
            val f = File(ctx.ext.rootDir, "manifest.json")
            I18nMessages.localizeManifest(
                JSONObject(f.readText(Charsets.UTF_8).removePrefix("\uFEFF")), ctx.ext.rootDir, ctx.ext.spec.id,
            )
        }
        router.register("runtime.getURL") { ctx, a ->
            val path = a.argOrNull(0) as? String ?: throw ApiException(ErrorCodes.BAD_REQUEST, "getURL 需要路径")
            ExtensionOrigins.urlFor(ctx.ext.spec.id, path)
        }
        router.register("runtime.getPlatformInfo") { _, _ ->
            JSONObject().put("os", "android").put("arch", arch()).put("nacl_arch", arch())
        }
        router.register("runtime.getContexts") { ctx, a ->
            if (ctx.ext.spec.manifestVersion != ManifestVersion.MV3) {
                throw ApiException(ErrorCodes.UNSUPPORTED, "runtime.getContexts 仅对 Manifest V3 开放")
            }
            val rawFilter = a.argOrNull(0)
            if (rawFilter != null && rawFilter !is JSONObject) {
                throw ApiException(ErrorCodes.BAD_REQUEST, "getContexts filter 必须是对象")
            }
            val filter = rawFilter as? JSONObject ?: JSONObject()
            validateContextFilter(filter)
            messenger.getContexts(ctx, filter)
        }
        router.register("runtime.getBackgroundPage") { ctx, _ ->
            if (ctx.ext.spec.manifestVersion == ManifestVersion.MV3) {
                throw ApiException(ErrorCodes.UNSUPPORTED, "Manifest V3 不存在 background page")
            }
            if (ctx.sender.kind != SenderKind.EXTENSION_PAGE) {
                throw ApiException(ErrorCodes.DENIED_PERMISSION, "runtime.getBackgroundPage 只能从拓展页面调用")
            }
            messenger.getBackgroundPage(ctx)
        }
        router.register("runtime.openOptionsPage") { ctx, _ ->
            // content script 不应直接控制浏览器标签；由拓展自己的后台页 / popup / options 调用。
            if (ctx.sender.kind != SenderKind.EXTENSION_PAGE) {
                throw ApiException(ErrorCodes.DENIED_PERMISSION, "runtime.openOptionsPage 只能从拓展页面调用")
            }
            val page = ctx.ext.spec.optionsPage ?: return@register null
            val bridge = tabBridge()
                ?: throw ApiException(ErrorCodes.UNSUPPORTED, "标签页暂不可用")
            // options_page 来自第三方 manifest，走与 tabs.create 相同的路径校验，拒绝 ../ 等越界路径。
            val url = TabUrlPolicy.normalize(ctx.ext.spec.id, page)
            bridge.create(TabCreateOptions(url = url, active = true))
            null
        }
        router.register("runtime.sendMessage") { ctx, a ->
            val target = a.argOrNull(0) as? String
            // 跨拓展消息需要 externally_connectable，当前不支持
            if (target != null && target != ctx.ext.spec.id) {
                throw ApiException(ErrorCodes.UNSUPPORTED, "不支持向其它拓展发消息")
            }
            messenger.sendMessage(ctx, null, a.argOrNull(1))
        }
        router.register("runtime.connect") { ctx, a ->
            val target = a.argOrNull(0) as? String
            if (target != null && target != ctx.ext.spec.id) {
                throw ApiException(ErrorCodes.UNSUPPORTED, "不支持连接其它拓展")
            }
            // shim 固定传 [targetExtensionId|null, connectInfo]；无目标时仍保留 null 占位。
            val info = a.argOrNull(1) as? JSONObject ?: a.argOrNull(0) as? JSONObject
            val name = info?.optString("name").orEmpty().take(256)
            messenger.connect(ctx, name)
        }
        router.register("runtime.port.postMessage") { ctx, a ->
            val id = a.argOrNull(0) as? String ?: throw ApiException(ErrorCodes.BAD_REQUEST, "Port ID 缺失")
            messenger.postPortMessage(ctx, id, a.argOrNull(1))
            null
        }
        router.register("runtime.port.disconnect") { ctx, a ->
            val id = a.argOrNull(0) as? String ?: throw ApiException(ErrorCodes.BAD_REQUEST, "Port ID 缺失")
            messenger.disconnectPort(ctx, id)
            null
        }
    }

    private fun validateContextFilter(filter: JSONObject) {
        val allowed = setOf(
            "contextTypes", "contextIds", "documentIds", "documentOrigins", "documentUrls",
            "frameIds", "incognito", "tabIds", "windowIds",
        )
        val unknown = filter.keys().asSequence().filter { it !in allowed }.sorted().toList()
        if (unknown.isNotEmpty()) throw ApiException(ErrorCodes.BAD_REQUEST, "getContexts 包含未知过滤字段：${unknown.joinToString()}")
    }

    private fun arch(): String = when (System.getProperty("os.arch")?.lowercase()) {
        "aarch64", "arm64", "armv8l" -> "arm64"
        "arm", "armv7l", "armv8" -> "arm"
        "x86_64", "amd64" -> "x86-64"
        "x86", "i686" -> "x86-32"
        else -> "arm64"
    }
}
