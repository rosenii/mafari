package dev.dsh.miuixbrowser.ext

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** 后台页状态变化的通知（由 [ExtensionPageHost] 在主线程发出） */
interface BackgroundListener {
    /** 后台页加载完成且与原生握手完毕：此时向它投递事件不会丢 */
    fun onBackgroundReady(extId: String)

    /** 后台页被销毁（停用 / 更新 / 卸载 / 关闭） */
    fun onBackgroundGone(extId: String)
}

/**
 * 后台页启动流程。「就绪」的定义：后台页 onPageFinished 且握手完成（见 [ExtensionPageHost]）。
 *
 * 就绪后按固定顺序发事件：
 * 1. 这次启动对应的 runtime 事件：应用启动 → `runtime.onStartup`；安装 / 更新 → `runtime.onInstalled`
 *    （details.reason = install / update，更新带 previousVersion）。二者互斥；重新启用两个都不发；
 * 2. 再让 [AlarmService] 恢复并补发错过的 `alarms.onAlarm`。
 *
 * 所有动作经 [execute] 串行执行（生产里是单线程 Executor），所以「就绪 / 消失」不会乱序，文件读写也不占主线程。
 */
class ExtensionLifecycle(
    private val sink: EventSink,
    private val alarms: AlarmService,
    private val execute: (Runnable) -> Unit,
) : BackgroundListener {

    private sealed class Pending {
        object Startup : Pending()
        class Installed(val previousVersion: String?) : Pending()
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    /** 应用启动、即将拉起后台页时（主线程）：该拓展就绪后发 onStartup */
    fun expectStartup(extId: String) {
        pending[extId] = Pending.Startup
    }

    /** 安装 / 更新、即将拉起后台页时（主线程）：就绪后发 onInstalled。[previousVersion] 为 null 表示全新安装 */
    fun expectInstalled(extId: String, previousVersion: String?) {
        pending[extId] = Pending.Installed(previousVersion)
    }

    /** 后台页不会再起来了（停用 / 卸载）：丢掉还没发的启动事件 */
    fun cancel(extId: String) {
        pending.remove(extId)
    }

    override fun onBackgroundReady(extId: String) {
        val p = pending.remove(extId)
        execute(Runnable {
            when (p) {
                is Pending.Startup -> sink.emit(extId, "runtime.onStartup", JSONObject())
                is Pending.Installed -> sink.emit(extId, "runtime.onInstalled", installedDetails(p.previousVersion))
                null -> Unit
            }
            alarms.markReady(extId)
        })
    }

    override fun onBackgroundGone(extId: String) {
        execute(Runnable { alarms.markUnready(extId) })
    }

    private fun installedDetails(previousVersion: String?): JSONObject {
        val o = JSONObject().put("reason", if (previousVersion == null) "install" else "update")
        if (previousVersion != null) o.put("previousVersion", previousVersion)
        return o
    }
}
