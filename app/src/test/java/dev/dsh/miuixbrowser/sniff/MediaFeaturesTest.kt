/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.sniff

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 纯 JVM：「媒体嗅探与播放」总开关 / 网页增强对各子开关的约束。 */
class MediaFeaturesTest {

    @Before @After fun reset() {
        MediaFeatures.master = true
        MediaFeatures.pageEnhancements = true
        MediaFeatures.enhancementsApply = true
        MediaFeatures.sniffSwitch = true
        MediaFeatures.autoPipSwitch = true
        MediaFeatures.autoFloatSwitch = true
    }

    @Test fun allOnByDefault() {
        assertTrue(MediaFeatures.active)
        assertTrue(MediaFeatures.sniffActive)
        assertTrue(MediaFeatures.autoPipActive)
        assertTrue(MediaFeatures.autoFloatActive)
    }

    @Test fun masterOffStopsEverything() {
        MediaFeatures.master = false
        assertFalse(MediaFeatures.active)
        assertFalse(MediaFeatures.sniffActive)
        assertFalse(MediaFeatures.autoPipActive)
        assertFalse(MediaFeatures.autoFloatActive)
    }

    @Test fun pageEnhancementsOffStopsEverythingOnWebView() {
        MediaFeatures.pageEnhancements = false
        assertFalse(MediaFeatures.active)
        assertFalse(MediaFeatures.sniffActive)
    }

    @Test fun pageEnhancementsIgnoredOnGecko() {
        MediaFeatures.enhancementsApply = false
        MediaFeatures.pageEnhancements = false
        assertTrue(MediaFeatures.active)
        assertTrue(MediaFeatures.sniffActive)
    }

    @Test fun individualSwitchesAreIndependent() {
        MediaFeatures.autoPipSwitch = false
        assertTrue(MediaFeatures.sniffActive)
        assertFalse(MediaFeatures.autoPipActive)
        assertTrue(MediaFeatures.autoFloatActive)
        MediaFeatures.sniffSwitch = false
        assertFalse(MediaFeatures.sniffActive)
        assertTrue(MediaFeatures.autoFloatActive)
    }
}
