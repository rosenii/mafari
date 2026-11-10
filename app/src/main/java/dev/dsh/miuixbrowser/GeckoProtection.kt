package dev.dsh.miuixbrowser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.mozilla.geckoview.ContentBlocking

/**
 * 「Gecko 高级保护」：把 Gecko 自带的隐私 / 安全能力集中到一处设置，并与应用原有的隐私开关合并生效。
 *
 * Gecko 的隐私设置只由本页决定，不再与 WebView 的「隐私增强保护」合并
 * （两个内核各用各的入口，设置页里按当前内核只显示其中一个）。
 *
 * 所有值落在 SharedPreferences，改动立即经 [GeckoPrefs] / ContentBlocking 写入 Gecko；
 * Gecko 还没启动时只记录，运行时创建后由 [pushAll] 一次性写入。
 */
object GeckoProtection {

    /** ETP 档位：0 默认（标准）/ 1 关闭 / 2 标准 / 3 严格 */
    var etp by mutableStateOf(0)
        private set

    var fingerprint by mutableStateOf(false)
        private set

    /** 强力抗指纹（resistFingerprinting）：时区固定 UTC、窗口尺寸取整，部分网页会异常 */
    var resistFingerprint by mutableStateOf(false)
        private set

    var gpc by mutableStateOf(false)
        private set

    /** 防 WebRTC 泄露真实 IP：只用默认出口地址，不暴露内网 / 多网卡地址 */
    var webrtcLeak by mutableStateOf(false)
        private set

    /** 彻底禁用 WebRTC（原先在 WebView 隐私设置里共用，现在 Gecko 自己管） */
    var webrtcOff by mutableStateOf(false)
        private set

    var trimReferrer by mutableStateOf(false)
        private set

    var stripTracking by mutableStateOf(false)
        private set

    var bounceTracking by mutableStateOf(false)
        private set

    var purgeTrackers by mutableStateOf(false)
        private set

    /** 严格第一方隔离：每个站点独立的 Cookie / 缓存，很多「用 Google 登录」会失效 */
    var firstPartyIsolate by mutableStateOf(false)
        private set

    var httpsOnly by mutableStateOf(false)
        private set

    var noPrefetch by mutableStateOf(false)
        private set

    var disableWebgl by mutableStateOf(false)
        private set

    /** 安全 DNS：0 关闭（跟随系统）/ 1 优先（失败回退系统）/ 2 仅安全 DNS */
    var dohMode by mutableStateOf(0)
        private set

    /** 服务商下标，见 [DOH_PROVIDERS]；最后一项是自定义 */
    var dohProvider by mutableStateOf(0)
        private set

    var dohCustom by mutableStateOf("")
        private set

    /** 加密 Client Hello（ECH）：藏起访问的域名；需要安全 DNS 才有效 */
    var ech by mutableStateOf(false)
        private set

        private set

    data class DohProvider(val name: String, val url: String)

    val DOH_PROVIDERS = listOf(
        DohProvider("Cloudflare", "https://mozilla.cloudflare-dns.com/dns-query"),
        DohProvider("阿里 DNS", "https://dns.alidns.com/dns-query"),
        DohProvider("腾讯 DNSPod", "https://doh.pub/dns-query"),
        DohProvider("Google", "https://dns.google/dns-query"),
        DohProvider("Quad9", "https://dns.quad9.net/dns-query"),
        DohProvider("自定义", ""),
    )

    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("mafari_gecko_protect", Context.MODE_PRIVATE)
        prefs = p
        etp = p.getInt("etp", 0).coerceIn(0, 3)
        fingerprint = p.getBoolean("fpp", false)
        resistFingerprint = p.getBoolean("rfp", false)
        gpc = p.getBoolean("gpc", false)
        webrtcLeak = p.getBoolean("webrtc_leak", false)
        webrtcOff = p.getBoolean("webrtc_off", false)
        trimReferrer = p.getBoolean("referrer", false)
        stripTracking = p.getBoolean("strip", false)
        bounceTracking = p.getBoolean("bounce", false)
        purgeTrackers = p.getBoolean("purge", false)
        firstPartyIsolate = p.getBoolean("fpi", false)
        httpsOnly = p.getBoolean("https_only", false)
        noPrefetch = p.getBoolean("no_prefetch", false)
        disableWebgl = p.getBoolean("no_webgl", false)
        dohMode = p.getInt("doh_mode", 0).coerceIn(0, 2)
        dohProvider = p.getInt("doh_provider", 0).coerceIn(0, DOH_PROVIDERS.lastIndex)
        dohCustom = p.getString("doh_custom", "").orEmpty()
        ech = p.getBoolean("ech", false)
    }

    // ------------------------------------------------------------------ 修改（立即保存并写入 Gecko）

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs?.edit()?.apply(block)?.apply()
        pushAll()
    }

    fun updateEtp(v: Int) { etp = v.coerceIn(0, 3); edit { putInt("etp", etp) } }
    fun updateFingerprint(v: Boolean) { fingerprint = v; edit { putBoolean("fpp", v) } }
    fun updateResistFingerprint(v: Boolean) { resistFingerprint = v; edit { putBoolean("rfp", v) } }
    fun updateGpc(v: Boolean) { gpc = v; edit { putBoolean("gpc", v) } }
    fun updateWebrtcLeak(v: Boolean) { webrtcLeak = v; edit { putBoolean("webrtc_leak", v) } }
    fun updateWebrtcOff(v: Boolean) { webrtcOff = v; edit { putBoolean("webrtc_off", v) } }
    fun updateTrimReferrer(v: Boolean) { trimReferrer = v; edit { putBoolean("referrer", v) } }
    fun updateStripTracking(v: Boolean) { stripTracking = v; edit { putBoolean("strip", v) } }
    fun updateBounceTracking(v: Boolean) { bounceTracking = v; edit { putBoolean("bounce", v) } }
    fun updatePurgeTrackers(v: Boolean) { purgeTrackers = v; edit { putBoolean("purge", v) } }
    fun updateFirstPartyIsolate(v: Boolean) { firstPartyIsolate = v; edit { putBoolean("fpi", v) } }
    fun updateHttpsOnly(v: Boolean) { httpsOnly = v; edit { putBoolean("https_only", v) } }
    fun updateNoPrefetch(v: Boolean) { noPrefetch = v; edit { putBoolean("no_prefetch", v) } }
    fun updateDisableWebgl(v: Boolean) { disableWebgl = v; edit { putBoolean("no_webgl", v) } }
    fun updateDohMode(v: Int) { dohMode = v.coerceIn(0, 2); edit { putInt("doh_mode", dohMode) } }
    fun updateDohProvider(v: Int) { dohProvider = v.coerceIn(0, DOH_PROVIDERS.lastIndex); edit { putInt("doh_provider", dohProvider) } }
    fun updateDohCustom(v: String) { dohCustom = v.trim(); edit { putString("doh_custom", dohCustom) } }
    fun updateEch(v: Boolean) { ech = v; edit { putBoolean("ech", v) } }

    /** 全部恢复默认 */
    fun reset() {
        etp = 0; fingerprint = false; resistFingerprint = false; gpc = false; webrtcLeak = false; webrtcOff = false
        trimReferrer = false; stripTracking = false; bounceTracking = false; purgeTrackers = false
        firstPartyIsolate = false; httpsOnly = false; noPrefetch = false; disableWebgl = false
        dohMode = 0; dohProvider = 0; dohCustom = ""; ech = false
        prefs?.edit()?.clear()?.apply()
        pushAll()
    }

    /** 已开启的保护项数量（设置页行的摘要用） */
    fun activeCount(): Int = listOf(
        etp >= 2, fingerprint, resistFingerprint, gpc, webrtcLeak, webrtcOff, trimReferrer, stripTracking,
        bounceTracking, purgeTrackers, firstPartyIsolate, httpsOnly, noPrefetch, disableWebgl,
        dohMode > 0, ech,
    ).count { it }

    /** 当前生效的 DoH 地址；自定义为空时返回 null */
    private fun dohUrl(): String? =
        if (dohProvider == DOH_PROVIDERS.lastIndex) dohCustom.takeIf { it.startsWith("https://") }
        else DOH_PROVIDERS[dohProvider].url

    /** 把所有设置写入 Gecko（Gecko 未启动时偏好只记录，ETP 档位等运行时创建后再次调用即可） */
    fun pushAll() {
        // 增强型跟踪保护
        GeckoRuntimeHolder.peek()?.let { rt ->
            val flags = when (etp) {
                1 -> ContentBlocking.AntiTracking.NONE
                2 -> ContentBlocking.AntiTracking.DEFAULT
                3 -> ContentBlocking.AntiTracking.STRICT
                else -> ContentBlocking.AntiTracking.DEFAULT
            }
            runCatching { rt.settings.contentBlocking.setAntiTracking(flags) }
        }

        // 指纹
        val fpp = fingerprint
        GeckoPrefs.set("privacy.fingerprintingProtection", fpp)
        GeckoPrefs.set("privacy.trackingprotection.fingerprinting.enabled", fpp || etp == 3)
        GeckoPrefs.set("privacy.resistFingerprinting", resistFingerprint)

        // DNT / GPC
        val sendGpc = gpc
        GeckoPrefs.set("privacy.donottrackheader.enabled", sendGpc)
        GeckoPrefs.set("privacy.globalprivacycontrol.enabled", sendGpc)
        GeckoPrefs.set("privacy.globalprivacycontrol.functionality.enabled", true)

        // WebRTC：「禁用」= 整个关掉；「防泄露」= 只走默认出口
        GeckoPrefs.set("media.peerconnection.enabled", !webrtcOff)
        GeckoPrefs.set("media.peerconnection.ice.default_address_only", webrtcLeak)
        GeckoPrefs.set("media.peerconnection.ice.no_host", webrtcLeak)

        // 跨站请求只带来源（origin），不带完整路径
        GeckoPrefs.set("network.http.referer.XOriginTrimmingPolicy", if (trimReferrer) 2 else 0)

        // 清理 URL 里的跟踪参数（utm_*、fbclid…）、弹跳跟踪、跟踪者数据定期清理
        GeckoPrefs.set("privacy.query_stripping.enabled", stripTracking)
        GeckoPrefs.set("privacy.query_stripping.enabled.pbmode", stripTracking)
        GeckoPrefs.set("privacy.bounceTrackingProtection.mode", if (bounceTracking) 1 else 2)
        GeckoPrefs.set("privacy.purge_trackers.enabled", purgeTrackers || etp >= 2)

        GeckoPrefs.set("privacy.firstparty.isolate", firstPartyIsolate)

        // 仅 HTTPS
        GeckoPrefs.set("dom.security.https_only_mode", httpsOnly)
        GeckoPrefs.set("dom.security.https_only_mode_pbm", httpsOnly)

        // 预取 / 预连接 / 预测
        GeckoPrefs.set("network.prefetch-next", !noPrefetch)
        GeckoPrefs.set("network.dns.disablePrefetch", noPrefetch)
        GeckoPrefs.set("network.predictor.enabled", !noPrefetch)
        GeckoPrefs.set("network.http.speculative-parallel-limit", if (noPrefetch) 0 else 6)

        GeckoPrefs.set("webgl.disabled", disableWebgl)

        // 安全 DNS（DoH）：trr.mode 0 = 默认（关）/ 2 = 优先 / 3 = 仅 DoH
        val url = dohUrl()
        val mode = if (dohMode > 0 && url != null) if (dohMode == 1) 2 else 3 else 0
        if (url != null) {
            GeckoPrefs.set("network.trr.uri", url)
            GeckoPrefs.set("network.trr.custom_uri", url)
        }
        GeckoPrefs.set("network.trr.mode", mode)
        GeckoPrefs.set("network.trr.confirmationNS", "skip")
        // ECH 依赖加密 DNS 拿到 ECHConfig
        GeckoPrefs.set("network.dns.echconfig.enabled", ech)
        GeckoPrefs.set("network.dns.http3_echconfig.enabled", ech)
    }
}
