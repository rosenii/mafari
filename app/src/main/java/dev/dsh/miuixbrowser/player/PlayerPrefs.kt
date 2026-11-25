/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.player

import android.content.Context

/**
 * 原生播放器的用户设置（设置 → 媒体嗅探与播放 → 播放器）。
 *
 * 和 AppSettings 共用同一份 SharedPreferences（mafari_settings）：播放页是独立 Activity，
 * 不构造 AppSettings，直接读这里；AppSettings 里的同名开关写同一个 key。
 */
object PlayerPrefs {
    const val PREFS = "mafari_settings"
    const val KEY_RENDER = "player_render_mode"
    const val KEY_SPEED = "player_default_speed"
    const val KEY_LONG_PRESS = "player_long_press_speed"
    const val KEY_BG_PLAY = "player_bg_play"
    const val KEY_QUICK_MENU = "player_quick_menu"

    /** 悬浮播放器下方快捷菜单里的按钮（顺序即显示顺序）；每个按钮可在设置里单独关 */
    val QUICK_IDS = listOf("cast", "download", "shot", "speed", "quality", "source")
    fun quickKey(id: String) = "player_quick_$id"

    /** 老版本把后台播放存在播放页自己的 prefs 里（mafari_player / bg_play），第一次读时沿用 */
    private const val LEGACY_PREFS = "mafari_player"
    private const val LEGACY_KEY_BG_PLAY = "bg_play"

    const val RENDER_TEXTURE = 0
    const val RENDER_SURFACE = 1

    /** 默认倍速可选值（下拉顺序） */
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    /** 长按倍速可选值（下拉顺序） */
    val LONG_PRESS_SPEEDS = listOf(1.5f, 2f, 3f, 4f)

    fun clampRender(v: Int): Int = if (v == RENDER_SURFACE) RENDER_SURFACE else RENDER_TEXTURE

    /** 不在可选列表里的值（手改 prefs / 旧数据）一律回到 [fallback] */
    fun snap(v: Float, options: List<Float>, fallback: Float): Float =
        options.firstOrNull { Math.abs(it - v) < 0.001f } ?: fallback

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun renderMode(context: Context): Int = clampRender(prefs(context).getInt(KEY_RENDER, RENDER_TEXTURE))

    fun setRenderMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_RENDER, clampRender(mode)).apply()
    }

    fun defaultSpeed(context: Context): Float = snap(prefs(context).getFloat(KEY_SPEED, 1f), SPEEDS, 1f)

    fun setDefaultSpeed(context: Context, v: Float) {
        prefs(context).edit().putFloat(KEY_SPEED, snap(v, SPEEDS, 1f)).apply()
    }

    fun longPressSpeed(context: Context): Float = snap(prefs(context).getFloat(KEY_LONG_PRESS, 2f), LONG_PRESS_SPEEDS, 2f)

    fun setLongPressSpeed(context: Context, v: Float) {
        prefs(context).edit().putFloat(KEY_LONG_PRESS, snap(v, LONG_PRESS_SPEEDS, 2f)).apply()
    }

    /** 快捷菜单总开关（默认开） */
    fun quickMenu(context: Context): Boolean = prefs(context).getBoolean(KEY_QUICK_MENU, true)

    /** 快捷菜单里实际要显示的按钮（总开关关了就是空），保持 [QUICK_IDS] 的顺序 */
    fun quickItems(context: Context): List<String> {
        val p = prefs(context)
        if (!p.getBoolean(KEY_QUICK_MENU, true)) return emptyList()
        return QUICK_IDS.filter { p.getBoolean(quickKey(it), true) }
    }

    fun bgPlay(context: Context): Boolean {
        val p = prefs(context)
        if (p.contains(KEY_BG_PLAY)) return p.getBoolean(KEY_BG_PLAY, true)
        return context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getBoolean(LEGACY_KEY_BG_PLAY, true)
    }

    fun setBgPlay(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_BG_PLAY, on).apply()
    }
}
