/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.engine.gecko

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.GeckoResult
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 把 GeckoResult 挂起等待结果（成功返回值，可能为 null；失败抛出原异常）。
 * lite / liteSync 变体里 GeckoView 只以 compileOnly 参与编译，共用代码里的 await 也靠它通过编译，运行时不会被调用。
 */
suspend fun <T> GeckoResult<T>.await(): T? = suspendCancellableCoroutine { cont ->
    this.then<Void>(
        { value ->
            if (cont.isActive) cont.resume(value)
            null
        },
        { error ->
            if (cont.isActive) cont.resumeWithException(error)
            null
        },
    )
}

/** Compose 的 context 常是 ContextWrapper，剥到 Activity；找不到返回 null */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
