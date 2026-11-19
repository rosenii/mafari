package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject

/**
 * permissions.getAll / contains。
 *
 * request/remove 需要浏览器侧的用户授权界面与权限状态事务，目前不伪造成功；
 * origins.contains 采用精确 match-pattern 匹配，避免把更宽的已授权范围错误地推断为已授权。
 */
class PermissionsApi {
    fun register(router: ApiRouter) {
        router.register("permissions.getAll") { ctx, _ ->
            val api = PermissionChecker.effectivePermissions(ctx.ext).sorted()
            val origins = ctx.ext.grantedHostPermissions.map { it.raw }.distinct().sorted()
            JSONObject()
                .put("permissions", JSONArray(api))
                .put("origins", JSONArray(origins))
        }
        router.register("permissions.contains") { ctx, args ->
            val requested = args.argOrNull(0) as? JSONObject
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "permissions.contains 需要对象参数")
            val haveApi = PermissionChecker.effectivePermissions(ctx.ext)
            val haveOrigins = ctx.ext.grantedHostPermissions.map { it.raw }.toSet()
            val api = requested.stringList("permissions")
            val origins = requested.stringList("origins")
            api.all { it in haveApi } && origins.all { it in haveOrigins }
        }
    }

    private fun JSONObject.stringList(key: String): List<String> {
        val raw = opt(key) ?: return emptyList()
        if (raw !is JSONArray) throw ApiException(ErrorCodes.BAD_REQUEST, "$key 必须是字符串数组")
        return (0 until raw.length()).map { i ->
            raw.opt(i) as? String ?: throw ApiException(ErrorCodes.BAD_REQUEST, "$key 必须是字符串数组")
        }
    }
}
