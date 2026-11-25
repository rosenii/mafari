/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.data.LiquidParams
import dev.dsh.miuixbrowser.data.LiquidPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidParamsTest {
    @Test fun everyPresetRoundTrips() {
        LiquidPresets.ALL.forEach { preset ->
            assertEquals(preset.name, preset.params, LiquidParams.decode(preset.params.encode()))
        }
    }

    @Test fun presetValuesStayInsideSliderRanges() {
        LiquidPresets.ALL.forEach { preset ->
            LiquidParams.FIELDS.forEach { f ->
                val v = f.get(preset.params)
                assertTrue("${preset.name}/${f.name}=$v", v >= f.min && v <= f.max)
            }
        }
    }

    @Test fun defaultKeepsOriginalLook() {
        val d = LiquidParams.Default
        assertEquals(1f, d.blurDp(1f), 0f)
        assertEquals(22f, d.lensHeightDp(22f), 0f)
        assertEquals(52f, d.lensAmountDp(52f), 0f)
        assertEquals(0.35f, d.tintAlpha(0.35f), 0f)
        assertFalse(d.hasColorTweak) // 默认不追加 colorControls，外观与改造前一致
        assertTrue(d.hasBlur && d.hasLens && d.vibrancy && d.depthEffect && d.chromaticAberration)
    }

    @Test fun decodeRejectsBadInput() {
        assertNull(LiquidParams.decode(null))
        assertNull(LiquidParams.decode("1,2,3"))
        assertNull(LiquidParams.decode(LiquidParams.Default.encode().replaceFirst("1.0", "abc")))
    }

    @Test fun decodeClampsOutOfRange() {
        val parts = LiquidParams.Default.encode().split(',').toMutableList()
        parts[0] = "99"
        val p = LiquidParams.decode(parts.joinToString(","))
        assertNotNull(p)
        assertEquals(4f, p!!.blurScale, 0f)
    }

    @Test fun tintAlphaIsClamped() {
        assertEquals(1f, LiquidParams.Default.copy(tintScale = 2.5f).tintAlpha(0.92f), 0f)
    }

    @Test fun zeroRefractionDisablesLens() {
        assertFalse(LiquidParams.Default.copy(refractHeight = 0f).hasLens)
        assertFalse(LiquidParams.Default.copy(refractAmount = 0f).hasLens)
    }
}
