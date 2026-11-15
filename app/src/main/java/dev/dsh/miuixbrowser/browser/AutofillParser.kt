package dev.dsh.miuixbrowser.browser


/**
 * 系统自动填充结构里一个输入框的快照（纯数据，不碰 Android 类，见 AutofillParserTest）。
 * 由 [MafariAutofillService] 从 AssistStructure 拍下来；[index] 是它在快照列表里的位置，
 * 服务凭它回查对应的 AutofillId。
 *
 * [hints] 已合并「View 的 autofillHints」和「网页 autocomplete 属性的各个词」；
 * [labels] 是 name / id / placeholder / idEntry 等可读线索（任意大小写，解析时统一转小写）。
 */
internal class AfNode(
    val index: Int,
    val hints: List<String> = emptyList(),
    val inputType: Int = 0,
    val htmlType: String? = null,
    val labels: List<String> = emptyList(),
    val editable: Boolean = true,
    val visible: Boolean = true,
    val focused: Boolean = false,
    /** 确认是网页内容（祖先是 WebView / GeckoView，或节点带 HTML 信息）：原生输入框（地址栏、对话框）不参与。 */
    val inWeb: Boolean = false,
    val domain: String? = null,
    val scheme: String? = null,
    /** 当前已填的文本（保存时用）。 */
    val text: String = "",
)

/**
 * 解析结果。[password] 为 null = 只有用户名（分步登录的第一步）；
 * [newPassword] = 目标密码框是「新密码」（注册 / 改密码）：不提供填充，只提示保存。
 */
internal class AfForm(
    val username: AfNode?,
    val password: AfNode?,
    val newPassword: Boolean,
    val domain: String?,
    val scheme: String?,
)

/**
 * 在一页的输入框里找出「用户名 + 密码」。信号优先级：autofillHints / autocomplete → HTML type 与 inputType →
 * 名称线索 → 密码框前面紧挨着的文本框。Gecko 与系统 WebView 给出的结构不同（有的带 hints、有的只有 HTML 属性），
 * 所以这里所有信号都看，不依赖某一种内核的特例。
 */
internal object AutofillParser {

    private const val CLASS_MASK = 0xF
    private const val VARIATION_MASK = 0xFF0
    private const val CLASS_TEXT = 0x1
    private const val VAR_EMAIL = 0x20
    private const val VAR_PASSWORD = 0x80
    private const val VAR_WEB_EMAIL = 0xD0
    private const val VAR_WEB_PASSWORD = 0xE0

    /** 用户名往前最多看几个控件（并且不越过上一个密码框），避免把页面别处的邮箱框当成登录名。 */
    private const val LOOKBACK = 4

    private val PASSWORD_HINTS = setOf("password", "currentpassword", "newpassword")
    private val NEW_PASSWORD_HINTS = setOf("newpassword")
    private val STRONG_USER_HINTS = setOf("username", "newusername", "emailaddress", "email")
    private val TEXT_TYPES = setOf("text", "email", "tel", "url")
    private val USER_WORDS = listOf(
        "user", "login", "email", "mail", "account", "acct", "uname", "phone", "mobile", "identifier", "signin",
        "用户", "账号", "帐号", "账户", "邮箱", "邮件", "手机", "登录",
    )
    private val NOT_USER_WORDS = listOf(
        "search", "query", "captcha", "verif", "otp", "coupon", "promo", "zip", "postal", "city", "company",
        "firstname", "lastname", "fullname", "first_name", "last_name", "搜索", "验证", "姓名",
    )

    /** [visibleOnly] = false 供保存用：提交后表单常被藏起来，保存请求里的结构里它们可能已不可见。 */
    fun parse(all: List<AfNode>, visibleOnly: Boolean = true): AfForm? {
        val nodes = all.filter { (!visibleOnly || it.visible) && it.editable && (it.inWeb || it.domain != null) }
        if (nodes.isEmpty()) return null
        val domainNode = all.firstOrNull { it.domain != null }
        val domain = domainNode?.domain
        val scheme = domainNode?.scheme

        val passwords = nodes.filter { isPassword(it) }
        if (passwords.isNotEmpty()) {
            val current = passwords.filter { !isNewPassword(it) }
            val pw = current.firstOrNull { it.focused } ?: current.firstOrNull()
                ?: passwords.firstOrNull { it.focused } ?: passwords.first()
            return AfForm(findUsername(nodes, pw), pw, isNewPassword(pw), domain, scheme)
        }

        // 没有密码框：只在焦点落在「明确是用户名 / 邮箱」的框时才认（分步登录），否则不打扰
        val focused = nodes.firstOrNull { it.focused }
        val target = if (focused != null) focused.takeIf { isStrongUser(it) } else nodes.firstOrNull { isStrongUser(it) }
        return target?.let { AfForm(it, null, false, domain, scheme) }
    }

    internal fun isPassword(n: AfNode): Boolean {
        if (n.hints.any { norm(it) in PASSWORD_HINTS }) return true
        if (n.htmlType.equals("password", ignoreCase = true)) return true
        val v = n.inputType and VARIATION_MASK
        return (n.inputType and CLASS_MASK) == CLASS_TEXT && (v == VAR_PASSWORD || v == VAR_WEB_PASSWORD)
    }

    private fun isNewPassword(n: AfNode) = n.hints.any { norm(it) in NEW_PASSWORD_HINTS }

    private fun isStrongUser(n: AfNode): Boolean {
        if (isPassword(n)) return false
        if (n.hints.any { norm(it) in STRONG_USER_HINTS }) return true
        if (n.htmlType.equals("email", ignoreCase = true)) return true
        val v = n.inputType and VARIATION_MASK
        return (n.inputType and CLASS_MASK) == CLASS_TEXT && (v == VAR_EMAIL || v == VAR_WEB_EMAIL)
    }

    private fun isTextLike(n: AfNode): Boolean {
        if (isPassword(n)) return false
        val t = n.htmlType?.lowercase()
        return t == null || t.isEmpty() || t in TEXT_TYPES
    }

    private fun labelOf(n: AfNode) = n.labels.joinToString(" ") { it.lowercase() }

    private fun looksLikeUser(n: AfNode): Boolean {
        val s = labelOf(n)
        return USER_WORDS.any { it in s } && NOT_USER_WORDS.none { it in s }
    }

    private fun findUsername(nodes: List<AfNode>, pw: AfNode): AfNode? {
        val idx = nodes.indexOf(pw)
        val before = nodes.subList(0, idx).takeLastWhile { !isPassword(it) }.takeLast(LOOKBACK)
        before.lastOrNull { isStrongUser(it) }?.let { return it }
        before.lastOrNull { isTextLike(it) && looksLikeUser(it) }?.let { return it }
        val last = before.lastOrNull() ?: return null
        return last.takeIf { isTextLike(it) && NOT_USER_WORDS.none { w -> w in labelOf(it) } }
    }

    /** hints 的写法有 emailAddress / new-password / current-password 等，统一成小写无分隔符再比。 */
    private fun norm(s: String) = s.lowercase().replace("-", "").replace("_", "")
}
