/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.engine.webview

import dev.dsh.miuixbrowser.adblock.AdBlock
import dev.dsh.miuixbrowser.adblock.AdMatch
import dev.dsh.miuixbrowser.util.Diag
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.IDN

/**
 * 一个 web origin：scheme://host[:port]，与 Chromium 的序列化形式一致
 * （host 小写 punycode，默认端口省略，IPv6 带方括号），可直接作为 addDocumentStartJavaScript 的 origin 规则，
 * 也可与页面里的 self.origin 逐字比较。
 */
class WebOrigin private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    /** 规范化 origin，同时也是注入规则（不含通配符、不含路径）。 */
    val origin: String =
        if (port == -1) "$scheme://$host" else "$scheme://$host:$port"

    override fun equals(other: Any?) = other is WebOrigin && other.origin == origin
    override fun hashCode() = origin.hashCode()
    override fun toString() = origin

    companion object {
        /** 只接受 http / https；解析失败、无主机名、带通配符的一律返回 null。 */
        fun parse(url: String?): WebOrigin? {
            if (url.isNullOrBlank()) return null
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            var host = uri.host?.lowercase()?.trimEnd('.') ?: return null
            if (host.isEmpty() || '*' in host) return null
            host = if (':' in host) {
                if (host.startsWith("[")) host else "[$host]"
            } else {
                runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) }.getOrNull()?.lowercase() ?: return null
            }
            var port = uri.port
            if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) port = -1
            if (port != -1 && port !in 1..65535) return null
            return WebOrigin(scheme, host, port)
        }
    }
}

/**
 * 指纹保护的注入器：每个 WebView 一个实例，只在主线程使用。
 *
 * 主架构：origin-scoped document-start 注入 + 内联配置。
 * - 原生侧为每个「站点」（scheme + 可注册域名）生成一份只含该站点配置的脚本，用
 *   addDocumentStartJavaScript(view, script, 规则集合) 注册；来源匹配由 WebView 完成，
 *   脚本内部还会用同一套规则再校验一次 self.origin 和祖先链。
 * - 配置不放进任何页面可读的位置：没有 JS 桥、没有全局变量，不同站点的配置也从不合并进同一份脚本。
 * - 「允许注入的 origin」（本类里的 [handlers]）与「保护豁免列表」（[AdBlock.fpExemptHosts]）是两个概念：
 *   前者只表示「已经为它注册了脚本」，后者命中时一定不注册。
 *
 * 缺口与收窄手段（详见 docs/FINGERPRINT.md §3 / §9）：
 * - 注入只作用于「注册完成之后才开始加载」的文档，所以必须在导航开始前调用 [prepare]。
 * - **重定向**：① [prepare] 时同站点兄弟来源（裸域 / www / m / login 等子域）一并注册；
 *   ② 预注册清单里的重定向落点（OIDC IdP 等）随站点一并注册；③ shouldOverrideUrlLoading 里补注册（尽力而为）；
 *   ④ [onPageStarted] / [onCommitted] 检测「落在未注册 origin」并按 [UnprotectedPolicy] 兜底。④ 不是注入时机，
 *   它只能保证不在无保护的情况下继续停留，不能让那一次加载本身获得保护。
 * - **跨源 iframe**：预注册清单（[FpPreregistry]）里的 frame 来源在首次导航前批量注册（pinned，不被 LRU 驱逐），
 *   并把清单里的嵌入方作为「允许的跨源祖先」内联进脚本。清单之外的跨源 iframe 不保护（fail-open，有意如此）。
 *
 * 降级路径：[docStartSupported] 为 false 时，不注册脚本，改由 [FpFallbackBridge]（无参 fp()）提供
 * 原生维护的顶层 origin 的配置，只保护主框架 / 同源 iframe，安全边界弱于主路径；预注册与落地检测都不适用。
 */
class FingerprintInjector(private val webView: WebView) {

    /** 落在未预注册 origin 时的兜底策略，数值与 [AdBlock.fpUnprotectedPolicy] 一致。 */
    enum class UnprotectedPolicy(val id: Int) {
        /** 只记录并补注册（对之后的加载生效），页面照常加载。 */
        OBSERVE(AdBlock.FP_UNPROTECTED_OBSERVE),

        /** 停止当前加载，补注册后在保护下重新加载一次（带一次性令牌的回调 URL 除外，见 [looksSingleUse]）。 */
        RELOAD(AdBlock.FP_UNPROTECTED_RELOAD),

        /** 停止加载并交给 [onUnprotectedBlocked]（由界面提示）。 */
        STOP(AdBlock.FP_UNPROTECTED_STOP);

        companion object {
            fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: OBSERVE
        }
    }

    /** 必须用 isFeatureSupported 运行时判断，不能只看 Android / Chromium 版本。 */
    val docStartSupported: Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }
            .getOrDefault(false)

    /** [UnprotectedPolicy.RELOAD] 重新加载时用的入口；默认直接 loadUrl，BrowserTab 会换成带请求头的版本。 */
    var loader: (String) -> Unit = { webView.loadUrl(it) }

    /** [UnprotectedPolicy.STOP] 触发后的界面提示入口。 */
    var onUnprotectedBlocked: ((String) -> Unit)? = null

    /** 一次注册：句柄 + 它覆盖的规则（规范文本，同时也是传给 WebView 的规则集合）+ 内联的嵌入方。 */
    private class Registered(
        val handler: ScriptHandler,
        val rules: Set<String>,
        val parsed: List<OriginRule>,
        val embedders: Set<String>,
    )

    /** 站点键（scheme://可注册域名）→ 注册；访问顺序 LRU，超出上限时撤销最久未用且未固定的。 */
    private val handlers = LinkedHashMap<String, Registered>(16, 0.75f, true)

    /** 预注册清单里的 frame 站点：固定注册，不参与 LRU 驱逐。 */
    private val pinned = HashSet<String>()
    private var pinnedReady = false

    private var generation = AdBlock.fingerprintGeneration

    /** 已经按 [UnprotectedPolicy.RELOAD] 重载过的 URL，防止循环。 */
    private val reloaded = HashSet<String>()

    /** 原生维护的最近一次顶层导航地址，仅供降级路径的 [fallbackConfig] 使用。 */
    @Volatile
    private var topUrl: String = ""

    /**
     * 在导航开始之前调用（主线程）。url 为即将加载的主框架地址。
     * 保护关闭 / 命中豁免 / 不是 http(s) 时什么都不注册。
     */
    fun prepare(url: String?) {
        if (!url.isNullOrBlank() && !url.startsWith("about:", ignoreCase = true)) topUrl = url
        if (!docStartSupported) return
        val origin = WebOrigin.parse(url) ?: return
        syncGeneration()
        ensurePreregistered()
        if (AdBlock.fingerprintConfigFor(origin) == null) return
        val data = FpPreregistry.data()
        val site = AdMatch.registrable(origin.host)

        // ① 重定向落点：导航到该站点时，清单里登记的落点（IdP 等）一并注册。先于本站点注册，使本站点在 LRU 里更新。
        for (r in data.redirects) {
            if (r.site != site) continue
            for (t in r.targets) registerRule(t, pin = false)
        }

        // ② 本 origin 自己 + 同站点兄弟（裸域 / www / m / login …）。
        val rules = LinkedHashSet<String>()
        rules.add(origin.origin)
        siblingRules(origin).let { rules.addAll(it) }
        register(origin.scheme, site, rules, embeddersFor(origin.scheme, site, data), pin = false)
    }

    /**
     * 前进 / 后退不会经过 shouldOverrideUrlLoading，需要先为目标历史项注册。
     * offset：-1 后退，+1 前进。
     */
    fun prepareHistory(offset: Int) {
        runCatching {
            val list = webView.copyBackForwardList()
            val index = list.currentIndex + offset
            if (index in 0 until list.size) prepare(list.getItemAtIndex(index)?.url)
        }
    }

    /**
     * 浏览器已为 [url] 开始加载（onPageStarted）。主路径：先检测落地是否已被覆盖，再补注册（对之后的加载生效）；
     * 降级路径：补跑脚本。
     */
    fun onPageStarted(pageUrl: String?) {
        if (!pageUrl.isNullOrBlank() && !pageUrl.startsWith("about:", ignoreCase = true)) topUrl = pageUrl
        if (docStartSupported) {
            checkLanding(pageUrl, "start")
            prepare(pageUrl)
            return
        }
        val origin = WebOrigin.parse(pageUrl) ?: return
        if (AdBlock.fingerprintConfigFor(origin) == null) return
        val js = AdBlock.fingerprintJs()
        if (js.isNotEmpty()) webView.evaluateJavascript(js, null)
    }

    /**
     * 主框架已提交新地址（doUpdateVisitedHistory）。服务端重定向的最终落点只有在这里才看得到，
     * 因为 onPageStarted 给的是重定向之前的地址。只做检测，不做注册之外的事。
     */
    fun onCommitted(finalUrl: String?) {
        if (!docStartSupported) return
        checkLanding(finalUrl, "commit")
    }

    /**
     * 兜底检测：主框架落在一个「本应保护、却没有注册覆盖」的 http(s) origin 上。
     * 这意味着本次加载没有拿到 document-start 脚本；这里只能止损，不能补救这一次的注入。
     * 注意要在 [prepare] 之前调用，否则补注册会让检测永远通过。
     */
    private fun checkLanding(url: String?, stage: String) {
        val origin = WebOrigin.parse(url) ?: return
        // 设置 / 豁免变化后旧注册作废：这次落地是旧配置下加载的，不算「漏网」，只同步代数
        if (AdBlock.fingerprintGeneration != generation) {
            syncGeneration()
            return
        }
        if (AdBlock.fingerprintConfigFor(origin) == null) return // 保护关闭或被豁免，本来就不需要
        if (isCovered(origin)) return
        val target = url ?: return
        Diag.log("fingerprint: unprotected landing ($stage) ${origin.origin}")
        // 无论策略如何都先补注册，之后对该 origin 的加载就有保护了
        prepare(target)
        when (UnprotectedPolicy.fromId(AdBlock.fpUnprotectedPolicy)) {
            UnprotectedPolicy.OBSERVE -> Unit
            UnprotectedPolicy.RELOAD -> {
                if (looksSingleUse(target) || !markReloaded(target)) return
                webView.stopLoading()
                webView.post { loader(target) }
            }
            UnprotectedPolicy.STOP -> {
                webView.stopLoading()
                onUnprotectedBlocked?.invoke(target)
            }
        }
    }

    private fun markReloaded(url: String): Boolean {
        if (reloaded.size > 32) reloaded.clear()
        return reloaded.add(url)
    }

    private fun isCovered(origin: WebOrigin): Boolean =
        handlers.values.any { reg -> reg.parsed.any { it.matches(origin) } }

    /** 设置或豁免列表变化后，由 [prepare] 懒检测并撤销全部旧注册（旧脚本里内联着旧配置）。 */
    private fun syncGeneration() {
        val g = AdBlock.fingerprintGeneration
        if (g == generation) return
        generation = g
        clear()
    }

    fun clear() {
        handlers.values.forEach { runCatching { it.handler.remove() } }
        handlers.clear()
        pinned.clear()
        pinnedReady = false
        reloaded.clear()
    }

    /**
     * 预注册清单里的跨源 frame：在 WebView 初始化后、首次导航前批量注册（由第一次 [prepare] 触发，
     * 也可由持有者在 loadUrl 之前显式调用）。固定注册，不被 LRU 驱逐；设置变化后随 [clear] 失效并重新注册。
     */
    fun ensurePreregistered() {
        if (!docStartSupported) return
        syncGeneration() // 先同步代数：clear() 会把 pinnedReady 复位
        if (pinnedReady) return
        pinnedReady = true
        val byKey = LinkedHashMap<String, Triple<String, MutableSet<String>, MutableSet<String>>>()
        for (e in FpPreregistry.data().embeds) {
            for (f in e.frames) {
                if (!ruleAllowed(f)) continue
                val key = "${f.scheme}://${f.site}"
                val entry = byKey.getOrPut(key) { Triple(f.scheme, LinkedHashSet<String>(), LinkedHashSet<String>()) }
                entry.second.add(f.text)
                e.embedders.forEach { entry.third.add(it.text) }
            }
        }
        var n = 0
        for ((key, v) in byKey) {
            if (n++ >= MAX_PINNED) {
                Diag.log("fingerprint: prereg frame limit $MAX_PINNED reached, rest skipped")
                break
            }
            register(v.first, key.substringAfter("://"), v.second, v.third, pin = true)
        }
    }

    /** 当前站点 key 对应的嵌入方：只内联与「这个站点作为被嵌入方」有关的条目，不泄露清单里别的站点。 */
    private fun embeddersFor(scheme: String, site: String, data: FpPreregistry.Data): Set<String> {
        val out = LinkedHashSet<String>()
        for (e in data.embeds) {
            if (e.frames.any { it.scheme == scheme && it.site == site }) e.embedders.forEach { out.add(it.text) }
        }
        return out
    }

    private fun registerRule(rule: OriginRule, pin: Boolean) {
        if (!ruleAllowed(rule)) return
        register(rule.scheme, rule.site, setOf(rule.text), emptySet(), pin)
    }

    /** 规则不得覆盖到豁免站点：通配规则要求该站点之下没有任何豁免条目。 */
    private fun ruleAllowed(rule: OriginRule): Boolean {
        if (AdBlock.isFingerprintExempt(rule.host)) return false
        if (rule.wildcard && AdBlock.fpExemptUnder(rule.site)) return false
        return true
    }

    /**
     * 同站点兄弟：对默认端口的普通域名，把裸域和 `*.站点` 一起纳入同一份注册（同一份脚本、同一个 seed），
     * 覆盖 www ↔ m ↔ login 这类站内重定向。IP / 带端口 / 公共后缀 / 站点下有豁免条目时不扩展。
     * 注意：通配只匹配子域名，所以裸域要单独一条。
     */
    private fun siblingRules(origin: WebOrigin): Set<String> {
        val h = origin.host
        if (origin.port != -1 || h.startsWith("[") || h.all { it.isDigit() || it == '.' }) return emptySet()
        val site = AdMatch.registrable(h)
        if (!AdMatch.isRegistrableOrDeeper(site)) return emptySet()
        if (AdBlock.isFingerprintExempt(site) || AdBlock.fpExemptUnder(site)) return emptySet()
        return linkedSetOf("${origin.scheme}://$site", "${origin.scheme}://*.$site")
    }

    /**
     * 登记（或增量更新）一个站点的注册：规则 / 嵌入方与已有注册取并集，没有变化就只刷新 LRU；
     * 有变化时先注册新脚本再撤销旧的，期间不会出现无脚本的空窗。
     */
    private fun register(
        scheme: String,
        site: String,
        addRules: Set<String>,
        addEmbedders: Set<String>,
        pin: Boolean,
    ): Boolean {
        val key = "$scheme://$site"
        val existing = handlers[key] // get() 同时刷新 LRU 顺序
        val rules = LinkedHashSet<String>()
        val embedders = LinkedHashSet<String>()
        existing?.let { rules.addAll(it.rules); embedders.addAll(it.embedders) }
        rules.addAll(addRules)
        embedders.addAll(addEmbedders)
        if (existing != null && existing.rules == rules && existing.embedders == embedders) {
            if (pin) pinned.add(key)
            return true
        }
        if (rules.size > MAX_RULES_PER_SITE || embedders.size > MAX_EMBEDDERS_PER_SITE) {
            Diag.log("fingerprint: rule limit exceeded for $key")
            return existing != null
        }
        val config = AdBlock.fingerprintSiteConfig(site) ?: return false
        val script = AdBlock.fingerprintScriptFor(rules, embedders, config)
        if (script.isEmpty()) return false
        val handler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, script, rules)
        }.onFailure { Diag.log("fingerprint: register $key failed ${it.message}") }.getOrNull() ?: return false
        existing?.let { runCatching { it.handler.remove() } }
        handlers[key] = Registered(handler, rules, rules.mapNotNull { OriginRule.parse(it) }, embedders)
        if (pin) pinned.add(key)
        evict()
        return true
    }

    private fun evict() {
        while (handlers.size - pinned.count { it in handlers } > MAX_ORIGINS) {
            val eldest = handlers.entries.firstOrNull { it.key !in pinned } ?: return
            handlers.remove(eldest.key)
            runCatching { eldest.value.handler.remove() }
        }
    }

    /**
     * 降级路径专用，任意线程。返回 "掩码|种子|origin"，没有配置返回空串。
     * 只给出「当前顶层 origin」自己的配置——页面最多读到自己的配置，读不到别的站点的。
     */
    fun fallbackConfig(): String {
        if (docStartSupported) return ""
        val origin = WebOrigin.parse(topUrl) ?: return ""
        val config = AdBlock.fingerprintConfigFor(origin) ?: return ""
        return "${config.mask}|${config.seed}|${origin.origin}"
    }

    companion object {
        /** 每个 WebView 同时保留的（非固定）站点注册数；每份脚本约 25 KB，上限也限制了内存占用。 */
        const val MAX_ORIGINS = 16

        /** 预注册清单里固定注册的 frame 站点上限。 */
        const val MAX_PINNED = 24

        private const val MAX_RULES_PER_SITE = 8
        private const val MAX_EMBEDDERS_PER_SITE = 16

        private val SINGLE_USE_PARAMS = Regex(
            "(?i)[?&#](code|state|token|id_token|access_token|ticket|saml(response|request)?|samlart|nonce|" +
                "assertion|ssotoken|oauth_token|oauth_verifier)=",
        )

        /**
         * 带一次性凭证的回调地址（OIDC / OAuth / SAML / CAS 登录回跳）：重新请求同一个 URL 往往会因为凭证已被
         * 消费而失败，所以 RELOAD 策略对它们退化为 OBSERVE。
         */
        internal fun looksSingleUse(url: String): Boolean = SINGLE_USE_PARAMS.containsMatchIn(url)
    }
}

/**
 * 降级路径的无参桥（对象名 MafariFp），只在 WebView 不支持 DOCUMENT_START_SCRIPT 时安装。
 * 没有 host / topHost 参数：页面传什么都影响不了返回值。
 */
class FpFallbackBridge(private val injector: FingerprintInjector) {
    @JavascriptInterface
    fun fp(): String = injector.fallbackConfig()
}
