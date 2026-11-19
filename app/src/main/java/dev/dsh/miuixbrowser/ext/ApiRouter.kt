package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

class CallContext(val ext: InstalledExtension, val sender: Sender, val api: String)

/** 返回值必须是 JSON 兼容类型：JSONObject / JSONArray / String / Number / Boolean / null */
fun interface ApiHandler {
    suspend fun handle(ctx: CallContext, args: JSONArray): Any?
}

/** 原生 → 拓展各上下文的事件推送（storage.onChanged 等）。具体怎么送达由注入层 / 后台宿主实现 */
fun interface EventSink {
    fun emit(extId: String, event: String, payload: JSONObject)
}

/** API 全名（如 "storage.local.get"）→ 处理器。没有处理器的 API 回 UNSUPPORTED（区别于根本不存在的 UNKNOWN_API） */
class ApiRouter {
    private val handlers = HashMap<String, ApiHandler>()

    fun register(api: String, handler: ApiHandler) {
        handlers[api] = handler
    }

    fun find(api: String): ApiHandler? = handlers[api]

    fun registered(): Set<String> = handlers.keys.toSet()
}

/**
 * 引擎无关的运行时入口：传输层把「原始消息 + 可信的来源信息」交进来，拿回要回给页面的响应字符串。
 * 校验顺序：协议 → 拓展存在 → 启用 / 来源 / 权限 → 处理器。
 */
class ExtensionRuntime(
    private val repo: ExtensionRepository,
    private val router: ApiRouter,
) {
    suspend fun handle(raw: String, sender: Sender): String {
        val req = try {
            ApiProtocol.parseRequest(raw)
        } catch (e: ApiException) {
            return ApiProtocol.error(ApiProtocol.bestEffortId(raw), e.code, e.message ?: "")
        }
        return try {
            val ext = repo.get(req.extId) ?: throw ApiException(ErrorCodes.NO_SUCH_EXTENSION, "拓展未安装")
            PermissionChecker.require(ext, req.api, sender)
            val handler = router.find(req.api)
                ?: throw ApiException(ErrorCodes.UNSUPPORTED, "尚未实现：${req.api}")
            ApiProtocol.ok(req.id, handler.handle(CallContext(ext, sender, req.api), req.args))
        } catch (e: ApiException) {
            ApiProtocol.error(req.id, e.code, e.message ?: "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 内部异常不把细节回给页面侧
            ApiProtocol.error(req.id, ErrorCodes.INTERNAL, "内部错误")
        }
    }
}

/** args[i]；越界或 JSON null 都返回 null */
fun JSONArray.argOrNull(i: Int): Any? {
    if (i >= length()) return null
    val v = opt(i)
    return if (v == null || v == JSONObject.NULL) null else v
}
