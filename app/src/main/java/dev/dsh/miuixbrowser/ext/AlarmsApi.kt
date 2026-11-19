package dev.dsh.miuixbrowser.ext

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

/** 一个闹钟。[periodInMinutes] 为 null 表示一次性 */
class Alarm(val name: String, val scheduledTime: Long, val periodInMinutes: Double?) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("name", name).put("scheduledTime", scheduledTime.toDouble())
        if (periodInMinutes != null) o.put("periodInMinutes", periodInMinutes)
        return o
    }
}

/** 唤醒定时器：全局只有一个挂着的唤醒。[schedule] 会顶掉之前挂的那一个。实现必须线程安全 */
interface WakeTimer {
    fun schedule(delayMs: Long, task: Runnable)
    fun cancel()
}

/**
 * 生产用的 [WakeTimer]：主线程 Handler 叫醒，真正的工作立刻切到 [worker]（后台线程），主线程不碰文件也不投递事件。
 */
class HandlerWakeTimer(
    private val worker: Executor,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : WakeTimer {
    private var current: Runnable? = null

    @Synchronized
    override fun schedule(delayMs: Long, task: Runnable) {
        current?.let { handler.removeCallbacks(it) }
        val wake = Runnable { worker.execute(task) }
        current = wake
        handler.postDelayed(wake, delayMs)
    }

    @Synchronized
    override fun cancel() {
        current?.let { handler.removeCallbacks(it) }
        current = null
    }
}

/**
 * chrome.alarms 的实现（WebView 内核）。设计取舍见 docs：
 * - 只有一个计时器，所有拓展共用，按「最早的闹钟」排一次期；没有闹钟（或没有就绪的拓展）就什么都不挂；
 * - 单次最多睡 [MAX_SLEEP_MS]：醒来按墙上时钟核对，应对设备休眠 / 手动改时间；
 * - 只在变更时写盘（创建、清除、周期闹钟触发、补发后各一次），原子替换 `data/<id>/alarms.json`，不轮询；
 * - 「就绪」= 后台页加载完且握手完成（由 [ExtensionLifecycle] 通知）。没就绪的拓展到期闹钟不触发、也不参与排期；
 *   就绪时错过的闹钟只补一次：周期闹钟从当前时间重排，一次性闹钟补完删除（与 Chrome 一致）；
 * - 不做后台唤醒：进程被杀期间闹钟顺延到下次启动补发。
 *
 * 线程：所有状态在 [lock] 下访问，文件读写与事件投递都发生在调用线程（API 协程 / 唤醒的 worker），不占主线程。
 */
class AlarmService(
    private val dataDir: (String) -> File,
    private val sink: EventSink,
    private val timer: WakeTimer,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val MAX_ALARMS_PER_EXTENSION = 500
        const val MAX_NAME_LENGTH = 256
        const val MIN_INTERVAL_MINUTES = 0.5
        const val MAX_SLEEP_MS = 10L * 60 * 1000
        const val EVENT = "alarms.onAlarm"
        private const val FILE_NAME = "alarms.json"
    }

    private class State(val alarms: LinkedHashMap<String, Alarm>, var ready: Boolean = false)

    private val lock = Any()
    private val states = HashMap<String, State>()

    // ───────────────────────── 生命周期（由 ExtensionLifecycle / ExtensionSystem 调用） ─────────────────────────

    /** 后台页就绪：从存储恢复，补发错过的闹钟，然后参与排期。调用方要先发完 onStartup / onInstalled */
    fun markReady(extId: String) {
        val events = ArrayList<Alarm>()
        synchronized(lock) {
            val st = stateOf(extId)
            st.ready = true
            collectDue(extId, st, compensating = true, out = events)
            rescheduleLocked()
        }
        emitAll(extId, events)
    }

    /** 后台页没了（停用 / 更新前 / 被销毁）：不再触发、不参与排期；闹钟留在存储里 */
    fun markUnready(extId: String) {
        synchronized(lock) {
            states[extId]?.ready = false
            rescheduleLocked()
        }
    }

    /** 拓展更新（Chrome：更新会清掉旧闹钟）：内存与存储一起清 */
    fun reset(extId: String) {
        synchronized(lock) {
            val st = states[extId]
            val wasReady = st?.ready ?: false
            states[extId] = State(LinkedHashMap(), wasReady)
            runCatching { File(dataDir(extId), FILE_NAME).delete() }
            rescheduleLocked()
        }
    }

    /** 卸载 / 停用后丢掉内存状态（存储文件随拓展数据目录一起删，或在停用时保留） */
    fun forget(extId: String) {
        synchronized(lock) {
            states.remove(extId)
            rescheduleLocked()
        }
    }

    // ───────────────────────── 唤醒 ─────────────────────────

    /** 定时器到点（在 worker 线程）：触发到期的就绪闹钟，再排下一次 */
    fun onWake() {
        val fired = LinkedHashMap<String, MutableList<Alarm>>()
        synchronized(lock) {
            for ((extId, st) in states) {
                if (!st.ready) continue
                val out = ArrayList<Alarm>()
                collectDue(extId, st, compensating = false, out = out)
                if (out.isNotEmpty()) fired[extId] = out
            }
            rescheduleLocked()
        }
        for ((extId, list) in fired) emitAll(extId, list)
    }

    // ───────────────────────── API ─────────────────────────

    fun register(router: ApiRouter) {
        router.register("alarms.create") { ctx, a -> create(ctx.ext.spec.id, a.argOrNull(0), a.argOrNull(1)); null }
        router.register("alarms.get") { ctx, a -> get(ctx.ext.spec.id, a.argOrNull(0))?.toJson() }
        router.register("alarms.getAll") { ctx, _ ->
            JSONArray().also { arr -> getAll(ctx.ext.spec.id).forEach { arr.put(it.toJson()) } }
        }
        router.register("alarms.clear") { ctx, a -> clear(ctx.ext.spec.id, a.argOrNull(0)) }
        router.register("alarms.clearAll") { ctx, _ -> clearAll(ctx.ext.spec.id) }
    }

    fun create(extId: String, nameArg: Any?, infoArg: Any?) {
        val name = when (nameArg) {
            null -> ""
            is String -> nameArg
            else -> throw ApiException(ErrorCodes.BAD_REQUEST, "name 必须是字符串")
        }
        if (name.length > MAX_NAME_LENGTH) {
            throw ApiException(ErrorCodes.QUOTA, "闹钟名称最长 $MAX_NAME_LENGTH 个字符")
        }
        val info = infoArg as? JSONObject ?: throw ApiException(ErrorCodes.BAD_REQUEST, "alarmInfo 必须是对象")
        val whenMs = numberOrNull(info, "when")
        val delay = numberOrNull(info, "delayInMinutes")
        val period = numberOrNull(info, "periodInMinutes")
        if (whenMs != null && delay != null) {
            throw ApiException(ErrorCodes.BAD_REQUEST, "Cannot set both when and delayInMinutes.")
        }
        if (whenMs == null && delay == null && period == null) {
            throw ApiException(ErrorCodes.BAD_REQUEST, "Alarm must specify at least one of when, delayInMinutes or periodInMinutes.")
        }
        if (period != null && period <= 0) {
            throw ApiException(ErrorCodes.BAD_REQUEST, "periodInMinutes 必须大于 0")
        }
        if (delay != null && delay < 0) {
            throw ApiException(ErrorCodes.BAD_REQUEST, "delayInMinutes 不能为负数")
        }
        val periodClamped = period?.let { maxOf(it, MIN_INTERVAL_MINUTES) }
        val delayClamped = delay?.let { maxOf(it, MIN_INTERVAL_MINUTES) }
        val t = now()
        val scheduled = when {
            whenMs != null -> whenMs.toLong()
            delayClamped != null -> t + minutesToMs(delayClamped)
            else -> t + minutesToMs(periodClamped!!)
        }
        synchronized(lock) {
            val st = stateOf(extId)
            if (!st.alarms.containsKey(name) && st.alarms.size >= MAX_ALARMS_PER_EXTENSION) {
                throw ApiException(ErrorCodes.QUOTA, "每个拓展最多 $MAX_ALARMS_PER_EXTENSION 个闹钟")
            }
            st.alarms.remove(name) // 同名覆盖，并且排到末尾
            st.alarms[name] = Alarm(name, scheduled, periodClamped)
            persist(extId, st)
            rescheduleLocked()
        }
    }

    fun get(extId: String, nameArg: Any?): Alarm? = synchronized(lock) {
        stateOf(extId).alarms[(nameArg as? String) ?: ""]
    }

    fun getAll(extId: String): List<Alarm> = synchronized(lock) { stateOf(extId).alarms.values.toList() }

    fun clear(extId: String, nameArg: Any?): Boolean = synchronized(lock) {
        val st = stateOf(extId)
        val removed = st.alarms.remove((nameArg as? String) ?: "") != null
        if (removed) {
            persist(extId, st)
            rescheduleLocked()
        }
        removed
    }

    fun clearAll(extId: String): Boolean = synchronized(lock) {
        val st = stateOf(extId)
        val had = st.alarms.isNotEmpty()
        if (had) {
            st.alarms.clear()
            persist(extId, st)
            rescheduleLocked()
        }
        had
    }

    // ───────────────────────── 内部（均在 lock 内） ─────────────────────────

    private fun numberOrNull(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)
        if (v !is Number) throw ApiException(ErrorCodes.BAD_REQUEST, "$key 必须是数字")
        val d = v.toDouble()
        if (d.isNaN() || d.isInfinite()) throw ApiException(ErrorCodes.BAD_REQUEST, "$key 不是有效数字")
        return d
    }

    private fun minutesToMs(m: Double): Long = Math.round(m * 60_000.0)

    private fun stateOf(extId: String): State = states.getOrPut(extId) { State(load(extId)) }

    /**
     * 收集 [st] 里已到期的闹钟并更新它们：
     * - 一次性：删除；
     * - 周期：正常唤醒时保持原相位（scheduledTime + period，已经落后就从当前时间起）；
     *   补发（[compensating]）时从当前时间起重排，不按错过的周期数逐次补发。
     * 有改动就写一次盘。
     */
    private fun collectDue(extId: String, st: State, compensating: Boolean, out: MutableList<Alarm>) {
        val t = now()
        var changed = false
        for (alarm in st.alarms.values.toList()) {
            if (alarm.scheduledTime > t) continue
            out.add(alarm)
            changed = true
            val period = alarm.periodInMinutes
            if (period == null) {
                st.alarms.remove(alarm.name)
            } else {
                val step = minutesToMs(period)
                var next = if (compensating) t + step else alarm.scheduledTime + step
                if (next <= t) next = t + step
                st.alarms[alarm.name] = Alarm(alarm.name, next, period)
            }
        }
        if (changed) persist(extId, st)
    }

    private fun rescheduleLocked() {
        var earliest = Long.MAX_VALUE
        for (st in states.values) {
            if (!st.ready) continue
            for (a in st.alarms.values) if (a.scheduledTime < earliest) earliest = a.scheduledTime
        }
        if (earliest == Long.MAX_VALUE) {
            timer.cancel()
            return
        }
        val delay = (earliest - now()).coerceIn(0L, MAX_SLEEP_MS)
        timer.schedule(delay, Runnable { onWake() })
    }

    private fun emitAll(extId: String, alarms: List<Alarm>) {
        for (a in alarms) {
            try {
                sink.emit(extId, EVENT, a.toJson())
            } catch (e: Exception) {
                log("alarms $extId: emit failed ${e.message}")
            }
        }
    }

    private fun load(extId: String): LinkedHashMap<String, Alarm> {
        val out = LinkedHashMap<String, Alarm>()
        try {
            val f = File(dataDir(extId), FILE_NAME)
            if (!f.isFile) return out
            val arr = JSONObject(f.readText(Charsets.UTF_8)).getJSONArray("alarms")
            for (i in 0 until arr.length()) {
                if (out.size >= MAX_ALARMS_PER_EXTENSION) break
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "")
                if (name.length > MAX_NAME_LENGTH || !o.has("scheduledTime")) continue
                val scheduled = o.optDouble("scheduledTime", Double.NaN)
                if (scheduled.isNaN()) continue
                val period = if (o.has("periodInMinutes")) o.optDouble("periodInMinutes", Double.NaN) else Double.NaN
                out[name] = Alarm(name, scheduled.toLong(), period.takeIf { !it.isNaN() && it > 0 })
            }
        } catch (_: Exception) {
            out.clear() // 文件损坏：当作空，下次变更会覆盖
        }
        return out
    }

    /** 写盘失败只记日志：本次进程里的闹钟照常工作 */
    private fun persist(extId: String, st: State) {
        try {
            val dir = dataDir(extId)
            dir.mkdirs()
            val arr = JSONArray()
            st.alarms.values.forEach { arr.put(it.toJson()) }
            val text = JSONObject().put("v", 1).put("alarms", arr).toString()
            val f = File(dir, FILE_NAME)
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) throw java.io.IOException("无法写入 $FILE_NAME")
            }
        } catch (e: Exception) {
            log("alarms $extId: 写盘失败 ${e.message}")
        }
    }
}
