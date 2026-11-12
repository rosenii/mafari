package dev.dsh.miuixbrowser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class UserScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val code: String,
    val enabled: Boolean = true,
    /** 油猴 @match / @include 规则，逗号分隔；空表示所有站点 */
    val match: String = "",
    /** 从 URL 导入时的来源，方便更新 */
    val sourceUrl: String = "",
    /** 油猴 @run-at，默认 document-end */
    val runAt: String = "document-end",
    /** 油猴 @icon，脚本列表左侧显示 */
    val icon: String = "",
    /** 最近一次成功从来源地址拉取 / 更新的时间（毫秒）；本地脚本为 0 */
    val updatedAt: Long = 0L,
    /** 最近一次更新失败的原因；空表示正常 */
    val error: String = "",
) {
    fun matches(url: String): Boolean {
        val patterns = match.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (patterns.isEmpty()) return true
        return patterns.any { pattern ->
            val escaped = pattern.replace(".", "\\.").replace("*", ".*")
            runCatching { Regex("^$escaped$") }.getOrNull()?.containsMatchIn(url) == true
        }
    }
}

/** 从油猴元数据里抽 @name / @match / @run-at */
fun parseUserScript(code: String): Triple<String, String, String> {
    val name = Regex("@name\\s+(.+)").find(code)?.groupValues?.get(1)?.trim().orEmpty()
    val matches = Regex("@(?:match|include)\\s+(.+)").findAll(code)
        .map { it.groupValues[1].trim() }
        .joinToString(",")
    val runAt = Regex("@run-at\\s+(.+)").find(code)?.groupValues?.get(1)?.trim()
        ?.substringBefore(" ")?.trim() ?: "document-end"
    return Triple(name, matches, runAt)
}

class ExtensionStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("mafari_ext", Context.MODE_PRIVATE)

    /**
     * 旧版本把去广告订阅也存在脚本列表里（subscriptionUrl 非空）。
     * 读取时把它们从脚本列表里剥离出来，交给 AdBlock.migrateLegacy，
     * 迁移完成后调用 commitLegacyMigration 落盘。
     */
    val legacyAdSubs = mutableListOf<LegacyAdSub>()

    val items = mutableStateListOf<UserScript>().apply { addAll(load()) }

    fun commitLegacyMigration() {
        if (legacyAdSubs.isEmpty()) return
        legacyAdSubs.clear()
        persist()
    }

    private fun load(): List<UserScript> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val legacyUrl = obj.optString("subscriptionUrl")
                    if (legacyUrl.isNotBlank()) {
                        legacyAdSubs.add(
                            LegacyAdSub(
                                name = obj.optString("name"),
                                url = legacyUrl,
                                enabled = obj.optBoolean("enabled", true),
                            ),
                        )
                        continue
                    }
                    add(
                        UserScript(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            code = obj.optString("code"),
                            enabled = obj.optBoolean("enabled", true),
                            match = obj.optString("match"),
                            sourceUrl = obj.optString("sourceUrl"),
                            runAt = obj.optString("runAt", "document-end"),
                            icon = obj.optString("icon"),
                            updatedAt = obj.optLong("updatedAt", 0L),
                            error = obj.optString("error"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(
        name: String,
        code: String,
        match: String = "",
        sourceUrl: String = "",
    ) {
        val (parsedName, parsedMatch, parsedRunAt) = parseUserScript(code)
        items.add(
            UserScript(
                name = name.ifBlank { parsedName.ifBlank { "未命名脚本" } },
                code = code,
                match = match.ifBlank { parsedMatch },
                sourceUrl = sourceUrl,
                runAt = parsedRunAt,
                icon = UserscriptEngine.parse(code).icon,
                updatedAt = if (sourceUrl.isNotBlank()) System.currentTimeMillis() else 0L,
            ),
        )
        persist()
        prefetchResources(code)
    }

    /* ------------------------------------------------------------------ */
    /* 网络：安装 / 更新 / 自动更新 / 首次启动自动安装推荐脚本                */
    /* ------------------------------------------------------------------ */

    /** 自动更新：0 手动 1 每天 2 每 3 天 3 每周（与「去广告」订阅一致） */
    var autoUpdate by mutableIntStateOf(prefs.getInt("auto_update", 2).coerceIn(0, 3))
        private set

    fun updateAutoUpdate(mode: Int) {
        autoUpdate = mode.coerceIn(0, 3)
        prefs.edit().putInt("auto_update", autoUpdate).apply()
    }

    /** 从地址下载并作为新脚本加入列表；已存在同来源的脚本则改为更新它。 */
    suspend fun install(url: String): Result<UserScript> {
        val text = fetchUserScriptText(url).getOrElse { return Result.failure(it) }
        return Result.success(installText(url, text))
    }

    /** 已经拿到脚本文本时直接安装 / 更新（同来源地址只保留一份）。 */
    fun installText(url: String, text: String): UserScript {
        val existing = items.firstOrNull { it.sourceUrl == url }
        if (existing != null) {
            applyRemote(existing.id, text)
            return items.first { it.id == existing.id }
        }
        add("", text, sourceUrl = url)
        return items.last()
    }

    /** 重新下载某个脚本的来源地址。返回 true = 内容有变化并已更新，false = 已是最新。 */
    suspend fun refresh(id: String): Result<Boolean> {
        val script = items.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalStateException("脚本不存在"))
        if (script.sourceUrl.isBlank()) {
            return Result.failure(IllegalStateException("本地脚本没有更新地址"))
        }
        val text = fetchUserScriptText(script.sourceUrl).getOrElse {
            markError(id, it.message ?: "更新失败")
            return Result.failure(it)
        }
        return Result.success(applyRemote(id, text))
    }

    /** 更新全部有来源地址的脚本，返回 (成功数, 总数)；onlyEnabled 时只更新已启用的。 */
    suspend fun refreshAll(onlyEnabled: Boolean = false): Pair<Int, Int> {
        val targets = items.filter { it.sourceUrl.isNotBlank() && (!onlyEnabled || it.enabled) }.map { it.id }
        var ok = 0
        for (id in targets) if (refresh(id).isSuccess) ok++
        return ok to targets.size
    }

    /** 按设置的间隔在后台自动更新脚本，启动时调用一次。 */
    suspend fun autoUpdateIfDue() {
        val days = when (autoUpdate) {
            1 -> 1
            2 -> 3
            3 -> 7
            else -> return
        }
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_auto", 0L) < days * 86_400_000L) return
        if (items.none { it.sourceUrl.isNotBlank() }) return
        prefs.edit().putLong("last_auto", now).apply()
        refreshAll()
    }

    /**
     * 首次打开 app 自动拉取并安装「推荐脚本」。
     * 每个推荐脚本只自动安装一次（记在 bootstrapped 里）：用户之后删掉它不会被再装回来；
     * 下载失败的下次启动继续重试。
     */
    suspend fun bootstrapRecommended() {
        val done = prefs.getStringSet("bootstrapped", emptySet()).orEmpty().toMutableSet()
        var changed = false
        for (builtin in BUILTIN_SCRIPTS) {
            val url = builtin.userScriptUrl
            if (url.isBlank() || url in done) continue
            if (items.any { it.sourceUrl == url }) { // 已经装过（旧版本手动添加）
                done += url; changed = true
                continue
            }
            if (install(url).isSuccess) { done += url; changed = true }
        }
        if (changed) prefs.edit().putStringSet("bootstrapped", done).apply()
    }

    private fun markError(id: String, message: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        items[index] = items[index].copy(error = message)
        persist()
    }

    /** 把下载到的脚本写回：名称 / 启用状态 / 自定义匹配保持不变。返回内容是否有变化。 */
    private fun applyRemote(id: String, text: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val old = items[index]
        val changed = old.code != text
        val (_, _, parsedRunAt) = parseUserScript(text)
        items[index] = if (changed) {
            old.copy(
                code = text,
                runAt = parsedRunAt,
                icon = UserscriptEngine.parse(text).icon.ifBlank { old.icon },
                updatedAt = System.currentTimeMillis(),
                error = "",
            )
        } else {
            old.copy(updatedAt = System.currentTimeMillis(), error = "")
        }
        persist()
        if (changed) prefetchResources(text)
        return changed
    }

    fun update(id: String, name: String, code: String, enabled: Boolean, match: String = "") {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        val (parsedName, parsedMatch, parsedRunAt) = parseUserScript(code)
        items[index] = items[index].copy(
            name = name.ifBlank { parsedName },
            code = code,
            enabled = enabled,
            match = match.ifBlank { parsedMatch },
            runAt = parsedRunAt,
            icon = UserscriptEngine.parse(code).icon.ifBlank { items[index].icon },
        )
        persist()
        prefetchResources(code)
    }

    /** 后台把 @require / @resource 拉到本地缓存，注入时只读缓存 */
    private fun prefetchResources(code: String) {
        Thread { runCatching { ScriptResources.prefetch(appContext, code) } }.start()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        items[index] = items[index].copy(enabled = enabled)
        persist()
    }

    /** 网页增强总开关联动：全部脚本一起开 / 关 */
    fun setAllEnabled(enabled: Boolean) {
        var changed = false
        items.forEachIndexed { index, script ->
            if (script.enabled != enabled) {
                items[index] = script.copy(enabled = enabled)
                changed = true
            }
        }
        if (changed) persist()
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("code", it.code)
                    .put("enabled", it.enabled)
                    .put("match", it.match)
                    .put("sourceUrl", it.sourceUrl)
                    .put("runAt", it.runAt)
                    .put("icon", it.icon)
                    .put("updatedAt", it.updatedAt)
                    .put("error", it.error),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "scripts"
    }
}

/** 下载脚本文本并校验确实是油猴脚本（防止把错误页 / 登录页当成脚本覆盖掉）。 */
suspend fun fetchUserScriptText(url: String, allowFilterList: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 mafari")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("服务器返回 HTTP $code")
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }.recoverCatching {
        throw IllegalStateException(it.message?.takeIf { m -> m.startsWith("服务器返回") } ?: "下载失败，检查网络或链接")
    }.mapCatching { text ->
        // 只放行带 ==UserScript== 头的脚本；allowFilterList 时也放行去广告规则列表（由调用方转交去广告）
        if (!text.contains("==UserScript==") && !(allowFilterList && AdParser.looksLikeFilterList(text))) error("不是有效的油猴脚本")
        text
    }
}
