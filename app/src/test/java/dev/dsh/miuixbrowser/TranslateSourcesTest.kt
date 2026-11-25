/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.dsh.miuixbrowser.translate.BingSource
import dev.dsh.miuixbrowser.translate.CustomSource
import dev.dsh.miuixbrowser.translate.GoogleSource
import dev.dsh.miuixbrowser.translate.TranslateConfig
import dev.dsh.miuixbrowser.translate.TranslateException
import dev.dsh.miuixbrowser.translate.TranslateLangs
import dev.dsh.miuixbrowser.translate.TranslateService
import dev.dsh.miuixbrowser.translate.TranslateSourceId
import dev.dsh.miuixbrowser.translate.TranslateSources
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder

/** 纯 JVM：用本地 HttpServer 模拟三个翻译源，覆盖请求格式、响应解析、token 缓存 / 刷新、批处理与缓存。 */
class TranslateSourcesTest {

    /** 一个最小的本地服务：每个路径一个处理函数，记录收到的请求。 */
    private class Mock : AutoCloseable {
        class Req(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: String)

        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = java.util.Collections.synchronizedList(ArrayList<Req>())
        val base get() = "http://127.0.0.1:${server.address.port}"

        fun on(path: String, handler: (Req) -> Pair<Int, String>) {
            server.createContext(path) { ex: HttpExchange ->
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
                val req = Req(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery.orEmpty(), headers, body)
                requests += req
                val (code, resp) = handler(req)
                val bytes = resp.toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }

        fun start() = apply { server.start() }
        override fun close() = server.stop(0)
    }

    private fun formValues(body: String, key: String) =
        body.split('&').filter { it.startsWith("$key=") }.map { URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    // ------------------------------------------------------------------ Google

    @Test
    fun google_batchPostsFormAndParsesPairs() {
        Mock().use { m ->
            m.on("/translate_a/t") { r ->
                val qs = formValues(r.body, "q")
                200 to JSONArray(qs.map { JSONArray().put("[$it]").put("en") }).toString()
            }
            m.start()
            val out = GoogleSource(m.base).translate(listOf("Hello", "a&b=c 你好"), "zh-CN")
            assertEquals(listOf("[Hello]", "[a&b=c 你好]"), out)
            val r = m.requests.single()
            assertEquals("POST", r.method)
            assertTrue(r.query, r.query.contains("tl=zh-CN") && r.query.contains("client=gtx"))
        }
    }

    @Test
    fun google_parsesSingleAndSentenceShapes() {
        // 单条：根上直接是字符串
        assertEquals(listOf("你好"), callParse("""["你好","en"]""", 1))
        // 单条：嵌套一层
        assertEquals(listOf("你好"), callParse("""[["你好","en"]]""", 1))
        // 逐句形态（single 接口），分句拼接
        assertEquals(listOf("你好。世界"), callParse("""[[["你好。","Hello. ",null],["世界","World",null]],null,"en"]""", 1))
        // 多条里夹逐句
        assertEquals(listOf("A", "BC"), callParse("""[["A","en"],[["B","b"],["C","c"]]]""", 2))
    }

    private fun callParse(body: String, n: Int): List<String> {
        val m = Class.forName("dev.dsh.miuixbrowser.translate.GoogleSource\$Companion")
        val inst = Class.forName("dev.dsh.miuixbrowser.translate.GoogleSource").getDeclaredField("Companion").get(null)
        @Suppress("UNCHECKED_CAST")
        return m.getDeclaredMethod("parseBatch", String::class.java, Int::class.javaPrimitiveType).invoke(inst, body, n) as List<String>
    }

    @Test
    fun google_countMismatchIsParseError() {
        try {
            callParse("""[["a","en"]]""", 2)
            fail("should throw")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertEquals(TranslateException.Kind.PARSE, (e.cause as TranslateException).kind)
        }
    }

    @Test
    fun google_fallsBackToSingleWhenBatchRejected() {
        Mock().use { m ->
            m.on("/translate_a/t") { 400 to "bad" }
            m.on("/translate_a/single") { r ->
                val q = r.query.split('&').first { it.startsWith("q=") }.substringAfter('=').let { URLDecoder.decode(it, "UTF-8") }
                200 to """[[["<$q>","$q",null]],null,"en"]"""
            }
            m.start()
            val out = GoogleSource(m.base).translate(listOf("one", "two"), "ja")
            assertEquals(listOf("<one>", "<two>"), out)
        }
    }

    @Test
    fun google_rateLimitDoesNotFallBack() {
        Mock().use { m ->
            m.on("/translate_a/t") { 429 to "slow down" }
            m.on("/translate_a/single") { 200 to "[]" }
            m.start()
            try {
                GoogleSource(m.base).translate(listOf("x"), "en")
                fail("should throw")
            } catch (e: TranslateException) {
                assertEquals(TranslateException.Kind.HTTP, e.kind)
                assertTrue(e.detail, e.detail.startsWith("HTTP 429"))
            }
            assertTrue(m.requests.none { it.path.endsWith("single") })
        }
    }

    @Test
    fun google_unreachableIsNetworkError() {
        try {
            GoogleSource("http://127.0.0.1:1").translate(listOf("x"), "en")
            fail("should throw")
        } catch (e: TranslateException) {
            assertEquals(TranslateException.Kind.NETWORK, e.kind)
        }
    }

    // ------------------------------------------------------------------ Bing

    private fun bingResponse(body: String): String {
        val texts = JSONArray(body)
        return JSONArray(
            (0 until texts.length()).map {
                JSONObject().put("detectedLanguage", JSONObject().put("language", "en"))
                    .put("translations", JSONArray().put(JSONObject().put("text", "B:" + texts.getString(it)).put("to", "zh-Hans")))
            },
        ).toString()
    }

    @Test
    fun bing_postsStringArrayWithoutTokenAndMapsLanguage() {
        Mock().use { m ->
            m.on("/translate/translatetext") { r -> 200 to bingResponse(r.body) }
            m.start()
            val src = BingSource(m.base + "/translate/translatetext")
            assertEquals(listOf("B:hi"), src.translate(listOf("hi"), "zh-CN"))
            assertEquals(listOf("B:a", "B:b"), src.translate(listOf("a", "b"), "zh-TW"))
            val rs = m.requests
            assertEquals(2, rs.size)
            assertEquals("POST", rs[0].method)
            assertTrue(rs[0].query, rs[0].query.contains("to=zh-Hans"))
            assertTrue(rs[1].query, rs[1].query.contains("to=zh-Hant"))
            assertTrue(rs[0].query, rs[0].query.contains("isEnterpriseClient=false"))
            assertTrue("不再带 Authorization", rs[0].headers["authorization"] == null)
            assertEquals("[\"hi\"]", rs[0].body)
        }
    }

    @Test
    fun bing_http401IsAuthError() {
        Mock().use { m ->
            m.on("/translate/translatetext") { 401 to "denied" }
            m.start()
            try {
                BingSource(m.base + "/translate/translatetext").translate(listOf("x"), "en")
                fail("should throw")
            } catch (e: TranslateException) {
                assertEquals(TranslateException.Kind.AUTH, e.kind)
            }
        }
    }

    @Test
    fun bing_htmlBodyIsParseError() {
        Mock().use { m ->
            m.on("/translate/translatetext") { 200 to "<html>blocked</html>" }
            m.start()
            try {
                BingSource(m.base + "/translate/translatetext").translate(listOf("x"), "en")
                fail("should throw")
            } catch (e: TranslateException) {
                assertEquals(TranslateException.Kind.PARSE, e.kind)
            }
        }
    }

    // ------------------------------------------------------------------ 自定义（LibreTranslate）

    @Test
    fun custom_sendsLibreTranslateRequest() {
        Mock().use { m ->
            m.on("/translate") { r ->
                val q = JSONObject(r.body).getJSONArray("q")
                200 to JSONObject().put("translatedText", JSONArray((0 until q.length()).map { "L:" + q.getString(it) })).toString()
            }
            m.start()
            val out = CustomSource(m.base + "/", "secret").translate(listOf("a", "b"), "zh-TW")
            assertEquals(listOf("L:a", "L:b"), out)
            val body = JSONObject(m.requests.single().body)
            assertEquals("zt", body.getString("target"))
            assertEquals("auto", body.getString("source"))
            assertEquals("secret", body.getString("api_key"))
        }
    }

    @Test
    fun custom_acceptsStringResultAndFullTranslateUrlAndNoKey() {
        Mock().use { m ->
            m.on("/translate") { 200 to """{"translatedText":"只有一条"}""" }
            m.start()
            assertEquals(listOf("只有一条"), CustomSource(m.base + "/translate", "").translate(listOf("x"), "zh-CN"))
            assertTrue(!JSONObject(m.requests.single().body).has("api_key"))
        }
    }

    @Test
    fun custom_errorMessagesMapToKinds() {
        Mock().use { m ->
            m.on("/translate") { 400 to """{"error":"Invalid API key"}""" }
            m.start()
            try {
                CustomSource(m.base, "bad").translate(listOf("x"), "en")
                fail("should throw")
            } catch (e: TranslateException) {
                assertEquals(TranslateException.Kind.AUTH, e.kind)
                assertTrue(e.detail, e.detail.contains("Invalid API key"))
            }
        }
        try {
            CustomSource("not a url", "").translate(listOf("x"), "en")
            fail("should throw")
        } catch (e: TranslateException) {
            assertEquals(TranslateException.Kind.CONFIG, e.kind)
        }
    }

    // ------------------------------------------------------------------ TranslateService

    private class FakeSource(val failures: MutableList<TranslateException> = ArrayList()) : dev.dsh.miuixbrowser.translate.TranslateSource {
        override val id = "fake"
        val calls = ArrayList<List<String>>()
        override fun translate(texts: List<String>, to: String): List<String> {
            calls += texts
            if (failures.isNotEmpty()) throw failures.removeAt(0)
            return texts.map { "<$it>" }
        }
    }

    @Test
    fun service_dedupesCachesAndKeepsBlankAndOrder() {
        val fake = FakeSource()
        val svc = TranslateService(fake, "zh-CN", sleep = {})
        assertEquals(listOf("<a>", "  ", "<b>", "<a>"), svc.translate(listOf("a", "  ", "b", "a")))
        assertEquals(1, fake.calls.size)
        assertEquals(listOf("a", "b"), fake.calls[0])
        svc.translate(listOf("b", "c"))
        assertEquals("只请求没缓存过的", listOf("c"), fake.calls[1])
    }

    @Test
    fun service_batchesByCount() {
        val fake = FakeSource()
        val svc = TranslateService(fake, "en", sleep = {})
        svc.translate((1..45).map { "t$it" })
        assertEquals(listOf(20, 20, 5), fake.calls.map { it.size })
    }

    @Test
    fun service_batchesByChars() {
        val fake = FakeSource()
        val svc = TranslateService(fake, "en", sleep = {})
        val big = (0 until 6).map { "x".repeat(1000) + it }
        svc.translate(big)
        assertTrue(fake.calls.all { c -> c.sumOf { it.length } <= TranslateService.MAX_BATCH_CHARS })
        assertEquals(6, fake.calls.sumOf { it.size })
    }

    @Test
    fun service_splitsLongTextAndRejoins() {
        val fake = FakeSource()
        val svc = TranslateService(fake, "en", sleep = {})
        val sentence = "This is a sentence that is fairly long. "
        val text = sentence.repeat(120)
        val out = svc.translate(listOf(text)).single()
        assertTrue(fake.calls.flatten().all { it.length <= TranslateService.MAX_PIECE_CHARS })
        assertEquals(text, out.replace("<", "").replace(">", ""))
        // 每段都在句末切开
        assertTrue(fake.calls.flatten().all { it.trimEnd().endsWith(".") })
    }

    @Test
    fun splitText_roundTripsAndHardCuts() {
        val noBreaks = "あ".repeat(5000)
        val parts = TranslateService.splitText(noBreaks, 1500)
        assertEquals(noBreaks, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 1500 })
        val emoji = "😀".repeat(2000) // surrogate pairs 不能被切开
        val p2 = TranslateService.splitText(emoji, 1501)
        assertEquals(emoji, p2.joinToString(""))
        assertTrue(p2.all { !Character.isHighSurrogate(it.last()) })
    }

    @Test
    fun service_retriesTransientOnceButNotAuth() {
        val fake = FakeSource(mutableListOf(TranslateException(TranslateException.Kind.NETWORK, "timeout")))
        val svc = TranslateService(fake, "en", sleep = {})
        assertEquals(listOf("<a>"), svc.translate(listOf("a")))
        assertEquals(2, fake.calls.size)

        val fake2 = FakeSource(mutableListOf(TranslateException(TranslateException.Kind.AUTH, "HTTP 401")))
        try {
            TranslateService(fake2, "en", sleep = {}).translate(listOf("a"))
            fail("should throw")
        } catch (e: TranslateException) {
            assertEquals(1, fake2.calls.size)
        }

        val fake3 = FakeSource(mutableListOf(TranslateException(TranslateException.Kind.HTTP, "HTTP 503"), TranslateException(TranslateException.Kind.HTTP, "HTTP 503")))
        try {
            TranslateService(fake3, "en", sleep = {}).translate(listOf("a"))
            fail("should throw")
        } catch (e: TranslateException) {
            assertEquals("只重试一次", 2, fake3.calls.size)
        }
    }

    @Test
    fun service_partialProgressSurvivesFailure() {
        val fake = FakeSource()
        val svc = TranslateService(fake, "en", sleep = {})
        // 第 2 批失败（两次尝试都失败）
        fake.failures.clear()
        val texts = (1..30).map { "t$it" }
        svc.translate(texts.take(20))
        fake.failures += TranslateException(TranslateException.Kind.PARSE, "x")
        try { svc.translate(texts); fail("should throw") } catch (_: TranslateException) {}
        fake.calls.clear()
        svc.translate(texts)
        assertEquals("前 20 条已在缓存里，只请求剩下 10 条", listOf(10), fake.calls.map { it.size })
    }

    // ------------------------------------------------------------------ 配置 / 语言

    @Test
    fun sources_factoryAndConfig() {
        assertEquals(TranslateSourceId.GOOGLE, TranslateSources.create(TranslateConfig(sourceId = "google")).id)
        assertEquals(TranslateSourceId.BING, TranslateSources.create(TranslateConfig(sourceId = "whatever")).id)
        try {
            TranslateSources.create(TranslateConfig(sourceId = "custom", customUrl = " "))
            fail("should throw")
        } catch (e: TranslateException) {
            assertEquals(TranslateException.Kind.CONFIG, e.kind)
        }
        assertEquals(TranslateSourceId.CUSTOM, TranslateSources.create(TranslateConfig(sourceId = "custom", customUrl = "https://x")).id)
    }

    @Test
    fun langs_normalizeAndLocale() {
        assertEquals("zh-CN", TranslateLangs.normalize(null))
        assertEquals("zh-CN", TranslateLangs.normalize("xx"))
        assertEquals("ja", TranslateLangs.normalize("JA"))
        assertEquals("zh-TW", TranslateLangs.fromLocale("zh", "HK"))
        assertEquals("zh-CN", TranslateLangs.fromLocale("zh", "CN"))
        assertEquals("fr", TranslateLangs.fromLocale("fr", "CA"))
        assertEquals("en", TranslateLangs.fromLocale("sw", "KE"))
    }
}
