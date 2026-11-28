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
}
