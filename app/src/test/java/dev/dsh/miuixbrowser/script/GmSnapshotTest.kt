/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.script

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 纯 JVM：通道版注入源码的拼装（值快照取舍 / 快照 JSON / shim 包装），对应规格第三节第 6 条 */
class GmSnapshotTest {
    private val shim = File("src/main/assets/mafari_gm_shim.js").readText(Charsets.UTF_8)

    @Test
    fun smallValuesAreInlinedSmallestFirst() {
        val (inline, big) = GmSnapshot.partitionValues(listOf("b" to 20, "a" to 10, "c" to 30))
        assertEquals(listOf("a", "b", "c"), inline)
        assertTrue(big.isEmpty())
    }

    @Test
    fun singleLargeValueIsNotInlined() {
        val limit = GmSnapshot.INLINE_VALUE_MAX
        val (inline, big) = GmSnapshot.partitionValues(listOf("small" to 5, "edge" to limit, "huge" to limit + 1))
        assertEquals(listOf("small", "edge"), inline)
        assertEquals(listOf("huge"), big)
    }

    @Test
    fun totalCapDropsTheLargestKeys() {
        val v = GmSnapshot.INLINE_VALUE_MAX // 64K；4 个正好 256K，第 5 个超出总量
        val entries = (1..5).map { "k$it" to v - it } + ("tiny" to 1)
        val (inline, big) = GmSnapshot.partitionValues(entries)
        assertTrue("tiny" in inline)
        val inlined = inline.sumOf { name -> entries.first { it.first == name }.second }
        assertTrue("内联总量不能超过上限", inlined <= GmSnapshot.SNAPSHOT_TOTAL_CAP)
        assertEquals("被挤出去的是最大的那个", listOf("k1"), big)
        assertEquals(6, inline.size + big.size)
    }

    @Test
    fun snapshotJsonShape() {
        val o = JSONObject(GmSnapshot.snapshotJson("sid-1", "tok", mapOf("a" to "\"x\"", "n" to "1"), listOf("big1")))
        assertEquals("sid-1", o.getString("id"))
        assertEquals("tok", o.getString("tok"))
        assertEquals("\"x\"", o.getJSONObject("values").getString("a")) // 原始 JSON 字符串原样保留（不二次解析）
        assertEquals("big1", o.getJSONArray("big").getString(0))
        assertEquals("stream", o.getJSONArray("caps").getString(0))
    }

    @Test
    fun shimTemplateEndsWithTheSnapshotPlaceholder() {
        // GeckoGm / GmSnapshot 都只替换文末这一个
        assertTrue(shim.trimEnd().endsWith("})(__MAFARI_SNAPSHOT__)"))
    }

    @Test
    fun wrapInjectsSnapshotAtTheTailOnly() {
        val snap = GmSnapshot.snapshotJson("s", "t", emptyMap(), emptyList())
        val out = GmSnapshot.wrapWithShim("/*RUNTIME*/", shim, snap)
        assertTrue(out.startsWith("(function(__mafariBridge){\n/*RUNTIME*/\n})("))
        assertTrue(out.endsWith("})($snap);")) // 快照紧跟在 shim 的 `})(` 之后
        assertTrue(out.trimEnd().endsWith(");"))
        assertFalse(out.contains("})(__MAFARI_SNAPSHOT__)")) // 文末占位符已被替换
    }

    @Test
    fun snapshotContentIsNeverRescanned() {
        // 值里恰好含占位符文字：既不能被二次替换，也不能影响运行时 / shim 的拼接
        val snap = GmSnapshot.snapshotJson("s", "t", mapOf("k" to "\"__MAFARI_SNAPSHOT__\""), emptyList())
        val out = GmSnapshot.wrapWithShim("RT", shim, snap)
        assertTrue(out.endsWith("})($snap);"))
        assertEquals(1, Regex(Regex.escape("\"__MAFARI_SNAPSHOT__\"")).findAll(out).count())
    }

    @Test
    fun runtimeWithDollarSignsAndBackslashesIsKeptVerbatim() {
        val rt = "var s = 'a\\\\b'; var t = `\${x}`; // \$1 \$&"
        val out = GmSnapshot.wrapWithShim(rt, shim, "{}")
        assertTrue(out.contains("\n$rt\n"))
    }
}
