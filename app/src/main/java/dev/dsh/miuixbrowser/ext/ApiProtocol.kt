package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object ErrorCodes {
    const val BAD_REQUEST = "BAD_REQUEST"
    const val NO_SUCH_EXTENSION = "NO_SUCH_EXTENSION"
    const val DISABLED = "DISABLED"
    const val DENIED_ORIGIN = "DENIED_ORIGIN"
    const val DENIED_PERMISSION = "DENIED_PERMISSION"
    const val UNKNOWN_API = "UNKNOWN_API"
    const val UNSUPPORTED = "UNSUPPORTED"
    const val QUOTA = "QUOTA"
    const val NO_RECEIVER = "NO_RECEIVER"
    const val TIMEOUT = "TIMEOUT"
    /** 注入页面的脚本自己抛了异常 / 无法执行；message 里是页面侧的错误文本 */
    const val SCRIPT_ERROR = "SCRIPT_ERROR"
    const val INTERNAL = "INTERNAL"
    /** 原生代发的网络请求失败（对应页面里的 TypeError: Failed to fetch） */
    const val NETWORK = "NETWORK"
}

/** 带错误码的失败：会原样（code + message）回给调用方，所以 message 里不要放内部细节 */
class ApiException(val code: String, message: String) : Exception(message)

/**
 * 请求：`{"extId":"…","id":"…","api":"storage.local.get","args":[…]}`
 * 响应：`{"id":"…","ok":true,"result":…}` 或 `{"id":"…","ok":false,"error":{"code":"…","message":"…"}}`
 * 上层（注入的 JS 与原生）只认这个协议，不关心走的是 WebMessageListener 还是别的传输。
 * args 约定：JS 垫片在发送前把可选参数补齐成定长数组（缺省位置放 null），原生不再做重载猜测。
 */
class ApiRequest(val extId: String, val id: String, val api: String, val args: JSONArray)

object ApiProtocol {
    const val MAX_MESSAGE_CHARS = 4 * 1024 * 1024
    private val API_NAME = Regex("^[A-Za-z][A-Za-z0-9]*(\\.[A-Za-z][A-Za-z0-9]*){1,3}$")

    /** 失败时抛 [ApiException]（BAD_REQUEST）。拿得出 id 的话用 [bestEffortId] 给调用方回错误 */
    fun parseRequest(raw: String): ApiRequest {
        if (raw.length > MAX_MESSAGE_CHARS) throw ApiException(ErrorCodes.BAD_REQUEST, "消息过大")
        val o = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            throw ApiException(ErrorCodes.BAD_REQUEST, "不是合法 JSON")
        }
        val id = idOf(o) ?: throw ApiException(ErrorCodes.BAD_REQUEST, "缺少 id")
        val extId = o.optString("extId")
        if (!ExtensionIds.isSafeId(extId)) throw ApiException(ErrorCodes.BAD_REQUEST, "extId 不合法")
        val api = o.optString("api")
        if (!API_NAME.matches(api)) throw ApiException(ErrorCodes.BAD_REQUEST, "api 名称不合法")
        val args = if (o.has("args")) {
            o.optJSONArray("args") ?: throw ApiException(ErrorCodes.BAD_REQUEST, "args 必须是数组")
        } else {
            JSONArray()
        }
        return ApiRequest(extId, id, api, args)
    }

    fun bestEffortId(raw: String): String? = try {
        idOf(JSONObject(raw))
    } catch (_: Exception) {
        null
    }

    private fun idOf(o: JSONObject): String? {
        val v = o.opt("id")
        return when (v) {
            is String -> v.takeIf { it.isNotEmpty() && it.length <= 64 }
            is Int, is Long -> v.toString()
            else -> null
        }
    }

    fun ok(id: String, result: Any?): String =
        JSONObject().put("id", id).put("ok", true).put("result", result ?: JSONObject.NULL).toString()

    fun error(id: String?, code: String, message: String): String =
        JSONObject()
            .put("id", id ?: JSONObject.NULL)
            .put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()
}
