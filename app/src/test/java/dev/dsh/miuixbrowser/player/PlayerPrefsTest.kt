/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** 纯 JVM：播放器设置的取值收敛（不碰 SharedPreferences）。 */
class PlayerPrefsTest {

    @Test fun renderModeClamps() {
        assertEquals(PlayerPrefs.RENDER_TEXTURE, PlayerPrefs.clampRender(0))
        assertEquals(PlayerPrefs.RENDER_SURFACE, PlayerPrefs.clampRender(1))
        assertEquals(PlayerPrefs.RENDER_TEXTURE, PlayerPrefs.clampRender(7))
        assertEquals(PlayerPrefs.RENDER_TEXTURE, PlayerPrefs.clampRender(-1))
    }

    @Test fun snapKeepsListedValuesAndFallsBackOtherwise() {
        assertEquals(1.5f, PlayerPrefs.snap(1.5f, PlayerPrefs.SPEEDS, 1f), 0f)
        assertEquals(1f, PlayerPrefs.snap(1.3f, PlayerPrefs.SPEEDS, 1f), 0f)
        assertEquals(4f, PlayerPrefs.snap(4f, PlayerPrefs.LONG_PRESS_SPEEDS, 2f), 0f)
        assertEquals(2f, PlayerPrefs.snap(0.5f, PlayerPrefs.LONG_PRESS_SPEEDS, 2f), 0f)
    }

    @Test fun defaultsAreInOptionLists() {
        assertEquals(true, 1f in PlayerPrefs.SPEEDS)
        assertEquals(true, 2f in PlayerPrefs.LONG_PRESS_SPEEDS)
    }
}
