/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.browser

/**
 * 原生播放器在播放时，所有网页里的音视频都要停住（否则网页视频和原生播放器同时出声）。
 *
 * 播放器（[owner]）开始播放 → [setPlaying] 置位，所有标签暂停并拦住页面媒体（复用主页盖住页面时的 [BrowserTab] 拦截：
 * Gecko 在 MediaSession.onPlay 里补暂停，WebView 用脚本暂停现有媒体并在捕获阶段拦 play 事件）；
 * 暂停 / 播完 / 关掉 → 解除，页面里的媒体回到用户自己点播放。主线程调用。
 */
internal object PageMediaGate {
    @Volatile
    var nativePlaying = false
        private set

    private var owner: Any? = null

    /** 切换媒体时新播放页可能先于旧页销毁：只有当前持有者才能解除，别把新页的状态抹掉 */
    fun setPlaying(who: Any, playing: Boolean) {
        if (playing) {
            owner = who
        } else {
            if (owner !== who) return
            owner = null
        }
        if (nativePlaying == playing) return
        nativePlaying = playing
        BrowserState.refreshMediaBlockCurrent()
    }
}
