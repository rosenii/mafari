/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：Media3 高级选项的默认值、档位和取值收敛（不碰 SharedPreferences / Media3）。 */
class PlayerTuningTest {

    @Test fun defaultsKeepThePreviousHardcodedBehavior() {
        val t = PlayerTuning()
        val d = t.bufferDurations
        // 加选项之前写死的是 setBufferDurationsMs(15_000, 60_000, 1_000, 2_000)
        assertEquals(15_000, d.minMs)
        assertEquals(60_000, d.maxMs)
        assertEquals(1_000, d.playbackMs)
        assertEquals(2_000, d.rebufferMs)
        assertEquals(0, t.bufferBytes)
        assertEquals(0, t.backBufferMs)
        assertEquals(0, t.maxLongSide)
        assertEquals(15_000 to 20_000, t.netTimeouts)
    }

    @Test fun clampFallsBackToZeroOutOfRange() {
        assertEquals(2, PlayerTuning.clamp(2, 3))
        assertEquals(0, PlayerTuning.clamp(3, 3))
        assertEquals(0, PlayerTuning.clamp(-1, 3))
    }

    @Test fun dirtyValuesNeverThrow() {
        val t = PlayerTuning(bufferPreset = 99, bufferMbIdx = -5, backBufferIdx = 42, maxRes = 100, netTimeout = 7)
        assertEquals(PlayerTuning.BUFFER_PRESETS[0].minMs, t.bufferDurations.minMs)
        assertEquals(0, t.bufferBytes)
        assertEquals(0, t.backBufferMs)
        assertEquals(0, t.maxLongSide)
        assertEquals(15_000 to 20_000, t.netTimeouts)
    }

    @Test fun lowMemoryPresetCapsBytesOnlyWhenSizeIsAuto() {
        assertEquals(PlayerTuning.LOW_MEMORY_BYTES, PlayerTuning(bufferPreset = PlayerTuning.BUF_LOW_MEMORY).bufferBytes)
        assertEquals(64 * 1024 * 1024, PlayerTuning(bufferPreset = PlayerTuning.BUF_LOW_MEMORY, bufferMbIdx = 2).bufferBytes)
        assertEquals(256 * 1024 * 1024, PlayerTuning(bufferPreset = PlayerTuning.BUF_BALANCED, bufferMbIdx = 4).bufferBytes)
    }

    /** DefaultLoadControl 要求 playback <= rebuffer <= min <= max，档位表不能违反，否则打开播放器就崩 */
    @Test fun bufferPresetsSatisfyLoadControlInvariants() {
        PlayerTuning.BUFFER_PRESETS.forEach {
            assertTrue(it.playbackMs <= it.minMs)
            assertTrue(it.rebufferMs <= it.minMs)
            assertTrue(it.minMs <= it.maxMs)
        }
    }

    @Test fun optionTablesLineUp() {
        assertEquals(PlayerTuning.MAX_RES_LONG_SIDE.size, PlayerTuning.MAX_RES_LABELS_P.size)
        assertEquals(PlayerTuning.ALL_KEYS.toSet(), (PlayerTuning.CHOICE_SIZES.keys + PlayerTuning.SWITCH_DEFAULTS.keys))
        assertEquals(PlayerTuning.BUF_BALANCED, PlayerTuning.choiceDefault(PlayerTuning.KEY_BUF_PRESET))
        assertEquals(0, PlayerTuning.choiceDefault(PlayerTuning.KEY_HDR))
    }

    @Test fun media3Dot12OptionsDefaultToLibraryBehavior() {
        val t = PlayerTuning()
        assertEquals(false, t.scrubPreview)
        assertEquals(true, t.loadOnlySelectedTracks)
        assertEquals(true, t.canary)
        assertEquals(false, t.fastStart)
        assertEquals(false, t.fastSeek)
        // 默认档不改库里的卡死超时
        assertEquals(null, t.stuckTimeouts)
        assertTrue(t.stuckDetectionEnabled)
    }

    @Test fun stuckPresetsAreSane() {
        val relaxed = PlayerTuning(stuck = PlayerTuning.STUCK_RELAXED)
        assertTrue(relaxed.stuckDetectionEnabled)
        relaxed.stuckTimeouts!!.let {
            assertTrue(it.playingMs > 10_000 && it.notEndingMs > 60_000 && it.bufferingMs > 600_000)
        }
        val off = PlayerTuning(stuck = PlayerTuning.STUCK_OFF)
        assertEquals(false, off.stuckDetectionEnabled)
        // Media3 的 Builder 要求超时 > 0
        PlayerTuning.STUCK_PRESETS.filterNotNull().forEach {
            assertTrue(it.playingMs > 0 && it.notEndingMs > 0 && it.bufferingMs > 0)
        }
        // 脏值回到默认档
        assertEquals(null, PlayerTuning(stuck = 99).stuckTimeouts)
        assertTrue(PlayerTuning(stuck = -1).stuckDetectionEnabled)
    }
}
