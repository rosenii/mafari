/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.books

import dev.dsh.miuixbrowser.books.data.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanerTest {
    @Test fun decodesNamedAndNumericEntities() {
        assertEquals("甲 乙 “引号”·", TextCleaner.decode("甲&nbsp;乙&ensp;&ldquo;引号&rdquo;&middot;"))
        assertEquals("A B", TextCleaner.decode("A&#32;B"))
    }

    @Test fun removesUrlsAndWatermarks() {
        assertEquals("正文", TextCleaner.line("正文 https://ads.example.com/x?y=1"))
        assertEquals("正文", TextCleaner.line("正文 www.example.com"))
        assertNull(TextCleaner.line("请记住本站域名 www.example.com"))
        assertNull(TextCleaner.line("https://only-ad.example.com"))
        assertNull(TextCleaner.line("本章未完，点击下一页"))
    }

    @Test fun keepsLegitimateText() {
        val s = "电视里正在播放广告，他随手关掉了。"
        assertEquals(s, TextCleaner.line(s))
        val long = "这是一段正常正文，里面提到广告行业的变化，但整段是故事内容，不应该被删掉。".repeat(2)
        assertTrue(TextCleaner.line(long) != null)
    }

    @Test fun removesInlineBracketAd() {
        assertEquals("前文 后文", TextCleaner.line("前文【www.ad.com】后文"))
    }
}
