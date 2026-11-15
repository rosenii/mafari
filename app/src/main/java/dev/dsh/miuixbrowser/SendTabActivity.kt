package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.sync.fxa.FxaSync
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 系统分享面板里「发送到设备」的落点（和 Firefox 一样作为分享面板的自定义操作出现）。
 * 透明页 + 一个设备列表对话框：选一台同帐户的设备，把页面作为 Send Tab 发过去，然后立刻关闭。
 */
class SendTabActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (!EngineSupport.SYNC_BUNDLED || !url.startsWith("http")) {
            finish()
            return
        }
        FxaSync.init(this)
        dialog = AlertDialog.Builder(this)
            .setTitle("发送到设备")
            .setMessage("正在读取设备…")
            .setOnCancelListener { finish() }
            .show()
        scope.launch {
            val devices = FxaSync.loadDevices()
            dialog?.dismiss()
            if (devices == null) {
                Toast.makeText(this@SendTabActivity, "请先在 Mafari 里登录 Firefox 帐户", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            if (devices.isEmpty()) {
                Toast.makeText(this@SendTabActivity, "没有其他设备：请在其他设备上登录同一个 Firefox 帐户", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            dialog = AlertDialog.Builder(this@SendTabActivity)
                .setTitle("发送到设备")
                .setItems(devices.map { it.name }.toTypedArray()) { _, i ->
                    val d = devices[i]
                    val app = applicationContext
                    FxaSync.sendTab(d, title, url) { ok ->
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(app, if (ok) "已发送到 ${d.name}" else "发送失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    finish()
                }
                .setNegativeButton("取消") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }
}
