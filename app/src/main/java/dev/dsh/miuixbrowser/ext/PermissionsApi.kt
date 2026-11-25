/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject

/** A user-mediated request for optional extension permissions. */
fun interface ExtensionPermissionPrompt {
    suspend fun confirm(ext: InstalledExtension, permissions: Set<String>, origins: List<MatchPattern>): Boolean
}

/** Firefox-compatible optional permission lifecycle. Required permissions cannot be removed. */
class PermissionsApi(
    private val repo: ExtensionRepository,
    private val events: EventSink,
    private val prompt: ExtensionPermissionPrompt,
    private val onPermissionsChanged: (String) -> Unit,
) {
    constructor(repo: ExtensionRepository, events: EventSink, prompt: ExtensionPermissionPrompt) :
        this(repo, events, prompt, {})

    fun register(router: ApiRouter) {
        router.register("permissions.getAll") { ctx, _ ->
            val api = PermissionChecker.effectivePermissions(ctx.ext).sorted()
            val origins = ctx.ext.grantedHostPermissions.map { it.raw }.distinct().sorted()
            JSONObject().put("permissions", JSONArray(api)).put("origins", JSONArray(origins))
        }
        router.register("permissions.contains") { ctx, args ->
            val requested = args.argOrNull(0) as? JSONObject
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "permissions.contains 需要对象参数")
            val haveApi = PermissionChecker.effectivePermissions(ctx.ext)
            val haveOrigins = ctx.ext.grantedHostPermissions
            val api = requested.stringList("permissions")
            val origins = requested.stringList("origins").map { parsePattern(it) }
            api.all { it in haveApi } && origins.all { wanted -> haveOrigins.any { it.covers(wanted) } }
        }
        router.register("permissions.request") { ctx, args ->
            if (ctx.sender.kind != SenderKind.EXTENSION_PAGE) {
                throw ApiException(ErrorCodes.DENIED_PERMISSION, "permissions.request 只能从拓展页面调用")
            }
            val requested = args.argOrNull(0) as? JSONObject
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "permissions.request 需要对象参数")
            val api = requested.stringList("permissions").toSet()
            val origins = requested.stringList("origins").map(::parsePattern).distinctBy { it.raw }
            val optionalApi = ctx.ext.spec.optionalApiPermissions
            val optionalHosts = ctx.ext.spec.optionalHostPermissions
            if (api.any { it !in optionalApi && it !in PermissionChecker.effectivePermissions(ctx.ext) }) {
                throw ApiException(ErrorCodes.DENIED_PERMISSION, "只能请求 manifest.optional_permissions 中声明的权限")
            }
            if (origins.any { wanted -> optionalHosts.none { it.covers(wanted) } && ctx.ext.grantedHostPermissions.none { it.covers(wanted) } }) {
                throw ApiException(ErrorCodes.DENIED_PERMISSION, "只能请求 manifest.optional_host_permissions 中声明的网站权限")
            }
            val missingApi = api - PermissionChecker.effectivePermissions(ctx.ext)
            val missingOrigins = origins.filter { wanted -> ctx.ext.grantedHostPermissions.none { it.covers(wanted) } }
            if (missingApi.isEmpty() && missingOrigins.isEmpty()) return@register true
            if (!prompt.confirm(ctx.ext, missingApi, missingOrigins)) return@register false
            val latest = repo.get(ctx.ext.spec.id) ?: throw ApiException(ErrorCodes.NO_SUCH_EXTENSION, "拓展已卸载")
            val updated = latest.copy(
                grantedApiPermissions = latest.grantedApiPermissions + missingApi,
                grantedHostPermissions = (latest.grantedHostPermissions + missingOrigins).distinctBy { it.raw },
            )
            repo.put(updated)
            onPermissionsChanged(updated.spec.id)
            events.emit(updated.spec.id, "permissions.onAdded", permissionPayload(missingApi, missingOrigins))
            true
        }
        router.register("permissions.remove") { ctx, args ->
            if (ctx.sender.kind != SenderKind.EXTENSION_PAGE) {
                throw ApiException(ErrorCodes.DENIED_PERMISSION, "permissions.remove 只能从拓展页面调用")
            }
            val requested = args.argOrNull(0) as? JSONObject
                ?: throw ApiException(ErrorCodes.BAD_REQUEST, "permissions.remove 需要对象参数")
            val api = requested.stringList("permissions").toSet()
            val origins = requested.stringList("origins").map(::parsePattern)
            if (api.any { it !in ctx.ext.spec.optionalApiPermissions }) {
                throw ApiException(ErrorCodes.DENIED_PERMISSION, "必需权限不能移除")
            }
            if (origins.any { wanted ->
                    ctx.ext.spec.optionalHostPermissions.none { it.covers(wanted) } ||
                        ctx.ext.grantedHostPermissions.none { it.raw == wanted.raw }
                }) {
                throw ApiException(ErrorCodes.DENIED_PERMISSION, "只能移除已授予的 optional_host_permissions 完整规则")
            }
            val latest = repo.get(ctx.ext.spec.id) ?: throw ApiException(ErrorCodes.NO_SUCH_EXTENSION, "拓展已卸载")
            val removedApi = api.intersect(latest.grantedApiPermissions)
            val removedOrigins = origins.filter { wanted -> latest.grantedHostPermissions.any { it.raw == wanted.raw } }
            repo.put(latest.copy(
                grantedApiPermissions = latest.grantedApiPermissions - removedApi,
                grantedHostPermissions = latest.grantedHostPermissions.filterNot { it.raw in removedOrigins.map(MatchPattern::raw) },
            ))
            if (removedApi.isNotEmpty() || removedOrigins.isNotEmpty()) {
                onPermissionsChanged(latest.spec.id)
                events.emit(latest.spec.id, "permissions.onRemoved", permissionPayload(removedApi, removedOrigins))
            }
            removedApi.isNotEmpty() || removedOrigins.isNotEmpty()
        }
    }

    private fun permissionPayload(api: Set<String>, origins: List<MatchPattern>) = JSONObject()
        .put("permissions", JSONArray(api.sorted()))
        .put("origins", JSONArray(origins.map { it.raw }.sorted()))

    private fun parsePattern(raw: String): MatchPattern = MatchPattern.parse(raw)
        ?: throw ApiException(ErrorCodes.BAD_REQUEST, "无效的网站匹配规则：$raw")

    private fun JSONObject.stringList(key: String): List<String> {
        val raw = opt(key) ?: return emptyList()
        if (raw !is JSONArray) throw ApiException(ErrorCodes.BAD_REQUEST, "$key 必须是字符串数组")
        return (0 until raw.length()).map { i ->
            raw.opt(i) as? String ?: throw ApiException(ErrorCodes.BAD_REQUEST, "$key 必须是字符串数组")
        }
    }
}
