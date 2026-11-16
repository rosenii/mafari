package dev.dsh.miuixbrowser.script

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Rational
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import dev.dsh.miuixbrowser.MainActivity
import dev.dsh.miuixbrowser.util.Diag
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用户脚本的「宿主能力」：GM_mafariHost(op, args, onEvent) 的原生实现。
 * 给悬浮媒体播放器这类脚本补上 WebView / Gecko 都没有的三件事：系统画中画、DLNA 投屏、外部播放器。
 *
 * 两个内核走同一份实现：WebView 经 [GmBridge.host]，Gecko 经 GeckoGm 的 "host" 消息，
 * 回包 / 事件统一经 [Reply] 回到页面里 `__mafariHostCallback_<id>(kind, json)`：kind = "r" 是本次调用的结果，"e" 是之后的宿主事件。
 *
 * 画中画的做法：Android 的画中画是「整个 Activity 缩成小窗」，所以脚本必须先让播放器进入 HTML 全屏
 * （WebView：onShowCustomView 全屏容器；Gecko：onFullScreen），小窗里才只剩画面；这里只负责进 / 出画中画、
 * 小窗操作栏（后退 / 播放·暂停 / 快进）和状态回传。
 */
object MediaHost {

    /** 回到页面：fn = 回调全局函数名，kind = "r" 结果 / "e" 事件，payload = JSON 字符串。可在任意线程调用 */
    fun interface Reply {
        fun send(fn: String, kind: String, payload: String)
    }

    private const val ACTION_PIP = "dev.dsh.miuixbrowser.PIP_ACTION"
    private const val EXTRA_ACT = "a"
    private const val TAG = "MediaHost"

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "media-host-io").apply { isDaemon = true } }

    private class Sink(val fn: String, val reply: Reply)

    @Volatile private var sink: Sink? = null

    // ---- 画中画状态（主线程读写）----
    private var armW = 0
    private var armH = 0
    private var armed = false
    private var playing = false
    private var pipOn = false
    private var lastPipAt = 0L
    private var suppressUntil = 0L
    private var receiver: BroadcastReceiver? = null

    // ---- 投屏 / 节流 ----
    private var lastHeavyAt = 0L
    private var cast: CastSession? = null
    private var picker: AlertDialog? = null

    // ------------------------------------------------------------------ 入口

    /**
     * 处理脚本的一次宿主调用。可在任意线程调用，内部切到主线程。
     * [activity] 必须是 [MainActivity]：WebAppActivity（桌面轻应用）等其它宿主一律回 api = 0，脚本自动退回原来的行为。
     */
    fun handle(activity: Activity?, engine: String, op: String, json: String, fn: String, reply: Reply) {
        main.post {
            val a = activity as? MainActivity
            val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
            fun ok(extra: JSONObject = JSONObject()) = reply.send(fn, "r", extra.put("ok", true).toString())
            fun fail(why: String) = reply.send(fn, "r", JSONObject().put("ok", false).put("error", why).toString())
            try {
                when (op) {
                    "caps" -> {
                        if (a == null || a.isFinishing || a.isDestroyed) {
                            reply.send(fn, "r", JSONObject().put("api", 0).toString())
                        } else {
                            val pip = a.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                            reply.send(
                                fn, "r",
                                JSONObject().put("api", 1).put("engine", engine).put("pip", pip).put("cast", true).put("ext", true).toString(),
                            )
                        }
                    }
                    "sub" -> {
                        if (a == null) return@post fail("unsupported")
                        sink = Sink(fn, reply)
                        // 先把当前画中画状态同步给新订阅者（页面刷新 / 重新打开播放器时）
                        if (pipOn) emit(JSONObject().put("ev", "pip").put("on", true))
                        ok()
                    }
                    "arm" -> {
                        if (a == null) return@post fail("unsupported")
                        armed = o.optBoolean("on", false)
                        armW = o.optInt("w", 16)
                        armH = o.optInt("h", 9)
                        playing = armed
                        applyParams(a)
                        ok()
                    }
                    "pipState" -> {
                        if (a == null) return@post fail("unsupported")
                        playing = o.optBoolean("playing", false)
                        if (pipOn || armed) applyParams(a)
                        ok()
                    }
                    "pip" -> {
                        if (a == null || !heavyOk()) return@post fail("unsupported")
                        armW = o.optInt("w", 16)
                        armH = o.optInt("h", 9)
                        playing = o.optBoolean("playing", false)
                        if (enterPip(a)) ok() else fail("pip_failed")
                    }
                    "cast" -> {
                        if (a == null || !heavyOk()) return@post fail("unsupported")
                        if (!isHttp(o.optString("url"))) return@post fail("bad_url")
                        showCastPicker(a, o)
                        ok()
                    }
                    "castCtl" -> {
                        val s = cast ?: return@post fail("no_session")
                        val pos = o.optInt("pos", 0)
                        when (o.optString("a")) {
                            "play" -> io.execute { runCatching { s.dlna.play(s.dev) } }
                            "pause" -> io.execute { runCatching { s.dlna.pause(s.dev) } }
                            "seek" -> io.execute { runCatching { s.dlna.seek(s.dev, pos) } }
                            "stop" -> stopCast(s, null)
                        }
                        ok()
                    }
                    "external" -> {
                        if (a == null || !heavyOk()) return@post fail("unsupported")
                        when (launchExternal(a, o)) {
                            true -> ok()
                            else -> fail("no_app")
                        }
                    }
                    "release" -> {
                        armed = false
                        playing = false
                        cast?.let { stopCast(it, null) }
                        sink = null
                        runCatching { picker?.dismiss() }
                        if (a != null) {
                            applyParams(a)
                            // 页面已经关掉播放器 / 跳走了，画中画里没有东西可看：把应用拉回前台，小窗自然展开
                            if (pipOn && !a.isDestroyed) {
                                runCatching {
                                    a.startActivity(
                                        Intent(a, MainActivity::class.java)
                                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                                    )
                                }
                            }
                        }
                        ok()
                    }
                    else -> fail("unknown_op")
                }
            } catch (e: Exception) {
                Diag.log("$TAG: $op failed $e")
                fail(e.message ?: "error")
            }
        }
    }

    private fun emit(o: JSONObject) {
        val s = sink ?: return
        s.reply.send(s.fn, "e", o.toString())
    }

    private fun heavyOk(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastHeavyAt < 800) return false // 页面脚本不得连续拉起面板 / 外部应用
        lastHeavyAt = now
        return true
    }

    private fun isHttp(u: String) = u.startsWith("http://", true) || u.startsWith("https://", true)

    // ------------------------------------------------------------------ 画中画

    private fun ratio(w: Int, h: Int): Rational {
        val r = (w.coerceAtLeast(1).toDouble() / h.coerceAtLeast(1)).coerceIn(0.42, 2.39) // 系统只接受 1:2.39 ~ 2.39:1
        return Rational((r * 1000).toInt(), 1000)
    }

    private fun action(c: Context, code: Int, icon: Int, title: String, a: String) = RemoteAction(
        Icon.createWithResource(c, icon), title, title,
        PendingIntent.getBroadcast(
            c, code, Intent(ACTION_PIP).setPackage(c.packageName).putExtra(EXTRA_ACT, a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    private fun params(c: Context): PictureInPictureParams {
        val actions = listOf(
            action(c, 1, android.R.drawable.ic_media_rew, "后退 10 秒", "back"),
            if (playing) action(c, 2, android.R.drawable.ic_media_pause, "暂停", "pause")
            else action(c, 3, android.R.drawable.ic_media_play, "播放", "play"),
            action(c, 4, android.R.drawable.ic_media_ff, "快进 10 秒", "fwd"),
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(ratio(armW, armH))
            .setActions(actions)
            // Android 12+：播放中按 Home / 手势返回桌面，系统直接进画中画（比 onUserLeaveHint 更稳，没有切换闪烁）
            .setAutoEnterEnabled(armed && SystemClock.uptimeMillis() >= suppressUntil)
            .setSeamlessResizeEnabled(false)
            .build()
    }

    private fun applyParams(a: Activity) {
        if (a.isDestroyed || a.isFinishing) return
        runCatching { a.setPictureInPictureParams(params(a)) }.onFailure { Diag.log("$TAG: setPictureInPictureParams $it") }
    }

    private fun enterPip(a: Activity): Boolean {
        if (!a.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        if (a.isInPictureInPictureMode) return true
        lastPipAt = SystemClock.uptimeMillis()
        return runCatching { a.enterPictureInPictureMode(params(a)) }.getOrDefault(false)
    }

    /** MainActivity.onUserLeaveHint：按 Home / 切任务。系统已替我们自动进画中画（Android 12+）时这里是兜底 */
    fun onUserLeaveHint(a: Activity) {
        if (!armed || pipOn || SystemClock.uptimeMillis() < suppressUntil) return
        if (!a.isInPictureInPictureMode) enterPip(a)
    }

    /** MainActivity.onPictureInPictureModeChanged */
    fun onPipModeChanged(a: Activity, inPip: Boolean) {
        pipOn = inPip
        if (inPip) {
            lastPipAt = SystemClock.uptimeMillis()
            if (receiver == null) {
                val r = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        val act = i.getStringExtra(EXTRA_ACT) ?: return
                        emit(JSONObject().put("ev", "act").put("a", act))
                    }
                }
                a.registerReceiver(r, IntentFilter(ACTION_PIP), Context.RECEIVER_NOT_EXPORTED)
                receiver = r
            }
        } else {
            receiver?.let { runCatching { a.unregisterReceiver(it) } }
            receiver = null
        }
        emit(JSONObject().put("ev", "pip").put("on", inPip))
    }

    /**
     * MainActivity.onStop 用：刚进画中画的 700ms 内如果收到 onStop，是个别系统的过渡行为，不能当成「退到后台」去暂停媒体；
     * 用户点小窗的 ✕ 关闭画中画时 onStop 来得更晚，照常暂停。
     */
    fun keepPlayingOnStop(): Boolean = pipOn && SystemClock.uptimeMillis() - lastPipAt < 700

    /** 即将拉起别的 Activity（外部播放器 / 系统投屏）：这不是「用户离开」，3 秒内不要自动进画中画 */
    private fun suppressPip(a: Activity) {
        suppressUntil = SystemClock.uptimeMillis() + 3000
        applyParams(a)
        main.postDelayed({ if (SystemClock.uptimeMillis() >= suppressUntil) applyParams(a) }, 3100)
    }

    // ------------------------------------------------------------------ 外部播放器

    private fun launchExternal(a: Activity, o: JSONObject): Boolean {
        val url = o.optString("url")
        if (!isHttp(url)) return false
        val mime = DlnaCast.mimeOf(url, o.optString("type"))
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime)
        i.putExtra("title", o.optString("title"))
        // MX Player / 多数国产播放器认的 headers 约定：[k1, v1, k2, v2 …]
        val hdr = ArrayList<String>()
        o.optString("ua").takeIf { it.isNotBlank() }?.let { hdr += "User-Agent"; hdr += it }
        o.optString("referer").takeIf { it.isNotBlank() }?.let { hdr += "Referer"; hdr += it }
        o.optString("cookie").takeIf { it.isNotBlank() }?.let { hdr += "Cookie"; hdr += it }
        if (hdr.isNotEmpty()) i.putExtra("headers", hdr.toTypedArray())
        o.optInt("pos", 0).takeIf { it > 0 }?.let { i.putExtra("position", it * 1000) }
        val chooser = Intent.createChooser(i, "选择播放器 / 投屏 App").putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(a, MainActivity::class.java)),
        )
        suppressPip(a)
        return runCatching { a.startActivity(chooser) }.isSuccess
    }

    private fun openSystemCast(a: Activity) {
        suppressPip(a)
        val ok = runCatching { a.startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) }.isSuccess ||
            runCatching { a.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }.isSuccess
        Toast.makeText(
            a, if (ok) "连上电视后回到浏览器全屏播放即可（屏幕镜像会把整个画面投出去）" else "这台设备没有系统投屏入口",
            Toast.LENGTH_LONG,
        ).show()
    }

    // ------------------------------------------------------------------ DLNA 投屏

    private class CastSession(val dev: DlnaDevice, val dlna: DlnaCast) {
        @Volatile var stop = false
    }

    @Suppress("DEPRECATION")
    private fun wifiNetwork(c: Context): Network? {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** SSDP 套接字和 HTTP 都绑到 Wi-Fi 网络，直连（不走应用里设置的代理 / 开着的 VPN） */
    private fun newDlna(c: Context): DlnaCast {
        val net = wifiNetwork(c.applicationContext)
        return DlnaCast(
            bind = { s -> if (net != null) runCatching { net.bindSocket(s) } },
            open = { u ->
                (if (net != null) net.openConnection(u, Proxy.NO_PROXY) else u.openConnection(Proxy.NO_PROXY)) as HttpURLConnection
            },
        )
    }

    private fun showCastPicker(a: Activity, req: JSONObject) {
        runCatching { picker?.dismiss() }
        val devices = ArrayList<DlnaDevice>()
        val labels = ArrayList<String>()
        var status = "正在搜索局域网设备…"
        val listAdapter = ArrayAdapter(a, android.R.layout.simple_list_item_1, labels)
        fun rebuild() {
            labels.clear()
            for (d in devices) labels.add("📺  " + d.name)
            if (devices.isEmpty()) labels.add(status)
            labels.add("🖥  屏幕镜像（系统投屏）")
            labels.add("▶  外部播放器 / 投屏 App")
            listAdapter.notifyDataSetChanged()
        }
        rebuild()
        val cancelled = AtomicBoolean(false)
        val lv = ListView(a).apply { adapter = listAdapter }
        val dlg = AlertDialog.Builder(a).setTitle("投屏到").setView(lv).setNegativeButton("取消", null).create()
        dlg.setOnDismissListener { cancelled.set(true); if (picker === dlg) picker = null }
        lv.setOnItemClickListener { _, _, pos, _ ->
            when {
                devices.isNotEmpty() && pos < devices.size -> { dlg.dismiss(); startCast(a, devices[pos], req) }
                devices.isEmpty() && pos == 0 -> Unit // 状态行
                else -> {
                    dlg.dismiss()
                    if (pos - maxOf(devices.size, 1) == 0) openSystemCast(a)
                    else if (!launchExternal(a, req)) emit(JSONObject().put("ev", "cast").put("state", "error").put("msg", "没有找到可用的播放器"))
                }
            }
        }
        picker = dlg
        dlg.show()

        io.execute {
            val wm = a.applicationContext.getSystemService(WifiManager::class.java)
            // 部分机型不持有组播锁会丢掉 SSDP 的应答
            val lock = runCatching { wm?.createMulticastLock("mafari-ssdp")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
            try {
                newDlna(a).discover(7000, { cancelled.get() }) { dev ->
                    main.post {
                        if (!cancelled.get() && devices.none { it.location == dev.location }) { devices.add(dev); rebuild() }
                    }
                }
            } catch (e: Exception) {
                Diag.log("$TAG: discover $e")
            } finally {
                runCatching { lock?.release() }
            }
            main.post {
                if (!cancelled.get() && devices.isEmpty()) { status = "没有发现设备，请确认手机和电视在同一 Wi-Fi"; rebuild() }
            }
        }
    }

    private fun startCast(a: Activity, dev: DlnaDevice, req: JSONObject) {
        cast?.let { stopCast(it, null) }
        val s = CastSession(dev, newDlna(a))
        cast = s
        emit(JSONObject().put("ev", "cast").put("state", "connecting").put("name", dev.name))
        val url = req.optString("url")
        val startAt = req.optInt("pos", 0)
        io.execute {
            try {
                s.dlna.setUriAndPlay(dev, url, req.optString("title"), req.optString("type"))
                val began = SystemClock.uptimeMillis()
                var misses = 0
                var sawPlaying = false
                var seeked = startAt <= 3
                while (!s.stop) {
                    Thread.sleep(1000)
                    if (s.stop) break
                    val q = runCatching { s.dlna.query(dev) }.getOrNull()
                    if (q == null) {
                        if (++misses >= 6) { stopCast(s, "与「${dev.name}」失去连接"); return@execute }
                        continue
                    }
                    misses = 0
                    val live = q.state == "PLAYING" || q.state == "TRANSITIONING" || q.state == "PAUSED_PLAYBACK"
                    if (q.state == "PLAYING" || q.state == "PAUSED_PLAYBACK") sawPlaying = true
                    if (sawPlaying && !seeked) { seeked = true; runCatching { s.dlna.seek(dev, startAt) } }
                    if (!live && sawPlaying) { stopCast(s, null); return@execute } // 播完了，或在电视上点了停止
                    if (!live && SystemClock.uptimeMillis() - began > 25_000) {
                        stopCast(s, "「${dev.name}」没有开始播放：可能不支持该格式，或该地址需要请求头（防盗链）")
                        return@execute
                    }
                    if (live) {
                        emit(
                            JSONObject().put("ev", "cast").put("state", "playing").put("name", dev.name)
                                .put("pos", q.rel).put("dur", q.dur).put("playing", q.state != "PAUSED_PLAYBACK"),
                        )
                    }
                }
            } catch (e: Exception) {
                Diag.log("$TAG: cast $e")
                stopCast(s, e.message ?: "投屏失败")
            }
        }
    }

    /** 结束一次投屏：停掉轮询、尽力让电视也停下，再通知页面。[error] 非空表示失败 */
    private fun stopCast(s: CastSession, error: String?) {
        if (s.stop) return
        s.stop = true
        if (cast === s) cast = null
        io.execute { runCatching { s.dlna.stop(s.dev) } }
        emit(
            JSONObject().put("ev", "cast").put("state", if (error != null) "error" else "stopped").also {
                if (error != null) it.put("msg", error)
            },
        )
    }
}
