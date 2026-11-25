package dev.dsh.miuixbrowser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dev.dsh.miuixbrowser.browser.ShortcutHelper
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.util.LocalHtml
import java.io.File

/**
 * 系统「打开方式」/ 分享菜单里的网页 / 纯文本文件入口（HTML、MHTML、TXT、LOG、MD 等）
 * （清单里 VIEW + content / file + text/html 等过滤器，以及 SEND + 同样的 MIME，声明在这里）。
 * 无界面（透明）：后台线程把文件拷进应用私有目录（见 [LocalHtml]），再交给 [MainActivity] 在新标签里打开，随即结束。
 * 拷贝期间保持透明，小文件几乎瞬间完成；读不到 / 太大时给个提示。
 *
 * 分享菜单（ACTION_SEND）：
 * - 带文件（EXTRA_STREAM）：和「打开方式」一样拷贝后打开；
 * - 只带一段文字（EXTRA_TEXT，分享菜单里它和 txt 文件同为 text/plain，没法分开声明）：整段是一条 http(s) 链接就直接打开，
 *   否则包成一页可读文本。
 */
class HtmlOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = intent
        if (src == null) {
            finish()
            return
        }
        when (src.action) {
            Intent.ACTION_VIEW -> {
                val data = src.data
                if (LocalHtml.isImportable(data)) importFile(data!!, src.type) else finish()
            }
            Intent.ACTION_SEND -> handleShare(src)
            else -> finish()
        }
    }

    private fun handleShare(src: Intent) {
        val stream = sharedStream(src)
        if (stream != null) {
            if (LocalHtml.isImportable(stream)) importFile(stream, src.type) else finish()
            return
        }
        val text = src.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            finish()
            return
        }
        val link = LocalHtml.sharedLink(text)
        if (link != null) {
            // 用户主动选了 Mafari：固定在应用内打开，不受「外部链接打开方式」影响（MainActivity 按这个标记直接开）
            openInMain(Intent(Intent.ACTION_VIEW, Uri.parse(link)).putExtra(ShortcutHelper.EXTRA_FROM_SHORTCUT, true))
            finish()
            return
        }
        val app = applicationContext
        Thread { finishWith(LocalHtml.importText(app, text)) }.start()
    }

    /** 分享来的文件地址：先取 EXTRA_STREAM，没有再看 ClipData 的第一项（有的应用只放在 ClipData 里）。 */
    private fun sharedStream(src: Intent): Uri? =
        src.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            ?: src.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

    private fun importFile(src: Uri, mime: String?) {
        val app = applicationContext
        Thread { finishWith(LocalHtml.import(app, src, mime)) }.start()
    }

    private fun finishWith(result: Result<File>) {
        runOnUiThread {
            val file = result.getOrNull()
            if (file == null) {
                Toast.makeText(applicationContext, tr("无法打开这个文件"), Toast.LENGTH_LONG).show()
            } else {
                openInMain(Intent(Intent.ACTION_VIEW, Uri.fromFile(file)))
            }
            finish()
        }
    }

    private fun openInMain(view: Intent) {
        runCatching {
            startActivity(view.setClass(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
