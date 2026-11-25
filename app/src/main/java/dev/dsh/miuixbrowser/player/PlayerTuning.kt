/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.player

import android.content.Context

/**
 * Media3 高级选项（设置 → 媒体嗅探与播放 → Media3 高级选项）。
 *
 * 所有取值都是「下标 / 开关」，和 [PlayerPrefs] 共用 mafari_settings。播放器每次打开时读一次（[read]），
 * 改了设置后「下次打开播放器生效」。不动设置 = 用 Media3 当前版本自己的默认行为（Media3 1.12 起新增的几项见下面标注）。
 * 这里不碰任何 Media3 / Android 类型（除读 prefs 的 [read]），取值收敛和缓冲档位可以纯 JVM 单测。
 */
data class PlayerTuning(
    val hdr: Int = HDR_AUTO,
    val maxRes: Int = 0,
    val decoder: Int = DEC_AUTO,
    val decoderFallback: Boolean = false,
    val asyncCodec: Int = ASYNC_AUTO,
    val tunneling: Boolean = false,
    val bufferPreset: Int = BUF_BALANCED,
    val bufferMbIdx: Int = 0,
    val backBufferIdx: Int = 0,
    val seekMode: Int = SEEK_EXACT,
    val audioFocus: Boolean = true,
    val pauseOnNoisy: Boolean = true,
    val netTimeout: Int = NET_DEFAULT,
    // ---- Media3 1.12 起新增 ----
    /** 拖动进度条时实时预览画面（ExoPlayer 的 Scrubbing 模式） */
    val scrubPreview: Boolean = false,
    /** 卡死检测档位：默认 / 宽松 / 关闭（[STUCK_DEFAULT] ...） */
    val stuck: Int = STUCK_DEFAULT,
    /** 渐进式文件（MP4 等）只加载选中的画面轨 / 图片轨，1.12 起库里默认开 */
    val loadOnlySelectedTracks: Boolean = true,
    /** Media3 的「灰度」总开关（Flags.setCanaryModeEnabled），库里默认开 */
    val canary: Boolean = true,
    /** 实验：创建 AudioTrack 后提前初始化，缩短第一次起播等待 */
    val fastStart: Boolean = false,
    /** 实验：精确定位时目标位置之前的帧只解码不渲染（API 34+ 才有效果） */
    val fastSeek: Boolean = false,
) {
    /** 缓冲时长四元组（毫秒）：最小保持 / 最大缓冲 / 起播所需 / 卡顿后重新起播所需 */
    class BufferDurations(val minMs: Int, val maxMs: Int, val playbackMs: Int, val rebufferMs: Int)

    val bufferDurations: BufferDurations get() = BUFFER_PRESETS[clamp(bufferPreset, BUFFER_PRESETS.size)]

    /** 缓冲字节数上限；0 = 交给 ExoPlayer 按渲染器类型自己算（省内存档在「自动」时用 [LOW_MEMORY_BYTES]） */
    val bufferBytes: Int
        get() {
            val mb = BUFFER_MB[clamp(bufferMbIdx, BUFFER_MB.size)]
            return when {
                mb > 0 -> mb * 1024 * 1024
                bufferPreset == BUF_LOW_MEMORY -> LOW_MEMORY_BYTES
                else -> 0
            }
        }

    val backBufferMs: Int get() = BACK_BUFFER_SEC[clamp(backBufferIdx, BACK_BUFFER_SEC.size)] * 1000

    /** 清晰度上限的长边像素；0 = 不限 */
    val maxLongSide: Int get() = MAX_RES_LONG_SIDE[clamp(maxRes, MAX_RES_LONG_SIDE.size)]

    /** 卡死检测的三个超时（毫秒）；null = 不改，用库里的默认值（进度停住 10 秒 / 播完不结束 60 秒 / 缓冲无进展 10 分钟） */
    class StuckTimeouts(val playingMs: Int, val notEndingMs: Int, val bufferingMs: Int)

    val stuckTimeouts: StuckTimeouts? get() = STUCK_PRESETS[clamp(stuck, STUCK_PRESETS.size)]

    /** 卡死检测是否开着（关闭档会同时关掉 Media3 的 FLAG_ENABLE_STUCK_PLAYING_DETECTION 和缓冲超时） */
    val stuckDetectionEnabled: Boolean get() = clamp(stuck, STUCK_PRESETS.size) != STUCK_OFF

    /** (连接超时, 读取超时) 毫秒 */
    val netTimeouts: Pair<Int, Int> get() = NET_TIMEOUTS[clamp(netTimeout, NET_TIMEOUTS.size)]

    companion object {
        // HDR 策略：只对有多个清晰度 / 色域的流（HLS）生效；单个 mp4 文件没得选
        const val HDR_AUTO = 0
        const val HDR_PREFER_SDR = 1
        const val HDR_PREFER_HDR = 2

        const val DEC_AUTO = 0
        const val DEC_HW_FIRST = 1
        const val DEC_SW_FIRST = 2

        const val ASYNC_AUTO = 0
        const val ASYNC_ON = 1
        const val ASYNC_OFF = 2

        const val BUF_LOW_MEMORY = 0
        const val BUF_BALANCED = 1
        const val BUF_SMOOTH = 2
        const val BUF_MAX = 3

        const val SEEK_EXACT = 0
        const val SEEK_KEYFRAME = 1

        const val NET_DEFAULT = 0
        const val NET_STRICT = 1
        const val NET_RELAXED = 2

        const val STUCK_DEFAULT = 0
        const val STUCK_RELAXED = 1
        const val STUCK_OFF = 2

        const val LOW_MEMORY_BYTES = 48 * 1024 * 1024

        /** 下标对应 STUCK_*：默认（null = 不改库里的值）/ 宽松 30s·3min·30min / 关闭（全部拉满） */
        val STUCK_PRESETS: List<StuckTimeouts?> = listOf(
            null,
            StuckTimeouts(30_000, 180_000, 1_800_000),
            StuckTimeouts(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
        )

        /** 与加选项前的写死值一致的「均衡」档放在下标 1 */
        val BUFFER_PRESETS = listOf(
            BufferDurations(10_000, 30_000, 1_000, 2_000),
            BufferDurations(15_000, 60_000, 1_000, 2_000),
            BufferDurations(30_000, 120_000, 1_500, 3_000),
            BufferDurations(60_000, 300_000, 2_000, 5_000),
        )

        /** 缓冲大小上限（MB），0 = 自动 */
        val BUFFER_MB = listOf(0, 32, 64, 128, 256)

        /** 已播放部分保留多久（秒），用于往回拖不重新下载；0 = 不保留 */
        val BACK_BUFFER_SEC = listOf(0, 15, 30, 60)

        /** 最高清晰度（长边像素，横竖屏都适用）；0 = 不限 */
        val MAX_RES_LONG_SIDE = listOf(0, 3840, 2560, 1920, 1280, 854)
        val MAX_RES_LABELS_P = listOf(0, 2160, 1440, 1080, 720, 480)

        val NET_TIMEOUTS = listOf(15_000 to 20_000, 8_000 to 10_000, 30_000 to 40_000)

        fun clamp(v: Int, size: Int): Int = if (v in 0 until size) v else 0

        // ---- prefs 键 ----
        const val KEY_HDR = "player_adv_hdr"
        const val KEY_MAX_RES = "player_adv_max_res"
        const val KEY_DECODER = "player_adv_decoder"
        const val KEY_DECODER_FALLBACK = "player_adv_decoder_fallback"
        const val KEY_ASYNC = "player_adv_async_codec"
        const val KEY_TUNNELING = "player_adv_tunneling"
        const val KEY_BUF_PRESET = "player_adv_buf_preset"
        const val KEY_BUF_MB = "player_adv_buf_mb"
        const val KEY_BACK_BUF = "player_adv_back_buf"
        const val KEY_SEEK = "player_adv_seek"
        const val KEY_AUDIO_FOCUS = "player_adv_audio_focus"
        const val KEY_NOISY = "player_adv_noisy"
        const val KEY_NET = "player_adv_net"
        const val KEY_SCRUB = "player_adv_scrub_preview"
        const val KEY_STUCK = "player_adv_stuck"
        const val KEY_LOAD_SELECTED = "player_adv_load_selected"
        const val KEY_CANARY = "player_adv_canary"
        const val KEY_FAST_START = "player_adv_fast_start"
        const val KEY_FAST_SEEK = "player_adv_fast_seek"

        /** 全部键（设置页「恢复默认」用） */
        val ALL_KEYS = listOf(
            KEY_HDR, KEY_MAX_RES, KEY_DECODER, KEY_DECODER_FALLBACK, KEY_ASYNC, KEY_TUNNELING,
            KEY_BUF_PRESET, KEY_BUF_MB, KEY_BACK_BUF, KEY_SEEK, KEY_AUDIO_FOCUS, KEY_NOISY, KEY_NET,
            KEY_SCRUB, KEY_STUCK, KEY_LOAD_SELECTED, KEY_CANARY, KEY_FAST_START, KEY_FAST_SEEK,
        )

        /** 下拉类选项的选项数（键 → 个数），写入时按它收敛，手改 prefs 的脏值回到 0 */
        val CHOICE_SIZES = mapOf(
            KEY_HDR to 3, KEY_MAX_RES to MAX_RES_LONG_SIDE.size, KEY_DECODER to 3, KEY_ASYNC to 3,
            KEY_BUF_PRESET to BUFFER_PRESETS.size, KEY_BUF_MB to BUFFER_MB.size, KEY_BACK_BUF to BACK_BUFFER_SEC.size,
            KEY_SEEK to 2, KEY_NET to NET_TIMEOUTS.size, KEY_STUCK to STUCK_PRESETS.size,
        )

        /** 开关类选项的默认值 */
        val SWITCH_DEFAULTS = mapOf(
            KEY_DECODER_FALLBACK to false, KEY_TUNNELING to false, KEY_AUDIO_FOCUS to true, KEY_NOISY to true,
            KEY_SCRUB to false, KEY_LOAD_SELECTED to true, KEY_CANARY to true, KEY_FAST_START to false, KEY_FAST_SEEK to false,
        )

        /** 下拉类选项的默认下标（只有缓冲档不是 0） */
        fun choiceDefault(key: String): Int = if (key == KEY_BUF_PRESET) BUF_BALANCED else 0

        fun read(context: Context): PlayerTuning {
            val p = context.applicationContext.getSharedPreferences(PlayerPrefs.PREFS, Context.MODE_PRIVATE)
            fun choice(key: String) = clamp(p.getInt(key, choiceDefault(key)), CHOICE_SIZES.getValue(key))
            fun flag(key: String) = p.getBoolean(key, SWITCH_DEFAULTS.getValue(key))
            return PlayerTuning(
                hdr = choice(KEY_HDR),
                maxRes = choice(KEY_MAX_RES),
                decoder = choice(KEY_DECODER),
                decoderFallback = flag(KEY_DECODER_FALLBACK),
                asyncCodec = choice(KEY_ASYNC),
                tunneling = flag(KEY_TUNNELING),
                bufferPreset = choice(KEY_BUF_PRESET),
                bufferMbIdx = choice(KEY_BUF_MB),
                backBufferIdx = choice(KEY_BACK_BUF),
                seekMode = choice(KEY_SEEK),
                audioFocus = flag(KEY_AUDIO_FOCUS),
                pauseOnNoisy = flag(KEY_NOISY),
                netTimeout = choice(KEY_NET),
                scrubPreview = flag(KEY_SCRUB),
                stuck = choice(KEY_STUCK),
                loadOnlySelectedTracks = flag(KEY_LOAD_SELECTED),
                canary = flag(KEY_CANARY),
                fastStart = flag(KEY_FAST_START),
                fastSeek = flag(KEY_FAST_SEEK),
            )
        }
    }
}
