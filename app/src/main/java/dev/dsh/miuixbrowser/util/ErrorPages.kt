package dev.dsh.miuixbrowser.util

import dev.dsh.miuixbrowser.i18n.tr

/**
 * 访问失败页：把 WebView 的 errorCode / net::ERR_*、HTTP 状态码、Gecko 的错误码
 * 统一翻译成「原因 + 可以怎么做」的中文提示，并渲染成一页自包含的 HTML。
 *
 * 纯 Kotlin、不依赖 Android 类，可直接 JVM 单测。
 */
enum class ErrorKind {
    OFFLINE, NETWORK_CHANGED, DNS, TIMEOUT, CONNECTION_REFUSED, CONNECTION_RESET, ADDRESS_UNREACHABLE,
    CERT_DATE, CERT_AUTHORITY, CERT_NAME, CERT_REVOKED, CERT_OTHER, SSL_PROTOCOL, HTTPS_ONLY,
    PROXY, PROXY_AUTH, AUTH, TOO_MANY_REDIRECTS, BAD_URL, UNSUPPORTED_SCHEME, FILE_NOT_FOUND,
    BLOCKED, TOO_MANY_REQUESTS, UNSAFE_SITE, BAD_CONTENT, PORT_BLOCKED, CRASHED, FINGERPRINT, UNKNOWN,
}

/** [code] 是页面上展示的技术代码（如 ERR_NAME_NOT_RESOLVED / 404），[detail] 放进「技术详情」折叠区。 */
data class ErrorSpec(
    val kind: ErrorKind?,
    val code: String,
    val title: String,
    val summary: String,
    val tips: List<String>,
    val detail: String = "",
    val warn: Boolean = false,
    /** 证书类错误里允许用户「忽略风险继续访问」（页面渲染一个折叠的高级区 + 内部链接 [ErrorPages.PROCEED_URL]） */
    val canProceed: Boolean = false,
)

object ErrorPages {

    /* ---------- WebView ---------- */

    // WebViewClient.ERROR_*（字面值，免得纯 JVM 测试依赖 android.jar）
    private const val E_UNKNOWN = -1
    private const val E_HOST_LOOKUP = -2
    private const val E_UNSUPPORTED_AUTH = -3
    private const val E_AUTHENTICATION = -4
    private const val E_PROXY_AUTH = -5
    private const val E_CONNECT = -6
    private const val E_IO = -7
    private const val E_TIMEOUT = -8
    private const val E_REDIRECT_LOOP = -9
    private const val E_UNSUPPORTED_SCHEME = -10
    private const val E_SSL_HANDSHAKE = -11
    private const val E_BAD_URL = -12
    private const val E_FILE = -13
    private const val E_FILE_NOT_FOUND = -14
    private const val E_TOO_MANY_REQUESTS = -15
    private const val E_UNSAFE_RESOURCE = -16

    /**
     * WebView 的 onReceivedSslError：[primary] 是 android.net.http.SslError.getPrimaryError()
     * （0 未生效 / 1 已过期 / 2 域名不符 / 3 不受信任 / 4 日期无效 / 5 其它；字面值，免得纯 JVM 测试依赖 android.jar）。
     */
    fun fromSslError(primary: Int): ErrorSpec {
        val (kind, name) = when (primary) {
            0 -> ErrorKind.CERT_DATE to "SSL_NOTYETVALID"
            1 -> ErrorKind.CERT_DATE to "SSL_EXPIRED"
            2 -> ErrorKind.CERT_NAME to "SSL_IDMISMATCH"
            3 -> ErrorKind.CERT_AUTHORITY to "SSL_UNTRUSTED"
            4 -> ErrorKind.CERT_DATE to "SSL_DATE_INVALID"
            else -> ErrorKind.CERT_OTHER to "SSL_INVALID"
        }
        return fromKind(kind, name, "SslError.primaryError=$primary")
    }

    /** 忽略片段和末尾斜杠后是否同一个地址（判断 SSL 错误是不是当前主框架页面的）。 */
    fun sameUrl(a: String?, b: String?): Boolean {
        fun norm(u: String?) = u.orEmpty().substringBefore('#').trimEnd('/').lowercase()
        val x = norm(a)
        return x.isNotEmpty() && x == norm(b)
    }

    /** 忽略 www. 前缀与大小写后是否同一主机（Gecko 重载时失败地址可能被规范化 / 重定向到同主机的另一路径）。 */
    fun sameHost(a: String?, b: String?): Boolean {
        fun host(u: String?) = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:?#]+)""").find(u.orEmpty())
            ?.groupValues?.get(1)?.lowercase()?.removePrefix("www.").orEmpty()
        val x = host(a)
        return x.isNotEmpty() && x == host(b)
    }

    private val NET_NAME = Regex("""(?:net::)?(ERR_[A-Z0-9_]+)""")

    /** [description] 形如 `net::ERR_NAME_NOT_RESOLVED`：能解析出名字就按名字分（比粗粒度的 errorCode 准），否则按 errorCode。 */
    fun fromWebView(errorCode: Int, description: String?): ErrorSpec {
        val name = description?.let { NET_NAME.find(it)?.groupValues?.get(1) }
        val kind = name?.let(::kindOfNetName) ?: kindOfWebViewCode(errorCode)
        val label = name ?: webViewCodeName(errorCode)
        val detail = buildString {
            if (!description.isNullOrBlank()) append(description)
            if (isNotEmpty()) append("\n")
            append("errorCode=").append(errorCode)
        }
        return fromKind(kind, label, detail)
    }

    private fun kindOfNetName(n: String): ErrorKind? = when {
        n == "ERR_INTERNET_DISCONNECTED" -> ErrorKind.OFFLINE
        n == "ERR_NETWORK_CHANGED" -> ErrorKind.NETWORK_CHANGED
        n == "ERR_NAME_NOT_RESOLVED" || n == "ERR_NAME_RESOLUTION_FAILED" || n == "ERR_DNS_TIMED_OUT" ||
            n.startsWith("ERR_DNS_") -> ErrorKind.DNS
        n == "ERR_CONNECTION_TIMED_OUT" || n == "ERR_TIMED_OUT" || n == "ERR_NETWORK_IO_SUSPENDED" -> ErrorKind.TIMEOUT
        n == "ERR_CONNECTION_REFUSED" -> ErrorKind.CONNECTION_REFUSED
        n == "ERR_CONNECTION_RESET" || n == "ERR_CONNECTION_CLOSED" || n == "ERR_CONNECTION_ABORTED" ||
            n == "ERR_EMPTY_RESPONSE" || n == "ERR_CONNECTION_FAILED" || n == "ERR_SOCKET_NOT_CONNECTED" -> ErrorKind.CONNECTION_RESET
        n == "ERR_ADDRESS_UNREACHABLE" || n == "ERR_ADDRESS_INVALID" || n == "ERR_NETWORK_ACCESS_DENIED" -> ErrorKind.ADDRESS_UNREACHABLE
        n == "ERR_CERT_DATE_INVALID" -> ErrorKind.CERT_DATE
        n == "ERR_CERT_AUTHORITY_INVALID" -> ErrorKind.CERT_AUTHORITY
        n == "ERR_CERT_COMMON_NAME_INVALID" -> ErrorKind.CERT_NAME
        n == "ERR_CERT_REVOKED" || n == "ERR_CERT_UNABLE_TO_CHECK_REVOCATION" -> ErrorKind.CERT_REVOKED
        n.startsWith("ERR_CERT_") || n == "ERR_SSL_PINNED_KEY_NOT_IN_CERT_CHAIN" -> ErrorKind.CERT_OTHER
        n == "ERR_SSL_PROTOCOL_ERROR" || n == "ERR_SSL_VERSION_OR_CIPHER_MISMATCH" ||
            n.startsWith("ERR_SSL_") || n == "ERR_BAD_SSL_CLIENT_AUTH_CERT" -> ErrorKind.SSL_PROTOCOL
        n == "ERR_PROXY_CONNECTION_FAILED" || n == "ERR_TUNNEL_CONNECTION_FAILED" ||
            n == "ERR_NO_SUPPORTED_PROXIES" || n == "ERR_PROXY_CERTIFICATE_INVALID" -> ErrorKind.PROXY
        n == "ERR_PROXY_AUTH_UNSUPPORTED" || n == "ERR_PROXY_AUTH_REQUESTED" -> ErrorKind.PROXY_AUTH
        n == "ERR_TOO_MANY_REDIRECTS" || n == "ERR_UNSAFE_REDIRECT" -> ErrorKind.TOO_MANY_REDIRECTS
        n == "ERR_INVALID_URL" || n == "ERR_INVALID_REDIRECT" -> ErrorKind.BAD_URL
        n == "ERR_UNKNOWN_URL_SCHEME" || n == "ERR_DISALLOWED_URL_SCHEME" -> ErrorKind.UNSUPPORTED_SCHEME
        n == "ERR_FILE_NOT_FOUND" || n == "ERR_FILE_PATH_TOO_LONG" || n == "ERR_FILE_NO_SPACE" -> ErrorKind.FILE_NOT_FOUND
        n == "ERR_BLOCKED_BY_CLIENT" || n == "ERR_BLOCKED_BY_RESPONSE" || n == "ERR_BLOCKED_BY_ADMINISTRATOR" ||
            n == "ERR_ACCESS_DENIED" || n == "ERR_BLOCKED_BY_CSP" -> ErrorKind.BLOCKED
        n == "ERR_UNSAFE_PORT" -> ErrorKind.PORT_BLOCKED
        n == "ERR_INSUFFICIENT_RESOURCES" -> ErrorKind.TOO_MANY_REQUESTS
        n == "ERR_CONTENT_DECODING_FAILED" || n == "ERR_CONTENT_LENGTH_MISMATCH" ||
            n == "ERR_INCOMPLETE_CHUNKED_ENCODING" || n == "ERR_INVALID_RESPONSE" || n == "ERR_RESPONSE_HEADERS_TOO_BIG" ||
            n.startsWith("ERR_HTTP2_") || n.startsWith("ERR_QUIC_") || n.startsWith("ERR_INVALID_HTTP") -> ErrorKind.BAD_CONTENT
        else -> null
    }

    private fun kindOfWebViewCode(code: Int): ErrorKind = when (code) {
        E_HOST_LOOKUP -> ErrorKind.DNS
        E_UNSUPPORTED_AUTH, E_AUTHENTICATION -> ErrorKind.AUTH
        E_PROXY_AUTH -> ErrorKind.PROXY_AUTH
        E_CONNECT -> ErrorKind.CONNECTION_REFUSED
        E_IO -> ErrorKind.CONNECTION_RESET
        E_TIMEOUT -> ErrorKind.TIMEOUT
        E_REDIRECT_LOOP -> ErrorKind.TOO_MANY_REDIRECTS
        E_UNSUPPORTED_SCHEME -> ErrorKind.UNSUPPORTED_SCHEME
        E_SSL_HANDSHAKE -> ErrorKind.SSL_PROTOCOL
        E_BAD_URL -> ErrorKind.BAD_URL
        E_FILE, E_FILE_NOT_FOUND -> ErrorKind.FILE_NOT_FOUND
        E_TOO_MANY_REQUESTS -> ErrorKind.TOO_MANY_REQUESTS
        E_UNSAFE_RESOURCE -> ErrorKind.UNSAFE_SITE
        E_UNKNOWN -> ErrorKind.UNKNOWN
        else -> ErrorKind.UNKNOWN
    }

    private fun webViewCodeName(code: Int): String = when (code) {
        E_UNKNOWN -> "ERROR_UNKNOWN"
        E_HOST_LOOKUP -> "ERROR_HOST_LOOKUP"
        E_UNSUPPORTED_AUTH -> "ERROR_UNSUPPORTED_AUTH_SCHEME"
        E_AUTHENTICATION -> "ERROR_AUTHENTICATION"
        E_PROXY_AUTH -> "ERROR_PROXY_AUTHENTICATION"
        E_CONNECT -> "ERROR_CONNECT"
        E_IO -> "ERROR_IO"
        E_TIMEOUT -> "ERROR_TIMEOUT"
        E_REDIRECT_LOOP -> "ERROR_REDIRECT_LOOP"
        E_UNSUPPORTED_SCHEME -> "ERROR_UNSUPPORTED_SCHEME"
        E_SSL_HANDSHAKE -> "ERROR_FAILED_SSL_HANDSHAKE"
        E_BAD_URL -> "ERROR_BAD_URL"
        E_FILE -> "ERROR_FILE"
        E_FILE_NOT_FOUND -> "ERROR_FILE_NOT_FOUND"
        E_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        E_UNSAFE_RESOURCE -> "ERROR_UNSAFE_RESOURCE"
        else -> "ERROR_$code"
    }

    /* ---------- 通用：按类别出文案（WebView / Gecko 共用） ---------- */

    fun fromKind(kind: ErrorKind, code: String, detail: String = ""): ErrorSpec = when (kind) {
        ErrorKind.OFFLINE -> spec(
            kind, code, detail, tr("网络未连接"), tr("设备当前没有可用的网络。"),
            tr("检查 Wi-Fi 或移动数据是否已开启"), tr("确认没有开启飞行模式"), tr("网络恢复后点「重试」"),
        )
        ErrorKind.NETWORK_CHANGED -> spec(
            kind, code, detail, tr("网络刚刚发生了变化"), tr("加载过程中网络被切换（例如 Wi-Fi 与移动数据之间），连接被中断了。"),
            tr("直接点「重试」，通常即可恢复"), tr("仍然失败时，检查当前网络是否可用"),
        )
        ErrorKind.DNS -> spec(
            kind, code, detail, tr("找不到该网站"), tr("无法解析域名，网址可能拼写有误，或当前网络的 DNS 无法识别它。"),
            tr("检查网址拼写是否正确"), tr("换一个网络（Wi-Fi / 移动数据）再试"), tr("如果开着代理或 VPN，确认它工作正常"), tr("可在系统设置里把私人 DNS 改为 dns.alidns.com 或 1.1.1.1 再试"),
        )
        ErrorKind.TIMEOUT -> spec(
            kind, code, detail, tr("连接超时"), tr("网站太久没有响应。可能是网络较慢，也可能是对方服务器繁忙或在当前网络下无法访问。"),
            tr("检查网络信号后重试"), tr("换一个网络再试"), tr("站点本身故障时，稍后再访问"),
        )
        ErrorKind.CONNECTION_REFUSED -> spec(
            kind, code, detail, tr("网站拒绝了连接"), tr("服务器所在的主机可以到达，但它没有接受这次连接。"),
            tr("网站可能正在维护或已停止服务，稍后再试"), tr("如果访问的是局域网或自建服务，确认服务已启动、端口正确"), tr("检查防火墙或代理设置"),
        )
        ErrorKind.CONNECTION_RESET -> spec(
            kind, code, detail, tr("连接被中断"), tr("连接建立后被意外断开，或服务器没有返回任何数据。"),
            tr("点「重试」再次加载"), tr("网络不稳定时换个网络试试"), tr("反复出现多半是网站或中间网络的问题，稍后再访问"),
        )
        ErrorKind.ADDRESS_UNREACHABLE -> spec(
            kind, code, detail, tr("无法到达该地址"), tr("当前网络没有通往目标地址的路由。"),
            tr("访问局域网地址时，确认手机和设备在同一个网络"), tr("如果是 IPv6 地址，换到支持 IPv6 的网络再试"), tr("检查 VPN 或代理是否阻断了访问"),
        )
        ErrorKind.CERT_DATE -> spec(
            kind, code, detail, tr("连接不安全：证书已过期或尚未生效"), tr("网站证书的有效期与当前设备时间对不上，为保护你的信息，已停止加载。"),
            tr("先检查手机的日期和时间是否正确（建议开启自动设置）"), tr("时间无误则是网站证书过期，需要站长处理"), tr("不要在此类页面输入密码或支付信息"),
            warn = true,
        )
        ErrorKind.CERT_AUTHORITY -> spec(
            kind, code, detail, tr("连接不安全：证书不受信任"), tr("网站的证书不是由受信任的机构签发（可能是自签名证书，或当前网络正在劫持流量）。"),
            tr("公共 Wi-Fi 下出现，请换用可信网络"), tr("自建或内网站点，需先在系统中安装其根证书"), tr("不要在此类页面输入密码或支付信息"),
            warn = true,
        )
        ErrorKind.CERT_NAME -> spec(
            kind, code, detail, tr("连接不安全：证书与网址不匹配"), tr("网站出示的证书不是颁发给你正在访问的这个域名的。"),
            tr("检查网址是否有误，或试试带 / 不带 www 的写法"), tr("通常需要网站管理员修正证书配置"), tr("不要在此类页面输入密码或支付信息"),
            warn = true,
        )
        ErrorKind.CERT_REVOKED -> spec(
            kind, code, detail, tr("连接不安全：证书已被吊销"), tr("签发机构已声明该网站的证书不再可信。"),
            tr("建议不要继续访问此网站"), tr("如果这是你信任的站点，请联系站点管理员"),
            warn = true,
        )
        ErrorKind.CERT_OTHER -> spec(
            kind, code, detail, tr("连接不安全：证书存在问题"), tr("无法验证该网站证书的有效性，为保护你的信息，已停止加载。"),
            tr("换一个网络再试，排除网络劫持"), tr("不要在此类页面输入密码或支付信息"),
            warn = true,
        )
        ErrorKind.SSL_PROTOCOL -> spec(
            kind, code, detail, tr("无法建立安全连接"), tr("与网站协商加密连接时失败，可能是对方使用了过旧的加密设置，或网络干扰了握手。"),
            tr("检查设备时间是否准确"), tr("换一个网络再试"), tr("持续失败说明网站自身配置有问题，需要站长处理"),
        )
        ErrorKind.HTTPS_ONLY -> spec(
            kind, code, detail, tr("该网站不支持安全连接"), tr("已开启「仅 HTTPS」，而这个网站无法通过 HTTPS 访问，因此没有加载。"),
            tr("确认网址无误"), tr("如果明确信任该站点，可在设置中关闭仅 HTTPS 模式后再访问"),
        )
        ErrorKind.PROXY -> spec(
            kind, code, detail, tr("代理服务器无响应"), tr("已配置的代理无法连接，网页请求没能发出去。"),
            tr("检查代理地址和端口是否填写正确"), tr("确认代理服务正在运行"), tr("暂时关闭代理后重试"),
        )
        ErrorKind.PROXY_AUTH -> spec(
            kind, code, detail, tr("代理需要身份验证"), tr("代理服务器要求验证身份，但验证未通过或不被支持。"),
            tr("检查代理的用户名和密码"), tr("联系代理服务提供方"),
        )
        ErrorKind.AUTH -> spec(
            kind, code, detail, tr("需要身份验证"), tr("网站要求登录验证，但验证失败或使用了不支持的验证方式。"),
            tr("确认账号和密码是否正确"), tr("改用网站提供的网页登录入口"),
        )
        ErrorKind.TOO_MANY_REDIRECTS -> spec(
            kind, code, detail, tr("重定向次数过多"), tr("网站不断把你转到别的地址，形成了循环。"),
            tr("清除该网站的 Cookie 后重试"), tr("如果刚改过 HTTPS / 代理设置，也可能由此引起"), tr("仍然出现则是网站配置错误，需站长处理"),
        )
        ErrorKind.BAD_URL -> spec(
            kind, code, detail, tr("网址无效"), tr("这个地址的格式不正确，无法访问。"),
            tr("检查网址中是否有多余的空格、中文标点或漏写的字符"), tr("从搜索结果或书签重新进入"),
        )
        ErrorKind.UNSUPPORTED_SCHEME -> spec(
            kind, code, detail, tr("无法打开这类链接"), tr("这个链接使用了浏览器不支持的协议，也没有找到能处理它的应用。"),
            tr("确认已安装对应的应用"), tr("向链接提供方索取网页版地址"),
        )
        ErrorKind.FILE_NOT_FOUND -> spec(
            kind, code, detail, tr("找不到文件"), tr("要打开的文件不存在、已被移动，或没有读取权限。"),
            tr("确认文件仍在原位置"), tr("检查应用的存储权限"),
        )
        ErrorKind.BLOCKED -> spec(
            kind, code, detail, tr("访问被阻止"), tr("这次请求被拦截了，可能来自广告拦截规则、网站自身的安全策略，或设备管理策略。"),
            tr("如果是广告拦截造成的，可在拦截设置中对该站点放行"), tr("若设备由单位或家长管理，请联系管理员"), tr("也可能是网站只允许特定地区或客户端访问"),
        )
        ErrorKind.TOO_MANY_REQUESTS -> spec(
            kind, code, detail, tr("请求过于频繁"), tr("短时间内发出的请求太多，被暂时限制了。"),
            tr("等待几分钟后再重试"), tr("避免连续多次刷新"),
        )
        ErrorKind.UNSAFE_SITE -> spec(
            kind, code, detail, tr("已拦截不安全的网站"), tr("该网站被安全浏览服务标记为可能含有钓鱼、恶意软件或有害内容。"),
            tr("建议不要继续访问"), tr("如果你确认它是误报，请通过其它渠道联系站点方"),
            warn = true,
        )
        ErrorKind.BAD_CONTENT -> spec(
            kind, code, detail, tr("网站返回的数据异常"), tr("收到的内容不完整或格式不对，浏览器无法正确解析。"),
            tr("点「重试」再次加载"), tr("清除该网站的缓存后重试"), tr("反复出现多为服务器故障，稍后再访问"),
        )
        ErrorKind.PORT_BLOCKED -> spec(
            kind, code, detail, tr("该端口已被限制访问"), tr("出于安全考虑，浏览器不允许访问这个端口。"),
            tr("检查网址中的端口号是否正确"), tr("把服务改到常用端口（如 80、443、8080）"),
        )
        ErrorKind.CRASHED -> spec(
            kind, code, detail, tr("页面崩溃了"), tr("渲染这个页面的进程意外退出，可能是内存不足或页面本身有问题。"),
            tr("关闭其它标签页或应用，释放内存"), tr("点「重试」重新加载"),
        )
        ErrorKind.FINGERPRINT -> spec(
            kind, code, detail, tr("指纹保护已拦截加载"), tr("该页面未能在加载前完成指纹保护的注册，为避免暴露设备特征，已停止加载。"),
            tr("点「重试」重新加载"), tr("仍然失败时，可在隐私设置中调整指纹保护策略"),
        )
        ErrorKind.UNKNOWN -> spec(
            kind, code, detail, tr("页面加载失败"), tr("出现了未知的网络错误。"),
            tr("检查网络连接后重试"), tr("换一个网络再试"), tr("问题持续时，可把下方的技术详情反馈给开发者"),
        )
    }

    private fun spec(
        kind: ErrorKind, code: String, detail: String, title: String, summary: String,
        vararg tips: String, warn: Boolean = false,
    ) = ErrorSpec(kind, code, title, summary, tips.toList(), detail, warn, canProceed = isOverridable(kind))

    /**
     * 只有「站点自己的证书问题」可以让用户自担风险继续：过期 / 不受信任（自签名）/ 域名不匹配 / 其它验证失败。
     * 已吊销、安全浏览拦截、协议握手失败不给入口（继续也不会成功，或风险明确）。
     */
    fun isOverridable(kind: ErrorKind?): Boolean =
        kind == ErrorKind.CERT_DATE || kind == ErrorKind.CERT_AUTHORITY ||
            kind == ErrorKind.CERT_NAME || kind == ErrorKind.CERT_OTHER

    /** 错误页里「继续访问」链接的内部地址；不带任何参数，真正的目标由标签页记住的失败地址决定，页面无法伪造。 */
    const val PROCEED_URL = "mafari-error://proceed"

    fun isProceedUrl(url: String?): Boolean = url != null && url.equals(PROCEED_URL, ignoreCase = true)

    /** 去掉 canProceed（已经放行过一次仍失败，例如 HSTS 站点，不再重复给入口）。 */
    fun withoutProceed(spec: ErrorSpec): ErrorSpec = if (spec.canProceed) spec.copy(canProceed = false) else spec

    /** 局域网 / 本机 / IP 字面量：自签名证书很常见，错误页里给一句更贴切的说明。 */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().trim('[', ']').lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || !h.contains('.') && !h.contains(':')) return true
        if (h.contains(':')) return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd") // IPv6 本机 / 链路本地 / ULA
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
    }

    /** 指纹保护兜底拦截（不是网络错误，没有错误码）。 */
    fun fingerprintBlocked(): ErrorSpec = fromKind(ErrorKind.FINGERPRINT, "FINGERPRINT_PROTECTION")

    /** 渲染进程崩溃。 */
    fun crashed(code: String = "RENDERER_GONE"): ErrorSpec = fromKind(ErrorKind.CRASHED, code)

    /* ---------- HTTP 状态码 ---------- */

    /** 只在 [status] >= 400 时有意义；其它值回落到通用 4xx / 5xx 文案。 */
    fun fromHttp(status: Int, reason: String? = null): ErrorSpec {
        val detail = buildString {
            append("HTTP ").append(status)
            if (!reason.isNullOrBlank()) append(' ').append(reason)
        }
        fun h(title: String, summary: String, vararg tips: String) =
            ErrorSpec(null, status.toString(), title, summary, tips.toList(), detail)
        return when (status) {
            400 -> h(tr("请求有误"), tr("服务器无法理解这次请求。"), tr("检查网址里是否有异常字符"), tr("清除该网站的 Cookie 后重试"))
            401 -> h(tr("需要登录"), tr("访问这个页面需要先验证身份。"), tr("登录账号后再访问"), tr("如果已登录，登录状态可能已过期，请重新登录"))
            402 -> h(tr("需要付费"), tr("该内容需要付费后才能访问。"), tr("确认账号的订阅状态"))
            403 -> h(tr("没有访问权限"), tr("服务器拒绝了这次访问。"), tr("确认账号是否有权限"), tr("部分网站会限制地区、网络或浏览器，换个网络试试"), tr("如果开着代理，换一个出口节点再试"))
            404 -> h(tr("页面不存在"), tr("网站上没有找到这个页面，链接可能已失效，或网址输入有误。"), tr("检查网址拼写"), tr("回到网站首页，从导航重新查找"), tr("也可以搜索页面标题"))
            405 -> h(tr("不支持这种请求方式"), tr("服务器不允许对这个地址使用当前的请求方式。"), tr("返回上一页，从网站内的入口重新进入"))
            406, 415 -> h(tr("服务器不接受这种请求"), tr("服务器无法按当前请求的格式提供内容。"), tr("点「重试」"), tr("换一个页面入口再试"))
            408 -> h(tr("请求超时"), tr("服务器等了太久没有收到完整请求。"), tr("检查网络后重试"), tr("换一个网络再试"))
            409 -> h(tr("请求冲突"), tr("这次操作与服务器上的当前状态冲突。"), tr("刷新页面后重试"))
            410 -> h(tr("页面已被永久删除"), tr("该内容已被移除，并且不会再恢复。"), tr("回到网站首页查找相关内容"), tr("如果是书签，可以删除它"))
            413, 414, 431 -> h(tr("请求内容过长"), tr("地址或请求数据太长，服务器无法处理。"), tr("缩短网址或减少提交的内容"), tr("清除该网站的 Cookie 后重试"))
            418 -> h(tr("服务器不想泡咖啡"), tr("服务器返回了 418（我是茶壶）。这通常是网站的彩蛋或反爬机制。"), tr("稍后再试"), tr("换个网络访问"))
            421, 422 -> h(tr("服务器无法处理请求"), tr("服务器无法完成这次请求。"), tr("刷新页面后重试"))
            429 -> h(tr("请求过于频繁"), tr("短时间内访问次数过多，被网站暂时限制了。"), tr("等几分钟再访问"), tr("避免连续刷新"))
            451 -> h(tr("因法律原因无法访问"), tr("由于法律或监管要求，该内容在当前地区不可用。"), tr("这不是网络故障，刷新也无济于事"))
            500 -> h(tr("网站出了点问题"), tr("网站服务器内部出错，这不是你的网络或设备的问题。"), tr("稍后再试"), tr("问题持续则只能等站点方修复"))
            501 -> h(tr("网站不支持此功能"), tr("服务器没有实现完成这次请求所需的功能。"), tr("返回上一页，换一种方式访问"))
            502 -> h(tr("网关错误"), tr("网站的上游服务器返回了无效的响应，通常是后端故障或正在重启。"), tr("过几分钟再试"), tr("站点方修复前，刷新通常没有用"))
            503 -> h(tr("网站暂时不可用"), tr("服务器正在维护或负载过高，暂时无法处理请求。"), tr("稍后再访问"), tr("站点公告页或社交账号上也许有恢复时间"))
            504 -> h(tr("网关超时"), tr("网站的上游服务器响应太慢，请求超时了。"), tr("稍后再试"), tr("换个网络再试"))
            505 -> h(tr("协议版本不受支持"), tr("服务器不支持当前使用的 HTTP 版本。"), tr("稍后再试"))
            507, 508 -> h(tr("服务器资源异常"), tr("服务器存储空间不足，或在处理时陷入了循环。"), tr("这是站点方的问题，稍后再试"))
            520, 521, 522, 523, 524, 525, 526, 530 -> h(
                tr("网站背后的服务器没有正常响应"),
                tr("网站使用了 CDN，但 CDN 无法从源服务器取得正常内容（可能是源站宕机、超时或证书异常）。"),
                tr("稍后再试"), tr("这是站点方的问题，刷新通常没有用"),
            )
            in 400..499 -> h(tr("访问被服务器拒绝"), tr("服务器认为这次请求有问题，没有返回页面。"), tr("检查网址是否正确"), tr("必要时重新登录"), tr("换个网络再试"))
            in 500..599 -> h(tr("网站出了点问题"), tr("服务器出错，暂时无法提供这个页面。"), tr("稍后再试"), tr("问题持续则只能等站点方修复"))
            else -> h(tr("页面加载失败"), tr("服务器返回了异常的状态。"), tr("点「重试」重新加载"))
        }
    }

    /** 主框架 HTTP 错误：只有响应体为空时才用自定义页（有正文的 404 / 500 是网站自己的页面，应该照常展示）。 */
    fun shouldReplaceHttpError(status: Int, headers: Map<String, String>?): Boolean {
        if (status < 400) return false
        val len = headers?.entries?.firstOrNull { it.key.equals("content-length", true) }?.value?.trim()
        return len == "0"
    }

    /* ---------- 渲染 ---------- */

    fun esc(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    private fun hostOfUrl(url: String): String =
        Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://(?:[^/?#@]*@)?(\[[^\]]*\]|[^/?#:]*)""").find(url)?.groupValues?.get(1).orEmpty()

    /**
     * 渲染成自包含 HTML。[failingUrl] 用于「重试」与展示域名；[dark] 是应用当前的深浅色。
     * 页面不含脚本：重试是一个普通链接，详情用 <details>。
     * [spec].canProceed 时多一个折叠的「高级」区：说明风险 + 「继续访问（不安全）」链接；[nativeProceed] 表示
     * 该链接会转到内核自带的证书页（Gecko）而不是直接放行（WebView）。
     */
    fun render(spec: ErrorSpec, failingUrl: String, dark: Boolean, nativeProceed: Boolean = false): String {
        val bg = if (dark) "#1A1A1A" else "#FFFFFF"
        val fg = if (dark) "#E8E8E8" else "#222222"
        val sub = if (dark) "#A0A0A0" else "#6B6B6B"
        val card = if (dark) "#262626" else "#F4F5F7"
        val accent = if (spec.warn) "#E5484D" else "#3B7DFF"
        val host = hostOfUrl(failingUrl)
        val safeUrl = esc(failingUrl)
        val hostLine = if (host.isNotEmpty()) """<div class="host">${esc(host)}</div>""" else ""
        val tips = spec.tips.joinToString("") { "<li>${esc(it)}</li>" }
        val detail = buildString {
            append(esc(spec.code))
            if (spec.detail.isNotBlank()) append("\n").append(esc(spec.detail))
            if (failingUrl.isNotBlank()) append("\n").append(esc(failingUrl.take(300)))
        }
        val proceed = if (spec.canProceed) {
            val lan = isPrivateHost(host)
            val note = when {
                lan -> tr("这是局域网 / 本机地址，路由器、NAS 等自建设备常用自签名证书；确认是自己的设备即可继续。")
                else -> tr("继续访问后，你与该网站之间的通信可能被窃听或篡改。请勿输入密码、验证码或支付信息。")
            }
            val extra = if (nativeProceed) tr("下一步会打开内核自带的证书页，在那里确认「接受风险并继续」。") else tr("仅对该网站在本次运行期间有效，关闭应用后失效。")
            val label = if (nativeProceed) tr("继续（查看证书详情）") else tr("忽略风险，继续访问（不安全）")
            """<details class="adv"><summary>${tr("高级")}</summary><p>${esc(note)}</p><p>${esc(extra)}</p>
            <a class="proceed" href="$PROCEED_URL">${esc(label)}</a></details>"""
        } else ""
        return """
            <!DOCTYPE html><html lang="${dev.dsh.miuixbrowser.i18n.I18n.htmlLang()}"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${esc(spec.title)}</title>
            <style>
            *{box-sizing:border-box}
            body{margin:0;min-height:100vh;background:$bg;color:$fg;font-family:-apple-system,"Roboto","Noto Sans SC",sans-serif;
              display:flex;flex-direction:column;align-items:center;justify-content:center;padding:32px 24px;text-align:center}
            .badge{width:56px;height:56px;border-radius:50%;background:$accent;color:#fff;font-size:30px;font-weight:700;
              line-height:56px;margin-bottom:18px}
            h1{font-size:20px;margin:0 0 6px;font-weight:600}
            .host{font-size:13px;color:$sub;margin-bottom:12px;word-break:break-all}
            .sum{font-size:14px;line-height:1.6;color:$sub;max-width:420px;margin:0 0 18px}
            ul{list-style:none;margin:0 0 22px;padding:12px 16px;background:$card;border-radius:14px;max-width:420px;width:100%;
              text-align:left;font-size:13.5px;line-height:1.6}
            li{padding-left:16px;position:relative;margin:4px 0}
            li:before{content:"";position:absolute;left:2px;top:.72em;width:5px;height:5px;border-radius:50%;background:$accent}
            .btn{display:inline-block;padding:11px 36px;border-radius:24px;background:$accent;color:#fff;text-decoration:none;font-size:15px}
            details{margin-top:22px;max-width:420px;width:100%;font-size:12px;color:$sub}
            summary{cursor:pointer;list-style:none}
            .adv{margin-top:18px}
            .adv p{margin:8px 0;line-height:1.6}
            .proceed{display:inline-block;margin-top:4px;padding:9px 22px;border-radius:20px;border:1px solid #E5484D;color:#E5484D;text-decoration:none;font-size:13.5px}
            pre{white-space:pre-wrap;word-break:break-all;text-align:left;background:$card;border-radius:10px;padding:10px 12px;margin:8px 0 0;
              font-family:ui-monospace,Menlo,monospace;font-size:11.5px}
            </style></head><body>
            <div class="badge">!</div>
            <h1>${esc(spec.title)}</h1>
            $hostLine
            <p class="sum">${esc(spec.summary)}</p>
            <ul>$tips</ul>
            <a class="btn" href="$safeUrl">${tr("重试")}</a>
            $proceed
            <details><summary>${tr("技术详情")}</summary><pre>$detail</pre></details>
            </body></html>
        """.trimIndent()
    }
}
