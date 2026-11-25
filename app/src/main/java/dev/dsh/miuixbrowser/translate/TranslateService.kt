/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.translate

/**
 * 在某个 [TranslateSource] 之上做：去重、结果缓存、长文本按句切分、按条数 / 字数分批、失败重试一次。
 * 线程安全：页面里多批请求可以并发调 [translate]。
 */
class TranslateService(
    private val source: TranslateSource,
    val target: String,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    val sourceId: String get() = source.id

    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_MAX
    }

    /** 与 [texts] 等长、同序；空白文本原样返回。失败抛 [TranslateException]（已译好的部分仍留在缓存里，重试不会重复请求）。 */
    fun translate(texts: List<String>): List<String> {
        // 1. 拆成请求单元（长文本切成多段）
        val pieces = LinkedHashSet<String>()
        val plan = texts.map { t ->
            if (t.isBlank()) null else splitText(t, MAX_PIECE_CHARS).also { pieces.addAll(it) }
        }
        // 2. 缓存里没有的才请求
        val todo = synchronized(cache) { pieces.filter { cache[it] == null } }
        var i = 0
        while (i < todo.size) {
            val batch = ArrayList<String>()
            var chars = 0
            while (i < todo.size && batch.size < MAX_BATCH_TEXTS && (batch.isEmpty() || chars + todo[i].length <= MAX_BATCH_CHARS)) {
                batch += todo[i]
                chars += todo[i].length
                i++
            }
            val result = callWithRetry(batch)
            synchronized(cache) { batch.forEachIndexed { k, src -> cache[src] = result[k] } }
        }
        // 3. 拼回
        return synchronized(cache) {
            texts.mapIndexed { idx, t ->
                val ps = plan[idx] ?: return@mapIndexed t
                ps.joinToString("") { cache[it] ?: it }
            }
        }
    }

    private fun callWithRetry(batch: List<String>): List<String> {
        var last: TranslateException? = null
        for (attempt in 0..1) {
            try {
                val out = source.translate(batch, target)
                if (out.size != batch.size) throw TranslateException(TranslateException.Kind.PARSE, "size ${out.size}/${batch.size}")
                return out
            } catch (e: TranslateException) {
                last = e
                if (!retryable(e) || attempt == 1) throw e
                sleep(RETRY_DELAY_MS)
            }
        }
        throw last ?: TranslateException(TranslateException.Kind.NETWORK)
    }

    private fun retryable(e: TranslateException) = when (e.kind) {
        TranslateException.Kind.NETWORK -> true
        TranslateException.Kind.HTTP -> e.detail.startsWith("HTTP 5") || e.detail.startsWith("HTTP 429")
        else -> false
    }

    companion object {
        const val MAX_BATCH_TEXTS = 20
        const val MAX_BATCH_CHARS = 3500
        const val MAX_PIECE_CHARS = 1500
        const val CACHE_MAX = 4000
        const val RETRY_DELAY_MS = 400L

        private val SENTENCE_END = setOf('.', '!', '?', '。', '！', '？', ';', '；', '\n')

        /** 把超长文本在句末（其次空白）处切开；各段拼回去等于原文（包括空白）。 */
        fun splitText(text: String, max: Int): List<String> {
            if (text.length <= max) return listOf(text)
            val out = ArrayList<String>()
            var start = 0
            while (start < text.length) {
                if (text.length - start <= max) {
                    out += text.substring(start)
                    break
                }
                val limit = start + max
                var cut = -1
                // 先找最近的句末，其次空白；都没有就硬切（不拆 surrogate pair）
                for (p in limit downTo start + max / 2) {
                    if (SENTENCE_END.contains(text[p - 1])) { cut = p; break }
                }
                if (cut < 0) for (p in limit downTo start + max / 2) {
                    if (text[p - 1].isWhitespace()) { cut = p; break }
                }
                if (cut < 0) {
                    cut = limit
                    if (Character.isHighSurrogate(text[cut - 1])) cut--
                }
                out += text.substring(start, cut)
                start = cut
            }
            return out
        }
    }
}

/** 按配置创建翻译源；配置不全（自定义源没填地址）时抛 CONFIG。 */
object TranslateSources {
    fun create(cfg: TranslateConfig): TranslateSource = when (cfg.sourceId) {
        TranslateSourceId.GOOGLE -> GoogleSource()
        TranslateSourceId.CUSTOM -> {
            if (cfg.customUrl.isBlank()) throw TranslateException(TranslateException.Kind.CONFIG, "url")
            CustomSource(cfg.customUrl, cfg.customKey)
        }
        else -> BingSource()
    }

    fun createService(cfg: TranslateConfig): TranslateService =
        TranslateService(create(cfg), TranslateLangs.normalize(cfg.target))
}
