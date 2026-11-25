/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：伪装图片头的判定与裁剪偏移（规则与下载器 stripFakeImageHeader 一致）。 */
class FakeImageHeaderTest {

    private fun ts(packets: Int = 8): ByteArray {
        val b = ByteArray(188 * packets)
        for (i in 0 until packets) b[i * 188] = 0x47
        return b
    }

    private fun withPrefix(prefix: ByteArray, body: ByteArray) = prefix + body

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test fun pngPrefixIsStripped() {
        val junk = png + ByteArray(1000 - png.size) { 0x11 }
        val data = withPrefix(junk, ts())
        assertEquals(1000, FakeImageHeader.stripOffset(data, data.size))
    }

    @Test fun jpegGifBmpPrefixesAreRecognized() {
        for (magic in listOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()), byteArrayOf(0x47, 0x49, 0x46, 0x38), byteArrayOf(0x42, 0x4D, 0, 0))) {
            val junk = magic + ByteArray(600 - magic.size) { 0x22 }
            val data = withPrefix(junk, ts())
            assertEquals(600, FakeImageHeader.stripOffset(data, data.size))
        }
    }

    @Test fun plainTsIsUntouched() {
        val data = ts()
        assertFalse(FakeImageHeader.looksLikeImage(data, data.size))
        assertEquals(0, FakeImageHeader.stripOffset(data, data.size))
    }

    @Test fun mp4IsUntouched() {
        val data = ByteArray(2000).also { it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte(); it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte() }
        assertEquals(0, FakeImageHeader.stripOffset(data, data.size))
    }

    @Test fun realImageWithoutTsIsUntouched() {
        val data = png + ByteArray(5000) { 0x33 }
        assertTrue(FakeImageHeader.looksLikeImage(data, data.size))
        assertEquals(0, FakeImageHeader.stripOffset(data, data.size))
    }

    @Test fun tooShortIsUntouched() {
        val data = png + ByteArray(50)
        assertEquals(0, FakeImageHeader.stripOffset(data, data.size))
        assertFalse(FakeImageHeader.looksLikeImage(data, 3))
    }
}
