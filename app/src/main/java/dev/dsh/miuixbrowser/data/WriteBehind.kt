package dev.dsh.miuixbrowser.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 防抖写库调度器：[mark] 之后安静 [debounceMs] 毫秒才执行 [onFlush]；一直有改动则最迟 [maxWaitMs] 执行一次。
 *
 * 收藏（[FavoritesStore]）和脚本（[ExtensionStore]）原来各抄了一份相同的「dirty 标志 + 首次改动时间 + Handler 延后任务」。
 * 这里只收拢「什么时候写」；「写什么」（取快照、哪些表、怎么写）仍由各自的 [onFlush] 决定，
 * 脏标志也留在各自手里（收藏有三类，脚本只有一类）。
 *
 * 线程：[mark] 可以在任何线程调用（非主线程会转到主线程）；[onFlush] 一定在主线程执行，
 * 所以它可以直接读界面状态取快照，再把真正的写入交给 [AppDatabase.io]。
 */
internal class WriteBehind(
    private val debounceMs: Long = 300L,
    private val maxWaitMs: Long = 2_000L,
    private val onFlush: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var firstDirtyAt = 0L
    private val task = Runnable { flush() }

    /** 标记有改动待写：重新计时；距首次改动已满 [maxWaitMs] 则立刻写。 */
    fun mark() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { mark() }
            return
        }
        val now = SystemClock.uptimeMillis()
        if (firstDirtyAt == 0L) firstDirtyAt = now
        main.removeCallbacks(task)
        if (now - firstDirtyAt >= maxWaitMs) flush() else main.postDelayed(task, debounceMs)
    }

    /** 立即执行 [onFlush] 并取消待执行的延迟任务（应用退到后台时调用）。主线程。 */
    fun flush() {
        main.removeCallbacks(task)
        firstDirtyAt = 0L
        onFlush()
    }
}
