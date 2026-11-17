package dev.dsh.miuixbrowser.download

import dev.dsh.miuixbrowser.i18n.tr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import org.json.JSONObject
import dev.dsh.miuixbrowser.MainActivity

/**
 * 下载前台服务：常驻进度通知。
 * - Android 16+：用 Notification.ProgressStyle 并申请「实时更新」（Live Updates），
 *   进度会显示在状态栏胶囊 / 锁屏 / 息屏显示上；下载完成、失败后不再申请，恢复为普通通知。
 * - HyperOS：同时附带超级岛参数（见 [islandJson]）。
 */
class DownloadService : Service() {

    private var islandShown = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = build(tr("准备下载"), 0, true, "0%")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // 进程被杀后下载任务已落盘、恢复成暂停，不需要系统用空 intent 重启出一个空转的前台服务
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            tr("下载"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = tr("文件下载进度")
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        // 完成 / 失败走单独的渠道：进度渠道是 IMPORTANCE_LOW 不会提醒，结果通知需要能弹出、有提示音
        val resultChannel = NotificationChannel(
            RESULT_CHANNEL_ID,
            tr("下载结果"),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = tr("下载完成 / 失败的提醒")
            setShowBadge(false)
        }
        manager.createNotificationChannel(resultChannel)
    }

    /** 下载完成 / 失败的独立通知：不带进度条、不常驻、不申请实时更新，点击进入下载页 */
    private fun buildResult(title: String, text: String, success: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
        }
        val pending = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error,
            )
            .setContentIntent(pending)
            .setOngoing(false)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .build()
    }

    /** 先发独立的结果通知，再把进度通知（前台通知）移除，避免同一个通知被原地改写而不提醒 */
    private fun postResult(title: String, text: String, success: Boolean) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(nextResultId(), buildResult(title, text, success))
        }
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** OS3（超岛协议）才注入岛参数；更低版本走普通焦点通知 */
    private fun islandProtocol(): Int = runCatching {
        Settings.System.getInt(contentResolver, "notification_focus_protocol", 0)
    }.getOrDefault(0)

    private fun islandJson(name: String, percent: Int, text: String, allowFloat: Boolean): String {
        val param = JSONObject()
            .put("protocol", 1)
            .put("business", "download")
            .put("enableFloat", allowFloat)
            .put("updatable", true)
            .put("ticker", tr("下载中 {0}%", percent))
            .put("aodTitle", tr("下载中 {0}%", percent))

        val bigArea = JSONObject().apply {
            put(
                "imageTextInfoLeft",
                JSONObject().apply {
                    put("type", 1)
                    put(
                        "textInfo",
                        JSONObject().apply {
                            put("frontTitle", tr("下载中"))
                            put("title", "$percent%")
                            put("content", text)
                            put("useHighLight", false)
                        },
                    )
                },
            )
            put(
                "textInfo",
                JSONObject().apply {
                    put("frontTitle", name)
                    put("title", "$percent%")
                    put("content", text)
                },
            )
        }
        val smallArea = JSONObject().apply {
            put(
                "textInfo",
                JSONObject().apply {
                    put("frontTitle", "$percent%")
                    put("title", tr("下载中"))
                    put("content", name)
                },
            )
        }
        val island = JSONObject()
            .put("islandProperty", 1)
            .put("bigIslandArea", bigArea)
            .put("smallIslandArea", smallArea)

        val baseInfo = JSONObject().apply {
            put("title", tr("正在下载 {0}", name))
            put("content", text)
            put("colorTitle", "#006EFF")
            put("type", 2)
        }
        val hintInfo = JSONObject().apply {
            put("type", 1)
            put("title", tr("下载中"))
        }
        param.put("param_island", island)
        param.put("baseInfo", baseInfo)
        param.put("hintInfo", hintInfo)

        return JSONObject().put("param_v2", param).toString()
    }

    private fun build(
        title: String,
        progress: Int,
        indeterminate: Boolean,
        text: String,
        islandName: String? = null,
        taskId: Long = -1L,
        allowFloat: Boolean = false,
        paused: Boolean = false,
        ongoing: Boolean = true,
    ): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
        }
        val pending = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)

        // Android 16 实时更新：只在进行中（含暂停）申请；完成 / 失败的通知是普通通知
        if (Build.VERSION.SDK_INT >= 36 && ongoing) {
            runCatching {
                builder.setStyle(
                    Notification.ProgressStyle()
                        .setProgressIndeterminate(indeterminate)
                        .setProgress(progress.coerceIn(0, 100))
                        .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100))),
                )
                builder.setRequestPromotedOngoing(true)
                // 状态栏胶囊里显示的短文本（最多约 7 个字符）
                builder.setShortCriticalText(if (paused) tr("已暂停") else if (indeterminate) "…" else "$progress%")
            }
        }

        if (taskId > 0L) {
            val action = if (paused) {
                DownloadActionReceiver.ACTION_RESUME to tr("继续")
            } else {
                DownloadActionReceiver.ACTION_PAUSE to tr("暂停")
            }
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                    ),
                    action.second,
                    broadcast(taskId, action.first, 1),
                ).build(),
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        android.R.drawable.ic_menu_close_clear_cancel,
                    ),
                    tr("取消"),
                    broadcast(taskId, DownloadActionReceiver.ACTION_CANCEL, 2),
                ).build(),
            )
        }
        val notification = builder.build()
        // 岛参数每次带上（这样岛上能看到进度），但只有第一次允许浮出
        if (islandName != null && islandProtocol() >= 3) {
            runCatching {
                notification.extras.putString(
                    "miui.focus.param",
                    islandJson(islandName, progress, text, allowFloat && !islandShown),
                )
                notification.extras.putString("miui.focus.business", "download")
                islandShown = true
            }
        }
        return notification
    }

    /** 开始下载：允许岛浮出一次 */
    fun showStart(name: String, taskId: Long) {
        islandShown = false
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(
                    NOTIFICATION_ID,
                    build(tr("正在下载"), 0, true, name, name, taskId, allowFloat = true),
                )
            islandShown = true
        }
    }

    /** 进度更新：同步刷新岛内容（不浮出） */
    fun update(name: String, progress: Float, text: String, taskId: Long) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(
                    NOTIFICATION_ID,
                    build(name, (progress * 100).toInt(), false, text, name, taskId),
                )
        }
    }

    fun updatePaused(name: String, progress: Float, taskId: Long) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(
                    NOTIFICATION_ID,
                    build(tr("已暂停"), (progress * 100).toInt(), false, name, name, taskId, paused = true),
                )
        }
    }

    private fun broadcast(taskId: Long, action: String, requestCode: Int): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            this,
            requestCode,
            android.content.Intent(this, DownloadActionReceiver::class.java)
                .setAction(action)
                .putExtra(DownloadActionReceiver.EXTRA_ID, taskId),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun finish(text: String) {
        postResult(tr("下载完成"), text, success = true)
    }

    fun fail(text: String) {
        postResult(tr("下载失败"), text, success = false)
    }

    companion object {
        const val CHANNEL_ID = "mafari_download"
        const val RESULT_CHANNEL_ID = "mafari_download_result"
        const val NOTIFICATION_ID = 4201
        private const val RESULT_ID_BASE = 4300
        private val resultSeq = java.util.concurrent.atomic.AtomicInteger(0)

        /** 每次完成 / 失败用不同的通知 ID，多个任务的结果互不覆盖（循环使用 100 个 ID） */
        private fun nextResultId(): Int = RESULT_ID_BASE + (resultSeq.getAndIncrement() % 100)
        @Volatile
        var instance: DownloadService? = null

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, DownloadService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
