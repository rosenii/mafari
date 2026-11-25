/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.dsh.miuixbrowser.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.media3.common.Player
import dev.dsh.miuixbrowser.i18n.tr

/**
 * 后台播放：前台服务 + MediaSession + 媒体通知（锁屏 / 通知栏可以暂停、快进退、关闭）。
 *
 * 播放器本体仍由 [NativePlayerActivity] 持有（[MafariPlayer] 单实例，界面 / 画中画 / 后台共用），
 * 服务只做「让进程在后台保持前台级别 + 把状态投给系统」，不自己创建 ExoPlayer。
 * 活动销毁时 [stop]；通知上的 ✕ 经 [closeCallback] 让活动结束。
 */
class PlayerService : Service() {

    private var session: MediaSession? = null
    private var attached: MafariPlayer? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = current
        if (p == null) {
            // 活动已经没了（进程被回收后系统不会重启我们，这里只防竞态）
            stopSelf()
            return START_NOT_STICKY
        }
        if (attached !== p) {
            attached?.exo?.removeListener(listener)
            attached = p
            p.exo.addListener(listener)
        }
        ensureSession()
        when (intent?.action) {
            ACT_PLAY -> p.play()
            ACT_PAUSE -> p.pause()
            ACT_BACK -> p.seekBy(-SKIP_MS)
            ACT_FWD -> p.seekBy(SKIP_MS)
            ACT_CLOSE -> {
                p.pause()
                closeCallback?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIF_ID, buildNotification(p), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        publish()
        return START_NOT_STICKY
    }

    private fun ensureSession() {
        if (session != null) return
        session = MediaSession(this, "MafariPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { current?.play() }
                override fun onPause() { current?.pause() }
                override fun onSeekTo(pos: Long) { current?.seekTo(pos) }
                override fun onFastForward() { current?.seekBy(SKIP_MS) }
                override fun onRewind() { current?.seekBy(-SKIP_MS) }
                override fun onStop() {
                    current?.pause()
                    closeCallback?.invoke()
                    stopSelf()
                }
            })
            isActive = true
        }
    }

    /** 把播放状态 / 元数据同步给 MediaSession，并刷新通知（状态变化时由 ExoPlayer 的事件触发）。 */
    private fun publish() {
        val p = attached ?: return
        val s = session ?: return
        p.refresh()
        val state = when {
            p.error != null -> PlaybackState.STATE_ERROR
            p.buffering -> PlaybackState.STATE_BUFFERING
            p.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP or
            (if (!p.isLive && p.durationMs > 0) PlaybackState.ACTION_SEEK_TO else 0L)
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, p.positionMs, if (p.isPlaying) p.speed else 0f)
                .build(),
        )
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titleOf(p))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Mafari")
                .apply { if (p.durationMs > 0) putLong(MediaMetadata.METADATA_KEY_DURATION, p.durationMs) }
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(p))
    }

    private fun titleOf(p: MafariPlayer): String = p.request.title.ifBlank { tr("视频") }

    private fun action(code: Int, icon: Int, title: String, act: String): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon), title,
            PendingIntent.getService(
                this, code, Intent(this, PlayerService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

    private fun buildNotification(p: MafariPlayer): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, tr("后台播放"), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, NativePlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val playPause = if (p.isPlaying) {
            action(2, android.R.drawable.ic_media_pause, tr("暂停"), ACT_PAUSE)
        } else {
            action(3, android.R.drawable.ic_media_play, tr("播放"), ACT_PLAY)
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(titleOf(p))
            .setContentText(if (p.isPlaying) tr("正在播放") else tr("已暂停"))
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(p.isPlaying)
            .addAction(action(1, android.R.drawable.ic_media_rew, tr("后退 10 秒"), ACT_BACK))
            .addAction(playPause)
            .addAction(action(4, android.R.drawable.ic_media_ff, tr("快进 10 秒"), ACT_FWD))
            .addAction(action(5, android.R.drawable.ic_menu_close_clear_cancel, tr("关闭"), ACT_CLOSE))
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { style.setMediaSession(it.sessionToken) }
        b.setStyle(style)
        return b.build()
    }

    override fun onDestroy() {
        attached?.exo?.removeListener(listener)
        attached = null
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "mafari_player"
        private const val NOTIF_ID = 0x4D50 // "MP"
        private const val SKIP_MS = 10_000L
        private const val ACT_PLAY = "dev.dsh.miuixbrowser.player.PLAY"
        private const val ACT_PAUSE = "dev.dsh.miuixbrowser.player.PAUSE"
        private const val ACT_BACK = "dev.dsh.miuixbrowser.player.BACK"
        private const val ACT_FWD = "dev.dsh.miuixbrowser.player.FWD"
        private const val ACT_CLOSE = "dev.dsh.miuixbrowser.player.CLOSE"

        /** 服务操作的播放器：由 [NativePlayerActivity] 在 [start] 前后设置 / 清掉（只在主线程读写）。 */
        @Volatile internal var current: MafariPlayer? = null

        /** 通知上点「关闭」：让活动结束（活动销毁时清掉，避免泄漏） */
        @Volatile internal var closeCallback: (() -> Unit)? = null

        /** 必须在界面可见时调用（前台服务的后台启动限制）。 */
        fun start(context: Context, player: MafariPlayer) {
            current = player
            runCatching { context.startForegroundService(Intent(context, PlayerService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlayerService::class.java))
        }
    }
}
