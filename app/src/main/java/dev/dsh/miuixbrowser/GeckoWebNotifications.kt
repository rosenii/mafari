package dev.dsh.miuixbrowser

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 网站通知（Notification API）的显示：权限由 [GeckoSitePerms] / 设置里的「通知」决定，
 * 允许之后网页发来的通知在这里变成系统通知，点按回到应用。
 */
class GeckoWebNotifications(private val app: Context) : WebNotificationDelegate {

    private val ids = ConcurrentHashMap<String, Int>()

    override fun onShowNotification(notification: WebNotification) {
        runCatching {
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "网站通知", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val key = notification.tag.orEmpty().ifBlank { "n${seq.incrementAndGet()}" }
            val id = ids.getOrPut(key) { seq.incrementAndGet() }
            val open = Intent(app, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pending = PendingIntent.getActivity(
                app, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(notification.title.orEmpty())
                .setContentText(notification.text.orEmpty())
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        }
    }

    override fun onCloseNotification(notification: WebNotification) {
        runCatching {
            val key = notification.tag.orEmpty()
            val id = ids.remove(key) ?: return
            app.getSystemService(NotificationManager::class.java).cancel(id)
        }
    }

    private companion object {
        const val CHANNEL = "web_notifications"
        val seq = AtomicInteger(0x6e00)
    }
}
