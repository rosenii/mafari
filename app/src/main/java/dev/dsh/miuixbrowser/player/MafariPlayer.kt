/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)

package dev.dsh.miuixbrowser.player

import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Flags
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dev.dsh.miuixbrowser.i18n.tr

/** 清晰度条目：[group] / [index] 对应 ExoPlayer 轨道，选中时用 TrackSelectionOverride。 */
class Quality(val label: String, val height: Int, val bitrate: Int, internal val group: Tracks.Group, internal val index: Int, val hdr: Boolean = false)

/**
 * 原生播放器核心：ExoPlayer + 带请求头的网络层 + 伪装图片头裁剪。
 * 界面状态用 Compose State 暴露，必须在主线程使用（ExoPlayer 默认的应用线程）。
 * 这一层不碰任何界面类，后面悬浮窗 / 画中画 / 后台播放都复用同一个实例。
 */
class MafariPlayer(context: Context, val request: PlayerRequest) {

    val exo: ExoPlayer

    var isPlaying by mutableStateOf(false); private set
    var buffering by mutableStateOf(true); private set
    var positionMs by mutableLongStateOf(0); private set
    var durationMs by mutableLongStateOf(0); private set
    var bufferedMs by mutableLongStateOf(0); private set
    var speed by mutableFloatStateOf(1f); private set
    var muted by mutableStateOf(false); private set

    /** 播放器自身音量 0..1（ExoPlayer 的 volume，叠在系统媒体音量之上，不改系统音量） */
    var volume by mutableFloatStateOf(1f); private set
    var videoWidth by mutableIntStateOf(0); private set
    var videoHeight by mutableIntStateOf(0); private set
    var error by mutableStateOf<String?>(null); private set
    var qualities by mutableStateOf<List<Quality>>(emptyList()); private set

    /** 手动选中的清晰度；null = 自动（ExoPlayer 按带宽选） */
    var selectedQuality by mutableStateOf<Quality?>(null); private set

    /** 直播流：时长未知 / 动态窗口。进度条按这个决定是否可拖 */
    var isLive by mutableStateOf(false); private set

    /** 当前在用的视频解码器名（MediaCodec 名，如 c2.qti.avc.decoder）；还没初始化 / 已释放时为 null。菜单里「解码方式」显示它 */
    var videoDecoder by mutableStateOf<String?>(null); private set

    /** 可切换的视频源（主地址在最前）；只有一个时界面不显示源列表 */
    val sources: List<PlayerSource> = request.allSources()

    /** 当前正在播的源下标 */
    var sourceIndex by mutableIntStateOf(0); private set

    /** 当前播放的请求（地址 / 类型随切源变化，请求头不变）：复制链接 / 下载用它 */
    val currentRequest: PlayerRequest
        get() = sources.getOrNull(sourceIndex)?.let { request.copy(url = it.url, type = it.type, audioUrl = it.audioUrl) } ?: request

    /** 网络 / 本地数据源（和 ExoPlayer 默认工厂用的是同一个）：画面和声音分开的流要自己拼 MediaSource 时用 */
    private lateinit var mediaDataSource: androidx.media3.datasource.DataSource.Factory

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@MafariPlayer.isPlaying = isPlaying
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            syncPageMediaGate()
        }

        override fun onPlaybackStateChanged(state: Int) {
            syncPageMediaGate()
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) error = null
            refresh()
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            videoWidth = size.width
            // 带像素宽高比（非方形像素）时折算成显示用的宽度
            if (size.pixelWidthHeightRatio != 1f && size.pixelWidthHeightRatio > 0f) {
                videoWidth = (size.width * size.pixelWidthHeightRatio).toInt()
            }
            videoHeight = size.height
        }

        override fun onTracksChanged(tracks: Tracks) {
            val list = ArrayList<Quality>()
            for (g in tracks.groups) {
                if (g.type != C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until g.length) {
                    if (!g.isTrackSupported(i)) continue
                    val f = g.getTrackFormat(i)
                    val h = f.height.takeIf { it > 0 } ?: continue
                    val br = if (f.bitrate > 0) f.bitrate else 0
                    val hdr = isHdr(f)
                    list += Quality(label = if (hdr) "${h}p HDR" else "${h}p", height = h, bitrate = br, group = g, index = i, hdr = hdr)
                }
            }
            // 同高度同色域只留码率最高的一条，高到低排列（同高度 SDR 在前）
            qualities = list.groupBy { it.height to it.hdr }.map { (_, v) -> v.maxBy { it.bitrate } }
                .sortedWith(compareByDescending<Quality> { it.height }.thenBy { it.hdr })
            applyHdrPreference(tracks)
        }

        override fun onPlayerError(e: PlaybackException) {
            error = describe(e)
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = refresh()
    }

    /** 本次播放用的高级选项（打开播放器时读一次；改设置后下次打开生效） */
    private val tuning = PlayerTuning.read(context)

    init {
        // Media3 1.12 的实验特性开关（Flags 是进程级的静态状态，必须在创建 ExoPlayer 之前设；每次都显式设一遍，
        // 这样设置页改回默认后，上一次留下的覆盖值不会残留）
        applyMedia3Flags(tuning)

        val (connectMs, readMs) = tuning.netTimeouts
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(connectMs)
            .setReadTimeoutMs(readMs)
            .setDefaultRequestProperties(request.headers())
            .apply { if (request.ua.isNotBlank()) setUserAgent(request.ua) }
        // 只有 http(s) 走裁剪包装；file / content 等由 DefaultDataSource 处理
        val dataSource = DefaultDataSource.Factory(context, StripImageHeaderDataSource.Factory(http))
        mediaDataSource = dataSource

        // 解码：硬 / 软件优先、解码器回退、MediaCodec 异步队列
        val renderers = DefaultRenderersFactory(context)
            .setMediaCodecSelector(codecSelector(tuning.decoder))
            .setEnableDecoderFallback(tuning.decoderFallback)
            .apply {
                when (tuning.asyncCodec) {
                    PlayerTuning.ASYNC_ON -> forceEnableMediaCodecAsynchronousQueueing()
                    PlayerTuning.ASYNC_OFF -> forceDisableMediaCodecAsynchronousQueueing()
                }
            }

        // 缓冲 / 内存：时长档位 + 字节上限 + 后向缓冲。默认「均衡」= 15s / 60s / 1s / 2s，和以前写死的一样；
        // 网页视频更在意首帧速度，起播门槛都很低
        val d = tuning.bufferDurations
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(d.minMs, d.maxMs, d.playbackMs, d.rebufferMs)
            .apply {
                if (tuning.bufferBytes > 0) setTargetBufferBytes(tuning.bufferBytes)
                if (tuning.backBufferMs > 0) setBackBuffer(tuning.backBufferMs, true)
            }
            .build()

        // 轨道选择：清晰度上限 + 隧道播放。隧道开关只在 DefaultTrackSelector 的参数里有（TrackSelectionParameters 没有）
        val trackSelector = DefaultTrackSelector(context).also { ts ->
            ts.parameters = ts.buildUponParameters().apply {
                // 清晰度上限（长边）：自动选档时不超过它；手动选更高的档不受限
                tuning.maxLongSide.takeIf { it > 0 }?.let { setMaxVideoSize(it, it) }
                // 隧道播放：要 SurfaceView + 硬解 + 设备支持；TextureView 下画面出不来，所以只在渲染方式是 SurfaceView 时开
                // （播放页里临时切回 TextureView 不会重建播放器，此时请关掉这个选项）
                if (tuning.tunneling && PlayerPrefs.renderMode(context) == PlayerPrefs.RENDER_SURFACE) setTunnelingEnabled(true)
            }.build()
        }

        exo = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            // 渐进式文件（MP4 等）只加载选中的画面轨 / 图片轨：Media3 1.12 起默认开，这里按设置显式给值
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(dataSource)
                    .setLoadOnlySelectedTracks(tuning.loadOnlySelectedTracks),
            )
            .setLoadControl(loadControl)
            // 音频焦点：来电 / 别的播放器抢焦点时自动暂停（后台播放必须有，否则会和别的音频叠在一起）；
            // 高级选项里可以关掉，和别的应用混着放
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                tuning.audioFocus,
            )
            // 拔耳机 / 蓝牙断开时暂停，免得外放
            .setHandleAudioBecomingNoisy(tuning.pauseOnNoisy)
            // 后台播放（屏幕关闭）时保持 CPU 和 Wi-Fi 唤醒，否则几分钟后系统会掐掉网络
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // 卡死检测（1.12 起默认开：进度停住 10 秒 / 播完不结束 60 秒 / 缓冲无进展 10 分钟就报 StuckPlayerException）。
            // 「默认」不改库里的值；「宽松」放宽三个超时；「关闭」由 applyMedia3Flags 关掉开关并把三个超时拉满
            .apply {
                tuning.stuckTimeouts?.let {
                    setStuckPlayingDetectionTimeoutMs(it.playingMs)
                    setStuckPlayingNotEndingTimeoutMs(it.notEndingMs)
                    setStuckBufferingDetectionTimeoutMs(it.bufferingMs)
                }
            }
            .build()
        exo.setSeekParameters(if (tuning.seekMode == PlayerTuning.SEEK_KEYFRAME) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT)
        exo.addListener(listener)
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoder = decoderName
            }

            override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                if (videoDecoder == decoderName) videoDecoder = null
            }
        })
        // 设置里的「默认倍速」：打开就按这个速度起播（播放页里仍可临时改）
        PlayerPrefs.defaultSpeed(context).takeIf { it != 1f }?.let { changeSpeed(it) }
        setSource(currentRequest, request.startMs.coerceAtLeast(0))
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * Media3 1.12 的 [Flags] 注册表：灰度总开关 + 单项覆盖。必须在创建任何 Media3 组件前调用，且在应用线程。
     * 单项覆盖优先于灰度开关；对「库里静态默认值就是这个值」的开关，enable/disable 会顺手清掉旧覆盖，所以每次都调用即可。
     */
    private fun applyMedia3Flags(t: PlayerTuning) {
        Flags.setCanaryModeEnabled(t.canary)
        fun set(flag: Int, on: Boolean) = if (on) Flags.enableFlag(flag) else Flags.disableFlag(flag)
        set(Flags.FLAG_ENABLE_STUCK_PLAYING_DETECTION, t.stuckDetectionEnabled)
        set(Flags.FLAG_PLAY_PAUSE_AUDIO_TRACK_ON_INIT, t.fastStart)
        set(Flags.FLAG_ENABLE_MEDIACODEC_BUFFER_DECODE_ONLY, t.fastSeek)
    }

    /** 设置里是否开了「拖动进度时实时预览」：进度条据此决定拖动过程中要不要调 [scrubTo] */
    val scrubPreview: Boolean get() = tuning.scrubPreview

    private var scrubbing = false

    /**
     * 拖动进度条时实时预览：进入 ExoPlayer 的 Scrubbing 模式（拖动期间暂停声音、放宽解码器 flush 等，专为连续 seek 优化），
     * 并跳到 [ms]。必须配合 [endScrub]（松手 / 取消）使用，Scrubbing 模式会多占资源，不能一直开着。没开这个选项时什么也不做。
     */
    fun scrubTo(ms: Long) {
        if (!tuning.scrubPreview) return
        if (!scrubbing) {
            scrubbing = true
            exo.setScrubbingModeEnabled(true)
        }
        exo.seekTo(ms.coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
    }

    /** 退出 Scrubbing 模式（没进过就什么也不做）；之后由调用方再 [seekTo] 到松手的位置 */
    fun endScrub() {
        if (!scrubbing) return
        scrubbing = false
        exo.setScrubbingModeEnabled(false)
    }

    /** 解码器顺序：在系统给的候选里把硬解 / 软解挪到前面（不会删掉任何候选，所以不会因此变得放不了）。 */
    private fun codecSelector(pref: Int): MediaCodecSelector = when (pref) {
        PlayerTuning.DEC_HW_FIRST -> MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                .sortedBy { if (it.hardwareAccelerated && !it.softwareOnly) 0 else 1 }
        }
        PlayerTuning.DEC_SW_FIRST -> MediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                .sortedBy { if (it.softwareOnly) 0 else 1 }
        }
        else -> MediaCodecSelector.DEFAULT
    }

    /** 这一条视频轨是不是 HDR（PQ / HLG 传递函数，或杜比视界） */
    private fun isHdr(f: androidx.media3.common.Format): Boolean {
        val t = f.colorInfo?.colorTransfer
        return t == C.COLOR_TRANSFER_ST2084 || t == C.COLOR_TRANSFER_HLG || f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
    }

    /**
     * HDR 策略（高级选项）：流里同时有 HDR 和 SDR 的轨道时，自动选档只在偏好的那一类里挑。
     * 做法是给视频组加一个「轨道子集」覆盖（自适应仍然有效）；用户手动选了清晰度、或已经有覆盖时不动，所以不会反复触发。
     * 只有一类轨道（多数网页视频）时什么也不做。
     */
    private fun applyHdrPreference(tracks: Tracks) {
        val pref = tuning.hdr
        if (pref == PlayerTuning.HDR_AUTO || selectedQuality != null) return
        if (exo.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }) return
        val wantHdr = pref == PlayerTuning.HDR_PREFER_HDR
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_VIDEO) continue
            val all = (0 until g.length).filter { g.isTrackSupported(it) }
            val wanted = all.filter { isHdr(g.getTrackFormat(it)) == wantHdr }
            if (wanted.isEmpty() || wanted.size == all.size) continue
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, wanted))
                .build()
            return
        }
    }

    private fun buildItem(r: PlayerRequest): MediaItem = MediaItem.Builder().setUri(r.url).apply {
        if (r.isHls()) setMimeType(MimeTypes.APPLICATION_M3U8)
        if (r.title.isNotBlank()) {
            setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(r.title).build())
        }
    }.build()

    /** 切到第 [i] 个视频源，保留当前进度（直播流从头 / 现场开始）；清晰度选择随新源重置。 */
    fun playSource(i: Int) {
        if (i !in sources.indices || i == sourceIndex) return
        val pos = if (isLive) 0L else exo.currentPosition.coerceAtLeast(0)
        sourceIndex = i
        error = null
        selectedQuality = null
        qualities = emptyList()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        setSource(currentRequest, pos)
        exo.prepare()
        exo.play()
    }

    /**
     * 装上 [r] 的媒体：普通地址走 MediaItem；画面和声音是两条独立流（[PlayerRequest.audioUrl]，YouTube 自适应流）时，
     * 用两个 ProgressiveMediaSource 合并成一个源，进度 / 暂停 / 清晰度选择都照常。
     */
    private fun setSource(r: PlayerRequest, startMs: Long) {
        if (!r.isSplit) {
            exo.setMediaItem(buildItem(r), startMs)
            return
        }
        val factory = ProgressiveMediaSource.Factory(mediaDataSource)
        val video = factory.createMediaSource(buildItem(r))
        val audio = factory.createMediaSource(MediaItem.fromUri(r.audioUrl))
        exo.setMediaSource(MergingMediaSource(video, audio), startMs)
    }

    /**
     * 两种显示方式：TextureView 画进 View 层级，能被 miuix 的 layerBackdrop 采样做玻璃模糊；
     * SurfaceView 由系统合成器直接送显，更省电、延迟低，但采样不到（界面会退回不透明底）。
     * 切换时界面会先建新 View 再释放旧的，ExoPlayer 的 clear 只在 View 仍是当前输出时才生效，顺序无所谓。
     */
    /** 当前接着视频输出的 View：截屏要从它取画面 */
    private var videoView: View? = null

    fun attach(view: View) {
        videoView = view
        when (view) {
            is TextureView -> exo.setVideoTextureView(view)
            is SurfaceView -> exo.setVideoSurfaceView(view)
        }
    }

    fun detach(view: View) {
        if (videoView === view) videoView = null
        when (view) {
            is TextureView -> exo.clearVideoTextureView(view)
            is SurfaceView -> exo.clearVideoSurfaceView(view)
        }
    }

    /**
     * 截取当前正在播放的画面（按视频原始分辨率，长边最多 3840）。必须在主线程调用，回调也在主线程；
     * 拿不到（还没出画面 / 受保护内容 / View 已销毁）回 null。
     * TextureView 直接读纹理；SurfaceView 走 PixelCopy（两种渲染方式都能截）。
     */
    fun captureFrame(onDone: (android.graphics.Bitmap?) -> Unit) {
        val v = videoView
        val size = exo.videoSize
        if (v == null || size.width <= 0 || size.height <= 0) { onDone(null); return }
        val k = minOf(1f, 3840f / maxOf(size.width, size.height))
        val w = (size.width * k).toInt().coerceAtLeast(2)
        val h = (size.height * k).toInt().coerceAtLeast(2)
        when (v) {
            is TextureView -> onDone(runCatching { v.getBitmap(w, h) }.getOrNull())
            is SurfaceView -> {
                val bmp = runCatching { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) }.getOrNull()
                if (bmp == null) { onDone(null); return }
                runCatching {
                    android.view.PixelCopy.request(v, bmp, { r -> onDone(if (r == android.view.PixelCopy.SUCCESS) bmp else null) }, android.os.Handler(android.os.Looper.getMainLooper()))
                }.onFailure { onDone(null) }
            }
            else -> onDone(null)
        }
    }

    /** 周期性刷新进度（界面每 250ms 调一次；不在播放时也要刷新拖动后的位置） */
    fun refresh() {
        positionMs = exo.currentPosition.coerceAtLeast(0)
        bufferedMs = exo.bufferedPosition.coerceAtLeast(0)
        val d = exo.duration
        durationMs = if (d == C.TIME_UNSET) 0 else d
        isLive = exo.isCurrentMediaItemLive || (exo.isCurrentMediaItemDynamic && d == C.TIME_UNSET)
    }

    fun toggle() { if (exo.isPlaying) exo.pause() else { if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0); exo.play() } }
    fun play() = exo.play()
    fun pause() = exo.pause()

    fun seekTo(ms: Long) { exo.seekTo(ms.coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)); refresh() }
    fun seekBy(deltaMs: Long) = seekTo(exo.currentPosition + deltaMs)

    fun changeSpeed(s: Float) {
        exo.playbackParameters = PlaybackParameters(s)
        speed = s
    }

    /** 拖到 0 以上自动取消静音；拖到 0 等同静音（界面按 [muted] 或 volume == 0 显示喇叭关） */
    fun changeVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        if (volume > 0f) muted = false
        applyVolume()
    }

    fun toggleMute() {
        if (muted || volume == 0f) {
            muted = false
            if (volume == 0f) volume = 0.5f // 从 0 取消静音时给个可听见的音量
        } else {
            muted = true
        }
        applyVolume()
    }

    private fun applyVolume() {
        exo.volume = if (muted) 0f else volume
    }

    /** null = 自动 */
    fun selectQuality(q: Quality?) {
        val b = exo.trackSelectionParameters.buildUpon()
        if (q == null) {
            b.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            b.setOverrideForType(TrackSelectionOverride(q.group.mediaTrackGroup, q.index))
        }
        exo.trackSelectionParameters = b.build()
        selectedQuality = q
    }

    fun retry() {
        error = null
        exo.prepare()
        exo.play()
    }

    /** 想播放（含缓冲中）且没播完 / 没出错时，网页里的媒体一律停住；暂停 / 播完 / 释放后解除 */
    private fun syncPageMediaGate() {
        val active = exo.playWhenReady &&
            (exo.playbackState == Player.STATE_READY || exo.playbackState == Player.STATE_BUFFERING)
        dev.dsh.miuixbrowser.browser.PageMediaGate.setPlaying(this, active)
    }

    fun release() {
        dev.dsh.miuixbrowser.browser.PageMediaGate.setPlaying(this, false)
        endScrub()
        exo.removeListener(listener)
        exo.release()
    }

    private fun describe(e: PlaybackException): String {
        // 卡死检测（Media3 1.12 起默认开）报的错，cause 是 StuckPlayerException，错误码是 ERROR_CODE_TIMEOUT
        // （引擎内部「缓冲不加载」那一条是 ERROR_CODE_FAILED_RUNTIME_CHECK）。单独翻译成人话，不然界面只会显示「播放失败：ERROR_CODE_TIMEOUT」
        (e.cause as? StuckPlayerException)?.let { return describeStuck(it) }
        return describeCode(e)
    }

    private fun describeStuck(e: StuckPlayerException): String {
        val sec = (e.timeoutMs / 1000).coerceAtLeast(1)
        return when (e.stuckType) {
            StuckPlayerException.STUCK_PLAYING_NOT_ENDING -> tr("视频已经播到结尾却没有结束（{0} 秒），可以点击重试", sec)
            StuckPlayerException.STUCK_BUFFERING_NOT_LOADING,
            StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS -> tr("缓冲卡住了（{0} 秒没有进展），网络不好或源已失效，可以点击重试或换个源", sec)
            else -> tr("画面卡住了（{0} 秒没有进展），可以点击重试；经常出现的话可以在设置里把「卡死检测」调成宽松", sec)
        }
    }

    private fun describeCode(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val code = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            when (code) {
                403, 401 -> tr("服务器拒绝访问（{0}），可能需要登录或带防盗链信息", code)
                404 -> tr("资源不存在（404）")
                null -> tr("服务器返回了错误状态")
                else -> tr("服务器返回错误（{0}）", code)
            }
        }
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> tr("网络连接失败")
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> tr("无法识别的视频格式")
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> tr("设备不支持这种编码")
        else -> tr("播放失败：{0}", e.errorCodeName)
    }
}

/** 按 MediaCodec 名判断是不是软件解码器（Google 自带的 OMX.google / c2.android / c2.google，或 ffmpeg 类扩展）；其余按硬件算。 */
internal fun isSoftwareDecoder(name: String): Boolean {
    val n = name.lowercase()
    return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.") || n.contains("ffmpeg")
}
