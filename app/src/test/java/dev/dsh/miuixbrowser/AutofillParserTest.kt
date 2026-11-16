package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.browser.AfNode
import dev.dsh.miuixbrowser.browser.AutofillParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：自动填充结构里「用户名 + 密码」的识别（Gecko 带 hints、系统 WebView 多半只有 HTML 属性，两种都要认）。 */
class AutofillParserTest {

    private val textPassword = 0x81 // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD
    private val textEmail = 0x21 // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_EMAIL_ADDRESS

    private fun web(i: Int, hints: List<String> = emptyList(), htmlType: String? = null, labels: List<String> = emptyList(),
                    inputType: Int = 0, focused: Boolean = false, visible: Boolean = true) =
        AfNode(i, hints = hints, inputType = inputType, htmlType = htmlType, labels = labels, focused = focused,
            visible = visible, inWeb = true, domain = "example.com", scheme = "https")

    @Test
    fun geckoStyleHintsFindUsernameAndPassword() {
        val form = AutofillParser.parse(listOf(web(0, hints = listOf("username")), web(1, hints = listOf("password"))))
        assertNotNull(form)
        assertEquals(0, form!!.username!!.index)
        assertEquals(1, form.password!!.index)
        assertFalse(form.newPassword)
        assertEquals("example.com", form.domain)
        assertEquals("https", form.scheme)
    }

    @Test
    fun webViewStyleHtmlAttributesOnly() {
        val form = AutofillParser.parse(
            listOf(
                web(0, htmlType = "text", labels = listOf("login_email")),
                web(1, htmlType = "password", labels = listOf("pwd")),
            ),
        )
        assertEquals(0, form!!.username!!.index)
        assertEquals(1, form.password!!.index)
    }

    @Test
    fun passwordRecognisedFromInputTypeAndAutocompleteWords() {
        val byInputType = AutofillParser.parse(listOf(web(0), web(1, inputType = textPassword)))
        assertEquals(1, byInputType!!.password!!.index)
        // 网页 autocomplete 的词（current-password / new-password）已被服务合并进 hints，写法带连字符
        val byAutocomplete = AutofillParser.parse(listOf(web(0, hints = listOf("email")), web(1, hints = listOf("current-password"))))
        assertEquals(1, byAutocomplete!!.password!!.index)
        assertFalse(byAutocomplete.newPassword)
    }

    @Test
    fun newPasswordFormOnlyOffersSaveNotFill() {
        val form = AutofillParser.parse(
            listOf(web(0, hints = listOf("username")), web(1, hints = listOf("new-password")), web(2, hints = listOf("new-password"))),
        )
        assertTrue(form!!.newPassword)
    }

    @Test
    fun changePasswordFormTargetsTheCurrentPasswordField() {
        val form = AutofillParser.parse(
            listOf(web(0, hints = listOf("current-password")), web(1, hints = listOf("new-password")), web(2, hints = listOf("new-password"))),
        )
        assertEquals(0, form!!.password!!.index)
        assertFalse(form.newPassword)
    }

    @Test
    fun focusedPasswordWinsWhenSeveralLoginFormsOnOnePage() {
        val form = AutofillParser.parse(
            listOf(
                web(0, hints = listOf("username")), web(1, hints = listOf("password")),
                web(2, hints = listOf("username")), web(3, hints = listOf("password"), focused = true),
            ),
        )
        assertEquals(3, form!!.password!!.index)
        assertEquals(2, form.username!!.index)
    }

    @Test
    fun usernameFallsBackToTheFieldRightBeforeThePassword() {
        val form = AutofillParser.parse(listOf(web(0, htmlType = "text", labels = listOf("field1")), web(1, htmlType = "password")))
        assertEquals(0, form!!.username!!.index)
    }

    @Test
    fun searchBoxIsNeverTakenAsUsername() {
        val form = AutofillParser.parse(listOf(web(0, htmlType = "text", labels = listOf("q", "site-search")), web(1, htmlType = "password")))
        assertNull(form!!.username)
        assertEquals(1, form.password!!.index)
    }

    @Test
    fun usernameLookbackStopsAtThePreviousPasswordField() {
        val form = AutofillParser.parse(
            listOf(
                web(0, hints = listOf("email")), web(1, htmlType = "password"),
                web(2, htmlType = "text", labels = listOf("captcha")), web(3, htmlType = "password"),
            ),
        )
        // 第一个密码框 → 邮箱；第一个密码框之前的邮箱不会被第二个密码框借走
        assertEquals(0, form!!.username!!.index)
        val second = AutofillParser.parse(
            listOf(web(0, hints = listOf("email")), web(1, htmlType = "password"), web(2, htmlType = "password", focused = true)),
        )
        assertEquals(2, second!!.password!!.index)
        assertNull(second.username)
    }

    @Test
    fun nativeFieldsWithoutWebEvidenceAreIgnored() {
        val addressBar = AfNode(0, htmlType = null, labels = listOf("address"), inWeb = false, domain = null)
        val dialogPassword = AfNode(1, inputType = textPassword, inWeb = false, domain = null)
        assertNull(AutofillParser.parse(listOf(addressBar, dialogPassword)))
    }

    @Test
    fun domainCanComeFromAnyNodeOfThePage() {
        val bare = AfNode(0, hints = listOf("username"), inWeb = true, domain = null)
        val pw = AfNode(1, hints = listOf("password"), inWeb = true, domain = "login.example.org", scheme = "http")
        val form = AutofillParser.parse(listOf(bare, pw))
        assertEquals("login.example.org", form!!.domain)
        assertEquals("http", form.scheme)
    }

    @Test
    fun stepOneOfIdentifierFirstLoginOffersUsernameOnly() {
        val form = AutofillParser.parse(listOf(web(0, hints = listOf("emailAddress"), focused = true)))
        assertEquals(0, form!!.username!!.index)
        assertNull(form.password)
    }

    @Test
    fun plainTextFieldWithoutPasswordDoesNotTriggerAnything() {
        assertNull(AutofillParser.parse(listOf(web(0, htmlType = "text", labels = listOf("nickname")))))
        // 邮箱框但焦点在别处 → 不打扰
        assertNull(AutofillParser.parse(listOf(web(0, inputType = textEmail), web(1, htmlType = "text", focused = true))))
    }

    @Test
    fun hiddenFieldsAreSkippedForFillButKeptForSave() {
        val nodes = listOf(web(0, hints = listOf("username"), visible = false), web(1, hints = listOf("password"), visible = false))
        assertNull(AutofillParser.parse(nodes))
        val saved = AutofillParser.parse(nodes, visibleOnly = false)
        assertEquals(1, saved!!.password!!.index)
        assertEquals(0, saved.username!!.index)
    }

    @Test
    fun chineseLabelsCountAsUsernameHints() {
        val form = AutofillParser.parse(
            listOf(web(0, htmlType = "text", labels = listOf("请输入手机号/邮箱")), web(1, htmlType = "text", labels = listOf("验证码")), web(2, htmlType = "password")),
        )
        // 验证码框紧挨着密码框，但被排除；用户名取前面的手机号/邮箱框
        assertEquals(0, form!!.username!!.index)
    }

    @Test
    fun nativeFormsParseOnlyInNativeMode() {
        // 原生 App：没有 HTML 信息、不在 WebView 里，也没有网页域名
        val nodes = listOf(
            AfNode(0, labels = listOf("login_username"), inputType = 0x21),
            AfNode(1, inputType = textPassword),
        )
        assertNull(AutofillParser.parse(nodes)) // 网页模式不认原生输入框（地址栏等）
        val form = AutofillParser.parse(nodes, native = true)
        assertEquals(1, form!!.password!!.index)
        assertEquals(0, form.username!!.index)
        assertNull(form.domain)
    }

    @Test
    fun nativeModeIgnoresDeclaredWebDomain() {
        // 非受信应用自称的网页域名一律不采信：原生路径只认系统给出的包名
        val form = AutofillParser.parse(listOf(web(0, hints = listOf("username")), web(1, hints = listOf("password"))), native = true)
        assertNull(form!!.domain)
        assertNull(form.scheme)
    }
}
