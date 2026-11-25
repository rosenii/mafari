/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.translate

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * 进程内的翻译调度：按配置复用 [TranslateService]（译文缓存、Bing token 都跟着它走），
 * 请求在小线程池里跑，回调也在工作线程上（调用方自己切回主线程）。
 */
object TranslateManager {
    private val pool = Executors.newFixedThreadPool(
        3,
        ThreadFactory { r -> Thread(r, "mafari-translate").apply { isDaemon = true } },
    )

    @Volatile private var cachedCfg: TranslateConfig? = null
    @Volatile private var cachedService: TranslateService? = null

    @Synchronized
    private fun serviceFor(cfg: TranslateConfig): TranslateService {
        val cur = cachedService
        if (cur != null && cachedCfg == cfg) return cur
        return TranslateSources.createService(cfg).also {
            cachedCfg = cfg
            cachedService = it
        }
    }

    /** 清掉缓存（换翻译源 / 改了地址或 Key 时调用；配置本身变化也会自动换新，这里是给「清除缓存」用的） */
    @Synchronized
    fun reset() {
        cachedCfg = null
        cachedService = null
    }

    /** 异步翻译一批文本，[callback] 在工作线程上调用，恰好一次。 */
    fun submit(cfg: TranslateConfig, texts: List<String>, callback: (Result<List<String>>) -> Unit) {
        pool.execute {
            val result = try {
                Result.success(serviceFor(cfg).translate(texts))
            } catch (e: TranslateException) {
                Result.failure(e)
            } catch (e: Throwable) {
                Result.failure(TranslateException(TranslateException.Kind.PARSE, e.javaClass.simpleName, e))
            }
            callback(result)
        }
    }
}
