package dev.dsh.miuixbrowser

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.EditText
import android.widget.LinearLayout
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gecko 的网页弹层（GeckoView 自己不画任何界面，全部交给 PromptDelegate）：
 * JS alert / confirm / prompt、`<select>` 下拉、HTTP / 代理认证、日期时间、拦截弹窗、文件选择 / 拍照，
 * 以及网页登录的「保存密码」提示和「选择要填的账号」（存储端见 [GeckoLogins]）。
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

    // ---------------------------------------------------------------- 登录：保存提示 / 选择要填的账号
    //
    // 两个 prompt 都是异步的：先在 GeckoLogins 的 io 线程判断 / 验证，再回主线程弹框，用 [done] 保证只回应一次
    // （对话框的按钮、取消、验证失败可能先后触发）。

    override fun onLoginSave(
        session: GeckoSession,
        prompt: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>,
    ): GeckoResult<PromptDelegate.PromptResponse>? {
        val appCtx = activity?.applicationContext
        val option = prompt.options.firstOrNull()
        // 关了保存 / 无痕标签页 / 没有可用界面：当作「不保存」，不打扰
        if (option == null || appCtx == null || tab.incognito || !PasswordPrefs.saveEnabled(appCtx)) {
            return GeckoResult.fromValue(prompt.dismiss())
        }
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val done = AtomicBoolean(false)
        fun finish(r: PromptDelegate.PromptResponse) {
            if (done.compareAndSet(false, true)) result.complete(r)
        }
        GeckoLogins.runIo {
            val decision = runCatching { GeckoLogins.decide(appCtx, option.value) }.getOrNull()
            Handler(Looper.getMainLooper()).post {
                // 不能保存 / 已存且密码没变：不弹
                if (decision == null || decision.kind == GeckoLogins.SaveKind.SAME) {
                    finish(prompt.dismiss())
                    return@post
                }
                val update = decision.kind == GeckoLogins.SaveKind.UPDATE
                val who = decision.entry.username.ifEmpty { "（无用户名）" }
                val shown = show { b ->
                    b.setTitle(if (update) "更新密码？" else "保存密码？")
                    b.setMessage(PasswordCsv.displayHost(decision.entry.origin) + "\n" + who + "\n\n密码会加密保存在本机")
                    b.setPositiveButton(if (update) "更新" else "保存") { _, _ ->
                        // Gecko 确认后也会回调存储委托；这里先直接写一遍，库的合并是幂等的，不会重复
                        GeckoLogins.runIo { GeckoLogins.save(appCtx, decision.entry) }
                        finish(prompt.confirm(option))
                    }
                    b.setNegativeButton("不保存") { _, _ -> finish(prompt.dismiss()) }
                    b.setOnCancelListener { finish(prompt.dismiss()) }
                }
                if (!shown) finish(prompt.dismiss())
            }
        }
        return result
    }

    override fun onLoginSelect(
        session: GeckoSession,
        prompt: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>,
    ): GeckoResult<PromptDelegate.PromptResponse>? {
        val act = activity
        val appCtx = act?.applicationContext
        val options = prompt.options
        if (act == null || appCtx == null || options.isEmpty() || !PasswordPrefs.fillEnabled(appCtx)) {
            return GeckoResult.fromValue(prompt.dismiss())
        }
        val result = GeckoResult<PromptDelegate.PromptResponse>()
        val done = AtomicBoolean(false)
        fun finish(r: PromptDelegate.PromptResponse) {
            if (done.compareAndSet(false, true)) result.complete(r)
        }
        fun pick(option: Autocomplete.LoginSelectOption) {
            if (PasswordPrefs.verifyBeforeFill(appCtx)) {
                BiometricGate.authenticate(act, "验证身份", "验证后才会填入账号和密码") { ok ->
                    finish(if (ok) prompt.confirm(option) else prompt.dismiss())
                }
            } else {
                finish(prompt.confirm(option))
            }
        }
        val labels = options.map { o ->
            val l = o.value
            l.username.orEmpty().ifEmpty { "（无用户名）" } + "\n" + PasswordCsv.displayHost(l.origin.orEmpty())
        }.toTypedArray<CharSequence>()
        val shown = show { b ->
            b.setTitle("填充登录信息")
            b.setItems(labels) { _, i -> pick(options[i]) }
            b.setNegativeButton("取消") { _, _ -> finish(prompt.dismiss()) }
            b.setOnCancelListener { finish(prompt.dismiss()) }
        }
        if (!shown) finish(prompt.dismiss())
        return result
    }

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
            if (uris == null || uris.isEmpty()) {
                runCatching { result.complete(prompt.dismiss()) }
                return@ValueCallback
            }
            fun confirmWith(list: List<Uri>): PromptDelegate.PromptResponse =
                if (multiple && list.size > 1) prompt.confirm(ctx, list.toTypedArray())
                else prompt.confirm(ctx, list[0])

            // 第一步：和原来完全一致，直接把选择器给的 Uri 交给 Gecko（正常机型走这里，不做任何额外处理）
            val direct = runCatching { confirmWith(uris.toList()) }
                .onFailure { Diag.log("gecko file confirm failed (direct): ${it}") }
                .getOrNull()
            if (direct != null) {
                runCatching { result.complete(direct) }
                return@ValueCallback
            }
            // 第二步（仅第一步抛异常的机型）：部分原生选择器返回的 Uri，Gecko 自己取路径 / 拷贝时会抛异常，
            // 以前这里被吞掉并按取消处理，网页就“选了文件没反应”。改为先拷进应用缓存，再把 file Uri 交给 Gecko。
            Thread {
                val staged = stageUploads(ctx, uris)
                Handler(Looper.getMainLooper()).post {
                    val response = if (staged.isEmpty()) null else runCatching { confirmWith(staged) }
                        .onFailure { Diag.log("gecko file confirm failed (staged): ${it}") }
                        .getOrNull()
                    runCatching { result.complete(response ?: prompt.dismiss()) }
                }
            }.start()
        }
        tab.onShowFileChooser.invoke(params, callback)
        return result
    }

    /**
     * 把系统选择器返回的 Uri 逐个拷到 cacheDir/gecko_upload/<批次>/<原文件名>，返回 file Uri。
     * 保留原文件名（网页上传时显示 / 提交的就是它）；单个失败就跳过，全部失败返回空列表。
     */
    private fun stageUploads(ctx: Context, uris: Array<Uri>): List<Uri> {
        val root = File(ctx.cacheDir, "gecko_upload")
        // 清掉 1 小时前的旧批次，避免缓存无限增长（Gecko 读取发生在选择之后很短时间内）
        runCatching {
            val limit = System.currentTimeMillis() - 60 * 60 * 1000L
            root.listFiles()?.forEach { if (it.lastModified() < limit) it.deleteRecursively() }
        }
        val batch = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
        val out = ArrayList<Uri>(uris.size)
        uris.forEachIndexed { i, uri ->
            runCatching {
                if ("file".equals(uri.scheme, ignoreCase = true)) {
                    // file:// 本来就是路径，直接用（拷一份反而多占空间）
                    out.add(uri)
                    return@runCatching
                }
                val dir = File(batch, i.toString()).apply { mkdirs() }
                val file = File(dir, safeName(ctx, uri))
                val input = ctx.contentResolver.openInputStream(uri) ?: error("openInputStream null")
                input.use { ins -> file.outputStream().use { ins.copyTo(it) } }
                out.add(Uri.fromFile(file))
            }.onFailure { Diag.log("stage upload failed: $uri $it") }
        }
        return out
    }

    /** 取显示名；拿不到 / 含非法字符 / 过长时退回安全名，保证能建出文件 */
    private fun safeName(ctx: Context, uri: Uri): String {
        var name: String? = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
            }
        }.getOrNull()
        if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')
        var clean = (name ?: "").map { ch -> if (ch in "\\/:*?\"<>|" || ch.code < 32) '_' else ch }.joinToString("").trim().trim('.')
        if (clean.isEmpty()) clean = "upload_${System.currentTimeMillis()}"
        if (clean.length > 120) {
            val ext = clean.substringAfterLast('.', "").takeIf { it.length in 1..10 }
            clean = clean.take(100) + (ext?.let { ".$it" } ?: "")
        }
        return clean
    }
}
