/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.player

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.script.CastPickerUi
import dev.dsh.miuixbrowser.script.DlnaCast
import dev.dsh.miuixbrowser.script.DlnaDevice
import dev.dsh.miuixbrowser.script.MediaHost
import dev.dsh.miuixbrowser.util.Diag
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生播放页的投屏：把原来只给脚本悬浮播放器用的投屏能力（[MediaHost] 里的 DLNA 搜索 / 屏幕镜像 / 外部播放器，
 * 协议在 [DlnaCast]）接到 [MafariPlayer] 上。
 *
 *  - 点菜单里的「投屏」→ 弹「投屏到」面板（[dev.dsh.miuixbrowser.ui.CastPickerDialog]，状态在 [pickerUi]）：
 *    附近的 DLNA 设备 / 屏幕镜像 / 外部打开。
 *  - 选 DLNA 设备：本地播放暂停，电视从当前进度开始播（和脚本一样，DLNA 由电视自己拉流，带不了 Referer / Cookie，
 *    防盗链源会失败，面板里的「外部打开」可以兜底）。投屏期间播放页盖一层遥控（播放 / 暂停、±10 秒、结束），
 *    进度每秒轮询电视得到。
 *  - 结束投屏（点结束 / 电视上停止 / 播完 / 失联 / 关闭播放页）：电视停下；本地回到电视上的进度，保持暂停，由用户决定是否继续。
 *
 * 所有状态只能在主线程改；网络在 [MediaHost.io] 上做，经 [MediaHost.main] 回主线程。
 *
 * @param withPermission Android 17 起搜索局域网要先有「附近设备」权限：由 Activity 提供授权流程，回调在主线程。
 * @param beforeLeave 即将拉起别的 Activity（外部播放器 / 系统投屏）：播放页据此短时间内不要自动进画中画。
 */
class NativeCast(
    private val activity: ComponentActivity,
    private val player: () -> MafariPlayer?,
    private val withPermission: ((Boolean) -> Unit) -> Unit,
    private val beforeLeave: () -> Unit,
) {
    /** 「投屏到」面板的界面状态 */
    val pickerUi = CastPickerUi()

    /** 正在投屏（含连接中）的设备名；null = 没在投屏 */
    var deviceName by mutableStateOf<String?>(null)
        private set

    /** 已发出投屏请求、电视还没开始播 */
    var connecting by mutableStateOf(false)
        private set

    /** 电视上是否在播放（暂停中为 false） */
    var playing by mutableStateOf(true)
        private set

    /** 电视上的进度 / 总时长（秒；总时长未知为 0） */
    var posSec by mutableIntStateOf(0)
        private set
    var durSec by mutableIntStateOf(0)
        private set

    val active: Boolean get() = deviceName != null

    private class Session(val dev: DlnaDevice, val dlna: DlnaCast) {
        @Volatile var stop = false
    }

    private var session: Session? = null
    private var pickerCancel: AtomicBoolean? = null

    // ------------------------------------------------------------------ 面板

    fun openPicker() {
        val p = player() ?: return
        closePicker()
        val ui = pickerUi
        val cancelled = AtomicBoolean(false)
        pickerCancel = cancelled
        ui.devices.clear()
        ui.status = tr("正在搜索局域网设备…")
        ui.onDevice = { d -> closePicker(); startCast(d) }
        ui.onMirror = {
            closePicker()
            MediaHost.openSystemCast(activity, beforeLaunch = beforeLeave)
        }
        ui.onExternal = {
            closePicker()
            if (!MediaHost.launchExternal(activity, requestJson(p), beforeLaunch = beforeLeave)) {
                toast(tr("没有找到可用的播放器"))
            }
        }
        ui.onCancel = { closePicker() }
        ui.onRefresh = {
            if (!cancelled.get()) {
                ui.devices.clear()
                ui.status = tr("正在搜索局域网设备…")
                MediaHost.discoverDevices(activity, ui, cancelled)
            }
        }
        ui.show = true
        withPermission { granted ->
            if (cancelled.get()) return@withPermission
            if (granted) {
                MediaHost.discoverDevices(activity, ui, cancelled)
            } else {
                ui.status = tr("没有「附近设备」权限，无法搜索局域网设备。请在 系统设置 → 应用 → Mafari → 权限 里允许后重试")
            }
        }
    }

    private fun closePicker() {
        pickerCancel?.set(true) // 搜索循环也认这个标记，一并停掉
        pickerCancel = null
        pickerUi.scanning = false
        pickerUi.show = false
    }

    /** 脚本宿主 `external` / `cast` 用的请求格式（见 docs/MEDIA_HOST.md） */
    private fun requestJson(p: MafariPlayer): JSONObject {
        val r = p.currentRequest
        return JSONObject()
            .put("url", r.url).put("title", r.title).put("type", r.type)
            .put("ua", r.ua).put("referer", r.referer).put("cookie", r.cookie)
            .put("pos", (p.exo.currentPosition / 1000).toInt())
    }

    // ------------------------------------------------------------------ 投屏会话

    private fun startCast(dev: DlnaDevice) {
        val p = player() ?: return
        val r = p.currentRequest
        if (!(r.url.startsWith("http://", true) || r.url.startsWith("https://", true))) {
            toast(tr("这个视频地址无法投屏"))
            return
        }
        session?.let { end(it, null) }
        val s = Session(dev, MediaHost.newDlna(activity))
        session = s
        val startAt = if (p.isLive) 0 else (p.exo.currentPosition / 1000).toInt().coerceAtLeast(0)
        deviceName = dev.name
        connecting = true
        playing = true
        posSec = startAt
        durSec = 0
        p.pause() // 本地先停，免得电视和手机同时出声
        MediaHost.io.execute {
            try {
                s.dlna.setUriAndPlay(dev, r.url, r.title, r.type)
                val began = SystemClock.uptimeMillis()
                var misses = 0
                var sawPlaying = false
                var seeked = startAt <= 3
                while (!s.stop) {
                    Thread.sleep(1000)
                    if (s.stop) break
                    val q = runCatching { s.dlna.query(dev) }.getOrNull()
                    if (q == null) {
                        if (++misses >= 6) { end(s, tr("与「{0}」失去连接", dev.name)); return@execute }
                        continue
                    }
                    misses = 0
                    val live = q.state == "PLAYING" || q.state == "TRANSITIONING" || q.state == "PAUSED_PLAYBACK"
                    if (q.state == "PLAYING" || q.state == "PAUSED_PLAYBACK") sawPlaying = true
                    if (sawPlaying && !seeked) { seeked = true; runCatching { s.dlna.seek(dev, startAt) } }
                    if (!live && sawPlaying) { end(s, null); return@execute } // 播完了，或在电视上点了停止
                    if (!live && SystemClock.uptimeMillis() - began > 25_000) {
                        end(s, tr("「{0}」没有开始播放：可能不支持该格式，或该地址需要请求头（防盗链）", dev.name))
                        return@execute
                    }
                    if (live) {
                        MediaHost.main.post {
                            if (session === s) {
                                connecting = false
                                posSec = q.rel
                                durSec = q.dur
                                playing = q.state != "PAUSED_PLAYBACK"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Diag.log("NativeCast: $e")
                end(s, e.message ?: tr("投屏失败"))
            }
        }
    }

    /** 结束一次投屏（任意线程）：停轮询、尽力让电视也停下；回主线程把本地放回电视上的进度（保持暂停）并提示失败原因 */
    private fun end(s: Session, error: String?) {
        if (s.stop) return
        s.stop = true
        MediaHost.io.execute { runCatching { s.dlna.stop(s.dev) } }
        MediaHost.main.post {
            if (session !== s) return@post
            session = null
            val back = posSec
            deviceName = null
            connecting = false
            durSec = 0
            if (back > 0) player()?.let { if (!it.isLive) it.seekTo(back * 1000L) }
            if (error != null) toast(error)
        }
    }

    // ------------------------------------------------------------------ 遥控（投屏期间）

    fun stop() {
        session?.let { end(it, null) }
    }

    fun togglePlay() {
        val s = session ?: return
        if (connecting) return
        val wasPlaying = playing
        playing = !wasPlaying
        MediaHost.io.execute { runCatching { if (wasPlaying) s.dlna.pause(s.dev) else s.dlna.play(s.dev) } }
    }

    fun seekBy(deltaSec: Int) {
        val s = session ?: return
        if (connecting) return
        val max = if (durSec > 0) durSec else Int.MAX_VALUE
        val to = (posSec + deltaSec).coerceIn(0, max)
        posSec = to
        MediaHost.io.execute { runCatching { s.dlna.seek(s.dev, to) } }
    }

    /** 播放页销毁：不留一台还在播的电视。只发停止，不动界面状态。 */
    fun release() {
        closePicker()
        val s = session ?: return
        session = null
        s.stop = true
        MediaHost.io.execute { runCatching { s.dlna.stop(s.dev) } }
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
