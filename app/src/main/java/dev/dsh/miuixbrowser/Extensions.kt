package dev.dsh.miuixbrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
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

    /** 加密库；打不开为 null。下面这些属性必须声明在 items 之前（属性按声明顺序初始化，load() 会用到）。 */
    private val db: SQLiteDatabase? = HistoryDb.get(appContext)

    /** 本次运行是否用加密库存脚本；读库出错会置 false，退回旧存储（SharedPreferences）。 */
    private var useDb = db != null

    /** 读库时发现旧数据 id 为空 / 重复时置位。 */
    private var needsPersist = false

    // 写库按行增量、延后一小会儿合并成一次，并放到写库线程
    private val main = Handler(Looper.getMainLooper())
    private var dirty = false
    private var firstDirtyAt = 0L
    private val flushTask = Runnable { flush() }

    /** 上次成功写进库里的内容（只在写库线程读写；初始值在构造时设定，早于任何一次写入）。 */
    private var written: Map<String, ScriptDb.Row> = emptyMap()

    /**
     * 旧版本把去广告订阅也存在脚本列表里（subscriptionUrl 非空）。
     * 读取时把它们从脚本列表里剥离出来，交给 AdBlock.migrateLegacy，
     * 迁移完成后调用 commitLegacyMigration 落盘。
     */
    val legacyAdSubs = mutableListOf<LegacyAdSub>()

    val items = mutableStateListOf<UserScript>().apply { addAll(load()) }

    init {
        current = this
        if (needsPersist) persist()
    }

    fun commitLegacyMigration() {
        if (legacyAdSubs.isEmpty()) return
        legacyAdSubs.clear()
        // 加密库模式：旧值里只剩去广告订阅的残留，迁完就删；退回旧存储时照常重写
        if (useDb) prefs.edit().remove(KEY).commit() else persist()
    }

    private fun load(): List<UserScript> {
        val database = db
        if (database != null) {
            try {
                // 旧存储里还有脚本（首次升级 / 备份恢复 / 加密库不可用期间写过）：它优先，整体导入后清掉
                importLegacy(database)?.let { return it }
                val loaded = ScriptDb.load(database)
                written = ScriptDb.rows(loaded)
                return loaded
            } catch (t: Throwable) {
                Diag.log("scripts: encrypted db unavailable, using prefs: ${t.message}")
                useDb = false
            }
        }
        return loadLegacy()
    }

    private class LegacyParsed(val scripts: List<UserScript>, val adSubs: List<LegacyAdSub>, val adOnly: JSONArray)

    /** 解析旧格式（SharedPreferences 里的 JSON，也是备份里的格式）。格式不对抛异常。 */
    private fun parseLegacy(raw: String): LegacyParsed {
        val array = JSONArray(raw)
        val scripts = ArrayList<UserScript>()
        val ads = ArrayList<LegacyAdSub>()
        val adOnly = JSONArray()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val legacyUrl = obj.optString("subscriptionUrl")
            if (legacyUrl.isNotBlank()) {
                ads.add(
                    LegacyAdSub(
                        name = obj.optString("name"),
                        url = legacyUrl,
                        enabled = obj.optBoolean("enabled", true),
                    ),
                )
                adOnly.put(obj)
                continue
            }
            scripts.add(
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
        return LegacyParsed(scripts, ads, adOnly)
    }

    /**
     * 旧版把脚本整份存在 SharedPreferences「mafari_ext」的 scripts 里（备份恢复写回的也是它）。
     * 读得出来就在一个事务里整体导入加密库，成功后删掉旧值；读不出来不动它。没有旧值返回 null。
     * 旧值里只剩去广告订阅残留（没有脚本）时，库里的才是最新的，不覆盖；订阅残留留到 [commitLegacyMigration] 再删。
     */
    private fun importLegacy(database: SQLiteDatabase): List<UserScript>? {
        val raw = prefs.getString(KEY, null) ?: return null
        val parsed = runCatching { parseLegacy(raw) }.getOrNull() ?: return null
        legacyAdSubs.clear()
        legacyAdSubs.addAll(parsed.adSubs)
        val existing = ScriptDb.load(database)
        val result: List<UserScript>
        if (parsed.scripts.isEmpty() && existing.isNotEmpty()) {
            result = existing
            written = ScriptDb.rows(existing)
        } else {
            result = ScriptDb.dedupeIds(parsed.scripts).items
            written = ScriptDb.replaceAll(database, result)
        }
        val editor = prefs.edit()
        if (parsed.adSubs.isEmpty()) editor.remove(KEY) else editor.putString(KEY, parsed.adOnly.toString())
        if (!editor.commit()) Diag.log("scripts: could not clear legacy prefs after import")
        return result
    }

    private fun loadLegacy(): List<UserScript> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val parsed = runCatching { parseLegacy(raw) }.getOrNull() ?: return emptyList()
        legacyAdSubs.clear()
        legacyAdSubs.addAll(parsed.adSubs)
        return parsed.scripts
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
        if (useDb) {
            scheduleSave()
            return
        }
        prefs.edit().putString(KEY, ScriptDb.itemsToJson(items.toList())).apply()
    }

    /** 标记有改动待写库：安静 [SAVE_DEBOUNCE_MS] 后写；一直有改动则最迟 [SAVE_MAX_WAIT_MS] 写一次。 */
    private fun scheduleSave() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { scheduleSave() }
            return
        }
        dirty = true
        val now = SystemClock.uptimeMillis()
        if (firstDirtyAt == 0L) firstDirtyAt = now
        main.removeCallbacks(flushTask)
        if (now - firstDirtyAt >= SAVE_MAX_WAIT_MS) flush() else main.postDelayed(flushTask, SAVE_DEBOUNCE_MS)
    }

    /**
     * 立即把待写的改动交给写库线程（在主线程取快照，写入在 [HistoryDb.io] 上按顺序执行、单事务、只写有变化的行）。
     * 应用退到后台时调用，免得最后一小段改动还在等延迟。主线程。
     */
    fun flush() {
        main.removeCallbacks(flushTask)
        firstDirtyAt = 0L
        val database = db
        if (!useDb || database == null || !dirty) return
        dirty = false
        val snapshot = items.toList()
        HistoryDb.io.execute {
            runCatching { written = ScriptDb.sync(database, snapshot, written) }
                .onFailure { Diag.log("scripts: save failed: ${it.message}") }
        }
    }

    /** 备份用：当前内存里的脚本列表（旧版明文 JSON 格式）。 */
    fun snapshotJson(): String = ScriptDb.itemsToJson(items.toList())

    companion object {
        /** 最近创建的实例：备份导出靠它取界面正在用的那份（写库有短暂延迟，内存里的最新）。 */
        @Volatile var current: ExtensionStore? = null
            private set

        /** 退到后台时调用：把还在等延迟的脚本改动立刻交给写库线程。主线程。 */
        fun flushCurrent() {
            current?.flush()
        }

        /** 备份用：脚本列表的旧格式 JSON；优先取内存里最新的，没有则读库；库不可用返回 null。 */
        fun exportBackup(context: Context): String? {
            runCatching { current?.snapshotJson() }.getOrNull()?.let { return it }
            val database = HistoryDb.get(context) ?: return null
            return runCatching { ScriptDb.itemsToJson(ScriptDb.load(database)) }.getOrNull()
        }

        private const val KEY = "scripts"
        private const val SAVE_DEBOUNCE_MS = 300L
        private const val SAVE_MAX_WAIT_MS = 2_000L
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
