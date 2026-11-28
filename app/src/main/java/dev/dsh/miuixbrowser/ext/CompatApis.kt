/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import android.content.Context
import android.os.PowerManager
import dev.dsh.miuixbrowser.data.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress

/** `commands.getAll`：读取 manifest 的 commands，供扩展展示 / 判断快捷键。 */
class CommandsApi {
    fun register(router: ApiRouter) {
        router.register("commands.getAll") { c, _ -> commands(c.ext) }
        router.register("commands.update") { _, _ ->
            throw ApiException(ErrorCodes.UNSUPPORTED, "暂不支持修改扩展快捷键")
        }
        router.register("commands.reset") { _, _ ->
            throw ApiException(ErrorCodes.UNSUPPORTED, "暂不支持重置扩展快捷键")
        }
    }

    private fun commands(ext: InstalledExtension): JSONArray {
        val manifest = runCatching {
            JSONObject(File(ext.rootDir, "manifest.json").readText())
        }.getOrNull() ?: return JSONArray()
        val map = manifest.optJSONObject("commands") ?: return JSONArray()
        val out = JSONArray()
        for (name in map.keys()) {
            val o = map.optJSONObject(name) ?: continue
            val shortcut = when (val s = o.opt("suggested_key")) {
                is String -> s
                is JSONObject -> s.optString("default").ifBlank {
                    s.optString("linux").ifBlank { s.optString("windows").ifBlank { s.optString("mac") } }
                }
                else -> ""
            }
            out.put(
                JSONObject()
                    .put("name", name)
                    .put("description", o.optString("description"))
                    .put("shortcut", shortcut),
            )
        }
        return out
    }
}

/** `management`：只读的已安装扩展/自身信息；卸载与启停仍走 Mafari 自己的管理 UI。 */
class ManagementApi(private val repo: ExtensionRepository) {
    fun register(router: ApiRouter) {
        router.register("management.getSelf") { c, _ -> info(c.ext, self = true) }
        router.register("management.getAll") { _, _ -> JSONArray(repo.list().map { info(it, self = false) }) }
        router.register("management.get") { _, a ->
            val id = a.argOrNull(0)?.toString().orEmpty()
            val ext = repo.get(id) ?: throw ApiException(ErrorCodes.BAD_REQUEST, "没有这个扩展：$id")
            info(ext, self = false)
        }
        router.register("management.getPermissionWarningsById") { _, _ -> JSONArray() }
        router.register("management.getPermissionWarningsByManifest") { _, _ -> JSONArray() }
        router.register("management.setEnabled") { _, _ ->
            throw ApiException(ErrorCodes.UNSUPPORTED, "请在 Mafari 的扩展管理页启停扩展")
        }
        router.register("management.uninstall") { _, _ ->
            throw ApiException(ErrorCodes.UNSUPPORTED, "请在 Mafari 的扩展管理页卸载扩展")
        }
    }

    private fun info(ext: InstalledExtension, self: Boolean) = JSONObject()
        .put("id", ext.spec.id)
        .put("name", ext.spec.name)
        .put("version", ext.spec.version)
        .put("description", ext.spec.description)
        .put("enabled", ext.enabled)
        .put("type", "extension")
        .put("installType", if (self) "development" else "normal")
        .put("mayDisable", false)
        .put("shortName", ext.spec.name)
}

/** `idle.queryState`：屏幕是否点亮 + 是否锁屏；Android 上这就是用户是否空闲的最可靠信号。 */
class IdleApi(context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager

    fun register(router: ApiRouter) {
        router.register("idle.queryState") { _, _ ->
            when {
                km?.isKeyguardLocked == true -> "locked"
                pm?.isInteractive == true -> "active"
                else -> "idle"
            }
        }
        router.register("idle.setDetectionInterval") { _, a ->
            val sec = (a.argOrNull(0) as? Number)?.toInt() ?: 60
            if (sec < 15) throw ApiException(ErrorCodes.BAD_REQUEST, "detectionInterval 不能小于 15 秒")
            null
        }
    }
}

/** `dns.resolve`：用系统解析器返回 A / AAAA 地址。 */
class DnsApi {
    fun register(router: ApiRouter) {
        router.register("dns.resolve") { _, a ->
            val host = a.argOrNull(0)?.toString()?.trim().orEmpty()
            if (host.isEmpty()) throw ApiException(ErrorCodes.BAD_REQUEST, "缺少 hostname")
            val addresses = withContext(Dispatchers.IO) {
                runCatching { InetAddress.getAllByName(host) }.getOrElse {
                    throw ApiException(ErrorCodes.NETWORK, "域名解析失败：$host")
                }
            }
            JSONObject().put("addresses", JSONArray(addresses.map { it.hostAddress }))
        }
    }
}

/** `topSites.get`：从 Mafari 浏览历史取最近访问的站点（按时间倒序去重）。 */
class TopSitesApi(private val history: () -> HistoryStore) {
    fun register(router: ApiRouter) {
        router.register("topSites.get") { _, _ ->
            val entries = withContext(Dispatchers.IO) { history().query("", 30) }
            val seen = HashSet<String>()
            val out = JSONArray()
            for (e in entries) {
                val host = runCatching { java.net.URI(e.url).host }.getOrNull().orEmpty()
                if (host.isBlank() || !seen.add(host)) continue
                out.put(JSONObject().put("url", e.url).put("title", e.title))
                if (out.length() >= 20) break
            }
            out
        }
    }
}
