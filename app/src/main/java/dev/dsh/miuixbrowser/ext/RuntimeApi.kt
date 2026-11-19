package dev.dsh.miuixbrowser.ext

import org.json.JSONObject
import java.io.File

/**
 * runtime.sendMessage 的投递：把消息送到拓展的其它上下文（后台页、popup、其它标签里的 content script）并等回复。
 * 需要后台宿主与注入层，下一步实现；这里只定接口。参数约定见 [ApiRequest]：JS 垫片已把重载归一化，
 * 到这里 [extensionId] 为 null 表示发给自己。
 */
fun interface RuntimeMessenger {
    suspend fun sendMessage(ctx: CallContext, extensionId: String?, message: Any?): Any?
}

class RuntimeApi(
    private val messenger: RuntimeMessenger,
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
        router.register("runtime.sendMessage") { ctx, a ->
            val target = a.argOrNull(0) as? String
            // 跨拓展消息需要 externally_connectable，当前不支持
            if (target != null && target != ctx.ext.spec.id) {
                throw ApiException(ErrorCodes.UNSUPPORTED, "不支持向其它拓展发消息")
            }
            messenger.sendMessage(ctx, null, a.argOrNull(1))
        }
    }

    private fun arch(): String = when (System.getProperty("os.arch")?.lowercase()) {
        "aarch64", "arm64", "armv8l" -> "arm64"
        "arm", "armv7l", "armv8" -> "arm"
        "x86_64", "amd64" -> "x86-64"
        "x86", "i686" -> "x86-32"
        else -> "arm64"
    }
}
