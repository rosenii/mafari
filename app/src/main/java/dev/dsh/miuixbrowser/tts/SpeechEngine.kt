/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.tts

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dsh.miuixbrowser.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 一个朗读引擎。
 *
 * [system] 为 true 时用 Android 系统 TTS（[TextToSpeech]），其余字段不用；
 * 自定义引擎是一次 HTTP 请求：URL / 请求体里支持占位符
 * `{{speakText}}`、`{{speakTextUrl}}`、`{{speakTextUrl2}}`（双重 URL 编码）、`{{speakTextJson}}`、`{{speakSpeed}}`、`{{voice}}`、`{{model}}`，
 * 返回音频字节即可。这样既能填 Legado 的 `{{speakText}}` / `{{speakSpeed}}` 朗读引擎地址，
 * 也能填 OpenAI 兼容的 `/v1/audio/speech` 这类 AI 语音接口。
 */
class SpeechEngine(
    val id: String,
    val name: String,
    val system: Boolean = false,
    val url: String = "",
    val method: String = "GET",
    val headers: String = "",
    val body: String = "",
    val voice: String = "",
    val model: String = "",
    /** 来自哪份语音订阅（订阅地址 / 文件名 / 粘贴内容的哈希）；空 = 手动添加的引擎，不属于任何订阅文件夹 */
    val groupKey: String = "",
    /** 订阅文件夹显示名 */
    val groupName: String = "",
) {
    val displayName: String get() = if (system) tr("系统语音") else name
}

/** 朗读引擎 / 语速 / 自动下一章的持久化（filesDir/speech.json）。 */
object SpeechEngineStore {
    const val SYSTEM_ID = "system"
    private const val FILE = "speech.json"
    private val lock = Any()

    var engines by mutableStateOf<List<SpeechEngine>>(listOf(SpeechEngine(SYSTEM_ID, "系统语音", system = true)))
        private set
    var selectedId by mutableStateOf(SYSTEM_ID)
        private set

    /** 0.5 – 2.5 倍速 */
    var speed by mutableStateOf(1f)
        private set
    var autoNext by mutableStateOf(true)
        private set

    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            runCatching {
                val f = file(context)
                if (!f.isFile) return@runCatching
                val o = JSONObject(f.readText())
                val arr = o.optJSONArray("engines") ?: JSONArray()
                val list = ArrayList<SpeechEngine>()
                list.add(SpeechEngine(SYSTEM_ID, "系统语音", system = true))
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.optString("id")
                    if (id.isBlank() || id == SYSTEM_ID) continue
                    list.add(
                        SpeechEngine(
                            id = id,
                            name = e.optString("name").ifBlank { tr("自定义语音") },
                            url = e.optString("url"),
                            method = e.optString("method", "GET").ifBlank { "GET" },
                            headers = e.optString("headers"),
                            body = e.optString("body"),
                            voice = e.optString("voice"),
                            model = e.optString("model"),
                            groupKey = e.optString("groupKey"),
                            groupName = e.optString("groupName"),
                        ),
                    )
                }
                engines = list
                val sel = o.optString("selected", SYSTEM_ID)
                selectedId = if (list.any { it.id == sel }) sel else SYSTEM_ID
                speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 2.5f)
                autoNext = o.optBoolean("autoNext", true)
            }
        }
    }

    fun current(): SpeechEngine = engines.firstOrNull { it.id == selectedId } ?: engines.first()

    fun select(context: Context, id: String) {
        synchronized(lock) {
            if (engines.none { it.id == id }) return
            selectedId = id
            save(context)
        }
    }

    fun setSpeed(context: Context, value: Float) {
        synchronized(lock) {
            speed = value.coerceIn(0.5f, 2.5f)
            save(context)
        }
    }

    fun setAutoNext(context: Context, value: Boolean) {
        synchronized(lock) {
            autoNext = value
            save(context)
        }
    }

    /** 新增或更新自定义引擎；system 引擎不能改。 */
    fun upsert(context: Context, engine: SpeechEngine) {
        if (engine.system) return
        synchronized(lock) {
            val id = engine.id.ifBlank { UUID.randomUUID().toString().replace("-", "").take(12) }
            // 编辑订阅里的引擎不会让它跳出订阅文件夹
            val old = engines.firstOrNull { it.id == id }
            val e = SpeechEngine(
                id = id,
                name = engine.name.trim().ifBlank { tr("自定义语音") },
                url = engine.url.trim(),
                method = if (engine.method.equals("POST", true)) "POST" else "GET",
                headers = engine.headers.trim(),
                body = engine.body,
                voice = engine.voice.trim(),
                model = engine.model.trim(),
                groupKey = old?.groupKey ?: engine.groupKey,
                groupName = old?.groupName ?: engine.groupName,
            )
            engines = if (engines.any { it.id == id }) engines.map { if (it.id == id) e else it } else engines + e
            selectedId = id
            save(context)
        }
    }

    /**
     * 批量导入（语音引擎订阅），这批引擎都放进 [groupKey] / [groupName] 这个订阅文件夹。
     * 同一订阅里「地址 + 请求方式 + 请求体」相同的引擎视为同一个：更新规则、保留原 id，
     * 订阅里没写 voice / model 时保留用户自己填的。旧版本导入、还没有文件夹的同地址引擎会被收进来，不会重复出现。
     * 当前选用的引擎不变（只导入 1 个时才自动选中它）。返回新增条数。
     */
    fun importAll(context: Context, list: List<SpeechEngine>, groupKey: String = "", groupName: String = ""): Int {
        if (list.isEmpty()) return 0
        synchronized(lock) {
            var added = 0
            val next = engines.toMutableList()
            var lastId: String? = null
            for (e in list) {
                if (e.system) continue
                val id = importedId(groupKey, e)
                var idx = next.indexOfFirst { it.id == id }
                if (idx < 0 && groupKey.isNotEmpty()) {
                    idx = next.indexOfFirst {
                        it.groupKey.isEmpty() && it.url == e.url && it.id == importedId("", it)
                    }
                }
                if (idx >= 0) {
                    val old = next[idx]
                    next[idx] = SpeechEngine(
                        id = old.id,
                        name = e.name.ifBlank { old.name },
                        url = e.url,
                        method = e.method,
                        headers = e.headers,
                        body = e.body,
                        voice = e.voice.ifBlank { old.voice },
                        model = e.model.ifBlank { old.model },
                        groupKey = groupKey,
                        groupName = groupName,
                    )
                    lastId = old.id
                } else {
                    next.add(
                        SpeechEngine(
                            id = id,
                            name = e.name,
                            url = e.url,
                            method = e.method,
                            headers = e.headers,
                            body = e.body,
                            voice = e.voice,
                            model = e.model,
                            groupKey = groupKey,
                            groupName = groupName,
                        ),
                    )
                    lastId = id
                    added++
                }
            }
            engines = next
            if (list.size == 1 && lastId != null) selectedId = lastId
            save(context)
            return added
        }
    }

    private fun importedId(groupKey: String, e: SpeechEngine): String {
        val seed = if (groupKey.isEmpty()) e.url else groupKey + "\n" + e.url + "\n" + e.method + "\n" + e.body
        return "sub_" + Integer.toHexString(seed.hashCode())
    }

    /** 删除整个订阅文件夹里的引擎；正在用的在里面就回到系统语音。 */
    fun removeGroup(context: Context, groupKey: String) {
        if (groupKey.isEmpty()) return
        synchronized(lock) {
            val gone = engines.filter { it.groupKey == groupKey }.map { it.id }.toSet()
            if (gone.isEmpty()) return
            engines = engines.filterNot { it.id in gone }
            if (selectedId in gone) selectedId = SYSTEM_ID
            save(context)
        }
    }

    fun remove(context: Context, id: String) {
        if (id == SYSTEM_ID) return
        synchronized(lock) {
            engines = engines.filterNot { it.id == id }
            if (selectedId == id) selectedId = SYSTEM_ID
            save(context)
        }
    }

    private fun save(context: Context) {
        runCatching {
            val arr = JSONArray()
            engines.filterNot { it.system }.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id).put("name", it.name).put("url", it.url)
                        .put("method", it.method).put("headers", it.headers).put("body", it.body)
                        .put("voice", it.voice).put("model", it.model)
                        .put("groupKey", it.groupKey).put("groupName", it.groupName),
                )
            }
            val o = JSONObject()
                .put("selected", selectedId)
                .put("speed", speed.toDouble())
                .put("autoNext", autoNext)
                .put("engines", arr)
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(o.toString())
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString())
                tmp.delete()
            }
        }
    }
}
