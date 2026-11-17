package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.browser.PasswordCsv
import dev.dsh.miuixbrowser.crypto.BiometricGate
import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.data.PasswordDb
import dev.dsh.miuixbrowser.data.PasswordPrefs
import dev.dsh.miuixbrowser.ui.AutofillPickDialog
import dev.dsh.miuixbrowser.ui.LocalBlurMode
import dev.dsh.miuixbrowser.ui.PickRow
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 原生 App 里「选择账号…」被选中后由系统启动的透明界面（[MafariAutofillService.pickDataset]）：
 * 开着「填充前验证身份」时先过 [BiometricGate]；用应用自己的玻璃对话框（[AutofillPickDialog]，
 * 与浏览器内登录弹窗同一套材质 / 主题）列出密码库里的账号；选中后
 *  1. 解开这一条（只这一条），
 *  2. 把它关联到这个 App 的包名（[PasswordDb.linkApp]，之后在这个 App 里直接出现在下拉里），
 *  3. 作为认证结果交回系统填入。
 * 取消 / 失败 / 解不开都当作「没选」，页面什么也不会被填，也不会建立关联。
 *
 * 包名来自系统写进 AssistStructure 的值（不是应用自称的），所以应用无法借这个界面把密码关联到别的包名。
 */
class AutofillPickActivity : ComponentActivity() {

    private var done = false
    private var rows by mutableStateOf<List<PickRow>?>(null)
    private var showDialog by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG)
        val passId = intent.getParcelableExtra(EXTRA_PASS_ID, AutofillId::class.java)
        if (pkg == null || passId == null) {
            cancel()
            return
        }
        val userId = intent.getParcelableExtra(EXTRA_USER_ID, AutofillId::class.java)
        val label = MafariAutofillService.appLabel(this, pkg)

        setContent {
            val settings = remember { appContainer.settings }
            val mode = when (settings.themeMode) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
            val themeController = remember(mode) { ThemeController(mode) }
            MiuixTheme(controller = themeController) {
                CompositionLocalProvider(LocalBlurMode provides settings.blurMode) {
                    val list = rows
                    if (list != null) {
                        AutofillPickDialog(
                            show = showDialog,
                            targetLabel = label,
                            rows = list,
                            onPick = { row -> deliver(pkg, row.guid, userId, passId) },
                            onDismiss = { showDialog = false },
                            onDismissFinished = { cancel() },
                        )
                    }
                }
            }
        }

        if (PasswordPrefs.verifyBeforeFill(this)) {
            BiometricGate.authenticate(this, tr("验证身份"), tr("验证后才能选择要填入的账号")) { ok -> if (ok) load() else cancel() }
        } else {
            load()
        }
    }

    private fun load() {
        val app = applicationContext
        Thread {
            val items = runCatching {
                AppDatabase.get(app)?.let { db ->
                    PasswordDb.list(db)
                        .filter { it.httpRealm == null }
                        .sortedByDescending { it.timeLastUsed }
                        .take(MAX_ITEMS)
                }
            }.getOrNull().orEmpty()
            val list = items.map { item ->
                val appLabel = if (PasswordDb.isAppOrigin(item.origin))
                    MafariAutofillService.appLabel(app, item.origin.removePrefix(PasswordDb.APP_ORIGIN_PREFIX)) else null
                PickRow(item.guid, item.origin, item.username, appLabel)
            }
            runOnUiThread {
                if (isFinishing || done) return@runOnUiThread
                if (list.isEmpty()) {
                    Toast.makeText(app, tr("密码库里还没有账号"), Toast.LENGTH_SHORT).show()
                    cancel()
                } else {
                    rows = list
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun deliver(pkg: String, guid: String, userId: AutofillId?, passId: AutofillId) {
        if (done) return
        val app = applicationContext
        Thread {
            val entry = runCatching {
                AppDatabase.get(app)?.let { db ->
                    PasswordDb.reveal(app, db, guid)?.also {
                        PasswordDb.markUsed(db, guid)
                        // 这条本来就是该 App 自己保存的（androidapp://）不必再关联
                        if (!PasswordDb.isAppOrigin(it.origin)) PasswordDb.linkApp(db, guid, pkg)
                    }
                }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || done) return@runOnUiThread
                if (entry == null) {
                    Toast.makeText(app, tr("无法解密这条密码"), Toast.LENGTH_SHORT).show()
                    cancel()
                    return@runOnUiThread
                }
                val b = Dataset.Builder(MafariAutofillService.presentation(app, entry.username, PasswordCsv.displayHost(entry.origin)))
                if (userId != null) b.setValue(userId, AutofillValue.forText(entry.username))
                b.setValue(passId, AutofillValue.forText(entry.password))
                done = true
                setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, b.build()))
                finish()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun cancel() {
        if (done) return
        done = true
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_PASS_ID = "pass_id"
        const val EXTRA_USER_ID = "user_id"
        private const val MAX_ITEMS = 200
    }
}
