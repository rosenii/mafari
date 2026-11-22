package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import java.util.UUID

/**
 * `scripting.executeScript` / `insertCSS` / `removeCSS`，建在 [TabBridge.evaluate] 之上。
 *
 * 已实现 `target.tabId`、`func` + `args`、`files`、`css`、`files`（CSS）。
 * 支持 register / get / update / unregisterContentScripts；动态脚本注册 / 更新 / 注销后会通知宿主刷新受影响标签的 document-start handler；当前已加载文档不会回溯执行。
 * 当前仅支持扩展包内的 file，不支持内联 code、frameId 非 0 和 MAIN 世界隔离。
 *
 * WebView 上的限制（会写进兼容性提示）：
 * - 只能注入主框架：`frameIds` 只接受 0，`allFrames` 被忽略（只回主框架的结果）；
 * - 隔离世界只在「该拓展的 ISOLATED content script 已在这个标签主框架里跑起来」时才有（见 [TabBridge.evaluateInIsolatedWorld]）：
 *   此时 `world: "ISOLATED"`（默认）的 func / files 真的在拓展自己的隔离世界里执行，能直接用 `chrome` / `browser`，页面看不到它的全局与源码。
 *   没有这个世界（该页面没有匹配的 content script、系统 WebView 太旧）时**降级到页面世界**，行为与以前一致：拿不到 `chrome` / `browser`，页面脚本能看到它；
 *   `world: "MAIN"` 始终在页面世界执行；
 * - `files` 里的脚本抛异常时无法得知（`evaluateJavascript` 对异常与 undefined 都回 null），`func` 的异常会被捕获并报错；
 * - `activeTab`：没有 host 权限时，用户点击过该拓展（快捷菜单）的标签当前 origin 也可以注入，导航到别的 origin 即作废。
 *
 * 安全：
 * - 注入前核对 host 权限；脚本开头再用 `location.origin` 对照「检查权限时的 origin」，
 *   检查与执行之间标签跳到了别的站点就放弃注入，不会把脚本跑到没授权的页面上；
 *   （这里只比 origin：host 权限带路径限制、且同站 SPA 在检查后换了路径时，路径限制不保证生效）
 * - 文件只从拓展目录内读（[ExtensionInjection.fileReader] 已防 `..` / 越界 / 过大）；
 * - 异步结果用一次性随机槽位取回，槽位名不可猜，取走即删。
 */
class ScriptingApi(
    private val bridge: () -> TabBridge?,
    private val dynamicScripts: DynamicContentScriptStore = DynamicContentScriptStore(),
    private val onDynamicScriptsChanged: (String) -> Unit = {},
    private val pollIntervalMs: Long = 50L,
    private val timeoutMs: Long = 15_000L,
    private val newToken: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    /** 用户点击拓展后临时授予的「当前标签」访问；null = 不支持 activeTab */
    private val activeTab: ActiveTabGrants? = null,
) {

    fun register(router: ApiRouter) {
        router.register("scripting.executeScript") { ctx, a -> executeScript(ctx, details(a)) }
        router.register("scripting.insertCSS") { ctx, a ->
            insertCss(ctx, details(a))
            null
        }
        router.register("scripting.removeCSS") { ctx, a ->
            removeCss(ctx, details(a))
            null
        }
        router.register("scripting.registerContentScripts") { ctx, a ->
            val list = a.argOrNull(0) as? JSONArray ?: throw bad("registerContentScripts 需要数组")
            dynamicScripts.register(ctx.ext, (0 until list.length()).map { parseDynamic(ctx, list.optJSONObject(it)) })
            onDynamicScriptsChanged(ctx.ext.spec.id)
            null
        }
        router.register("scripting.getRegisteredContentScripts") { ctx, a ->
            val filter = a.argOrNull(0) as? JSONObject
            val ids = filter?.optJSONArray("ids")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() }
            val out = JSONArray()
            dynamicScripts.all(ctx.ext).filter { ids == null || it.id in ids }.forEach { out.put(dynamicToJson(it)) }
            out
        }
        router.register("scripting.updateContentScripts") { ctx, a ->
            val list = a.argOrNull(0) as? JSONArray ?: throw bad("updateContentScripts 需要数组")
            dynamicScripts.update(ctx.ext, (0 until list.length()).map { parseDynamic(ctx, list.optJSONObject(it)) })
            onDynamicScriptsChanged(ctx.ext.spec.id)
            null
        }
        // ---- Firefox MV2 contentScripts.register（uBlock Origin 等用）：不持久化，只接受 file 形式的 js / css ----
        router.register("contentScripts.register") { ctx, a ->
            val o = a.argOrNull(0) as? JSONObject ?: throw bad("contentScripts.register 需要对象")
            if (o.optString("id").isBlank()) throw bad("缺少脚本 id")
            val copy = JSONObject(o.toString()).put("persistAcrossSessions", false)
            dynamicScripts.register(ctx.ext, listOf(parseDynamic(ctx, copy)))
            onDynamicScriptsChanged(ctx.ext.spec.id)
            o.optString("id")
        }
        router.register("contentScripts.unregister") { ctx, a ->
            val id = (a.argOrNull(0) as? String)?.takeIf { it.isNotBlank() } ?: throw bad("缺少脚本 id")
            dynamicScripts.unregister(ctx.ext, setOf(id))
            onDynamicScriptsChanged(ctx.ext.spec.id)
            null
        }
        // ---- MV2 tabs.executeScript / insertCSS / removeCSS：转到 scripting 的实现（host 权限仍由 access() 校验）----
        router.register("tabs.executeScript") { ctx, a ->
            val d = legacyInjection(a, ctx)
            if (d.has("code")) throw ApiException(ErrorCodes.UNSUPPORTED, "tabs.executeScript 暂不支持内联 code，请改用 file")
            val file = d.str("file") ?: throw bad("tabs.executeScript 需要 file")
            val res = executeScript(ctx, JSONObject().put("target", d.getJSONObject("target")).put("files", JSONArray().put(file)))
            // MV2 返回「每个框架一个结果」的数组，而不是 scripting 的 {frameId, result}
            JSONArray().also { out ->
                (res as? JSONArray)?.let { arr -> for (i in 0 until arr.length()) out.put(arr.optJSONObject(i)?.opt("result") ?: JSONObject.NULL) }
            }
        }
        router.register("tabs.insertCSS") { ctx, a ->
            val d = legacyInjection(a, ctx)
            val inj = JSONObject().put("target", d.getJSONObject("target"))
            if (d.has("code")) inj.put("css", d.optString("code")) else inj.put("files", JSONArray().put(d.str("file") ?: throw bad("tabs.insertCSS 需要 code 或 file")))
            insertCss(ctx, inj)
            null
        }
        router.register("tabs.removeCSS") { ctx, a ->
            val d = legacyInjection(a, ctx)
            val inj = JSONObject().put("target", d.getJSONObject("target"))
            if (d.has("code")) inj.put("css", d.optString("code")) else inj.put("files", JSONArray().put(d.str("file") ?: throw bad("tabs.removeCSS 需要 code 或 file")))
            removeCss(ctx, inj)
            null
        }
        router.register("scripting.unregisterContentScripts") { ctx, a ->
            val filter = a.argOrNull(0) as? JSONObject
            val ids = filter?.optJSONArray("ids")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() }
            dynamicScripts.unregister(ctx.ext, ids)
            onDynamicScriptsChanged(ctx.ext.spec.id)
            null
        }
    }

    private fun parseDynamic(ctx: CallContext, o: JSONObject?): DynamicContentScriptStore.Entry {
        o ?: throw bad("动态脚本条目必须是对象")
        val id = o.optString("id").trim()
        if (id.isEmpty()) throw bad("动态脚本缺少 id")
        val existing = dynamicScripts.all(ctx.ext).firstOrNull { it.id == id }
        val matches = if (o.has("matches")) stringArray(o, "matches").map { MatchPattern.parse(it) ?: throw bad("无效 matches：$it") }
            else existing?.spec?.matches ?: throw bad("动态脚本缺少 matches")
        if (matches.isEmpty()) throw bad("matches 不能为空")
        // 只能注册到用户已经授予的 host 范围；不接受用动态脚本绕过站点权限。
        val grants = ctx.ext.grantedHostPermissions
        if (matches.any { requested -> grants.none { granted -> granted.covers(requested) } }) {
            throw ApiException(ErrorCodes.DENIED_PERMISSION, "动态脚本 matches 超出已授予的网站权限")
        }
        val js = if (o.has("js")) fileEntries(ctx, o.optJSONArray("js"), "js") else existing?.spec?.js.orEmpty()
        val css = if (o.has("css")) fileEntries(ctx, o.optJSONArray("css"), "css") else existing?.spec?.css.orEmpty()
        if (js.isEmpty() && css.isEmpty()) throw bad("动态脚本必须至少包含一个 js 或 css 文件")
        val exclude = if (o.has("excludeMatches")) stringArray(o, "excludeMatches").map { MatchPattern.parse(it) ?: throw bad("无效 excludeMatches：$it") }
            else existing?.spec?.excludeMatches.orEmpty()
        val runAt = when (o.optString("runAt", existing?.spec?.runAt?.let(::runAtName) ?: "document_idle")) {
            "document_start" -> RunAt.DOCUMENT_START
            "document_end" -> RunAt.DOCUMENT_END
            "document_idle" -> RunAt.DOCUMENT_IDLE
            else -> throw bad("runAt 必须是 document_start / document_end / document_idle")
        }
        val allFrames = o.optBoolean("allFrames", existing?.spec?.allFrames ?: false)
        val persist = o.optBoolean("persistAcrossSessions", existing?.persistAcrossSessions ?: true)
        return DynamicContentScriptStore.Entry(
            id,
            ContentScriptSpec(matches, exclude, js, css, runAt, allFrames, false, ScriptWorld.ISOLATED),
            persist,
        )
    }

    private fun fileEntries(ctx: CallContext, arr: JSONArray?, key: String): List<String> {
        arr ?: return emptyList()
        if (arr.length() > MAX_FILES) throw bad("$key 文件数量过多")
        return (0 until arr.length()).map { i ->
            val item = arr.opt(i)
            val path = when (item) {
                is String -> item
                is JSONObject -> item.optString("file").takeIf { it.isNotBlank() }
                    ?: throw bad("$key 目前只支持 file，不支持内联 code")
                else -> throw bad("$key 条目格式错误")
            }
            if (ExtensionInjection.fileReader(ctx.ext.rootDir)(path.trimStart('/')) == null) throw bad("读不到文件：$path")
            path.trimStart('/')
        }
    }

    private fun stringArray(o: JSONObject, key: String): List<String> {
        val arr = o.optJSONArray(key) ?: throw bad("$key 必须是字符串数组")
        return (0 until arr.length()).map { arr.optString(it).takeIf(String::isNotBlank) ?: throw bad("$key 必须是字符串数组") }
    }

    private fun dynamicToJson(e: DynamicContentScriptStore.Entry) = JSONObject()
        .put("id", e.id)
        .put("matches", JSONArray(e.spec.matches.map { it.raw }))
        .put("excludeMatches", JSONArray(e.spec.excludeMatches.map { it.raw }))
        .put("js", JSONArray(e.spec.js.map { JSONObject().put("file", it) }))
        .put("css", JSONArray(e.spec.css.map { JSONObject().put("file", it) }))
        .put("runAt", runAtName(e.spec.runAt))
        .put("allFrames", e.spec.allFrames)
        .put("persistAcrossSessions", e.persistAcrossSessions)

    private fun runAtName(runAt: RunAt) = when (runAt) {
        RunAt.DOCUMENT_START -> "document_start"
        RunAt.DOCUMENT_END -> "document_end"
        RunAt.DOCUMENT_IDLE -> "document_idle"
    }

    // ------------------------------------------------------------------ executeScript

    private suspend fun executeScript(ctx: CallContext, d: JSONObject): Any? {
        val tabId = parseTarget(d)
        val func = d.str("func")
        val files = d.files("files")
        if ((func == null) == (files == null)) throw bad("func 与 files 必须且只能提供一个")
        checkWorld(d)
        val isolated = (d.str("world") ?: "ISOLATED") == "ISOLATED"

        if (func != null) {
            if (func.length > MAX_SOURCE) throw bad("func 过大")
            val args = when {
                !d.has("args") || d.isNull("args") -> JSONArray()
                else -> d.opt("args") as? JSONArray ?: throw bad("args 必须是数组")
            }
            val argsJson = args.toString()
            if (argsJson.length > MAX_SOURCE) throw bad("args 过大")
            val (b, origin) = access(ctx, tabId)
            val ev = Evaluator(b, ctx.ext.spec.id, tabId, isolated)
            val token = newToken()
            val raw = ev.eval(funcScript(func, argsJson, origin, token))?.let { asJsonString(it) } ?: throw unavailable()
            var resp = decodeString(raw)?.let { parseObject(it) } ?: throw unavailable()
            if (resp.optInt("pending") == 1) resp = awaitPending(ev, token)
            return toResult(resp)
        }

        val sources = readFiles(ctx, files!!)
        val (b, origin) = access(ctx, tabId)
        val ev = Evaluator(b, ctx.ext.spec.id, tabId, isolated)
        val marker = "__mx_" + newToken()
        var last: Any? = JSONObject.NULL
        for (src in sources) {
            val raw = ev.eval(fileScript(src, origin, marker)) ?: throw unavailable()
            last = try {
                JSONTokener(raw).nextValue()
            } catch (_: Exception) {
                JSONObject.NULL
            }
            if (last == marker) throw ApiException(ErrorCodes.DENIED_ORIGIN, PAGE_CHANGED)
        }
        val item = JSONObject().put("frameId", 0)
        if (last != JSONObject.NULL) item.put("result", last)
        return JSONArray().put(item)
    }

    /** 异步函数：结果由页面写进一次性槽位，这里轮询取回 */
    private suspend fun awaitPending(ev: Evaluator, token: String): JSONObject {
        val out = withTimeoutOrNull(timeoutMs) {
            var found: JSONObject? = null
            while (found == null) {
                delay(pollIntervalMs)
                val raw = ev.eval(pollScript(token))?.let { asJsonString(it) } ?: throw unavailable()
                found = decodeString(raw)?.let { parseObject(it) }
            }
            found
        }
        if (out == null) {
            // 超时：尽力把槽位清掉
            try {
                ev.eval(pollScript(token))
            } catch (_: Exception) {
            }
            throw ApiException(ErrorCodes.TIMEOUT, "脚本没有在 ${timeoutMs / 1000} 秒内完成")
        }
        return out
    }

    private fun toResult(o: JSONObject): JSONArray {
        if (o.optInt("ok") != 1) {
            if (o.optInt("guard") == 1) throw ApiException(ErrorCodes.DENIED_ORIGIN, PAGE_CHANGED)
            throw ApiException(ErrorCodes.SCRIPT_ERROR, o.optString("m").ifEmpty { "脚本执行失败" })
        }
        val item = JSONObject().put("frameId", 0)
        if (o.has("v")) item.put("result", o.get("v"))
        return JSONArray().put(item)
    }

    // ------------------------------------------------------------------ CSS

    private suspend fun insertCss(ctx: CallContext, d: JSONObject) {
        val tabId = parseTarget(d)
        validateCssOrigin(d)
        val css = cssOf(ctx, d)
        val (b, origin) = access(ctx, tabId)
        val key = cssKey(ctx.ext.spec.id, css)
        val script = "(function(){if(location.origin.toLowerCase()!==" + JSONObject.quote(origin) + ")return\"0\";" +
            "var s=document.createElement(\"style\");s.setAttribute(\"data-mx\",\"$key\");" +
            "s.textContent=" + JSONObject.quote(css) + ";" +
            "(document.head||document.documentElement).appendChild(s);return\"1\"})()"
        expectOne(b.evaluate(tabId, script))
    }

    /** 只删本拓展插入过的、内容完全相同的那一份（用 id + 内容算出的键找） */
    private suspend fun removeCss(ctx: CallContext, d: JSONObject) {
        val tabId = parseTarget(d)
        validateCssOrigin(d)
        val css = cssOf(ctx, d)
        val (b, origin) = access(ctx, tabId)
        val key = cssKey(ctx.ext.spec.id, css)
        val script = "(function(){if(location.origin.toLowerCase()!==" + JSONObject.quote(origin) + ")return\"0\";" +
            "var s=document.querySelector('style[data-mx=\"$key\"]');" +
            "if(s&&s.parentNode)s.parentNode.removeChild(s);return\"1\"})()"
        expectOne(b.evaluate(tabId, script))
    }

    private fun expectOne(raw: String?) {
        val s = raw?.let { decodeString(it) } ?: throw unavailable()
        if (s != "1") throw ApiException(ErrorCodes.DENIED_ORIGIN, PAGE_CHANGED)
    }

    private fun validateCssOrigin(d: JSONObject) {
        val origin = d.str("origin") ?: "AUTHOR"
        if (origin != "AUTHOR" && origin != "USER") throw bad("origin 只能是 AUTHOR 或 USER")
        if (origin == "USER") throw ApiException(ErrorCodes.UNSUPPORTED, "WebView 适配层暂不支持 USER CSS cascade origin")
    }

    private fun cssOf(ctx: CallContext, d: JSONObject): String {
        val css = d.str("css")
        val files = d.files("files")
        if ((css == null) == (files == null)) throw bad("css 与 files 必须且只能提供一个")
        val text = css ?: readFiles(ctx, files!!).joinToString("\n")
        if (text.length > MAX_SOURCE) throw bad("css 过大")
        return text
    }

    private fun cssKey(extId: String, css: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest((extId + "\u0000" + css).toByteArray(Charsets.UTF_8))
        return h.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // ------------------------------------------------------------------ 脚本模板

    /** 同步返回 `{ok:1,v}`；返回 Promise 时先回 `{pending:1}`，结果稍后写进 window 上的一次性槽位 */
    private fun funcScript(func: String, argsJson: String, origin: String, token: String): String = buildString {
        append("(function(){")
        append("var R=function(o){return JSON.stringify(o)};")
        append("var S=function(o){var s;try{s=R(o)}catch(e){s=R({ok:0,m:\"返回值无法序列化\"})};")
        append("Object.defineProperty(window,\"").append(slot(token)).append("\",{value:s,configurable:true})};")
        append("try{")
        append("if(location.origin.toLowerCase()!==").append(JSONObject.quote(origin)).append(")return R({ok:0,guard:1});")
        // func 末尾换行：防止函数源码以注释收尾时吃掉后面的 `.apply(...)`
        append("var r=(").append(func).append("\n).apply(null,").append(argsJson).append(");")
        append("if(r&&typeof r.then===\"function\"){")
        append("r.then(function(v){S({ok:1,v:v})},function(e){S({ok:0,m:String(e&&e.message||e)})});")
        append("return R({pending:1})}")
        append("return R({ok:1,v:r})")
        append("}catch(e){return R({ok:0,m:String(e&&e.message||e)})}")
        append("})()")
    }

    /** 文件源码原样放进块语句：保持全局作用域（var / function 仍是全局），块语句的完成值就是最后一条语句的值 */
    private fun fileScript(src: String, origin: String, marker: String): String =
        "if(location.origin.toLowerCase()===" + JSONObject.quote(origin) + "){\n" + src + "\n}else{" +
            JSONObject.quote(marker) + "}"

    private fun pollScript(token: String): String =
        "(function(){var k=\"" + slot(token) + "\";var v=window[k];if(v===undefined)return null;" +
            "try{delete window[k]}catch(e){}return v})()"

    private fun slot(token: String) = "__mx_$token"

    // ------------------------------------------------------------------ 参数与权限

    /** MV2 (tabId|null, details) → details 里补上 target；tabId 缺省取活动标签；allFrames / frameId 非主框架明确拒绝 */
    private suspend fun legacyInjection(a: JSONArray, ctx: CallContext): JSONObject {
        val details = (a.argOrNull(1) as? JSONObject) ?: throw bad("缺少注入参数")
        if (details.optBoolean("allFrames", false)) throw ApiException(ErrorCodes.UNSUPPORTED, "WebView 上只支持主框架注入；allFrames 不可用")
        if (details.has("frameId") && details.optInt("frameId", 0) != 0) throw ApiException(ErrorCodes.UNSUPPORTED, "只支持主框架（frameId 0）")
        val explicit = a.argOrNull(0)
        val tabId = if (explicit == null || explicit == JSONObject.NULL) {
            (bridge() ?: throw ApiException(ErrorCodes.UNSUPPORTED, "标签页暂不可用")).list().firstOrNull { it.active }?.id
                ?: throw ApiException(ErrorCodes.UNSUPPORTED, "当前没有活动标签页")
        } else TabsApi.toInt(explicit, "tabId")
        return JSONObject(details.toString()).put("target", JSONObject().put("tabId", tabId))
    }

    private fun details(a: JSONArray): JSONObject = a.argOrNull(0) as? JSONObject ?: throw bad("缺少参数对象")

    private fun parseTarget(d: JSONObject): Int {
        val t = d.opt("target") as? JSONObject ?: throw bad("缺少 target")
        val id = TabsApi.toInt(t.opt("tabId"), "target.tabId")
        if (t.optBoolean("allFrames", false)) {
            throw ApiException(ErrorCodes.UNSUPPORTED, "scripting.executeScript 当前只支持主框架；allFrames 不可用")
        }
        if (t.has("documentIds") && !t.isNull("documentIds")) {
            throw ApiException(ErrorCodes.UNSUPPORTED, "WebView 尚不支持按 documentIds 注入")
        }
        val frames = t.optJSONArray("frameIds")
        if (frames != null) {
            if (frames.length() == 0) throw bad("frameIds 不能为空")
            if ((0 until frames.length()).any { TabsApi.toInt(frames.opt(it), "frameIds") != 0 }) {
                throw ApiException(ErrorCodes.UNSUPPORTED, "只支持主框架（frameId 0）")
            }
        }
        return id
    }

    private fun checkWorld(d: JSONObject) {
        val w = d.str("world")
        if (w != null && w != "ISOLATED" && w != "MAIN") throw bad("world 只能是 ISOLATED 或 MAIN")
    }

    /** 标签存在 + 对它当前网址有 host 权限；返回桥接与检查时的 origin（脚本里用它再核对一次） */
    private suspend fun access(ctx: CallContext, tabId: Int): Pair<TabBridge, String> {
        val b = bridge() ?: throw ApiException(ErrorCodes.UNSUPPORTED, "标签页暂不可用")
        val tab = b.get(tabId) ?: throw TabsApi.noTab(tabId)
        val denied = ApiException(
            ErrorCodes.DENIED_PERMISSION,
            "Cannot access contents of the page. Extension manifest must request permission to access the respective host.",
        )
        if (!PermissionChecker.hasHostAccess(ctx.ext, tab.url) && !activeTabAllows(ctx.ext, tab)) throw denied
        return b to (originOf(tab.url) ?: throw denied)
    }

    /** 声明并获授 activeTab，且用户已对这个标签的当前 origin 点击过该拓展 */
    private fun activeTabAllows(ext: InstalledExtension, tab: TabInfo): Boolean =
        activeTab != null &&
            "activeTab" in PermissionChecker.effectivePermissions(ext) &&
            activeTab.isGranted(ext.spec.id, tab.id, tab.url)

    /** `scheme://host[:port]`：与页面里的 `location.origin` 同形 */
    internal fun originOf(url: String): String? = UrlOrigin.of(url)

    private fun readFiles(ctx: CallContext, files: List<String>): List<String> {
        val read = ExtensionInjection.fileReader(ctx.ext.rootDir)
        var total = 0L
        return files.map { path ->
            val text = read(path.trimStart('/')) ?: throw bad("读不到文件：$path")
            total += text.length
            if (total > MAX_SOURCE) throw bad("文件总量过大")
            text
        }
    }

    private fun JSONObject.str(key: String): String? = with(TabsApi) { stringOrNull(key) }

    private fun JSONObject.files(key: String): List<String>? {
        if (!has(key) || isNull(key)) return null
        val a = opt(key) as? JSONArray ?: throw bad("$key 必须是字符串数组")
        if (a.length() == 0 || a.length() > MAX_FILES) throw bad("$key 的数量必须在 1 到 $MAX_FILES 之间")
        return (0 until a.length()).map { a.opt(it) as? String ?: throw bad("$key 必须是字符串数组") }
    }

    /**
     * 一次 executeScript 调用里所有脚本（本体 + 轮询）共用的执行器：要隔离世界就先试隔离世界；
     * 第一次发现没有入口就降级到页面世界，并且**本次调用之后一直留在页面世界**，保证 func 与它的异步结果槽位在同一个世界里。
     */
    private class Evaluator(
        private val b: TabBridge,
        private val extId: String,
        private val tabId: Int,
        wantIsolated: Boolean,
    ) {
        private var isolated = wantIsolated

        suspend fun eval(js: String): String? {
            if (isolated) {
                when (val r = b.evaluateInIsolatedWorld(extId, tabId, js)) {
                    is WorldEval.Done -> return r.raw
                    WorldEval.Unavailable -> isolated = false
                }
            }
            return b.evaluate(tabId, js)
        }
    }

    /**
     * func / 轮询脚本的返回值是 JSON 字符串，`evaluateJavascript` 会把它再包成带引号的字符串字面量；
     * 隔离世界的 `executeJavaScript` 若直接回内层文本（以 `{` 开头），这里补成同样的字符串字面量，后面统一走 [decodeString]。
     */
    private fun asJsonString(raw: String): String = if (raw.startsWith("{")) JSONObject.quote(raw) else raw

    /** `evaluateJavascript` 回的是「JSON 文本」：脚本返回字符串时是带引号的字符串字面量，这里还原；不是字符串返回 null */
    private fun decodeString(raw: String): String? = try {
        JSONTokener(raw).nextValue() as? String
    } catch (_: Exception) {
        null
    }

    private fun parseObject(s: String): JSONObject? = try {
        JSONObject(s)
    } catch (_: Exception) {
        null
    }

    private fun bad(msg: String) = ApiException(ErrorCodes.BAD_REQUEST, msg)
    private fun unavailable() = ApiException(ErrorCodes.SCRIPT_ERROR, "无法在该页面执行脚本（标签已关闭、正在显示主页或错误页）")

    private companion object {
        const val MAX_SOURCE = 1024 * 1024
        const val MAX_FILES = 20
        const val PAGE_CHANGED = "页面已跳转，已取消注入"
    }
}
