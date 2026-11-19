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
 * 未实现 `registerContentScripts` / `getRegisteredContentScripts` / `updateContentScripts` / `unregisterContentScripts`
 * （要改 [ExtensionInjection] 的装配，单独一步），调用会回 UNSUPPORTED。
 *
 * WebView 上的限制（会写进兼容性提示）：
 * - 只能注入主框架：`frameIds` 只接受 0，`allFrames` 被忽略（只回主框架的结果）；
 * - 没有隔离世界：`world: "ISOLATED"` 也在页面世界里跑，注入的代码拿不到 `chrome` / `browser`，页面脚本能看到它的全局与源码；
 * - `files` 里的脚本抛异常时无法得知（`evaluateJavascript` 对异常与 undefined 都回 null），`func` 的异常会被捕获并报错；
 * - 没有 `activeTab`：必须对该标签当前网址有 host 权限。
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
    private val pollIntervalMs: Long = 50L,
    private val timeoutMs: Long = 15_000L,
    private val newToken: () -> String = { UUID.randomUUID().toString().replace("-", "") },
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
    }

    // ------------------------------------------------------------------ executeScript

    private suspend fun executeScript(ctx: CallContext, d: JSONObject): Any? {
        val tabId = parseTarget(d)
        val func = d.str("func")
        val files = d.files("files")
        if ((func == null) == (files == null)) throw bad("func 与 files 必须且只能提供一个")
        checkWorld(d)

        if (func != null) {
            if (func.length > MAX_SOURCE) throw bad("func 过大")
            val args = when {
                !d.has("args") || d.isNull("args") -> JSONArray()
                else -> d.opt("args") as? JSONArray ?: throw bad("args 必须是数组")
            }
            val argsJson = args.toString()
            if (argsJson.length > MAX_SOURCE) throw bad("args 过大")
            val (b, origin) = access(ctx, tabId)
            val token = newToken()
            val raw = b.evaluate(tabId, funcScript(func, argsJson, origin, token)) ?: throw unavailable()
            var resp = decodeString(raw)?.let { parseObject(it) } ?: throw unavailable()
            if (resp.optInt("pending") == 1) resp = awaitPending(b, tabId, token)
            return toResult(resp)
        }

        val sources = readFiles(ctx, files!!)
        val (b, origin) = access(ctx, tabId)
        val marker = "__mx_" + newToken()
        var last: Any? = JSONObject.NULL
        for (src in sources) {
            val raw = b.evaluate(tabId, fileScript(src, origin, marker)) ?: throw unavailable()
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
    private suspend fun awaitPending(b: TabBridge, tabId: Int, token: String): JSONObject {
        val out = withTimeoutOrNull(timeoutMs) {
            var found: JSONObject? = null
            while (found == null) {
                delay(pollIntervalMs)
                val raw = b.evaluate(tabId, pollScript(token)) ?: throw unavailable()
                found = decodeString(raw)?.let { parseObject(it) }
            }
            found
        }
        if (out == null) {
            // 超时：尽力把槽位清掉
            try {
                b.evaluate(tabId, pollScript(token))
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

    private fun details(a: JSONArray): JSONObject = a.argOrNull(0) as? JSONObject ?: throw bad("缺少参数对象")

    private fun parseTarget(d: JSONObject): Int {
        val t = d.opt("target") as? JSONObject ?: throw bad("缺少 target")
        val id = TabsApi.toInt(t.opt("tabId"), "target.tabId")
        val frames = t.optJSONArray("frameIds")
        if (frames != null && (0 until frames.length()).any { TabsApi.toInt(frames.opt(it), "frameIds") != 0 }) {
            throw bad("只支持主框架（frameId 0）")
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
        if (!PermissionChecker.hasHostAccess(ctx.ext, tab.url)) throw denied
        return b to (originOf(tab.url) ?: throw denied)
    }

    /** `scheme://host[:port]`：小写、去掉用户信息与默认端口，与页面里的 `location.origin` 同形 */
    internal fun originOf(url: String): String? {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
        var hostPort = authority.substringAfterLast('@').lowercase()
        if (hostPort.isEmpty()) return null
        hostPort = hostPort.removeSuffix(if (scheme == "http") ":80" else ":443")
        return "$scheme://$hostPort"
    }

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
