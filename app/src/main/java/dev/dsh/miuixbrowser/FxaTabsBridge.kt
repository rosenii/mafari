package dev.dsh.miuixbrowser

import android.content.Context
import mozilla.components.browser.storage.sync.RemoteTabsStorage
import mozilla.components.browser.storage.sync.Tab
import mozilla.components.browser.storage.sync.TabEntry

/** 其他设备的标签页（Sync「Tabs」引擎）：上传本机标签、读取远端标签。 */
internal object FxaTabsBridge {
    class RemoteTab(val title: String, val url: String)
    class RemoteDevice(val id: String, val tabs: List<RemoteTab>)

    /** 把本机（非无痕）标签写进 Tabs 引擎的本地副本，随下一次同步上传。 */
    suspend fun storeLocal(storage: RemoteTabsStorage, tabs: List<Pair<String, String>>) {
        val now = System.currentTimeMillis()
        storage.store(
            tabs.filter { it.second.startsWith("http") }.map { (title, url) ->
                Tab(history = listOf(TabEntry(title, url, null)), active = 0, lastUsed = now, inactive = false) // TODO(API)
            },
        )
    }

    suspend fun remote(storage: RemoteTabsStorage): List<RemoteDevice> =
        storage.getAll().map { (client, tabs) ->
            RemoteDevice(
                id = client.id,
                tabs = tabs.mapNotNull { t ->
                    val e = t.history.getOrNull(t.active) ?: t.history.lastOrNull() ?: return@mapNotNull null
                    RemoteTab(e.title.ifBlank { e.url }, e.url)
                },
            )
        }
}
