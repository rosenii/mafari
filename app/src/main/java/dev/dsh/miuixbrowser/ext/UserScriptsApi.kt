package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Firefox-style userScripts facade backed by the same dynamic content-script injector as scripting.*. */
class UserScriptsApi(
    private val store: DynamicContentScriptStore,
    private val onChanged: (String) -> Unit,
) {
    fun register(router: ApiRouter) {
        router.register("userScripts.register") { ctx, args ->
            val registrations = args.optJSONArray(0) ?: throw bad("register 需要脚本数组")
            if (registrations.length() == 0) throw bad("脚本数组不能为空")
            val entries = (0 until registrations.length()).map { i -> parse(ctx.ext, registrations.optJSONObject(i) ?: throw bad("脚本项必须是对象")) }
            store.register(ctx.ext, entries)
            onChanged(ctx.ext.spec.id)
            null
        }
        router.register("userScripts.getScripts") { ctx, _ ->
            JSONArray(store.all(ctx.ext).map { entry ->
                JSONObject().put("id", entry.id)
                    .put("matches", JSONArray(entry.spec.matches.map { it.raw }))
                    .put("excludeMatches", JSONArray(entry.spec.excludeMatches.map { it.raw }))
                    .put("js", JSONArray(entry.spec.js.map { JSONObject().put("file", it) }))
                    .put("runAt", runAtName(entry.spec.runAt))
                    .put("allFrames", entry.spec.allFrames)
                    .put("world", entry.spec.world.name)
            })
        }
        router.register("userScripts.unregister") { ctx, args ->
            val options = args.optJSONObject(0)
            val ids = options?.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
            store.unregister(ctx.ext, ids)
            onChanged(ctx.ext.spec.id)
            null
        }
        router.register("userScripts.update") { ctx, args ->
            val updates = args.optJSONArray(0) ?: throw bad("update 需要脚本数组")
            val existing = store.all(ctx.ext).associateBy { it.id }
            val entries = (0 until updates.length()).map { i ->
                val patch = updates.optJSONObject(i) ?: throw bad("脚本项必须是对象")
                val id = patch.optString("id")
                val old = existing[id] ?: throw bad("未注册的脚本 ID：$id")
                val merged = JSONObject()
                    .put("id", id)
                    .put("matches", JSONArray(old.spec.matches.map { it.raw }))
                    .put("excludeMatches", JSONArray(old.spec.excludeMatches.map { it.raw }))
                    .put("js", JSONArray(old.spec.js.map { JSONObject().put("file", it) }))
                    .put("runAt", runAtName(old.spec.runAt))
                    .put("allFrames", old.spec.allFrames)
                    .put("world", old.spec.world.name)
                    .put("persistAcrossSessions", old.persistAcrossSessions)
                patch.keys().forEach { key -> merged.put(key, patch.get(key)) }
                parse(ctx.ext, merged, allowMissingCode = true)
            }
            store.update(ctx.ext, entries)
            onChanged(ctx.ext.spec.id)
            null
        }
    }

    private fun parse(ext: InstalledExtension, o: JSONObject, allowMissingCode: Boolean = false): DynamicContentScriptStore.Entry {
        val id = o.optString("id").takeIf { it.isNotBlank() && it.length <= 128 } ?: throw bad("脚本 id 缺失或过长")
        val matches = strings(o.optJSONArray("matches")).map { MatchPattern.parse(it) ?: throw bad("无效 match pattern：$it") }
        if (matches.isEmpty()) throw bad("matches 不能为空")
        // userScripts registrations must stay within the host permissions already granted to this extension.
        if (matches.any { requested -> ext.grantedHostPermissions.none { granted -> granted.covers(requested) } }) {
            throw ApiException(ErrorCodes.DENIED_PERMISSION, "userScripts.matches 超出已授予的主机权限")
        }
        val exclude = strings(o.optJSONArray("excludeMatches")).map { MatchPattern.parse(it) ?: throw bad("无效 excludeMatches：$it") }
        val js = mutableListOf<String>()
        val jsArray = o.optJSONArray("js")
        if (jsArray != null) for (i in 0 until jsArray.length()) {
            val item = jsArray.optJSONObject(i) ?: throw bad("js[$i] 必须是对象")
            val code = item.optString("code")
            val file = item.optString("file")
            if (code.isNotEmpty() && file.isNotEmpty()) throw bad("js 项不能同时指定 code 和 file")
            if (code.isNotEmpty()) {
                if (code.length > MAX_CODE) throw bad("脚本代码过大")
                val digest = MessageDigest.getInstance("SHA-256").digest((id + "\u0000" + code).toByteArray()).take(12).joinToString("") { "%02x".format(it) }
                val dir = File(ext.rootDir, ".mafari-user-scripts")
                if (!dir.exists() && !dir.mkdirs()) throw ApiException(ErrorCodes.INTERNAL, "无法创建 userScripts 目录")
                File(dir, "$digest.js").writeText(code, Charsets.UTF_8)
                js.add(".mafari-user-scripts/$digest.js")
            } else if (file.isNotEmpty()) {
                if (file.startsWith("/") || '\\' in file || file.split('/').any { it == ".." }) throw bad("不安全的脚本文件路径")
                val root = ext.rootDir.canonicalFile
                val target = File(root, file).canonicalFile
                if (!target.path.startsWith(root.path + File.separator) || !target.isFile) throw bad("脚本文件不存在或路径越界：$file")
                js.add(file)
            } else throw bad("js 项需要 code 或 file")
        }
        if (js.isEmpty() && !allowMissingCode) throw bad("js 不能为空")
        val runAt = when (o.optString("runAt", "document_idle")) {
            "document_start" -> RunAt.DOCUMENT_START
            "document_end" -> RunAt.DOCUMENT_END
            "document_idle" -> RunAt.DOCUMENT_IDLE
            else -> throw bad("无效 runAt")
        }
        val world = when (o.optString("world", "ISOLATED").uppercase()) {
            "ISOLATED" -> ScriptWorld.ISOLATED
            "MAIN" -> ScriptWorld.MAIN
            else -> throw bad("world 只能是 ISOLATED 或 MAIN")
        }
        return DynamicContentScriptStore.Entry(
            id,
            ContentScriptSpec(matches, exclude, js, emptyList(), runAt, o.optBoolean("allFrames"), false, world),
            o.optBoolean("persistAcrossSessions", true),
        )
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    private fun runAtName(value: RunAt) = when (value) { RunAt.DOCUMENT_START -> "document_start"; RunAt.DOCUMENT_END -> "document_end"; RunAt.DOCUMENT_IDLE -> "document_idle" }
    private fun bad(message: String) = ApiException(ErrorCodes.BAD_REQUEST, message)
    companion object { private const val MAX_CODE = 512 * 1024 }
}
