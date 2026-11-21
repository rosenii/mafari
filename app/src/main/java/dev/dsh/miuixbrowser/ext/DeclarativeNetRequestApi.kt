package dev.dsh.miuixbrowser.ext

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * MV3 declarativeNetRequest dynamic rules. The implementation deliberately supports a safe,
 * useful subset (block / redirect / allow, URL and resource-type conditions); unsupported
 * conditions are rejected rather than silently broadening a rule.
 */
class DeclarativeNetRequestApi(private val dataDir: (String) -> File, private val extensions: () -> List<InstalledExtension>) {
    private data class Rule(val json: JSONObject, val id: Int, val priority: Int, val action: String)
    private val cache = ConcurrentHashMap<String, List<Rule>>()

    fun register(router: ApiRouter) {
        router.register("declarativeNetRequest.getDynamicRules") { ctx, _ ->
            JSONArray(load(ctx.ext).map { JSONObject(it.json.toString()) })
        }
        router.register("declarativeNetRequest.updateDynamicRules") { ctx, args ->
            val update = args.optJSONObject(0) ?: JSONObject()
            val remove = update.optJSONArray("removeRuleIds") ?: JSONArray()
            val add = update.optJSONArray("addRules") ?: JSONArray()
            val current = load(ctx.ext).associateBy { it.id }.toMutableMap()
            for (i in 0 until remove.length()) current.remove(remove.optInt(i))
            val parsed = (0 until add.length()).map { i -> parseRule(add.optJSONObject(i) ?: throw bad("addRules[$i] 必须是对象")) }
            parsed.forEach { if (it.id in current) throw bad("规则 ID 已存在：${it.id}") else current[it.id] = it }
            if (current.size > MAX_RULES) throw bad("动态规则最多 $MAX_RULES 条")
            save(ctx.ext, current.values.sortedWith(compareByDescending<Rule> { it.priority }.thenBy { it.id }))
            JSONObject().put("rules", current.size)
        }
        router.register("declarativeNetRequest.getEnabledRulesets") { _, _ -> JSONArray() }
        router.register("declarativeNetRequest.updateEnabledRulesets") { _, args ->
            val o = args.optJSONObject(0) ?: JSONObject()
            if ((o.optJSONArray("enableRulesetIds")?.length() ?: 0) > 0 || (o.optJSONArray("disableRulesetIds")?.length() ?: 0) > 0)
                throw ApiException(ErrorCodes.UNSUPPORTED, "当前仅支持动态规则，尚未加载 manifest 静态 ruleset")
            null
        }
    }

    fun forget(extId: String) { cache.remove(extId) }

    /** Called on WebView's request interception thread. */
    fun intercept(tabId: Int, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val type = requestType(request)
        val method = request.method.uppercase()
        val candidates = extensions().asSequence()
            .filter { it.enabled && PermissionChecker.effectivePermissions(it).any { p -> p == "declarativeNetRequest" || p == "declarativeNetRequestWithHostAccess" } }
            .flatMap { ext -> load(ext).asSequence().map { ext to it } }
            .filter { (ext, rule) -> matches(rule.json.optJSONObject("condition") ?: JSONObject(), url, tabId, type, method, ext) }
            .sortedWith(compareByDescending<Pair<InstalledExtension, Rule>> { it.second.priority }.thenBy { actionRank(it.second.action) })
            .toList()
        val winner = candidates.firstOrNull() ?: return null
        val (ext, rule) = winner
        if (rule.action == "allow") return null
        val action = rule.json.optJSONObject("action") ?: return null
        if (rule.action == "block") return emptyResponse(204)
        if (rule.action == "redirect") {
            val redirect = action.optJSONObject("redirect") ?: return null
            val target = redirect.optString("url")
            if (target.isBlank()) return null
            val parsed = runCatching { Uri.parse(target) }.getOrNull() ?: return null
            if (parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) return null
            val hostAllowed = PermissionChecker.hasHostAccess(ext, target) || "declarativeNetRequestWithHostAccess" in PermissionChecker.effectivePermissions(ext)
            if (!hostAllowed) return null
            return WebResourceResponse("text/plain", "UTF-8", 302, "Found", mapOf("Location" to target), ByteArrayInputStream(ByteArray(0)))
        }
        return null
    }

    private fun load(ext: InstalledExtension): List<Rule> = cache[ext.spec.id] ?: synchronized(cache) {
        cache[ext.spec.id] ?: run {
            val f = File(dataDir(ext.spec.id), "declarative_net_request_dynamic.json")
            val rules = runCatching {
                val a = JSONArray(if (f.isFile) f.readText(Charsets.UTF_8) else "[]")
                (0 until a.length()).mapNotNull { i -> runCatching { parseRule(a.getJSONObject(i)) }.getOrNull() }
            }.getOrDefault(emptyList())
            rules.also { cache[ext.spec.id] = it }
        }
    }

    private fun save(ext: InstalledExtension, rules: List<Rule>) {
        val dir = dataDir(ext.spec.id)
        if (!dir.exists() && !dir.mkdirs()) throw ApiException(ErrorCodes.INTERNAL, "无法创建规则目录")
        val file = File(dir, "declarative_net_request_dynamic.json")
        val tmp = File(dir, file.name + ".tmp")
        val arr = JSONArray().also { a -> rules.forEach { a.put(it.json) } }
        try {
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (file.exists() && !file.delete()) throw IllegalStateException("无法替换规则文件")
            if (!tmp.renameTo(file)) throw IllegalStateException("无法提交规则文件")
            cache[ext.spec.id] = rules
        } catch (e: Exception) {
            tmp.delete()
            throw ApiException(ErrorCodes.INTERNAL, "动态规则保存失败：${e.message}")
        }
    }

    private fun parseRule(input: JSONObject): Rule {
        val id = input.optInt("id", -1)
        if (id <= 0) throw bad("rule.id 必须是正整数")
        val priority = input.optInt("priority", 1)
        if (priority < 1) throw bad("rule.priority 必须大于 0")
        val actionObj = input.optJSONObject("action") ?: throw bad("rule.action 缺失")
        val action = actionObj.optString("type")
        if (action !in setOf("block", "redirect", "allow")) throw ApiException(ErrorCodes.UNSUPPORTED, "暂不支持 DNR action：$action")
        if (action == "redirect") {
            val redirect = actionObj.optJSONObject("redirect") ?: throw bad("redirect action 缺少 redirect")
            val target = redirect.optString("url")
            val uri = runCatching { Uri.parse(target) }.getOrNull()
            if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) throw bad("redirect.url 必须是绝对 HTTP(S) URL")
        }
        val condition = input.optJSONObject("condition") ?: throw bad("rule.condition 缺失")
        val supportedConditions = setOf("urlFilter", "resourceTypes", "excludedResourceTypes", "tabIds", "excludedTabIds", "requestMethods")
        val unsupported = condition.keys().asSequence().filter { it !in supportedConditions }.toList()
        if (unsupported.isNotEmpty()) throw ApiException(ErrorCodes.UNSUPPORTED, "暂不支持 DNR 条件：${unsupported.joinToString()}")
        val urlFilter = condition.optString("urlFilter")
        if (urlFilter.isBlank()) throw bad("condition.urlFilter 必须是非空字符串")
        if (urlFilter.length > 2048) throw bad("urlFilter 过长")
        return Rule(JSONObject(input.toString()), id, priority, action)
    }

    private fun matches(c: JSONObject, url: String, tabId: Int, type: String, method: String, ext: InstalledExtension): Boolean {
        val tabs = c.optJSONArray("tabIds")
        if (tabs != null && (0 until tabs.length()).none { tabs.optInt(it) == tabId }) return false
        val excludedTabs = c.optJSONArray("excludedTabIds")
        if (excludedTabs != null && (0 until excludedTabs.length()).any { excludedTabs.optInt(it) == tabId }) return false
        val types = c.optJSONArray("resourceTypes")
        if (types != null && (0 until types.length()).none { types.optString(it) == type }) return false
        val excludedTypes = c.optJSONArray("excludedResourceTypes")
        if (excludedTypes != null && (0 until excludedTypes.length()).any { excludedTypes.optString(it) == type }) return false
        val methods = c.optJSONArray("requestMethods")
        if (methods != null && (0 until methods.length()).none { methods.optString(it).equals(method, true) }) return false
        val filter = c.optString("urlFilter")
        if (!matchesUrlFilter(filter, url)) return false
        // With host access, DNR may apply only to the granted host; without it, broad DNR is permitted by manifest permission.
        if ("declarativeNetRequestWithHostAccess" in PermissionChecker.effectivePermissions(ext) && !PermissionChecker.hasHostAccess(ext, url)) return false
        return true
    }

    private fun requestType(r: WebResourceRequest): String {
        if (r.isForMainFrame) return "main_frame"
        val accept = r.requestHeaders.entries.firstOrNull { it.key.equals("Accept", true) }?.value.orEmpty().lowercase()
        val path = r.url.path.orEmpty().lowercase()
        return when {
            "image/" in accept -> "image"
            "text/css" in accept || path.endsWith(".css") -> "stylesheet"
            "javascript" in accept || path.endsWith(".js") -> "script"
            "application/json" in accept || "xmlhttprequest" in accept -> "xmlhttprequest"
            "text/html" in accept -> "sub_frame"
            else -> "other"
        }
    }

    private fun actionRank(action: String) = when (action) { "allow" -> 0; "block" -> 1; else -> 2 }
    private fun emptyResponse(code: Int) = WebResourceResponse("text/plain", "UTF-8", code, if (code == 204) "No Content" else "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun bad(message: String) = ApiException(ErrorCodes.BAD_REQUEST, message)
    companion object {
        private const val MAX_RULES = 5000

        /** DNR's common ||domain^, |prefix, suffix| and substring syntax. */
        internal fun matchesUrlFilter(filter: String, url: String): Boolean {
            val f = filter.lowercase()
            val u = url.lowercase()
            if (f == "*") return true
            var pattern = f
            val domainAnchor = pattern.startsWith("||")
            if (domainAnchor) pattern = pattern.substring(2)
            val leftAnchor = !domainAnchor && pattern.startsWith("|")
            if (leftAnchor) pattern = pattern.substring(1)
            val rightAnchor = pattern.endsWith("|")
            if (rightAnchor) pattern = pattern.dropLast(1)
            val regex = buildString {
                append(if (domainAnchor) "^[a-z][a-z0-9+.-]*://([^/]*\\.)?" else if (leftAnchor) "^" else ".*")
                pattern.forEach { ch -> when (ch) {
                    '*' -> append(".*")
                    '^' -> append("(?:[^a-zA-Z0-9_.%-]|$)")
                    else -> append(Regex.escape(ch.toString()))
                } }
                if (rightAnchor) append("$") else append(".*")
            }
            return runCatching { Regex(regex, RegexOption.IGNORE_CASE).matches(u) }.getOrDefault(false)
        }
    }
}
