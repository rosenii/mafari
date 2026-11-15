package dev.dsh.miuixbrowser.util

import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job

/**
 * 取消协程 Job，显式传入 cause，避免编译出对 `Job.cancel$default` 的静态调用。
 *
 * 背景：lite 变体里 application-services / GeckoView 只以 compileOnly 参与编译，会把较新的
 * kotlinx-coroutines 带进编译 classpath，而运行时打进 APK 的是较旧版本。较新版本的 `Job` 接口里
 * `cancel(cause = null)` 的默认参数桥接方法是 `Job.cancel$default`（静态），旧版本没有它，
 * 运行到 `job.cancel()` 就抛 NoSuchMethodError 闪退。显式传参后调用的是 `Job.cancel(CancellationException?)`，
 * 两个版本都有，与编译 / 运行时的 coroutines 版本差异无关。
 */
fun Job.cancelCompat() {
    cancel(null as CancellationException?)
}
