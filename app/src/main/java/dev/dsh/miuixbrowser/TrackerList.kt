package dev.dsh.miuixbrowser

/** 常见第三方统计 / 追踪域名（只拦第三方子资源请求，命中即返回空响应）。 */
object TrackerList {
    private val domains = setOf(
        "google-analytics.com", "analytics.google.com", "googletagmanager.com", "googletagservices.com",
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "connect.facebook.net", "graph.facebook.com", "pixel.facebook.com",
        "hotjar.com", "mixpanel.com", "segment.io", "segment.com", "amplitude.com", "fullstory.com",
        "clarity.ms", "scorecardresearch.com", "quantserve.com", "taboola.com", "outbrain.com",
        "criteo.com", "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net", "moatads.com",
        "bugsnag.com", "sentry.io", "newrelic.com", "nr-data.net", "branch.io", "adjust.com", "appsflyer.com",
        "hm.baidu.com", "cnzz.com", "umeng.com", "51.la", "growingio.com", "sensorsdata.cn",
        "mmstat.com", "tanx.com", "cm.g.doubleclick.net", "bat.bing.com", "ads.twitter.com",
        "analytics.twitter.com", "ads.linkedin.com", "px.ads.linkedin.com", "snap.licdn.com",
        "analytics.tiktok.com", "ads-api.tiktok.com", "log.byteoversea.com", "mon.zijieapi.com",
    ).toSet()

    /**
     * host 或其任一父域名在名单里。逐级去掉最左一段做哈希查找（一次请求最多几次 O(1)），
     * 不再对每个请求遍历整张名单并拼接 ".域名" 字符串；调用方传入的 host 通常已是小写，避免重复 lowercase。
     */
    fun matches(host: String): Boolean {
        if (host.isEmpty()) return false
        var h = host
        if (h.any { it in 'A'..'Z' }) h = h.lowercase()
        var off = 0
        while (true) {
            if ((if (off == 0) h else h.substring(off)) in domains) return true
            val dot = h.indexOf('.', off)
            if (dot < 0) return false
            off = dot + 1
        }
    }
}
