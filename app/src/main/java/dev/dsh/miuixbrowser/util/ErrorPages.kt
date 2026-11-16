package dev.dsh.miuixbrowser.util

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
            kind, code, detail, "网络未连接", "设备当前没有可用的网络。",
            "检查 Wi-Fi 或移动数据是否已开启", "确认没有开启飞行模式", "网络恢复后点「重试」",
        )
        ErrorKind.NETWORK_CHANGED -> spec(
            kind, code, detail, "网络刚刚发生了变化", "加载过程中网络被切换（例如 Wi-Fi 与移动数据之间），连接被中断了。",
            "直接点「重试」，通常即可恢复", "仍然失败时，检查当前网络是否可用",
        )
        ErrorKind.DNS -> spec(
            kind, code, detail, "找不到该网站", "无法解析域名，网址可能拼写有误，或当前网络的 DNS 无法识别它。",
            "检查网址拼写是否正确", "换一个网络（Wi-Fi / 移动数据）再试", "如果开着代理或 VPN，确认它工作正常", "可在系统设置里把私人 DNS 改为 dns.alidns.com 或 1.1.1.1 再试",
        )
        ErrorKind.TIMEOUT -> spec(
            kind, code, detail, "连接超时", "网站太久没有响应。可能是网络较慢，也可能是对方服务器繁忙或在当前网络下无法访问。",
            "检查网络信号后重试", "换一个网络再试", "站点本身故障时，稍后再访问",
        )
        ErrorKind.CONNECTION_REFUSED -> spec(
            kind, code, detail, "网站拒绝了连接", "服务器所在的主机可以到达，但它没有接受这次连接。",
            "网站可能正在维护或已停止服务，稍后再试", "如果访问的是局域网或自建服务，确认服务已启动、端口正确", "检查防火墙或代理设置",
        )
        ErrorKind.CONNECTION_RESET -> spec(
            kind, code, detail, "连接被中断", "连接建立后被意外断开，或服务器没有返回任何数据。",
            "点「重试」再次加载", "网络不稳定时换个网络试试", "反复出现多半是网站或中间网络的问题，稍后再访问",
        )
        ErrorKind.ADDRESS_UNREACHABLE -> spec(
            kind, code, detail, "无法到达该地址", "当前网络没有通往目标地址的路由。",
            "访问局域网地址时，确认手机和设备在同一个网络", "如果是 IPv6 地址，换到支持 IPv6 的网络再试", "检查 VPN 或代理是否阻断了访问",
        )
        ErrorKind.CERT_DATE -> spec(
            kind, code, detail, "连接不安全：证书已过期或尚未生效", "网站证书的有效期与当前设备时间对不上，为保护你的信息，已停止加载。",
            "先检查手机的日期和时间是否正确（建议开启自动设置）", "时间无误则是网站证书过期，需要站长处理", "不要在此类页面输入密码或支付信息",
            warn = true,
        )
        ErrorKind.CERT_AUTHORITY -> spec(
            kind, code, detail, "连接不安全：证书不受信任", "网站的证书不是由受信任的机构签发（可能是自签名证书，或当前网络正在劫持流量）。",
            "公共 Wi-Fi 下出现，请换用可信网络", "自建或内网站点，需先在系统中安装其根证书", "不要在此类页面输入密码或支付信息",
            warn = true,
        )
        ErrorKind.CERT_NAME -> spec(
            kind, code, detail, "连接不安全：证书与网址不匹配", "网站出示的证书不是颁发给你正在访问的这个域名的。",
            "检查网址是否有误，或试试带 / 不带 www 的写法", "通常需要网站管理员修正证书配置", "不要在此类页面输入密码或支付信息",
            warn = true,
        )
        ErrorKind.CERT_REVOKED -> spec(
            kind, code, detail, "连接不安全：证书已被吊销", "签发机构已声明该网站的证书不再可信。",
            "建议不要继续访问此网站", "如果这是你信任的站点，请联系站点管理员",
            warn = true,
        )
        ErrorKind.CERT_OTHER -> spec(
            kind, code, detail, "连接不安全：证书存在问题", "无法验证该网站证书的有效性，为保护你的信息，已停止加载。",
            "换一个网络再试，排除网络劫持", "不要在此类页面输入密码或支付信息",
            warn = true,
        )
        ErrorKind.SSL_PROTOCOL -> spec(
            kind, code, detail, "无法建立安全连接", "与网站协商加密连接时失败，可能是对方使用了过旧的加密设置，或网络干扰了握手。",
            "检查设备时间是否准确", "换一个网络再试", "持续失败说明网站自身配置有问题，需要站长处理",
        )
        ErrorKind.HTTPS_ONLY -> spec(
            kind, code, detail, "该网站不支持安全连接", "已开启「仅 HTTPS」，而这个网站无法通过 HTTPS 访问，因此没有加载。",
            "确认网址无误", "如果明确信任该站点，可在设置中关闭仅 HTTPS 模式后再访问",
        )
        ErrorKind.PROXY -> spec(
            kind, code, detail, "代理服务器无响应", "已配置的代理无法连接，网页请求没能发出去。",
            "检查代理地址和端口是否填写正确", "确认代理服务正在运行", "暂时关闭代理后重试",
        )
        ErrorKind.PROXY_AUTH -> spec(
            kind, code, detail, "代理需要身份验证", "代理服务器要求验证身份，但验证未通过或不被支持。",
            "检查代理的用户名和密码", "联系代理服务提供方",
        )
        ErrorKind.AUTH -> spec(
            kind, code, detail, "需要身份验证", "网站要求登录验证，但验证失败或使用了不支持的验证方式。",
            "确认账号和密码是否正确", "改用网站提供的网页登录入口",
        )
        ErrorKind.TOO_MANY_REDIRECTS -> spec(
            kind, code, detail, "重定向次数过多", "网站不断把你转到别的地址，形成了循环。",
            "清除该网站的 Cookie 后重试", "如果刚改过 HTTPS / 代理设置，也可能由此引起", "仍然出现则是网站配置错误，需站长处理",
        )
        ErrorKind.BAD_URL -> spec(
            kind, code, detail, "网址无效", "这个地址的格式不正确，无法访问。",
            "检查网址中是否有多余的空格、中文标点或漏写的字符", "从搜索结果或书签重新进入",
        )
        ErrorKind.UNSUPPORTED_SCHEME -> spec(
            kind, code, detail, "无法打开这类链接", "这个链接使用了浏览器不支持的协议，也没有找到能处理它的应用。",
            "确认已安装对应的应用", "向链接提供方索取网页版地址",
        )
        ErrorKind.FILE_NOT_FOUND -> spec(
            kind, code, detail, "找不到文件", "要打开的文件不存在、已被移动，或没有读取权限。",
            "确认文件仍在原位置", "检查应用的存储权限",
        )
        ErrorKind.BLOCKED -> spec(
            kind, code, detail, "访问被阻止", "这次请求被拦截了，可能来自广告拦截规则、网站自身的安全策略，或设备管理策略。",
            "如果是广告拦截造成的，可在拦截设置中对该站点放行", "若设备由单位或家长管理，请联系管理员", "也可能是网站只允许特定地区或客户端访问",
        )
        ErrorKind.TOO_MANY_REQUESTS -> spec(
            kind, code, detail, "请求过于频繁", "短时间内发出的请求太多，被暂时限制了。",
            "等待几分钟后再重试", "避免连续多次刷新",
        )
        ErrorKind.UNSAFE_SITE -> spec(
            kind, code, detail, "已拦截不安全的网站", "该网站被安全浏览服务标记为可能含有钓鱼、恶意软件或有害内容。",
            "建议不要继续访问", "如果你确认它是误报，请通过其它渠道联系站点方",
            warn = true,
        )
        ErrorKind.BAD_CONTENT -> spec(
            kind, code, detail, "网站返回的数据异常", "收到的内容不完整或格式不对，浏览器无法正确解析。",
            "点「重试」再次加载", "清除该网站的缓存后重试", "反复出现多为服务器故障，稍后再访问",
        )
        ErrorKind.PORT_BLOCKED -> spec(
            kind, code, detail, "该端口已被限制访问", "出于安全考虑，浏览器不允许访问这个端口。",
            "检查网址中的端口号是否正确", "把服务改到常用端口（如 80、443、8080）",
        )
        ErrorKind.CRASHED -> spec(
            kind, code, detail, "页面崩溃了", "渲染这个页面的进程意外退出，可能是内存不足或页面本身有问题。",
            "关闭其它标签页或应用，释放内存", "点「重试」重新加载",
        )
        ErrorKind.FINGERPRINT -> spec(
            kind, code, detail, "指纹保护已拦截加载", "该页面未能在加载前完成指纹保护的注册，为避免暴露设备特征，已停止加载。",
            "点「重试」重新加载", "仍然失败时，可在隐私设置中调整指纹保护策略",
        )
        ErrorKind.UNKNOWN -> spec(
            kind, code, detail, "页面加载失败", "出现了未知的网络错误。",
            "检查网络连接后重试", "换一个网络再试", "问题持续时，可把下方的技术详情反馈给开发者",
        )
    }

    private fun spec(
        kind: ErrorKind, code: String, detail: String, title: String, summary: String,
        vararg tips: String, warn: Boolean = false,
    ) = ErrorSpec(kind, code, title, summary, tips.toList(), detail, warn)

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
            400 -> h("请求有误", "服务器无法理解这次请求。", "检查网址里是否有异常字符", "清除该网站的 Cookie 后重试")
            401 -> h("需要登录", "访问这个页面需要先验证身份。", "登录账号后再访问", "如果已登录，登录状态可能已过期，请重新登录")
            402 -> h("需要付费", "该内容需要付费后才能访问。", "确认账号的订阅状态")
            403 -> h("没有访问权限", "服务器拒绝了这次访问。", "确认账号是否有权限", "部分网站会限制地区、网络或浏览器，换个网络试试", "如果开着代理，换一个出口节点再试")
            404 -> h("页面不存在", "网站上没有找到这个页面，链接可能已失效，或网址输入有误。", "检查网址拼写", "回到网站首页，从导航重新查找", "也可以搜索页面标题")
            405 -> h("不支持这种请求方式", "服务器不允许对这个地址使用当前的请求方式。", "返回上一页，从网站内的入口重新进入")
            406, 415 -> h("服务器不接受这种请求", "服务器无法按当前请求的格式提供内容。", "点「重试」", "换一个页面入口再试")
            408 -> h("请求超时", "服务器等了太久没有收到完整请求。", "检查网络后重试", "换一个网络再试")
            409 -> h("请求冲突", "这次操作与服务器上的当前状态冲突。", "刷新页面后重试")
            410 -> h("页面已被永久删除", "该内容已被移除，并且不会再恢复。", "回到网站首页查找相关内容", "如果是书签，可以删除它")
            413, 414, 431 -> h("请求内容过长", "地址或请求数据太长，服务器无法处理。", "缩短网址或减少提交的内容", "清除该网站的 Cookie 后重试")
            418 -> h("服务器不想泡咖啡", "服务器返回了 418（我是茶壶）。这通常是网站的彩蛋或反爬机制。", "稍后再试", "换个网络访问")
            421, 422 -> h("服务器无法处理请求", "服务器无法完成这次请求。", "刷新页面后重试")
            429 -> h("请求过于频繁", "短时间内访问次数过多，被网站暂时限制了。", "等几分钟再访问", "避免连续刷新")
            451 -> h("因法律原因无法访问", "由于法律或监管要求，该内容在当前地区不可用。", "这不是网络故障，刷新也无济于事")
            500 -> h("网站出了点问题", "网站服务器内部出错，这不是你的网络或设备的问题。", "稍后再试", "问题持续则只能等站点方修复")
            501 -> h("网站不支持此功能", "服务器没有实现完成这次请求所需的功能。", "返回上一页，换一种方式访问")
            502 -> h("网关错误", "网站的上游服务器返回了无效的响应，通常是后端故障或正在重启。", "过几分钟再试", "站点方修复前，刷新通常没有用")
            503 -> h("网站暂时不可用", "服务器正在维护或负载过高，暂时无法处理请求。", "稍后再访问", "站点公告页或社交账号上也许有恢复时间")
            504 -> h("网关超时", "网站的上游服务器响应太慢，请求超时了。", "稍后再试", "换个网络再试")
            505 -> h("协议版本不受支持", "服务器不支持当前使用的 HTTP 版本。", "稍后再试")
            507, 508 -> h("服务器资源异常", "服务器存储空间不足，或在处理时陷入了循环。", "这是站点方的问题，稍后再试")
            520, 521, 522, 523, 524, 525, 526, 530 -> h(
                "网站背后的服务器没有正常响应",
                "网站使用了 CDN，但 CDN 无法从源服务器取得正常内容（可能是源站宕机、超时或证书异常）。",
                "稍后再试", "这是站点方的问题，刷新通常没有用",
            )
            in 400..499 -> h("访问被服务器拒绝", "服务器认为这次请求有问题，没有返回页面。", "检查网址是否正确", "必要时重新登录", "换个网络再试")
            in 500..599 -> h("网站出了点问题", "服务器出错，暂时无法提供这个页面。", "稍后再试", "问题持续则只能等站点方修复")
            else -> h("页面加载失败", "服务器返回了异常的状态。", "点「重试」重新加载")
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
     */
    fun render(spec: ErrorSpec, failingUrl: String, dark: Boolean): String {
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
        return """
            <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
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
            pre{white-space:pre-wrap;word-break:break-all;text-align:left;background:$card;border-radius:10px;padding:10px 12px;margin:8px 0 0;
              font-family:ui-monospace,Menlo,monospace;font-size:11.5px}
            </style></head><body>
            <div class="badge">!</div>
            <h1>${esc(spec.title)}</h1>
            $hostLine
            <p class="sum">${esc(spec.summary)}</p>
            <ul>$tips</ul>
            <a class="btn" href="$safeUrl">重试</a>
            <details><summary>技术详情</summary><pre>$detail</pre></details>
            </body></html>
        """.trimIndent()
    }
}
