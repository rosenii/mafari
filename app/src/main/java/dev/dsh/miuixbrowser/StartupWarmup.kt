package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.crypto.CipherSuites
import dev.dsh.miuixbrowser.data.AppDatabase
import dev.dsh.miuixbrowser.util.Diag
import android.content.Context
import android.os.SystemClock

/**
 * 冷启动预热：把「首帧之前主线程必然要等」的加密库打开，提前到进程一创建就在后台开始。
 *
 * 为什么需要：BrowserState / FavoritesStore / ExtensionStore 在首次组合时都会同步调用 [AppDatabase.get]。
 * 打开加密库要依次经过：Keystore 解包主密钥 → 对称套件（HKDF + AES-256-GCM）解开库密钥 → SQLCipher 派生密钥（PBKDF2）
 * （2.1.77 前是 ML-KEM 解封装，这一步在低端机上是毫秒级的大数运算，且要加载 ML-KEM 相关类；见 CipherSuite.kt 的套件 3）
 * → 十几张表的建表 / 迁移（密码库、Firefox 同步引入后表更多）。这一串耗时 1~2 秒，
 * 而首帧要等 setContent 的首次组合完成才会画出，期间系统启动画面（纯色、无图标）一直停着，就是「白屏」。
 *
 * 做法：
 *  1. [MafariApp] 在 Application.onCreate 里就调用 [start]，比 Activity.onCreate 早，吃掉 Activity 创建 / 主题解析 / Compose 初始化的时间；
 *  2. [MainActivity] 首帧先画一个占位面，等 [awaitDb] 在后台线程返回后再组合 BrowserScreen，
 *     这样主线程不会被加密库卡住，系统启动画面立刻收起。
 *
 * [start] 幂等，可重复调用。
 */
internal object StartupWarmup {

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext

        // 与「取库密钥 / 打开库」互不依赖的两件事并行做：加载 SQLCipher 的 so、初始化本机落盘用的加密套件（对称套件，不再触发 BouncyCastle ML-KEM 类加载）
        Thread({
            runCatching { System.loadLibrary("sqlcipher") }
            runCatching { CipherSuites.deviceDefault }
        }, "mafari-prewarm-crypto").apply { isDaemon = true }.start()

        // 每个 WebView 标签创建时都要用到的注入脚本全文：提前在后台读进缓存，首个标签在主线程创建时直接命中，不再打开 asset
        Thread({
            runCatching { dev.dsh.miuixbrowser.browser.InjectAssets.read(app, "mafari_blob_cache.js") }
            runCatching { dev.dsh.miuixbrowser.browser.InjectAssets.read(app, "mafari_gm_relay_wv.js") }
        }, "mafari-prewarm-assets").apply { isDaemon = true }.start()

        Thread({
            val t0 = SystemClock.elapsedRealtime()
            runCatching { AppDatabase.get(app) }
            Diag.log("prewarm: encrypted db ready in ${SystemClock.elapsedRealtime() - t0}ms")
        }, "mafari-prewarm-db").apply { isDaemon = true }.start()
    }

    /** 加密库已经完全就绪（含迁移，或已确定打不开、降级处理）：此时再调用 [AppDatabase.get] 不会阻塞。 */
    fun isDbReady(): Boolean = AppDatabase.isReady()

    /** 阻塞直到加密库就绪。必须在非主线程调用。 */
    fun awaitDb(context: Context) {
        start(context)
        AppDatabase.awaitReady(context.applicationContext)
    }
}
