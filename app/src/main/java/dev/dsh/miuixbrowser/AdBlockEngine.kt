package dev.dsh.miuixbrowser

/**
 * 去广告引擎（纯 Kotlin，不依赖 Android）。
 *
 * 支持 Adblock Plus / uBlock Origin / AdGuard 订阅源里最常用的那部分语法：
 * - 网络规则：`||domain^`、`|http://`、路径通配、`*` 与 `^` 通配、`@@` 例外、
 *   `$third-party / $domain= / $script,image,... / $important`
 * - 元素隐藏：`##selector`、`site.com##selector`、`~site.com`、`#@#` 例外
 * - hosts 格式：`0.0.0.0 ads.example.com`
 *
 * 不支持（解析时直接跳过，不会误伤）：正则规则、`$redirect / $removeparam / $csp / $replace`、
 * `#?#` `#$#` `#%#` 扩展选择器、`+js()` 脚本注入、HTML 过滤。
 */

/** 请求资源类型位（WebView 的 shouldInterceptRequest 不给类型，由 [AdMatch.inferType] 推断） */
object AdType {
    const val SCRIPT = 1
    const val IMAGE = 2
    const val STYLESHEET = 4
    const val XHR = 8
    const val SUBDOC = 16
    const val FONT = 32
    const val MEDIA = 64
    const val OTHER = 128
    const val ALL = 255
}

/** 一条网络规则（已编译成可直接匹配的形态）。 */
class NetRule(
    @JvmField val segs: Array<String>,
    @JvmField val hostAnchor: Boolean,
    @JvmField val startAnchor: Boolean,
    @JvmField val endAnchor: Boolean,
    /** 0 表示不限类型；否则是 [AdType] 位掩码 */
    @JvmField val types: Int,
    /** 0 不限；1 只匹配第三方；-1 只匹配第一方 */
    @JvmField val party: Int,
    @JvmField val include: Array<String>?,
    @JvmField val exclude: Array<String>?,
    @JvmField val important: Boolean,
)

/** 解析后的网络规则 + 建索引用的键。 */
class ParsedNet(
    @JvmField val rule: NetRule,
    /** `||example.com^` 这类可按域名索引的规则的域名键 */
    @JvmField val hostKey: String?,
    /** 其他规则按最长完整 token 索引 */
    @JvmField val token: String?,
)

/** 元素隐藏规则。include == null 表示通用规则（所有站点）。 */
class CosRule(
    @JvmField val selector: String,
    @JvmField val include: Array<String>?,
    @JvmField val exclude: Array<String>?,
)

/** 单个订阅源解析后的结果，可按订阅缓存，开关订阅时只需重新合并。 */
class ParsedList(
    @JvmField val title: String,
    @JvmField val block: ArrayList<ParsedNet>,
    @JvmField val allow: ArrayList<ParsedNet>,
    @JvmField val hide: ArrayList<CosRule>,
    @JvmField val unhide: ArrayList<CosRule>,
    @JvmField val skipped: Int,
) {
    val ruleCount: Int get() = block.size + allow.size + hide.size + unhide.size
}

/* ------------------------------------------------------------------ */
/* 解析                                                                */
/* ------------------------------------------------------------------ */

object AdParser {

    private const val MAX_SELECTOR = 400
    private const val NUL = '\u0000'

    /** 内容看起来是不是规则列表（Adblock 语法或 hosts 格式）。 */
    fun looksLikeFilterList(text: String): Boolean {
        val head = text.take(8000)
        if (head.contains("==UserScript==")) return false
        if (head.contains("[Adblock", ignoreCase = true) || head.contains("[uBlock", ignoreCase = true)) return true
        var score = 0
        var lines = 0
        for (raw in text.lineSequence()) {
            if (++lines > 800) break
            val line = raw.trim()
            when {
                line.startsWith("! Title:") || line.startsWith("! Version:") || line.startsWith("! Homepage:") -> score += 3
                line.startsWith("||") && (line.contains('^') || line.contains('$')) -> score += 1
                line.startsWith("@@||") -> score += 1
                line.contains("##") && !line.startsWith("//") && !line.startsWith("<") -> score += 1
                line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ") -> score += 1
            }
            if (score >= 8) return true
        }
        return score >= 8
    }

    fun parse(text: String): ParsedList {
        var title = ""
        val block = ArrayList<ParsedNet>()
        val allow = ArrayList<ParsedNet>()
        val hide = ArrayList<CosRule>()
        val unhide = ArrayList<CosRule>()
        var skipped = 0

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val c0 = line[0]
            if (c0 == '!') {
                if (title.isEmpty() && line.startsWith("! Title:")) title = line.substring(8).trim()
                continue
            }
            if (c0 == '[') continue
            if (c0 == '#' && (line.length == 1 || line[1] == ' ')) continue // hosts 文件里的注释

            val cos = cosmeticSplit(line)
            if (cos != null) {
                val (kind, domainPart, selector) = cos
                if (kind == 0) { skipped++; continue }
                if (!isSafeSelector(selector)) { skipped++; continue }
                val rule = buildCos(domainPart, selector)
                if (rule == null) { skipped++; continue }
                if (kind == 1) hide.add(rule) else unhide.add(rule)
                continue
            }

            if (c0.isDigit()) {
                val host = hostsLineDomain(line)
                if (host != null) {
                    block.add(hostBlockRule(host))
                    continue
                }
            }

            val net = parseNet(line)
            if (net == null) { skipped++; continue }
            if (net.second) allow.add(net.first) else block.add(net.first)
        }
        return ParsedList(title, block, allow, hide, unhide, skipped)
    }

    /* ---------------- 元素隐藏 ---------------- */

    /**
     * 返回 (kind, 域名部分, 选择器)；kind: 1 = 隐藏，2 = 例外，0 = 不支持的扩展语法。
     * 不是元素隐藏规则返回 null。
     */
    private fun cosmeticSplit(line: String): Triple<Int, String, String>? {
        val idx = line.indexOf('#')
        if (idx < 0) return null
        // 域名部分不会含有 / ? 等网络规则才有的字符，先排除明显的网络规则
        for (i in 0 until idx) {
            val ch = line[i]
            if (ch == '/' || ch == '?' || ch == '$' || ch == '^' || ch == '|') return null
        }
        return when {
            line.startsWith("##", idx) -> {
                val sel = line.substring(idx + 2).trim()
                if (sel.isEmpty() || sel.startsWith("+js(") || sel.startsWith("^")) Triple(0, "", "")
                else if (hasExtendedSyntax(sel)) Triple(0, "", "")
                else Triple(1, line.substring(0, idx), sel)
            }
            line.startsWith("#@#", idx) -> {
                val sel = line.substring(idx + 3).trim()
                if (sel.isEmpty() || sel.startsWith("+js(")) Triple(0, "", "")
                else Triple(2, line.substring(0, idx), sel)
            }
            line.startsWith("#?#", idx) || line.startsWith("#$#", idx) || line.startsWith("#%#", idx) ||
                line.startsWith("#@?#", idx) || line.startsWith("#@$#", idx) || line.startsWith("#@%#", idx) ||
                line.startsWith("#$?#", idx) -> Triple(0, "", "")
            else -> null
        }
    }

    private val EXTENDED = arrayOf(
        ":-abp-", ":has-text(", ":contains(", ":xpath(", ":matches-css", ":matches-media", ":matches-path",
        ":matches-attr", ":matches-property", ":nth-ancestor(", ":upward(", ":min-text-length(", ":others(",
        ":remove(", ":style(", ":-ms-", ":watch-attr(", ":if(", ":if-not(", ":not(:has-text", ":-js-",
        "::-", ":is-visible", ":-moz-",
    )

    private fun hasExtendedSyntax(selector: String): Boolean = EXTENDED.any { selector.contains(it) }

    /**
     * 订阅源是第三方内容，选择器最终会拼进一段 CSS 注入页面，必须确保它不能「逃出」选择器：
     * 不允许 { } ; 注释，括号 / 方括号 / 引号必须配平。
     */
    fun isSafeSelector(selector: String): Boolean {
        if (selector.isEmpty() || selector.length > MAX_SELECTOR) return false
        var paren = 0
        var bracket = 0
        var quote = NUL
        var i = 0
        while (i < selector.length) {
            val ch = selector[i]
            if (quote != NUL) {
                if (ch == '\\') { i += 2; continue }
                if (ch == quote) quote = NUL
                i++
                continue
            }
            when (ch) {
                '"', '\'' -> quote = ch
                '\\' -> { i += 2; continue }
                '{', '}', ';', '<' -> return false
                '(' -> paren++
                ')' -> { paren--; if (paren < 0) return false }
                '[' -> bracket++
                ']' -> { bracket--; if (bracket < 0) return false }
                '/' -> if (i + 1 < selector.length && selector[i + 1] == '*') return false
            }
            i++
        }
        return quote == NUL && paren == 0 && bracket == 0
    }

    private fun buildCos(domainPart: String, selector: String): CosRule? {
        if (domainPart.isBlank()) return CosRule(selector, null, null)
        val inc = ArrayList<String>()
        val exc = ArrayList<String>()
        var unsupported = false
        for (entry in domainPart.split(',')) {
            val d = entry.trim().lowercase()
            if (d.isEmpty()) continue
            val neg = d.startsWith("~")
            val name = if (neg) d.substring(1) else d
            // 实体（google.*）、正则、通配都不支持
            if (name.contains('*') || name.startsWith("/") || !name.contains('.') && name != "localhost") {
                unsupported = true
                continue
            }
            if (neg) exc.add(name) else inc.add(name)
        }
        if (inc.isEmpty() && exc.isEmpty()) return null
        if (inc.isEmpty() && unsupported) return null
        return CosRule(
            selector,
            if (inc.isEmpty()) null else inc.toTypedArray(),
            if (exc.isEmpty()) null else exc.toTypedArray(),
        )
    }

    /* ---------------- hosts ---------------- */

    private fun hostsLineDomain(line: String): String? {
        val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        if (parts[0] != "0.0.0.0" && parts[0] != "127.0.0.1" && parts[0] != "::1") return null
        val host = parts[1].lowercase()
        if (!host.contains('.') || host == "localhost" || host.endsWith(".localdomain")) return null
        if (host.any { !(it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '_') }) return null
        return host
    }

    private fun hostBlockRule(host: String): ParsedNet = ParsedNet(
        NetRule(arrayOf("$host^"), true, false, false, 0, 0, null, null, false),
        hostKey = host,
        token = null,
    )

    /* ---------------- 网络规则 ---------------- */

    /** 返回 (规则, 是否例外)；不支持的返回 null。 */
    private fun parseNet(line0: String): Pair<ParsedNet, Boolean>? {
        var line = line0
        var exception = false
        if (line.startsWith("@@")) {
            exception = true
            line = line.substring(2)
        }
        if (line.isEmpty()) return null

        var optStr = ""
        val dollar = line.lastIndexOf('$')
        if (dollar >= 0) {
            val candidate = line.substring(dollar + 1)
            // 选项里不会出现 / 和空格；正则规则里的 $ 属于正则本身
            if (!candidate.contains('/') && !candidate.contains(' ')) {
                optStr = candidate
                line = line.substring(0, dollar)
            }
        }
        if (line.isEmpty()) return null

        // 正则规则（/.../）不支持
        if (line.length >= 2 && line[0] == '/' && line[line.length - 1] == '/') return null

        val opts = parseOptions(optStr) ?: return null

        var hostAnchor = false
        var startAnchor = false
        var endAnchor = false
        var body = line
        if (body.startsWith("||")) {
            hostAnchor = true
            body = body.substring(2)
        } else if (body.startsWith("|")) {
            startAnchor = true
            body = body.substring(1)
        }
        if (body.endsWith("|")) {
            endAnchor = true
            body = body.substring(0, body.length - 1)
        }
        body = body.lowercase()
        if (body.isEmpty()) return null
        if (hostAnchor && body.startsWith("*")) return null

        // 压缩连续的 *，去掉首尾的 *
        val sb = StringBuilder(body.length)
        var prevStar = false
        for (ch in body) {
            if (ch == '*') {
                if (!prevStar) sb.append(ch)
                prevStar = true
            } else {
                sb.append(ch)
                prevStar = false
            }
        }
        body = sb.toString()
        if (body.startsWith("*")) {
            body = body.substring(1)
            startAnchor = false
        }
        if (body.endsWith("*")) {
            body = body.substring(0, body.length - 1)
            endAnchor = false
        }
        if (body.isEmpty()) return null

        val segs = body.split('*').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return null

        // 太短、没有锚点的规则会误伤大量请求
        val literal = body.count { it != '*' && it != '^' }
        if (!hostAnchor && !startAnchor && literal < 4) return null
        if (hostAnchor && literal < 4) return null

        val types = opts.finalTypes() ?: return null
        val rule = NetRule(
            segs = segs.toTypedArray(),
            hostAnchor = hostAnchor,
            startAnchor = startAnchor,
            endAnchor = endAnchor,
            types = types,
            party = opts.party,
            include = opts.include?.toTypedArray(),
            exclude = opts.exclude?.toTypedArray(),
            important = opts.important,
        )
        val hostKey = if (hostAnchor) hostKeyOf(segs[0], segs.size == 1, endAnchor) else null
        val token = if (hostKey == null) bestToken(segs, hostAnchor || startAnchor, endAnchor) else null
        return Pair(ParsedNet(rule, hostKey, token), exception)
    }

    /** `||example.com^` / `||example.com/path` 的域名键；域名后必须有终止符，避免 `||example.com` 命中 example.community */
    private fun hostKeyOf(first: String, single: Boolean, endAnchor: Boolean): String? {
        var end = -1
        for (i in first.indices) {
            val ch = first[i]
            if (ch == '/' || ch == '^' || ch == ':' || ch == '?') { end = i; break }
        }
        if (end < 0) {
            // 整条规则就是 ||example.com| 这种
            if (single && endAnchor) end = first.length else return null
        }
        if (end <= 0) return null
        val key = first.substring(0, end)
        if (!key.contains('.')) return null
        for (ch in key) {
            if (!(ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '_')) return null
        }
        return key
    }

    /** 几乎每个 URL 都有的 token，拿来建索引等于没建 */
    private val COMMON_TOKENS = setOf("http", "https", "www", "com", "net", "org", "html", "htm", "php", "cgi")

    private fun isTokenChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9'

    /** 取规则里最长的、两侧都有明确边界的 token，用来给规则建倒排索引。 */
    private fun bestToken(segs: List<String>, firstAnchored: Boolean, endAnchor: Boolean): String? {
        var best: String? = null
        for ((si, seg) in segs.withIndex()) {
            var i = 0
            while (i < seg.length) {
                if (!isTokenChar(seg[i])) { i++; continue }
                var j = i
                while (j < seg.length && isTokenChar(seg[j])) j++
                val leftOk = i > 0 || (si == 0 && firstAnchored)
                val rightOk = j < seg.length || (si == segs.lastIndex && endAnchor)
                val len = j - i
                if (leftOk && rightOk && len >= 3 && len <= 40 && (best == null || len > best.length)) {
                    val t = seg.substring(i, j)
                    if (t !in COMMON_TOKENS) best = t
                }
                i = j
            }
        }
        return best
    }

    private class Opts {
        var types = 0
        var negTypes = 0
        var party = 0
        var include: ArrayList<String>? = null
        var exclude: ArrayList<String>? = null
        var important = false

        /** 返回 null 表示这条规则永远不会匹配（类型被排除光了） */
        fun finalTypes(): Int? {
            if (types == 0 && negTypes == 0) return 0
            val base = if (types == 0) AdType.ALL else types
            val result = base and negTypes.inv() and AdType.ALL
            return if (result == 0) null else result
        }
    }

    private fun typeBits(name: String): Int = when (name) {
        "script" -> AdType.SCRIPT
        "image" -> AdType.IMAGE
        "stylesheet", "css" -> AdType.STYLESHEET
        "xmlhttprequest", "xhr", "fetch" -> AdType.XHR
        "subdocument", "frame" -> AdType.SUBDOC
        "font" -> AdType.FONT
        "media" -> AdType.MEDIA
        "object", "object-subrequest", "other", "ping", "beacon", "websocket", "webrtc" -> AdType.OTHER
        "all" -> AdType.ALL
        else -> -1
    }

    /** 选项里出现我们实现不了的东西，整条规则丢弃，避免把「重定向 / 改写」规则当成「直接拦截」。 */
    private fun parseOptions(optStr: String): Opts? {
        val o = Opts()
        if (optStr.isEmpty()) return o
        for (raw in optStr.split(',')) {
            var opt = raw.trim().lowercase()
            if (opt.isEmpty()) continue
            var neg = false
            if (opt.startsWith("~")) {
                neg = true
                opt = opt.substring(1)
            }
            val eq = opt.indexOf('=')
            val name = if (eq >= 0) opt.substring(0, eq) else opt
            val value = if (eq >= 0) opt.substring(eq + 1) else ""
            when (name) {
                "third-party", "3p", "strict3p" -> o.party = if (neg) -1 else 1
                "first-party", "1p", "strict1p" -> o.party = if (neg) 1 else -1
                "important" -> o.important = true
                "match-case", "empty", "mp4" -> Unit
                "domain", "from" -> {
                    if (value.isEmpty()) return null
                    for (d in value.split('|')) {
                        val dn = d.trim()
                        if (dn.isEmpty()) continue
                        val dneg = dn.startsWith("~")
                        val dname = if (dneg) dn.substring(1) else dn
                        if (dname.contains('*') || dname.startsWith("/")) return null
                        if (dneg) {
                            (o.exclude ?: ArrayList<String>().also { o.exclude = it }).add(dname)
                        } else {
                            (o.include ?: ArrayList<String>().also { o.include = it }).add(dname)
                        }
                    }
                }
                else -> {
                    val bits = typeBits(name)
                    if (bits < 0) return null
                    if (neg) o.negTypes = o.negTypes or bits else o.types = o.types or bits
                }
            }
        }
        return o
    }
}

/* ------------------------------------------------------------------ */
/* 匹配                                                                */
/* ------------------------------------------------------------------ */

/** 一次请求的匹配上下文。[url] 必须是小写。 */
class ReqCtx(
    @JvmField val url: String,
    @JvmField val host: String,
    @JvmField val hostStart: Int,
    @JvmField val pageHost: String,
    @JvmField val type: Int,
) {
    // 0 = 未计算，1 = 第三方，2 = 同站。只有带 $third-party / $~third-party 的规则才会读取，
    // 其余请求不再为每个子资源计算两次可注册域名。
    private var party = 0

    val thirdParty: Boolean
        get() {
            if (party == 0) party = if (AdMatch.isThirdParty(host, pageHost)) 1 else 2
            return party == 1
        }
}

object AdMatch {

    private val SLD = setOf("com", "net", "org", "gov", "edu", "co", "ac", "or", "ne", "go")

    fun domainMatches(host: String, domain: String): Boolean =
        host == domain || (host.length > domain.length && host.endsWith(domain) && host[host.length - domain.length - 1] == '.')

    /** 近似的可注册域名（不引入完整公共后缀表）：处理 com.cn / co.uk 这类二级后缀。 */
    fun registrable(host: String): String {
        if (host.isEmpty() || host[0] == '[') return host
        if (host.all { it.isDigit() || it == '.' }) return host
        // 请求热路径：用 lastIndexOf 定位末尾几段，避免 split / takeLast / joinToString 产生的临时对象
        val d1 = host.lastIndexOf('.')
        if (d1 < 0) return host
        val d2 = host.lastIndexOf('.', d1 - 1)
        if (d2 < 0) return host // 只有两段
        val lastLen = host.length - d1 - 1
        if (lastLen == 2 && host.substring(d2 + 1, d1) in SLD) {
            val d3 = host.lastIndexOf('.', d2 - 1)
            return if (d3 < 0) host else host.substring(d3 + 1)
        }
        return host.substring(d2 + 1)
    }

    /**
     * host 不是公共后缀本身（粗略判断，与 [registrable] 同一套启发式）：至少两段，且不是 co.uk 这一类。
     * 用于拒绝 `*.com` / `*.co.uk` 这样的过宽通配符规则。
     */
    fun isRegistrableOrDeeper(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size < 2 || parts.any { it.isEmpty() }) return false
        val last = parts[parts.size - 1]
        val second = parts[parts.size - 2]
        if (parts.size == 2 && last.length == 2 && second in SLD) return false
        return true
    }

    fun isThirdParty(host: String, pageHost: String): Boolean {
        if (pageHost.isEmpty() || host.isEmpty()) return false
        return registrable(host) != registrable(pageHost)
    }

    /** 从小写 URL 里取主机名的起止位置，没有找到返回 null。 */
    fun hostRange(url: String): IntArray? {
        val scheme = url.indexOf("://")
        if (scheme < 0) return null
        var start = scheme + 3
        var end = url.length
        for (i in start until url.length) {
            val ch = url[i]
            if (ch == '/' || ch == '?' || ch == '#') { end = i; break }
        }
        val at = url.lastIndexOf('@', end - 1)
        if (at >= start) start = at + 1
        // 去掉端口
        if (end > start && url[start] != '[') {
            val colon = url.lastIndexOf(':', end - 1)
            if (colon >= start) end = colon
        }
        if (end <= start) return null
        return intArrayOf(start, end)
    }

    /** 由 Accept 头与 URL 扩展名推断资源类型；拿不到线索时返回「脚本 / XHR / 其他」。 */
    fun inferType(url: String, accept: String?): Int {
        val a = accept?.lowercase().orEmpty()
        if (a.startsWith("image/")) return AdType.IMAGE
        if (a.contains("text/css")) return AdType.STYLESHEET
        if (a.contains("text/html")) return AdType.SUBDOC
        var pathEnd = url.indexOf('?')
        if (pathEnd < 0) pathEnd = url.indexOf('#')
        if (pathEnd < 0) pathEnd = url.length
        val slash = url.lastIndexOf('/', pathEnd - 1)
        val dot = url.lastIndexOf('.', pathEnd - 1)
        if (dot > slash && dot > 0) {
            when (url.substring(dot + 1, pathEnd)) {
                "js", "mjs" -> return AdType.SCRIPT
                "css" -> return AdType.STYLESHEET
                "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp", "avif", "apng" -> return AdType.IMAGE
                "woff", "woff2", "ttf", "otf", "eot" -> return AdType.FONT
                "mp4", "webm", "m3u8", "mp3", "ogg", "m4a", "flv", "mpd", "m4s" -> return AdType.MEDIA
                "html", "htm" -> return AdType.SUBDOC
            }
        }
        return AdType.SCRIPT or AdType.XHR or AdType.OTHER
    }

    /** ^ 匹配的分隔符：除字母、数字、_ - . % 之外的任意字符 */
    private fun isSep(c: Char): Boolean =
        !(c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-' || c == '.' || c == '%')

    /** 段 [seg] 必须从 url[pos] 开始完整匹配（支持 ^），返回结束位置，失败 -1。 */
    private fun matchSegAt(url: String, pos: Int, seg: String): Int {
        var u = pos
        val n = seg.length
        var i = 0
        while (i < n) {
            val pc = seg[i]
            if (pc == '^') {
                if (u == url.length) {
                    // ^ 可以在 URL 末尾匹配「空」，但它必须是段里的最后一个字符
                    return if (i == n - 1) u else -1
                }
                if (!isSep(url[u])) return -1
                u++
            } else {
                if (u >= url.length || url[u] != pc) return -1
                u++
            }
            i++
        }
        return u
    }

    /** 从 [from] 起向后找 [seg] 的最左匹配，返回匹配结束位置。 */
    private fun findSegEnd(url: String, from: Int, seg: String): Int {
        if (seg.indexOf('^') < 0) {
            val idx = url.indexOf(seg, from)
            return if (idx < 0) -1 else idx + seg.length
        }
        var s = from
        while (s <= url.length) {
            val e = matchSegAt(url, s, seg)
            if (e >= 0) return e
            s++
        }
        return -1
    }

    /** 带末尾锚点（|）的最后一段：必须正好贴着 URL 末尾。 */
    private fun matchSegSuffix(url: String, from: Int, seg: String): Int {
        val s0 = url.length - seg.length
        for (cand in intArrayOf(s0, s0 + 1)) {
            if (cand < from || cand > url.length) continue
            if (matchSegAt(url, cand, seg) == url.length) return url.length
        }
        return -1
    }

    private fun matchRuleAt(rule: NetRule, url: String, start: Int, anchoredStart: Boolean): Boolean {
        val segs = rule.segs
        val last = segs.size - 1
        var pos = start
        for (k in 0..last) {
            val seg = segs[k]
            val mustEnd = rule.endAnchor && k == last
            val e: Int = if (k == 0 && anchoredStart) {
                val m = matchSegAt(url, pos, seg)
                if (m >= 0 && mustEnd && m != url.length) -1 else m
            } else if (mustEnd) {
                matchSegSuffix(url, pos, seg)
            } else {
                findSegEnd(url, pos, seg)
            }
            if (e < 0) return false
            pos = e
        }
        return true
    }

    private fun constraintsOk(rule: NetRule, ctx: ReqCtx): Boolean {
        if (rule.types != 0 && (rule.types and ctx.type) == 0) return false
        if (rule.party == 1 && !ctx.thirdParty) return false
        if (rule.party == -1 && ctx.thirdParty) return false
        val inc = rule.include
        if (inc != null) {
            var hit = false
            for (d in inc) if (domainMatches(ctx.pageHost, d)) { hit = true; break }
            if (!hit) return false
        }
        val exc = rule.exclude
        if (exc != null) {
            for (d in exc) if (domainMatches(ctx.pageHost, d)) return false
        }
        return true
    }

    /** 域名索引命中后：host 的后缀从 [suffixOffset] 开始对上了规则的域名键。 */
    fun matchIndexed(rule: NetRule, ctx: ReqCtx, suffixOffset: Int): Boolean {
        if (!constraintsOk(rule, ctx)) return false
        return matchRuleAt(rule, ctx.url, ctx.hostStart + suffixOffset, true)
    }

    fun matchGeneral(rule: NetRule, ctx: ReqCtx): Boolean {
        if (!constraintsOk(rule, ctx)) return false
        val url = ctx.url
        if (rule.hostAnchor) {
            val hostEnd = ctx.hostStart + ctx.host.length
            var cand = ctx.hostStart
            while (cand < hostEnd) {
                if (matchRuleAt(rule, url, cand, true)) return true
                val dot = url.indexOf('.', cand)
                if (dot < 0 || dot >= hostEnd) break
                cand = dot + 1
            }
            return false
        }
        return matchRuleAt(rule, url, 0, rule.startAnchor)
    }
}

/* ------------------------------------------------------------------ */
/* 编译后的规则集                                                      */
/* ------------------------------------------------------------------ */

private class NetIndex {
    val byHost = HashMap<String, ArrayList<NetRule>>()
    val byToken = HashMap<String, ArrayList<NetRule>>()
    val fallback = ArrayList<NetRule>()
    var size = 0

    fun add(p: ParsedNet) {
        size++
        val key = p.hostKey
        val token = p.token
        when {
            key != null -> byHost.getOrPut(key) { ArrayList(2) }.add(p.rule)
            token != null -> byToken.getOrPut(token) { ArrayList(2) }.add(p.rule)
            else -> fallback.add(p.rule)
        }
    }

    fun find(ctx: ReqCtx): NetRule? {
        if (size == 0) return null
        // 1. 域名索引：从完整主机名逐级向上
        val host = ctx.host
        var off = 0
        while (off < host.length) {
            val list = byHost[if (off == 0) host else host.substring(off)]
            if (list != null) {
                for (r in list) if (AdMatch.matchIndexed(r, ctx, off)) return r
            }
            val dot = host.indexOf('.', off)
            if (dot < 0) break
            off = dot + 1
        }
        // 2. token 倒排索引
        if (byToken.isNotEmpty()) {
            val url = ctx.url
            var i = 0
            val n = url.length
            while (i < n) {
                val c = url[i]
                if (c in 'a'..'z' || c in '0'..'9') {
                    var j = i + 1
                    while (j < n && (url[j] in 'a'..'z' || url[j] in '0'..'9')) j++
                    if (j - i in 3..40) {
                        val list = byToken[url.substring(i, j)]
                        if (list != null) {
                            for (r in list) if (AdMatch.matchGeneral(r, ctx)) return r
                        }
                    }
                    i = j
                } else i++
            }
        }
        // 3. 无 token 的兜底规则
        for (r in fallback) if (AdMatch.matchGeneral(r, ctx)) return r
        return null
    }
}

/** 合并了所有启用订阅的引擎，构建后只读，可在任意线程并发使用。 */
class AdEngine(lists: List<ParsedList>) {

    private val block = NetIndex()
    private val allow = NetIndex()

    private val genericHide = LinkedHashSet<String>()
    private val genericHideExcluded = ArrayList<CosRule>()
    private val specificHide = HashMap<String, ArrayList<CosRule>>()
    private val unhideGeneric = HashSet<String>()
    private val unhideSpecific = HashMap<String, HashSet<String>>()
    private var genericCss = ""

    private val cssCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 64
    }

    val networkRuleCount: Int
    val cosmeticRuleCount: Int

    init {
        for (l in lists) {
            for (p in l.block) block.add(p)
            for (p in l.allow) allow.add(p)
            for (r in l.unhide) {
                val inc = r.include
                if (inc == null) unhideGeneric.add(r.selector)
                else for (d in inc) unhideSpecific.getOrPut(d) { HashSet() }.add(r.selector)
            }
            for (r in l.hide) {
                val inc = r.include
                when {
                    inc != null -> for (d in inc) specificHide.getOrPut(d) { ArrayList(2) }.add(r)
                    r.exclude != null -> genericHideExcluded.add(r)
                    else -> genericHide.add(r.selector)
                }
            }
        }
        networkRuleCount = block.size + allow.size
        cosmeticRuleCount = genericHide.size + genericHideExcluded.size + specificHide.values.sumOf { it.size }
        genericCss = buildCss(genericSelectors(emptySet()))
    }

    private fun genericSelectors(extraUnhide: Set<String>): List<String> {
        val out = ArrayList<String>(minOf(genericHide.size, MAX_GENERIC))
        for (s in genericHide) {
            if (s in unhideGeneric || s in extraUnhide) continue
            out.add(s)
            if (out.size >= MAX_GENERIC) break
        }
        return out
    }

    /** 请求是否应被拦截。[url] 小写；[accept] 为请求头里的 Accept。 */
    fun shouldBlock(url: String, pageHost: String, accept: String?): Boolean {
        if (block.size == 0) return false
        val range = AdMatch.hostRange(url) ?: return false
        val host = url.substring(range[0], range[1])
        val ctx = ReqCtx(
            url = url,
            host = host,
            hostStart = range[0],
            pageHost = pageHost,
            type = AdMatch.inferType(url, accept),
        )
        val hit = block.find(ctx) ?: return false
        if (hit.important) return true
        return allow.find(ctx) == null
    }

    /** 某个站点要注入的元素隐藏 CSS，没有规则时返回空串。 */
    fun cssFor(host: String): String {
        if (host.isEmpty()) return ""
        synchronized(cssCache) { cssCache[host]?.let { return it } }
        val css = computeCss(host)
        synchronized(cssCache) { cssCache[host] = css }
        return css
    }

    private fun computeCss(host: String): String {
        val specific = LinkedHashSet<String>()
        val localUnhide = HashSet<String>()
        var off = 0
        while (off < host.length) {
            val key = if (off == 0) host else host.substring(off)
            specificHide[key]?.forEach { r ->
                val exc = r.exclude
                if (exc == null || exc.none { AdMatch.domainMatches(host, it) }) specific.add(r.selector)
            }
            unhideSpecific[key]?.let { localUnhide.addAll(it) }
            val dot = host.indexOf('.', off)
            if (dot < 0) break
            off = dot + 1
        }
        for (r in genericHideExcluded) {
            val exc = r.exclude ?: continue
            if (exc.none { AdMatch.domainMatches(host, it) }) specific.add(r.selector)
        }
        specific.removeAll(localUnhide)
        specific.removeAll(unhideGeneric)

        val generic = if (localUnhide.any { it in genericHide }) {
            buildCss(genericSelectors(localUnhide))
        } else {
            genericCss
        }
        val own = buildCss(specific.toList())
        return if (own.isEmpty()) generic else if (generic.isEmpty()) own else generic + "\n" + own
    }

    private companion object {
        const val MAX_GENERIC = 8000
        const val CHUNK = 40

        /**
         * CSS 选择器列表里只要有一个选择器非法，整条规则都会失效。
         * 分成小块各自成规则，坏选择器最多废掉一小块。
         */
        fun buildCss(selectors: List<String>): String {
            if (selectors.isEmpty()) return ""
            val sb = StringBuilder(selectors.size * 24)
            var i = 0
            while (i < selectors.size) {
                val end = minOf(i + CHUNK, selectors.size)
                if (sb.isNotEmpty()) sb.append('\n')
                for (k in i until end) {
                    if (k > i) sb.append(',')
                    sb.append(selectors[k])
                }
                sb.append("{display:none!important}")
                i = end
            }
            return sb.toString()
        }
    }
}
