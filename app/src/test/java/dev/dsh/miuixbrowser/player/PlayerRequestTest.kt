/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：请求头拼装与 HLS 判定（Intent 往返依赖 Android，不在这里测）。 */
class PlayerRequestTest {
    @Test fun headersOnlyIncludeNonBlank() {
        assertEquals(emptyMap<String, String>(), PlayerRequest("https://a/b.mp4").headers())
        assertEquals(
            mapOf("Referer" to "https://a/", "Origin" to "https://a", "Cookie" to "k=v"),
            PlayerRequest("https://a/b.mp4", referer = "https://a/", cookie = "k=v").headers(),
        )
    }

    @Test fun originDerivedFromRefererOrExplicit() {
        assertEquals("https://p.example.com:8443", PlayerRequest.originOf("https://p.example.com:8443/embed/1?x=2#y"))
        assertEquals("", PlayerRequest.originOf("content://media/1"))
        // 显式 origin 优先于从 referer 推出的
        assertEquals(
            "https://o.example",
            PlayerRequest("https://a/b.mp4", referer = "https://a/p", origin = "https://o.example").headers()["Origin"],
        )
        // 本地文件不带 Origin
        assertFalse(PlayerRequest("content://m/1", standalone = true, referer = "https://a/").headers().containsKey("Origin"))
    }

    @Test fun hlsDetection() {
        assertTrue(PlayerRequest("https://a/x/index.m3u8").isHls())
        assertTrue(PlayerRequest("https://a/x/index.M3U8?token=1#t").isHls())
        assertFalse(PlayerRequest("https://a/x/video.mp4?u=a.m3u8").isHls())
        assertTrue(PlayerRequest("https://a/play?id=1", type = "hls").isHls())
        assertFalse(PlayerRequest("https://a/x/index.m3u8", type = "mp4").isHls())
    }

    @Test fun downloadNameSanitizesTitleAndPicksExtension() {
        assertEquals("video.mp4", PlayerRequest("https://a/x").downloadName())
        assertEquals("a b c.mp4", PlayerRequest("https://a/x.mp4", title = "a/b:c").downloadName())
        assertEquals("电影 第1集.mkv", PlayerRequest("https://a/v/f.mkv?x=1", title = "电影  第1集").downloadName())
        // 地址后缀不是常见视频格式时回落到 mp4；HLS 合并后统一 mp4
        assertEquals("t.mp4", PlayerRequest("https://a/v/page.php?f=1", title = "t").downloadName())
        assertEquals("t.mp4", PlayerRequest("https://a/index.m3u8", title = "t").downloadName())
        assertEquals("video.mp4", PlayerRequest("https://a/x", title = " ... ").downloadName())
        assertTrue(PlayerRequest("https://a/x", title = "x".repeat(300)).downloadName().length <= 84)
    }

    @Test fun allSourcesPutsMainFirstAndDedupes() {
        val r = PlayerRequest(
            "https://a/1.mp4",
            type = "mp4",
            sources = listOf(
                PlayerSource("https://a/1.mp4", "dup"),
                PlayerSource("https://b/2.m3u8", "线路2", "hls"),
                PlayerSource("ftp://c/3", "bad"),
                PlayerSource("https://b/2.m3u8", "again"),
            ),
        )
        val all = r.allSources()
        assertEquals(listOf("https://a/1.mp4", "https://b/2.m3u8"), all.map { it.url })
        assertEquals("mp4", all[0].type)
        assertEquals("线路2", all[1].label)
        // 没有额外源：只有主地址，界面据此不显示源列表
        assertEquals(1, PlayerRequest("https://a/1.mp4").allSources().size)
    }
}
