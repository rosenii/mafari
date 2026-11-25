/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.browser.PasswordCsv
import dev.dsh.miuixbrowser.crypto.BiometricGate
import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.data.PasswordDb
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.Toast

/**
 * [MafariAutofillService] 里「需要验证身份」的数据集被选中后由系统启动的透明界面：
 * 先过 [BiometricGate]（和 Gecko 的填充、密码页查看同一套验证），通过后才在后台解开**这一条**密码，
 * 作为认证结果交回系统，由系统填进页面。取消 / 失败 / 解不开都当作「没选」，页面什么也不会被填。
 */
class AutofillAuthActivity : Activity() {

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val guid = intent.getStringExtra(EXTRA_GUID)
        val passId = intent.getParcelableExtra(EXTRA_PASS_ID, AutofillId::class.java)
        val userId = intent.getParcelableExtra(EXTRA_USER_ID, AutofillId::class.java)
        if (guid == null || passId == null) {
            cancel()
            return
        }
        BiometricGate.authenticate(this, tr("验证身份"), tr("验证后才会填入账号和密码")) { ok ->
            if (ok) deliver(guid, userId, passId) else cancel()
        }
    }

    private fun deliver(guid: String, userId: AutofillId?, passId: AutofillId) {
        val app = applicationContext
        Thread {
            val entry = runCatching {
                AppDatabase.get(app)?.let { db ->
                    PasswordDb.reveal(app, db, guid)?.also { PasswordDb.markUsed(db, guid) }
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
        const val EXTRA_GUID = "guid"
        const val EXTRA_PASS_ID = "pass_id"
        const val EXTRA_USER_ID = "user_id"
    }
}
