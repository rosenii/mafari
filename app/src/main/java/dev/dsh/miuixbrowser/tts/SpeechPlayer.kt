/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dsh.miuixbrowser.tts.SpeechEngine
import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class SpeechState { IDLE, PREPARING, SPEAKING, PAUSED, ERROR }

/**
 * 听书播放器：系统 TTS 直接读，HTTP / AI 语音引擎逐段请求音频并用 MediaPlayer 播。
 * 只读当前章节的文本；一章读完由调用方决定是否跳下一章（见 ReaderScreen 的 autoNext）。
 *
 * 方法都在主线程调用（Compose 里），网络和写临时文件走 IO。
 */
class SpeechPlayer(private val context: Context, private val scope: CoroutineScope) {
    var state by mutableStateOf(SpeechState.IDLE)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingStart: (() -> Unit)? = null

    private var media: MediaPlayer? = null
    private var job: Job? = null
    private var chunks: List<String> = emptyList()
    private var index = 0
    private var engine: SpeechEngine? = null
    private var speed = 1f
    private var onFinished: (() -> Unit)? = null
    /** 每次开始朗读 +1：旧 utterance 的 onDone / onError 不能推进新一轮 */
    private var session = 0

    init {
        val created = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.let { t ->
                runCatching {
                    t.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                }
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        if (!isCurrentUtterance(utteranceId)) return
                        scope.launch { onSystemDone(utteranceId) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (!isCurrentUtterance(utteranceId)) return
                        scope.launch { fail(tr("系统语音朗读失败")) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (!isCurrentUtterance(utteranceId)) return
                        scope.launch { fail(tr("系统语音朗读失败（{0}）", errorCode)) }
                    }
                })
                }
                val p = pendingStart
                pendingStart = null
                if (state != SpeechState.PAUSED) p?.invoke()
            } else {
                scope.launch { fail(tr("系统语音初始化失败")) }
            }
        }
        tts = created
    }

    /** 朗读中改倍速：系统 TTS 下一段生效；HTTP 音频按引擎能力即时 / 下一段生效。 */
    fun setSpeed(value: Float) {
        val v = value.coerceIn(0.5f, 2.5f)
        if (v == speed) return
        speed = v
        val eng = engine ?: return
        if (!eng.system && !serverAppliesSpeed(eng)) {
            runCatching { media?.let { it.playbackParams = it.playbackParams.setSpeed(v) } }
        }
    }

    fun start(engine: SpeechEngine, text: String, speed: Float, onFinished: () -> Unit) {
        stopInternal()
        this.engine = engine
        this.speed = speed.coerceIn(0.5f, 2.5f)
        this.onFinished = onFinished
        session++
        chunks = splitText(text)
        index = 0
        error = null
        if (chunks.isEmpty()) {
            state = SpeechState.IDLE
            this.onFinished = null
            return
        }
        if (engine.system) {
            state = SpeechState.PREPARING
            if (ttsReady) speakNextSystem() else pendingStart = { speakNextSystem() }
        } else {
            state = SpeechState.PREPARING
            job = scope.launch { runHttp() }
        }
    }

    fun pause() {
        when {
            state == SpeechState.SPEAKING && engine?.system == true -> {
                runCatching { tts?.stop() }
                state = SpeechState.PAUSED
            }
            state == SpeechState.SPEAKING -> {
                runCatching { media?.pause() }
                state = SpeechState.PAUSED
            }
            state == SpeechState.PREPARING -> state = SpeechState.PAUSED
        }
    }

    fun resume() {
        if (state != SpeechState.PAUSED) return
        if (engine?.system == true) {
            state = SpeechState.PREPARING
            speakNextSystem()
        } else {
            val m = media
            if (m != null) {
                runCatching { m.start() }
                state = SpeechState.SPEAKING
            } else {
                state = SpeechState.PREPARING
                if (job?.isActive != true) job = scope.launch { runHttp() }
            }
        }
    }

    fun stop() {
        stopInternal()
        state = SpeechState.IDLE
        chunks = emptyList()
        index = 0
        error = null
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    private fun stopInternal() {
        job?.cancel()
        job = null
        pendingStart = null
        onFinished = null
        runCatching { tts?.stop() }
        releaseMedia()
    }

    private fun releaseMedia() {
        val m = media
        media = null
        runCatching { m?.release() }
    }

    private fun isCurrentUtterance(id: String?): Boolean =
        id != null && id.startsWith("mafari-tts-$session-")

    private fun onSystemDone(id: String?) {
        if (engine?.system != true) return
        if (state != SpeechState.SPEAKING) return
        if (!isCurrentUtterance(id)) return
        index++
        speakNextSystem()
    }

    private fun speakNextSystem() {
        if (index >= chunks.size) {
            finish()
            return
        }
        val text = chunks[index]
        if (text.isBlank()) {
            index++
            speakNextSystem()
            return
        }
        if (!ttsReady) {
            state = SpeechState.PREPARING
            pendingStart = { speakNextSystem() }
            return
        }
        val t = tts ?: return
        val locale = if (text.any { it in '\u4e00'..'\u9fff' }) Locale.SIMPLIFIED_CHINESE else Locale.getDefault()
        runCatching { t.language = locale }
        runCatching { t.setSpeechRate(speed) }
        state = SpeechState.SPEAKING
        val result = runCatching { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mafari-tts-$session-$index") }
            .getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) fail(tr("系统语音朗读失败"))
    }

    private suspend fun runHttp() {
        val eng = engine ?: return
        try {
            while (currentCoroutineContext().isActive && index < chunks.size) {
                while (state == SpeechState.PAUSED) delay(100)
                state = SpeechState.PREPARING
                val text = chunks[index]
                val bytes = withContext(Dispatchers.IO) { SpeechHttp.fetch(eng, text, speed) }
                if (bytes.isEmpty()) throw IllegalStateException(tr("语音引擎没有返回音频"))
                while (state == SpeechState.PAUSED) delay(100)
                state = SpeechState.SPEAKING
                playAudio(bytes)
                if (state == SpeechState.IDLE) return
                index++
            }
            if (state != SpeechState.PAUSED) finish()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            fail(t.message ?: tr("语音请求失败"))
        }
    }

    private suspend fun playAudio(bytes: ByteArray) {
        val file = File(appContext.cacheDir, "mafari-tts-${System.nanoTime()}.audio")
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        suspendCancellableCoroutine { cont ->
        val mp = MediaPlayer()
        media = mp
        fun clean() {
            if (media === mp) media = null
            runCatching { mp.release() }
            file.delete()
        }
        try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener {
                clean()
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setOnErrorListener { _, what, extra ->
                clean()
                if (cont.isActive) cont.resumeWithException(IllegalStateException(tr("音频播放失败（{0}/{1}）", what, extra)))
                true
            }
            mp.start()
            if (!serverAppliesSpeed(engine)) {
                runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
            }
            cont.invokeOnCancellation { clean() }
        } catch (t: Throwable) {
            clean()
            if (cont.isActive) cont.resumeWithException(t)
        }
        }
    }

    private fun finish() {
        state = SpeechState.IDLE
        val cb = onFinished
        onFinished = null
        cb?.invoke()
    }

    private fun fail(message: String) {
        error = message
        state = SpeechState.ERROR
        stopInternal()
        state = SpeechState.ERROR
    }

    private fun serverAppliesSpeed(engine: SpeechEngine?): Boolean {
        val u = engine?.url.orEmpty()
        return u.contains("{{speakSpeed}}") || u.contains("{{speed}}") ||
            Regex("[?&](?:speed|rate)=", RegexOption.IGNORE_CASE).containsMatchIn(u)
    }

    private fun splitText(text: String): List<String> {
        val clean = text.replace('\u3000', ' ').replace(Regex("[ \\t]+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val sentences = Regex("[^。！？!?；;\\n]+[。！？!?；;]?").findAll(clean)
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (s in sentences) {
            if (s.length > 200) {
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                var i = 0
                while (i < s.length) {
                    val end = (i + 200).coerceAtMost(s.length)
                    out.add(s.substring(i, end))
                    i = end
                }
                continue
            }
            if (sb.isNotEmpty() && sb.length + s.length > 180) {
                out.add(sb.toString())
                sb.clear()
            }
            sb.append(s)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}

/** 自定义语音引擎的 HTTP 请求：URL / 请求体 / 请求头里替换占位符，返回音频字节。 */
private object SpeechHttp {
    private const val MAX_BYTES = 32 * 1024 * 1024

    fun fetch(engine: SpeechEngine, text: String, speed: Float): ByteArray {
        val urlText = replace(engine.url, text, speed, forUrl = true, engine.voice, engine.model)
        require(urlText.startsWith("http://", true) || urlText.startsWith("https://", true)) {
            "语音引擎地址必须是 http / https"
        }
        val bodyText = if (engine.body.isBlank()) "" else replace(engine.body, text, speed, forUrl = false, engine.voice, engine.model)
        val headers = parseHeaders(replace(engine.headers, text, speed, forUrl = false, engine.voice, engine.model))
        var bytes = request(urlText, engine.method, headers, bodyText, engine.body.trimStart().startsWith("{") || engine.body.trimStart().startsWith("["))
        // 有些 AI 接口返回 {"url":"..."} / {"audio":"base64"}，再取一次
        if (looksJson(bytes)) {
            val o = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            if (o != null) {
                val direct = o.optString("url").ifBlank { o.optString("audio_url") }
                val b64 = o.optString("audio").ifBlank { o.optString("data") }
                when {
                    direct.isNotBlank() -> bytes = request(direct, "GET", emptyMap(), "", false)
                    b64.isNotBlank() -> bytes = Base64.getDecoder().decode(b64.substringAfter(','))
                    else -> throw IllegalStateException(tr("语音引擎返回的是 JSON 而不是音频：{0}", String(bytes, Charsets.UTF_8).take(200)))
                }
            }
        }
        return bytes
    }

    private fun looksJson(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 64), Charsets.UTF_8).trimStart()
        return head.startsWith("{")
    }

    private fun request(url: String, method: String, headers: Map<String, String>, body: String, jsonBody: Boolean): ByteArray {
        val post = method.equals("POST", true)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (post) "POST" else "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            for ((k, v) in headers) setRequestProperty(k, v)
            if (getRequestProperty("Accept") == null) setRequestProperty("Accept", "audio/*, application/json")
            if (getRequestProperty("User-Agent") == null) setRequestProperty("User-Agent", "Mozilla/5.0 mafari")
            if (post) {
                doOutput = true
                if (getRequestProperty("Content-Type") == null) {
                    setRequestProperty("Content-Type", if (jsonBody) "application/json" else "application/x-www-form-urlencoded")
                }
            }
        }
        try {
            if (post) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8).take(300) }.orEmpty()
                throw IllegalStateException(tr("语音引擎返回 HTTP {0}{1}", code, if (err.isBlank()) "" else "：$err"))
            }
            val input = conn.inputStream
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            input.use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw IllegalStateException(tr("语音音频过大"))
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun replace(template: String, text: String, speed: Float, forUrl: Boolean, voice: String, model: String): String {
        if (template.isBlank()) return template
        val urlText = URLEncoder.encode(text, "UTF-8")
        val urlText2 = URLEncoder.encode(urlText, "UTF-8")
        val jsonText = JSONObject.quote(text).removeSurrounding("\"")
        val sp = String.format(Locale.US, "%.2f", speed)
        var out = template
            .replace("{{speakTextUrl2}}", urlText2)
            .replace("{{speakTextUrl}}", urlText)
            .replace("{{speakTextJson}}", jsonText)
            .replace("{{speakText}}", if (forUrl) urlText else text)
            .replace("{{text}}", if (forUrl) urlText else text)
            .replace("{{speakSpeed}}", sp)
            .replace("{{speed}}", sp)
            .replace("{{voice}}", voice)
            .replace("{{model}}", model)
        // Legado 朗读引擎常把 speed=0.9 写死；没有占位符时按用户倍速覆盖，否则滑块无效
        if (forUrl && !template.contains("{{speakSpeed}}") && !template.contains("{{speed}}")) {
            out = Regex("([?&](?:speed|rate)=)[^&]*", RegexOption.IGNORE_CASE).replace(out) { m ->
                m.groupValues[1] + sp
            }
        }
        return out
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        val t = raw.trim()
        if (t.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        if (t.startsWith("{")) {
            runCatching {
                val o = JSONObject(t)
                for (k in o.keys()) out[k] = o.optString(k)
            }
            return out
        }
        for (line in t.lines()) {
            val i = line.indexOf(':')
            if (i > 0) out[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        return out
    }
}
