package dev.dsh.miuixbrowser

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.EditText
import android.widget.LinearLayout
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import java.util.Calendar
import java.util.Locale

/**
 * Gecko 的网页弹层（GeckoView 自己不画任何界面，全部交给 PromptDelegate）：
 * JS alert / confirm / prompt、`<select>` 下拉、HTTP / 代理认证、日期时间、拦截弹窗、文件选择 / 拍照。
 *
 * - 文件选择：包装成 WebView 版同一套 [WebChromeClient.FileChooserParams] + 回调，交给界面层现有的
 *   「系统选择器 / 相机」流程（`BrowserTab.onShowFileChooser`），不重复实现。
 * - 其余用系统 [AlertDialog]：由当前 Activity 弹出，页面被关闭 / 导航时 Gecko 会让 prompt 失效，回调里都做了容错。
 */
class GeckoPromptHandler(
    private val tab: BrowserTab,
) : PromptDelegate {

    private val activity: Activity? get() = tab.geckoActivity()

    private fun host(): String = runCatching { Uri.parse(tab.url).host }.getOrNull().orEmpty()

    private fun titleOf(raw: String?): String =
        raw?.takeIf { it.isNotBlank() } ?: host().ifEmpty { "网页" }.let { "来自 $it 的消息" }

    /** 弹出对话框；activity 不可用 / 已销毁时返回 false，调用方按取消收尾 */
    private fun show(build: (AlertDialog.Builder) -> Unit): Boolean {
        val act = activity ?: return false
        if (act.isFinishing || act.isDestroyed) return false
        return runCatching {
            val builder = AlertDialog.Builder(act)
            build(builder)
            val dialog = builder.create()
            dialog.show()
        }.isSuccess
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- alert / confirm / prompt

    override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptDelegate.PromptResponse>? {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val ok = show({ b ->
            b.setTitle(titleOf(prompt.title))
                .setMessage(prompt.message)
                .setPositiveButton("确定") { _, _ -> result.complete(prompt.dismiss()) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
        })
        if (!ok) result.complete(prompt.dismiss())
        return result
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptDelegate.PromptResponse>? {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val ok = show({ b ->
            b.setTitle(titleOf(prompt.title))
                .setMessage(prompt.message)
                .setPositiveButton("确定") { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                .setNegativeButton("取消") { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
        })
        if (!ok) result.complete(prompt.dismiss())
        return result
    }

    override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptDelegate.PromptResponse>? {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val act = activity
        if (act == null) {
            result.complete(prompt.dismiss())
            return result
        }
        val input = EditText(act).apply {
            setText(prompt.defaultValue.orEmpty())
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val box = LinearLayout(act).apply {
            setPadding(dp(act, 20), dp(act, 8), dp(act, 20), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val ok = show({ b ->
            b.setTitle(titleOf(prompt.title))
                .setMessage(prompt.message)
                .setView(box)
                .setPositiveButton("确定") { _, _ -> result.complete(prompt.confirm(input.text.toString())) }
                .setNegativeButton("取消") { _, _ -> result.complete(prompt.dismiss()) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
        })
        if (!ok) result.complete(prompt.dismiss())
        return result
    }

    // ---------------------------------------------------------------- <select> / 菜单

    private fun flatten(choices: Array<PromptDelegate.ChoicePrompt.Choice>?, out: MutableList<PromptDelegate.ChoicePrompt.Choice>) {
        choices?.forEach { c ->
            if (c.separator) return@forEach
            val sub = c.items
            if (sub != null && sub.isNotEmpty()) flatten(sub, out) else out.add(c)
        }
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptDelegate.PromptResponse>? {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val list = mutableListOf<PromptDelegate.ChoicePrompt.Choice>()
        flatten(prompt.choices, list)
        if (list.isEmpty()) {
            result.complete(prompt.dismiss())
            return result
        }
        val labels = list.map { it.label.orEmpty().ifBlank { "（空）" } }.toTypedArray()
        val ok = if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
            val checked = BooleanArray(list.size) { list[it].selected }
            show({ b ->
                b.setTitle(prompt.title?.takeIf { it.isNotBlank() })
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton("确定") { _, _ ->
                        val ids = list.filterIndexed { i, _ -> checked[i] }.map { it.id }.toTypedArray()
                        result.complete(prompt.confirm(ids))
                    }
                    .setNegativeButton("取消") { _, _ -> result.complete(prompt.dismiss()) }
                    .setOnCancelListener { result.complete(prompt.dismiss()) }
            })
        } else {
            val current = list.indexOfFirst { it.selected }.coerceAtLeast(-1)
            show({ b ->
                b.setTitle(prompt.title?.takeIf { it.isNotBlank() })
                    .setSingleChoiceItems(labels, current) { dialog, which ->
                        dialog.dismiss()
                        result.complete(prompt.confirm(list[which].id))
                    }
                    .setOnCancelListener { result.complete(prompt.dismiss()) }
            })
        }
        if (!ok) result.complete(prompt.dismiss())
        return result
    }

    // ---------------------------------------------------------------- HTTP / 代理认证

    override fun onAuthPrompt(session: GeckoSession, prompt: PromptDelegate.AuthPrompt): GeckoResult<PromptDelegate.PromptResponse>? {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val opts = prompt.authOptions
        val flags = opts.flags
        // 代理认证：设置里配置了账号时自动提交（SOCKS5 认证由本机中转处理，不会走到这里）
        val isProxy = flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.PROXY != 0
        if (isProxy && WebViewProxy.authEnabled && WebViewProxy.authUser.isNotEmpty()) {
            result.complete(prompt.confirm(WebViewProxy.authUser, WebViewProxy.authPass))
            return result
        }
        val act = activity
        if (act == null) {
            result.complete(prompt.dismiss())
            return result
        }
        val onlyPassword = flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
        val user = EditText(act).apply {
            hint = "用户名"
            setText(opts.username.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val pass = EditText(act).apply {
            hint = "密码"
            setText(opts.password.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 20), dp(act, 8), dp(act, 20), 0)
            if (!onlyPassword) addView(user)
            addView(pass)
        }
        val ok = show({ b ->
            b.setTitle(if (isProxy) "代理需要登录" else "需要登录")
                .setMessage(prompt.message ?: opts.uri.orEmpty())
                .setView(box)
                .setPositiveButton("登录") { _, _ ->
                    result.complete(
                        if (onlyPassword) prompt.confirm(pass.text.toString())
                        else prompt.confirm(user.text.toString(), pass.text.toString()),
                    )
                }
                .setNegativeButton("取消") { _, _ -> result.complete(prompt.dismiss()) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
        })
        if (!ok) result.complete(prompt.dismiss())
        return result
    }

    // ---------------------------------------------------------------- 日期 / 时间

    override fun onDateTimePrompt(session: GeckoSession, prompt: PromptDelegate.DateTimePrompt): GeckoResult<PromptDelegate.PromptResponse>? {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val act = activity
        if (act == null || act.isFinishing) {
            result.complete(prompt.dismiss())
            return result
        }
        val cal = Calendar.getInstance()
        // Gecko 给的默认值：date "2025-03-09" / time "14:30" / datetime-local "2025-03-09T14:30" / month "2025-03"
        prompt.defaultValue?.takeIf { it.isNotBlank() }?.let { v ->
            runCatching {
                val date = v.substringBefore('T')
                val time = if (v.contains('T')) v.substringAfter('T') else v
                val dp = date.split('-')
                if (dp.size >= 2 && prompt.type != PromptDelegate.DateTimePrompt.Type.TIME) {
                    cal.set(Calendar.YEAR, dp[0].toInt())
                    cal.set(Calendar.MONTH, dp[1].toInt() - 1)
                    if (dp.size >= 3) cal.set(Calendar.DAY_OF_MONTH, dp[2].toInt())
                }
                if (prompt.type == PromptDelegate.DateTimePrompt.Type.TIME || v.contains('T')) {
                    val tp = time.split(':')
                    cal.set(Calendar.HOUR_OF_DAY, tp[0].toInt())
                    cal.set(Calendar.MINUTE, tp.getOrNull(1)?.toInt() ?: 0)
                }
            }
        }
        fun two(n: Int) = String.format(Locale.US, "%02d", n)
        fun done(value: String) = result.complete(prompt.confirm(value))
        fun cancel() = result.complete(prompt.dismiss())
        fun pickTime(datePart: String?) {
            val d = TimePickerDialog(act, { _, h, m ->
                val t = two(h) + ":" + two(m)
                done(if (datePart != null) "${datePart}T$t" else t)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true)
            d.setOnCancelListener { cancel() }
            d.show()
        }
        fun pickDate(then: ((String) -> Unit)?, monthOnly: Boolean = false) {
            val d = DatePickerDialog(act, { _, y, m, day ->
                val value = if (monthOnly) "$y-${two(m + 1)}" else "$y-${two(m + 1)}-${two(day)}"
                if (then != null) then(value) else done(value)
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
            d.setOnCancelListener { cancel() }
            d.show()
        }
        val ok = runCatching {
            when (prompt.type) {
                PromptDelegate.DateTimePrompt.Type.DATE -> pickDate(null)
                PromptDelegate.DateTimePrompt.Type.MONTH -> pickDate(null, monthOnly = true)
                PromptDelegate.DateTimePrompt.Type.TIME -> pickTime(null)
                PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL -> pickDate({ date -> pickTime(date) })
                else -> cancel() // WEEK：系统没有对应控件
            }
        }.isSuccess
        if (!ok) cancel()
        return result
    }

    // ---------------------------------------------------------------- 弹窗拦截

    override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptDelegate.PromptResponse>? {
        // 没有用户手势的弹窗一律拦截（点击触发的 window.open 走 NavigationDelegate.onNewSession，不会到这里）
        tab.geckoPopupBlocked(prompt.targetUri.orEmpty())
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
    }

    // ---------------------------------------------------------------- 文件选择 / 拍照

    override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt): GeckoResult<PromptDelegate.PromptResponse>? {
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        if (prompt.type == PromptDelegate.FilePrompt.Type.FOLDER) {
            result.complete(prompt.dismiss())
            return result
        }
        val multiple = prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
        val capture = prompt.capture != PromptDelegate.FilePrompt.Capture.NONE
        val accepts = prompt.mimeTypes?.filter { it.isNotBlank() }?.toTypedArray() ?: emptyArray()
        val params = object : WebChromeClient.FileChooserParams() {
            override fun getMode(): Int = if (multiple) MODE_OPEN_MULTIPLE else MODE_OPEN
            override fun getAcceptTypes(): Array<String> = accepts
            override fun isCaptureEnabled(): Boolean = capture
            override fun getTitle(): CharSequence? = prompt.title
            override fun getFilenameHint(): String? = null
            override fun createIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        }
        val callback = ValueCallback<Array<Uri>> { uris ->
            val ctx = tab.geckoContext()
            runCatching {
                result.complete(
                    when {
                        uris == null || uris.isEmpty() -> prompt.dismiss()
                        multiple && uris.size > 1 -> prompt.confirm(ctx, uris)
                        else -> prompt.confirm(ctx, uris[0])
                    },
                )
            }.onFailure { result.complete(prompt.dismiss()) }
        }
        tab.onShowFileChooser.invoke(params, callback)
        return result
    }
}
