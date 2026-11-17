package dev.dsh.miuixbrowser.script

import dev.dsh.miuixbrowser.i18n.tr

import android.app.Activity
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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Rational
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.dsh.miuixbrowser.MainActivity
import dev.dsh.miuixbrowser.util.Diag
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「投屏到」面板的界面状态：原生侧（[MediaHost]）写，BrowserScreen 里的 CastPickerDialog 读并用 miuix 材质抽象层绘制。
 * 只能在主线程修改。
 */
class CastPickerUi {
    var show by mutableStateOf(false)
    var status by mutableStateOf("")
    /** 正在搜索设备：刷新按钮转圈 */
    var scanning by mutableStateOf(false)
    val devices = mutableStateListOf<DlnaDevice>()
    internal var onDevice: (DlnaDevice) -> Unit = {}
    internal var onMirror: () -> Unit = {}
    internal var onExternal: () -> Unit = {}
    internal var onCancel: () -> Unit = {}
    internal var onRefresh: () -> Unit = {}

    fun refresh() = onRefresh()
    fun pickDevice(d: DlnaDevice) = onDevice(d)
    fun pickMirror() = onMirror()
    fun pickExternal() = onExternal()
    fun cancel() = onCancel()
}

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
    private var pickerCancel: AtomicBoolean? = null

    /** 投屏选择面板的界面状态（由 BrowserScreen 里的 CastPickerDialog 绘制） */
    val pickerUi = CastPickerUi()

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
                        closePicker()
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
            action(c, 1, android.R.drawable.ic_media_rew, tr("后退 10 秒"), "back"),
            if (playing) action(c, 2, android.R.drawable.ic_media_pause, tr("暂停"), "pause")
            else action(c, 3, android.R.drawable.ic_media_play, tr("播放"), "play"),
            action(c, 4, android.R.drawable.ic_media_ff, tr("快进 10 秒"), "fwd"),
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
        val chooser = Intent.createChooser(i, tr("选择播放器 / 投屏 App")).putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(a, MainActivity::class.java)),
        )
        suppressPip(a)
        return runCatching { a.startActivity(chooser) }.isSuccess
    }

    /**
     * 屏幕镜像入口：国内 ROM 优先走各家自带的「无线显示 / 投屏」页（小米 / 华为荣耀 / OPPO·一加·realme / vivo / 三星），
     * 一个都起不来才退回 AOSP 的 ACTION_CAST_SETTINGS（那个在国内 ROM 上往往是 Google Cast 页）。
     * 组件名在不同机型 / 系统版本上并不稳定，所以按「厂商候选 → 通用候选 → 系统兜底」逐个尝试，起不来（不存在 / 未导出）就换下一个。
     */
    private fun openSystemCast(a: Activity) {
        suppressPip(a)
        fun comp(pkg: String, cls: String) = Intent().setClassName(pkg, cls)
        val vendorSpecific = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco", "blackshark" -> listOf(
                comp("com.milink.service", "com.miui.circulate.world.CirculateActivity"),
                comp("com.xiaomi.mirror", "com.xiaomi.mirror.MirrorActivity"),
                Intent("miui.intent.action.WIRELESS_DISPLAY"),
            )
            "huawei", "honor" -> listOf(
                comp("com.huawei.android.wfdft", "com.huawei.android.wfdft.WifiDisplayActivity"),
                Intent("com.huawei.android.wfd.action.WIFI_DISPLAY"),
            )
            "oppo", "oneplus", "realme" -> listOf(
                comp("com.oplus.wirelesssettings", "com.oplus.wirelesssettings.wifi.WifiDisplaySettingsActivity"),
                comp("com.coloros.wirelesssettings", "com.coloros.wirelesssettings.wifidisplay.WifiDisplaySettingsActivity"),
            )
            "vivo", "iqoo" -> listOf(
                comp("com.android.settings", "com.vivo.settings.wifidisplay.WifiDisplaySettingsActivity"),
            )
            "samsung" -> listOf(
                Intent("com.samsung.android.smartmirroring.action.SMART_VIEW"),
                comp("com.samsung.android.smartmirroring", "com.samsung.android.smartmirroring.SmartMirroringActivity"),
            )
            else -> emptyList()
        }
        val generic = listOf(
            comp("com.android.settings", "com.android.settings.Settings\$WifiDisplaySettingsActivity"),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            Intent("android.settings.CAST_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        val ok = (vendorSpecific + generic).any { i ->
            runCatching { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Diag.log("$TAG: cast settings ${i.component ?: i.action} $it") }
                .isSuccess
        }
        Toast.makeText(
            a, if (ok) tr("连上电视后回到浏览器全屏播放即可（屏幕镜像会把整个画面投出去）") else tr("这台设备没有系统投屏入口"),
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

    /** 当前这一轮搜索的取消标记：手动刷新时作废上一轮 */
    private var scanCancel: AtomicBoolean? = null

    private fun closePicker() {
        pickerCancel?.set(true)
        pickerCancel = null
        scanCancel?.set(true)
        scanCancel = null
        pickerUi.scanning = false
        pickerUi.show = false
    }

    private fun showCastPicker(a: MainActivity, req: JSONObject) {
        closePicker()
        val ui = pickerUi
        val cancelled = AtomicBoolean(false)
        pickerCancel = cancelled
        ui.devices.clear()
        ui.status = tr("正在搜索局域网设备…")
        ui.onDevice = { d -> closePicker(); startCast(a, d, req) }
        ui.onMirror = { closePicker(); openSystemCast(a) }
        ui.onExternal = {
            closePicker()
            if (!launchExternal(a, req)) {
                emit(JSONObject().put("ev", "cast").put("state", "error").put("msg", tr("没有找到可用的播放器")))
            }
        }
        ui.onCancel = { closePicker() }
        ui.onRefresh = {
            if (!cancelled.get()) {
                ui.devices.clear()
                ui.status = tr("正在搜索局域网设备…")
                discoverDevices(a, ui, cancelled)
            }
        }
        ui.show = true

        // Android 17 起访问局域网要先有「附近设备」权限：没授权先弹系统授权框，授权后再开始搜索
        a.withLocalNetworkPermission { granted ->
            if (cancelled.get()) return@withLocalNetworkPermission
            if (granted) {
                discoverDevices(a, ui, cancelled)
            } else {
                ui.status = tr("没有「附近设备」权限，无法搜索局域网设备。请在 系统设置 → 应用 → Mafari → 权限 里允许后重试")
            }
        }
    }

    private fun discoverDevices(a: Activity, ui: CastPickerUi, picker: AtomicBoolean) {
        scanCancel?.set(true)
        val cancelled = AtomicBoolean(false).also { scanCancel = it }
        ui.scanning = true
        io.execute {
            val wm = a.applicationContext.getSystemService(WifiManager::class.java)
            // 部分机型不持有组播锁会丢掉 SSDP 的应答
            val lock = runCatching { wm?.createMulticastLock("mafari-ssdp")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
            try {
                newDlna(a).discover(7000, { cancelled.get() || picker.get() }) { dev ->
                    main.post {
                        if (!cancelled.get() && !picker.get() && ui.devices.none { it.location == dev.location }) ui.devices.add(dev)
                    }
                }
            } catch (e: Exception) {
                Diag.log("$TAG: discover $e")
            } finally {
                runCatching { lock?.release() }
            }
            main.post {
                if (cancelled.get() || picker.get()) return@post
                ui.scanning = false
                if (ui.devices.isEmpty()) ui.status = tr("没有发现设备，请确认手机和电视在同一 Wi-Fi")
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
                        if (++misses >= 6) { stopCast(s, tr("与「{0}」失去连接", dev.name)); return@execute }
                        continue
                    }
                    misses = 0
                    val live = q.state == "PLAYING" || q.state == "TRANSITIONING" || q.state == "PAUSED_PLAYBACK"
                    if (q.state == "PLAYING" || q.state == "PAUSED_PLAYBACK") sawPlaying = true
                    if (sawPlaying && !seeked) { seeked = true; runCatching { s.dlna.seek(dev, startAt) } }
                    if (!live && sawPlaying) { stopCast(s, null); return@execute } // 播完了，或在电视上点了停止
                    if (!live && SystemClock.uptimeMillis() - began > 25_000) {
                        stopCast(s, tr("「{0}」没有开始播放：可能不支持该格式，或该地址需要请求头（防盗链）", dev.name))
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
                stopCast(s, e.message ?: tr("投屏失败"))
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
