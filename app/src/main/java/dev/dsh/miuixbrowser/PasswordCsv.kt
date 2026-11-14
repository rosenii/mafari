package dev.dsh.miuixbrowser

/*
 * 密码的纯 JVM 部分（不依赖 Android，可直接单元测试）：数据模型 + 其他浏览器 / 密码管理器导出的 CSV 解析。
 *
 * 支持的来源（按表头自动识别，大小写 / 顺序 / 多余列都不影响）：
 *   Chrome / Edge / Brave / Opera / Vivaldi   name,url,username,password,note
 *   Firefox（about:logins 导出）               url,username,password,httpRealm,formActionOrigin,guid,timeCreated,timeLastUsed,timePasswordChanged
 *   Safari / iCloud 钥匙串                      Title,URL,Username,Password,Notes,OTPAuth
 *   Bitwarden                                  folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp
 *   1Password                                  Title,Url,Username,Password,Notes,Type
 *   LastPass                                   url,username,password,totp,extra,name,grouping,fav
 *   KeePass / KeePassXC                        Title,Username,Password,URL,Notes（或 Account,Login Name,Password,Web Site,Comments）
 *   Dashlane                                   title,website,username,password,note
 * 不在名单里的表头只要含「网址 + 密码」两列也能导入（见 [PasswordCsv.COLUMN_ALIASES]）。
 */

/** 一条已保存的登录凭据。字段与 Firefox 同步的 passwords 记录一一对应（时间均为毫秒）。 */
data class PasswordEntry(
    /** 记录 id：本地生成 / 导入文件里带的（Firefox 的 guid）。同时是 Firefox 同步记录的 id。 */
    val guid: String,
    /** 站点来源：scheme://host[:port]，用于匹配页面（Firefox 的 hostname）。 */
    val origin: String,
    /** 表单提交目标来源（Firefox 的 formSubmitURL）；null = 不限（任意表单可用）。与 [httpRealm] 互斥。 */
    val formOrigin: String? = null,
    /** HTTP 认证的 realm；非 null 表示这是 HTTP Basic / Digest 认证凭据，而不是网页表单。 */
    val httpRealm: String? = null,
    val username: String,
    val password: String,
    val usernameField: String = "",
    val passwordField: String = "",
    /** 备注（Firefox 同步记录没有此字段，仅本地保存；CSV 导入 / 将来导出时带上）。 */
    val note: String = "",
    val timeCreated: Long = 0L,
    val timeLastUsed: Long = 0L,
    val timePasswordChanged: Long = 0L,
    val timesUsed: Int = 0,
)

object PasswordCsv {

    /** 单个文件的上限，超出直接拒绝（防止误选巨大文件把内存撑爆）。 */
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_ROWS = 50_000
    const val MAX_FIELD = 4096
    const val MAX_NOTE = 16 * 1024

    /** 解析结果：[entries] 为有效记录；[skipped] 为被丢弃的行（缺网址 / 缺密码 / 网址不合法等）。 */
    class Result(
        val format: String,
        val entries: List<PasswordEntry>,
        val skipped: Int,
        val totalRows: Int,
    )

    class FormatException(message: String) : Exception(message)

    /** 列名 → 规范列。键一律小写、去掉空格 / 下划线 / 连字符后匹配。 */
    private val COLUMN_ALIASES: Map<String, List<String>> = mapOf(
        "url" to listOf("url", "origin", "website", "weburl", "site", "loginuri", "loginurl", "hostname", "address", "uri"),
        "username" to listOf("username", "user", "login", "loginname", "loginusername", "email", "userid"),
        "password" to listOf("password", "loginpassword", "pass", "pwd"),
        "note" to listOf("note", "notes", "comment", "comments", "extra", "memo"),
        "name" to listOf("name", "title", "label"),
        "realm" to listOf("httprealm", "realm"),
        "formorigin" to listOf("formactionorigin", "formsubmiturl", "formurl", "action"),
        "guid" to listOf("guid", "id"),
        "created" to listOf("timecreated", "created", "createdat", "datecreated"),
        "lastused" to listOf("timelastused", "lastused", "lastusedat"),
        "changed" to listOf("timepasswordchanged", "passwordchanged", "modified", "updated", "lastmodified", "dateupdated"),
    )

    private val aliasIndex: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        // 先登记的优先（url 在 name 之前，避免 website 之类歧义）
        for ((canon, names) in COLUMN_ALIASES) for (n in names) m.putIfAbsent(n, canon)
        m
    }

    private fun normHeader(h: String) =
        h.trim().trimStart('﻿').lowercase().filter { it != ' ' && it != '_' && it != '-' }

    /** 先看文件长什么样：用于界面提示「识别为 xxx」。表头不合格返回 null。 */
    fun detectFormat(headerRow: List<String>): String? {
        val h = headerRow.map { normHeader(it) }.toSet()
        fun has(vararg k: String) = k.all { it in h }
        return when {
            has("loginuri", "loginusername", "loginpassword") -> "Bitwarden"
            has("httprealm", "formactionorigin") || (has("guid", "timecreated", "timepasswordchanged")) -> "Firefox"
            has("otpauth") -> "Safari / iCloud 钥匙串"
            has("grouping", "fav") && has("url") -> "LastPass"
            has("type") && has("title") && has("url") -> "1Password"
            has("loginname", "website") || has("account", "loginname") -> "KeePass"
            has("website", "title") -> "Dashlane"
            has("name", "url", "username", "password") -> "Chrome / Edge"
            has("url", "password") || has("website", "password") -> "通用 CSV"
            else -> null
        }
    }

    /**
     * 解析 CSV 文本。表头不含「网址 + 密码」列时抛 [FormatException]（不是密码文件，别当成空文件悄悄吞掉）。
     * 同一文件里重复的（来源 + 用户名 + 密码完全一致）只留一条。
     */
    fun parse(text: String): Result {
        val rows = parseRows(text)
        if (rows.isEmpty()) throw FormatException("文件是空的")
        val header = rows[0]
        val format = detectFormat(header) ?: throw FormatException("无法识别表头：需要至少包含「网址」和「密码」两列")

        // 规范列 → 列下标（同一规范列出现多次时取第一个）
        val col = HashMap<String, Int>()
        header.forEachIndexed { i, raw ->
            val canon = aliasIndex[normHeader(raw)] ?: return@forEachIndexed
            col.putIfAbsent(canon, i)
        }
        // Bitwarden 的 type 列（login / note / card / identity）：只收 login
        val typeIdx = header.indexOfFirst { normHeader(it) == "type" }
        val bitwarden = format == "Bitwarden"
        if (col["url"] == null || col["password"] == null) throw FormatException("缺少「网址」或「密码」列")

        val out = ArrayList<PasswordEntry>()
        val seen = HashSet<String>()
        var skipped = 0
        var total = 0
        for (r in 1 until rows.size) {
            val row = rows[r]
            if (row.all { it.isBlank() }) continue // 空行不算
            total++
            if (total > MAX_ROWS) { skipped += rows.size - r; break }
            fun cell(c: String): String = col[c]?.let { row.getOrNull(it) }.orEmpty()
            if (bitwarden && typeIdx >= 0 && row.getOrNull(typeIdx)?.trim()?.lowercase().let { it != null && it != "login" && it.isNotEmpty() }) {
                skipped++; continue
            }
            val password = cell("password")
            val origin = originOf(cell("url"))
            if (password.isEmpty() || origin == null || password.length > MAX_FIELD) { skipped++; continue }
            val username = cell("username").take(MAX_FIELD)
            val realm = cell("realm").takeIf { it.isNotBlank() }
            val formRaw = cell("formorigin").trim()
            val formOrigin = when {
                realm != null -> null
                formRaw.isEmpty() -> null
                else -> originOf(formRaw)
            }
            val key = "$origin\u0000$username\u0000$password"
            if (!seen.add(key)) { skipped++; continue }
            val note = cell("note").take(MAX_NOTE)
            val created = parseTime(cell("created"))
            val changed = parseTime(cell("changed"))
            out.add(
                PasswordEntry(
                    guid = cell("guid").trim().takeIf { isSyncGuid(it) } ?: "",
                    origin = origin,
                    formOrigin = formOrigin,
                    httpRealm = realm?.take(MAX_FIELD),
                    username = username,
                    password = password,
                    note = note,
                    timeCreated = created,
                    timeLastUsed = parseTime(cell("lastused")),
                    timePasswordChanged = if (changed > 0) changed else created,
                ),
            )
        }
        return Result(format, out, skipped, total)
    }

    /** Firefox 记录 id：不强求 12 位 base64url（旧版是 {uuid}），只要是可打印、无空白、长度合理即可沿用。 */
    private fun isSyncGuid(s: String): Boolean =
        s.length in 8..64 && s.all { it.isLetterOrDigit() || it in "-_{}" }

    /** 时间列可能是毫秒 / 秒的数字，也可能是 ISO 日期。认不出返回 0。 */
    internal fun parseTime(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0L
        t.toLongOrNull()?.let { n ->
            return when {
                n <= 0 -> 0L
                n < 100_000_000_000L -> n * 1000      // 秒
                n > 100_000_000_000_000L -> n / 1000  // 微秒
                else -> n
            }
        }
        return runCatching { java.time.Instant.parse(t).toEpochMilli() }.getOrDefault(0L)
    }

    /**
     * 把任意网址 / 主机名规整成「来源」scheme://host[:port]（小写，去默认端口，去路径 / 参数）。
     * 没写协议按 https 处理；非 http(s)（如 android://、chrome://）、带不合法字符的返回 null。
     */
    fun originOf(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty() || s.length > 2048 || s.any { it.isISOControl() || it == ' ' }) return null
        val schemeEnd = s.indexOf("://")
        val scheme: String
        if (schemeEnd > 0) {
            scheme = s.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") return null
            s = s.substring(schemeEnd + 3)
        } else {
            if (s.startsWith("//")) s = s.substring(2)
            // 没写协议但带冒号：只有「主机:端口」合法；mailto:x、android:xxx 之类直接拒绝
            val head = s.substringBefore('/').substringBefore('?').substringBefore('#')
            if (!head.startsWith("[") && head.contains(':') &&
                !head.substringAfterLast(':').all { it.isDigit() }
            ) return null
            scheme = "https"
        }
        var authority = s.substringBefore('/').substringBefore('?').substringBefore('#')
        authority = authority.substringAfterLast('@') // 去掉 user:pass@
        if (authority.isEmpty()) return null
        val host: String
        var port = ""
        if (authority.startsWith("[")) { // IPv6
            val end = authority.indexOf(']')
            if (end < 0) return null
            host = authority.substring(0, end + 1).lowercase()
            val rest = authority.substring(end + 1)
            if (rest.isNotEmpty()) { if (!rest.startsWith(":")) return null; port = rest.substring(1) }
        } else {
            host = authority.substringBeforeLast(':', authority).lowercase().trimEnd('.')
            if (authority.contains(':')) port = authority.substringAfterLast(':')
        }
        if (host.isEmpty() || host.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == '[' || it == ']' || it == ':' || it.code > 127) }) return null
        if (port.isNotEmpty()) {
            val p = port.toIntOrNull() ?: return null
            if (p !in 1..65535) return null
            if ((scheme == "https" && p == 443) || (scheme == "http" && p == 80)) port = ""
        }
        return "$scheme://$host" + if (port.isNotEmpty()) ":$port" else ""
    }

    /** 展示用：去掉协议的主机（含端口）。 */
    fun displayHost(origin: String): String = origin.substringAfter("://").removePrefix("www.")

    /**
     * RFC 4180 解析：引号字段（含逗号 / 换行 / 双写引号转义）、CRLF / LF / CR 行尾、UTF-8 BOM。
     * 分隔符自动判断（逗号 / 分号 / 制表符，看表头行哪个多）。
     */
    internal fun parseRows(input: String): List<List<String>> {
        val text = input.removePrefix("﻿")
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delim = listOf(',', ';', '\t').maxByOrNull { d -> firstLine.count { it == d } }
            ?.takeIf { d -> firstLine.contains(d) } ?: ','

        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') { sb.append('"'); i++ } else inQuotes = false
                } else sb.append(c)
            } else when {
                c == '"' && sb.isEmpty() -> inQuotes = true
                c == delim -> { row.add(sb.toString()); sb.setLength(0) }
                c == '\r' || c == '\n' -> {
                    if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                    row.add(sb.toString()); sb.setLength(0)
                    rows.add(row); row = ArrayList()
                    if (rows.size > MAX_ROWS + 1) return rows
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
        return rows
    }

    /** 字节 → 文本：UTF-8（含 BOM）优先；UTF-16 带 BOM 的（Excel「Unicode 文本」）也认；其余按 UTF-8 宽松解码。 */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return String(bytes, Charsets.UTF_8)
    }
}
