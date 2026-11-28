package dev.dsh.miuixbrowser.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEngineImportTest {
    @Test fun readsLegadoTtsSubscriptionAndConvertsDoubleEncode() {
        val raw = """[
            {"name":"B AI 中文","url":"https://dds.example/synth?text={{java.encodeURI(java.encodeURI(speakText))}}&speed=0.9&voiceId=x","header":"{\"X-Test\":\"1\"}"},
            {"name":"OpenAI","url":"https://api.example/v1/audio/speech","method":"POST","body":"{\"input\":\"{{speakText}}\"}"}
        ]"""
        val r = SpeechEngineImport.parse(raw)
        assertEquals(2, r.engines.size)
        assertEquals("B AI 中文", r.engines[0].name)
        assertTrue(r.engines[0].url.contains("{{speakTextUrl2}}"))
        assertTrue(!r.engines[0].url.contains("java.encodeURI"))
        assertEquals("POST", r.engines[1].method)
        assertTrue(r.engines[1].body.contains("{{speakText}}"))
    }

    @Test fun acceptsWrappedEngineArrayAndSkipsDisabled() {
        val raw = """{"engines":[
            {"name":"a","url":"https://a.example/x","enabled":false},
            {"name":"b","url":"https://b.example/x"}
        ]}"""
        val r = SpeechEngineImport.parse(raw)
        assertEquals(1, r.engines.size)
        assertEquals("b", r.engines[0].name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyOrNonHttpUrls() {
        SpeechEngineImport.parse("""[{"name":"x","url":"file:///x"}]""")
    }

    @Test fun splitsLegadoUrlOptionsIntoMethodBodyAndHeaders() {
        val raw = """[{"name":"P","header":"{\"X-Root\":\"1\"}",
            "url":"https://x.example/tts?t={{speakText}},{\"method\":\"POST\",\"body\":\"{\\\"input\\\":\\\"{{java.encodeURI(speakText)}}\\\"}\",\"headers\":{\"X-Opt\":\"2\"}}"}]"""
        val e = SpeechEngineImport.parse(raw).engines.single()
        assertEquals("https://x.example/tts?t={{speakText}}", e.url)
        assertEquals("POST", e.method)
        assertTrue(e.body.contains("{{speakTextUrl}}"))
        assertTrue(e.headers.contains("X-Root") && e.headers.contains("X-Opt"))
    }

    @Test fun placeholderBraceIsNotMistakenForOptions() {
        val raw = """[{"name":"a","url":"https://x.example/t?a=1,{{speakText}}"}]"""
        assertEquals("https://x.example/t?a=1,{{speakText}}", SpeechEngineImport.parse(raw).engines.single().url)
    }

    @Test fun convertsSingleEncodeVariants() {
        val raw = """[
            {"name":"a","url":"https://a.example/?t={{java.encodeURI(speakText,\"UTF-8\")}}"},
            {"name":"b","url":"https://b.example/?t={{ encodeURIComponent( speakText ) }}"},
            {"name":"c","url":"https://c.example/?t={{encodeURIComponent(encodeURIComponent(speakText))}}"}
        ]"""
        val r = SpeechEngineImport.parse(raw)
        assertEquals(3, r.engines.size)
        assertTrue(r.engines[0].url.endsWith("{{speakTextUrl}}"))
        assertTrue(r.engines[1].url.endsWith("{{speakTextUrl}}"))
        assertTrue(r.engines[2].url.endsWith("{{speakTextUrl2}}"))
    }

    @Test fun skipsJsEnginesAndReportsThem() {
        val raw = """[
            {"name":"js","url":"@js:java.ajax('x')"},
            {"name":"opt","url":"https://o.example/t,{\"js\":\"1+1\"}"},
            {"name":"expr","url":"https://e.example/t?s={{speakSpeed*2}}"},
            {"name":"ok","url":"https://ok.example/t?s={{speakSpeed}}"}
        ]"""
        val r = SpeechEngineImport.parse(raw)
        assertEquals(listOf("ok"), r.engines.map { it.name })
        assertEquals(3, r.unsupported.size)
        assertEquals(3, r.skipped)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWhenEveryEngineNeedsJs() {
        SpeechEngineImport.parse("""[{"name":"js","url":"https://x.example/t?s={{java.md5Encode(speakText)}}"}]""")
    }

    @Test fun keepsSameUrlWithDifferentBodiesButDropsExactDuplicates() {
        val raw = """[
            {"name":"v1","url":"https://api.example/tts","method":"POST","body":"{\"voice\":\"a\",\"t\":\"{{speakTextJson}}\"}"},
            {"name":"v2","url":"https://api.example/tts","method":"POST","body":"{\"voice\":\"b\",\"t\":\"{{speakTextJson}}\"}"},
            {"name":"v1 again","url":"https://api.example/tts","method":"POST","body":"{\"voice\":\"a\",\"t\":\"{{speakTextJson}}\"}"}
        ]"""
        val r = SpeechEngineImport.parse(raw)
        assertEquals(listOf("v1", "v2"), r.engines.map { it.name })
        assertEquals(1, r.skipped)
    }

    @Test fun bodyWithoutMethodMeansPost() {
        val r = SpeechEngineImport.parse("""[{"name":"a","url":"https://a.example/t","body":"x={{speakText}}"}]""")
        assertEquals("POST", r.engines.single().method)
    }

    @Test fun wrapperNameBecomesTitle() {
        val r = SpeechEngineImport.parse("""{"name":"我的订阅","engines":[{"name":"a","url":"https://a.example/t"}]}""")
        assertEquals("我的订阅", r.title)
    }

    @Test fun invalidJsonGivesReadableError() {
        val e = runCatching { SpeechEngineImport.parse("{not json") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }
}

