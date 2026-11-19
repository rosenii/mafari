package dev.dsh.miuixbrowser.ext

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Text-only WebExtension notifications, restricted to extension pages. */
class NotificationsApi(private val context: Context, private val pages: ExtensionPageHost) {
    private data class Entry(val id: String, val title: String, val message: String, val contextMessage: String, val priority: Int)
    private val entries = ConcurrentHashMap<String, ConcurrentHashMap<String, Entry>>()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ExtensionNotificationReceiver.callback = { extId, id, action ->
            when (action) {
                ACTION_CLICK -> {
                    entries[extId]?.remove(id)
                    pages.emitEvent(extId, EVENT_CLICKED, JSONObject().put("notificationId", id))
                }
                ACTION_DISMISS -> {
                    entries[extId]?.remove(id)
                    pages.emitEvent(extId, EVENT_CLOSED, JSONObject().put("notificationId", id).put("byUser", true))
                }
            }
        }
    }

    fun register(router: ApiRouter) {
        router.register("notifications.create") { ctx, args ->
            val first = args.argOrNull(0)
            val id: String
            val options: JSONObject
            if (first is JSONObject) {
                id = UUID.randomUUID().toString()
                options = first
            } else {
                id = (first as? String).orEmpty().ifEmpty { UUID.randomUUID().toString() }
                options = args.argOrNull(1) as? JSONObject
                    ?: throw ApiException(ErrorCodes.BAD_REQUEST, "notifications.create 需要 options 对象")
            }
            create(ctx.ext, id, options)
            id
        }
        router.register("notifications.update") { ctx, args ->
            val id = args.argOrNull(0) as? String ?: throw ApiException(ErrorCodes.BAD_REQUEST, "notificationId 必须是字符串")
            val options = args.argOrNull(1) as? JSONObject ?: throw ApiException(ErrorCodes.BAD_REQUEST, "options 必须是对象")
            update(ctx.ext, id, options)
        }
        router.register("notifications.clear") { ctx, args ->
            val id = args.argOrNull(0) as? String ?: throw ApiException(ErrorCodes.BAD_REQUEST, "notificationId 必须是字符串")
            clear(ctx.ext, id)
        }
        router.register("notifications.getAll") { ctx, _ ->
            val out = JSONObject()
            entries[ctx.ext.spec.id]?.forEach { (id, entry) -> out.put(id, entry.toJson()) }
            out
        }
    }

    private fun create(ext: InstalledExtension, id: String, options: JSONObject) {
        if (id.length > MAX_ID_LENGTH) throw ApiException(ErrorCodes.BAD_REQUEST, "notificationId 过长")
        requireAndroidPermission()
        val entry = parseEntry(id, options)
        val byId = entries.computeIfAbsent(ext.spec.id) { ConcurrentHashMap() }
        if (byId.size >= MAX_PER_EXTENSION && id !in byId) throw ApiException(ErrorCodes.QUOTA, "每个拓展最多 $MAX_PER_EXTENSION 条通知")
        byId[id] = entry
        show(ext, entry)
    }

    private fun update(ext: InstalledExtension, id: String, options: JSONObject): Boolean {
        val old = entries[ext.spec.id]?.get(id) ?: return false
        requireAndroidPermission()
        val merged = JSONObject().put("title", old.title).put("message", old.message)
            .put("contextMessage", old.contextMessage).put("priority", old.priority)
        val keys = options.keys()
        while (keys.hasNext()) { val key = keys.next(); merged.put(key, options.opt(key)) }
        val next = parseEntry(id, merged)
        entries[ext.spec.id]?.set(id, next)
        show(ext, next)
        return true
    }

    private fun clear(ext: InstalledExtension, id: String): Boolean {
        val removed = entries[ext.spec.id]?.remove(id) != null
        manager.cancel(ext.spec.id, id.hashCode())
        if (removed) pages.emitEvent(ext.spec.id, EVENT_CLOSED, JSONObject().put("notificationId", id).put("byUser", false))
        return removed
    }

    private fun parseEntry(id: String, options: JSONObject): Entry {
        val keys = options.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in setOf("type", "title", "message", "contextMessage", "priority")) {
                throw ApiException(ErrorCodes.UNSUPPORTED, "通知字段暂不支持：$key")
            }
        }
        val type = options.optString("type", "basic")
        if (type != "basic") throw ApiException(ErrorCodes.UNSUPPORTED, "目前仅支持 basic 类型通知")
        val title = options.optString("title").take(MAX_FIELD_LENGTH)
        val message = options.optString("message").take(MAX_FIELD_LENGTH)
        val contextMessage = options.optString("contextMessage").take(MAX_FIELD_LENGTH)
        val priority = options.optInt("priority", 0).coerceIn(-2, 2)
        if (title.isBlank() && message.isBlank()) throw ApiException(ErrorCodes.BAD_REQUEST, "通知至少需要 title 或 message")
        return Entry(id, title, message, contextMessage, priority)
    }

    private fun show(ext: InstalledExtension, entry: Entry) {
        val channel = channelId(ext.spec.id, entry.priority)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(channel) == null) {
            val importance = when { entry.priority >= 2 -> NotificationManager.IMPORTANCE_HIGH; entry.priority <= -2 -> NotificationManager.IMPORTANCE_LOW; else -> NotificationManager.IMPORTANCE_DEFAULT }
            manager.createNotificationChannel(NotificationChannel(channel, "${ext.spec.name} notifications", importance))
        }
        val click = pendingIntent(ext.spec.id, entry.id, ACTION_CLICK, 0)
        val dismiss = pendingIntent(ext.spec.id, entry.id, ACTION_DISMISS, 1)
        val builder = createBuilder(channel)
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(entry.title).setContentText(entry.message)
            .setStyle(Notification.BigTextStyle().bigText(entry.message))
            .setContentIntent(click).setDeleteIntent(dismiss).setAutoCancel(true).setOnlyAlertOnce(true)
        if (entry.contextMessage.isNotBlank()) builder.setSubText(entry.contextMessage)
        manager.notify(ext.spec.id, entry.id.hashCode(), builder.build())
    }

    @Suppress("DEPRECATION")
    private fun createBuilder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else Notification.Builder(context)

    private fun pendingIntent(extId: String, id: String, action: String, salt: Int): PendingIntent {
        val intent = Intent(context, ExtensionNotificationReceiver::class.java).setAction(action)
            .putExtra(EXTRA_EXT_ID, extId).putExtra(EXTRA_NOTIFICATION_ID, id)
        return PendingIntent.getBroadcast(context, 31 * extId.hashCode() + id.hashCode() + salt, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun requireAndroidPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            throw ApiException(ErrorCodes.DENIED_PERMISSION, "Mafari 尚未获得 Android 通知权限，请先在系统设置中允许通知")
        if (!manager.areNotificationsEnabled()) throw ApiException(ErrorCodes.DENIED_PERMISSION, "Mafari 通知已被系统关闭")
    }

    private fun channelId(extId: String, priority: Int) = "webext_${extId.hashCode().toUInt().toString(16)}_${priority + 2}"
    private fun Entry.toJson() = JSONObject().put("type", "basic").put("title", title).put("message", message)
        .put("contextMessage", contextMessage).put("priority", priority)

    companion object {
        const val EVENT_CLICKED = "notifications.onClicked"
        const val EVENT_CLOSED = "notifications.onClosed"
        const val ACTION_CLICK = "dev.dsh.miuixbrowser.ext.NOTIFICATION_CLICK"
        const val ACTION_DISMISS = "dev.dsh.miuixbrowser.ext.NOTIFICATION_DISMISS"
        const val EXTRA_EXT_ID = "extensionId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        private const val MAX_ID_LENGTH = 256
        private const val MAX_FIELD_LENGTH = 4096
        private const val MAX_PER_EXTENSION = 64
    }
}

/** Non-exported receiver; invoked by the app's notification PendingIntents. */
class ExtensionNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extId = intent.getStringExtra(NotificationsApi.EXTRA_EXT_ID) ?: return
        val id = intent.getStringExtra(NotificationsApi.EXTRA_NOTIFICATION_ID) ?: return
        if (!ExtensionIds.isSafeId(extId) || id.length > 256) return
        callback?.invoke(extId, id, intent.action ?: return)
    }
    companion object { @Volatile var callback: ((String, String, String) -> Unit)? = null }
}
