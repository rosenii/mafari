package dev.dsh.miuixbrowser

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * GM_notification 的原生实现。回调 kind：click（点通知）/ close（划掉）/ timeout（到时自动收起）。
 * 每个通知恰好回调一次；同 tag 的新通知会顶替旧的，旧的按 close 结算。
 */
object GmNotifications {

    private const val CHANNEL = "userscript"
    private const val CHANNEL_SILENT = "userscript_silent"
    const val EXTRA_NOTIFICATION = "gm_notification_id"
    const val ACTION_DISMISS = "dev.dsh.miuixbrowser.GM_NOTIFICATION_DISMISS"

    private val seq = AtomicInteger(0x6d00)
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<Int, (String) -> Unit>()
    private val byTag = ConcurrentHashMap<String, Int>()
    private val timeouts = ConcurrentHashMap<Int, Runnable>()

    /** 没有通知权限时抛异常，由桥转成 JS 异常，运行时据此退回 toast */
    fun post(
        context: Context,
        title: String,
        text: String,
        tag: String,
        timeoutMs: Long,
        silent: Boolean,
        callback: (String) -> Unit,
    ) {
        val app = context.applicationContext
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("notification permission denied")
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        ensureChannels(nm)

        val id = if (tag.isNotBlank()) {
            byTag[tag] ?: seq.incrementAndGet().also { byTag[tag] = it }
        } else {
            seq.incrementAndGet()
        }
        settle(id, "close") // 同 id 的上一条（同 tag 顶替）
        pending[id] = callback

        val open = Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_NOTIFICATION, id)
        }
        val dismiss = Intent(app, GmNotificationReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra(EXTRA_NOTIFICATION, id)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val n = Notification.Builder(app, if (silent) CHANNEL_SILENT else CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title.ifBlank { app.getString(R.string.app_name) })
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(app, id, open, flags))
            .setDeleteIntent(PendingIntent.getBroadcast(app, id, dismiss, flags))
            .build()
        nm.notify(id, n)

        if (timeoutMs > 0) {
            val r = Runnable {
                timeouts.remove(id)
                if (pending.containsKey(id)) {
                    nm.cancel(id) // 会触发 deleteIntent，但 settle 已先移除回调，不会重复
                    settle(id, "timeout")
                }
            }
            timeouts[id] = r
            main.postDelayed(r, timeoutMs)
        }
    }

    /** MainActivity.onNewIntent：用户点了通知 */
    fun consumeClick(intent: Intent?) {
        val id = intent?.getIntExtra(EXTRA_NOTIFICATION, -1) ?: return
        if (id < 0) return
        intent.removeExtra(EXTRA_NOTIFICATION)
        settle(id, "click")
    }

    internal fun onDismissed(id: Int) = settle(id, "close")

    private fun settle(id: Int, kind: String) {
        timeouts.remove(id)?.let { main.removeCallbacks(it) }
        val cb = pending.remove(id) ?: return
        runCatching { cb(kind) }
    }

    private fun ensureChannels(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "脚本通知", NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SILENT, "脚本通知（静音）", NotificationManager.IMPORTANCE_LOW),
        )
    }
}

/** 通知被划掉（deleteIntent）→ 按 close 结算 */
class GmNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GmNotifications.ACTION_DISMISS) return
        GmNotifications.onDismissed(intent.getIntExtra(GmNotifications.EXTRA_NOTIFICATION, -1))
    }
}
