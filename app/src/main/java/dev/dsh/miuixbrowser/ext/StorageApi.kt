package dev.dsh.miuixbrowser.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class StorageChange(val oldValue: Any?, val newValue: Any?)

/**
 * 一个存储区（某拓展的 local / session / sync 之一）。[file] 为 null 表示只存内存（session）。
 * 数据整体放内存、每次修改整体原子落盘：拓展存储通常很小，换来实现简单与崩溃安全。
 * 配额按 Chrome 的口径：键长度 + 值 JSON 长度的总和。
 */
class JsonStorageArea(
    private val file: File?,
    private val quotaBytes: () -> Long,
    private val maxItemBytes: Long = Long.MAX_VALUE,
) {
    private val lock = Any()
    private var data: JSONObject? = null

    private fun load(): JSONObject {
        data?.let { return it }
        val loaded = try {
            if (file != null && file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
        } catch (_: Exception) {
            JSONObject() // 文件损坏：当作空，下一次写入会覆盖
        }
        data = loaded
        return loaded
    }

    /** keys：null = 全部；String；JSONArray（字符串）；JSONObject（键为要取的 key，值为缺省值） */
    fun get(keys: Any?): JSONObject = synchronized(lock) {
        val d = load()
        val out = JSONObject()
        when (keys) {
            null -> d.keys().forEach { out.put(it, copy(d.get(it))) }
            is String -> if (d.has(keys)) out.put(keys, copy(d.get(keys)))
            is JSONArray -> for (i in 0 until keys.length()) {
                val k = keys.optString(i)
                if (d.has(k)) out.put(k, copy(d.get(k)))
            }
            is JSONObject -> keys.keys().forEach { k ->
                out.put(k, if (d.has(k)) copy(d.get(k)) else copy(keys.get(k)))
            }
            else -> throw ApiException(ErrorCodes.BAD_REQUEST, "keys 类型不对")
        }
        out
    }

    fun getBytesInUse(keys: Any?): Long = synchronized(lock) {
        val d = load()
        val names: List<String> = when (keys) {
            null -> d.keys().asSequence().toList()
            is String -> listOf(keys)
            is JSONArray -> (0 until keys.length()).map { keys.optString(it) }
            else -> throw ApiException(ErrorCodes.BAD_REQUEST, "keys 类型不对")
        }
        names.filter { d.has(it) }.sumOf { sizeOf(it, d.get(it)) }
    }

    fun set(items: JSONObject): Map<String, StorageChange> = synchronized(lock) {
        val d = load()
        val changes = LinkedHashMap<String, StorageChange>()
        var total = totalBytes(d)
        for (k in items.keys().asSequence().toList()) {
            val newV = items.get(k)
            val itemSize = sizeOf(k, newV)
            if (itemSize > maxItemBytes) throw ApiException(ErrorCodes.QUOTA, "QUOTA_BYTES_PER_ITEM quota exceeded")
            total += itemSize - (if (d.has(k)) sizeOf(k, d.get(k)) else 0L)
        }
        if (total > quotaBytes()) throw ApiException(ErrorCodes.QUOTA, "QUOTA_BYTES quota exceeded")
        for (k in items.keys().asSequence().toList()) {
            val oldV = if (d.has(k)) d.get(k) else null
            val newV = copy(items.get(k))
            if (oldV == null || !jsonEquals(oldV, newV)) changes[k] = StorageChange(oldV, newV)
            d.put(k, newV)
        }
        if (changes.isNotEmpty()) persist(d)
        changes
    }

    fun remove(keys: Any?): Map<String, StorageChange> = synchronized(lock) {
        val d = load()
        val names: List<String> = when (keys) {
            is String -> listOf(keys)
            is JSONArray -> (0 until keys.length()).map { keys.optString(it) }
            else -> throw ApiException(ErrorCodes.BAD_REQUEST, "keys 类型不对")
        }
        val changes = LinkedHashMap<String, StorageChange>()
        for (k in names) if (d.has(k)) changes[k] = StorageChange(d.remove(k), null)
        if (changes.isNotEmpty()) persist(d)
        changes
    }

    fun clear(): Map<String, StorageChange> = synchronized(lock) {
        val d = load()
        val changes = LinkedHashMap<String, StorageChange>()
        for (k in d.keys().asSequence().toList()) changes[k] = StorageChange(d.get(k), null)
        data = JSONObject()
        if (changes.isNotEmpty()) persist(data!!)
        changes
    }

    // ───────────────────────── 内部 ─────────────────────────

    private fun persist(d: JSONObject) {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(d.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw IOException("无法写入存储文件")
        }
    }

    private fun totalBytes(d: JSONObject): Long =
        d.keys().asSequence().sumOf { sizeOf(it, d.get(it)) }

    /** 与 Chrome 口径一致：键长 + 值的 JSON 序列化长度（字符串带引号） */
    private fun sizeOf(key: String, v: Any): Long =
        key.length.toLong() + (if (v is String) JSONObject.quote(v) else v.toString()).length.toLong()

    /** 深拷贝：不把内部持有的 JSON 对象交出去，也不持有调用方的对象 */
    private fun copy(v: Any): Any = when (v) {
        is JSONObject -> JSONObject(v.toString())
        is JSONArray -> JSONArray(v.toString())
        else -> v
    }

    companion object {
        /** 结构相等：数字按数值比较（1 与 1.0 相等），对象不看键顺序 */
        fun jsonEquals(a: Any?, b: Any?): Boolean {
            if (a is JSONObject && b is JSONObject) {
                if (a.length() != b.length()) return false
                return a.keys().asSequence().all { k -> b.has(k) && jsonEquals(a.get(k), b.get(k)) }
            }
            if (a is JSONArray && b is JSONArray) {
                if (a.length() != b.length()) return false
                return (0 until a.length()).all { jsonEquals(a.get(it), b.get(it)) }
            }
            if (a is Number && b is Number) return a.toDouble() == b.toDouble()
            return a == b
        }
    }
}

/**
 * storage.local / session / sync 的处理器。
 * sync 目前与 local 同样落本地，只是按 Chrome 的 sync 配额（总量 100KB、单项 8KB）限制，保证拓展行为一致；将来可接账号同步。
 * 每次修改后向 [events] 推送 `storage.onChanged`。
 */
class StorageApi(
    private val dataDir: (extId: String) -> File,
    private val events: EventSink,
    /**
     * `storage.session` 的变化事件只能发给这里：只含拓展自己的页面（后台页 / popup / options）的 sink。
     * session 区对 content script 关闭（与 Chrome 默认的 TRUSTED_CONTEXTS 一致），change 事件里带着新旧值，
     * 如果走 [events]（包含标签页里的 content script），等于把 session 的内容推到了网页所在的 JS 世界。
     */
    private val trustedEvents: EventSink = events,
) {
    private val areas = HashMap<String, JsonStorageArea>()

    private fun area(ctx: CallContext, name: String): JsonStorageArea = synchronized(areas) {
        val id = ctx.ext.spec.id
        areas.getOrPut("$id/$name") {
            when (name) {
                "local" -> JsonStorageArea(
                    File(dataDir(id), "storage_local.json"),
                    quotaBytes = {
                        if ("unlimitedStorage" in PermissionChecker.effectivePermissions(ctx.ext)) Long.MAX_VALUE
                        else LOCAL_QUOTA
                    },
                )
                "sync" -> JsonStorageArea(File(dataDir(id), "storage_sync.json"), { SYNC_QUOTA }, SYNC_ITEM_QUOTA)
                else -> JsonStorageArea(null, { SESSION_QUOTA }) // session：只在内存，进程结束即清空
            }
        }
    }

    /** 拓展卸载 / 停用重置时调用：丢掉内存里的区（磁盘文件随 data 目录删除） */
    fun forget(extId: String) = synchronized(areas) {
        areas.keys.removeAll { it.startsWith("$extId/") }
        Unit
    }

    fun register(router: ApiRouter) {
        for (name in listOf("local", "session", "sync")) {
            router.register("storage.$name.get") { ctx, a -> area(ctx, name).get(a.argOrNull(0)) }
            router.register("storage.$name.getBytesInUse") { ctx, a -> area(ctx, name).getBytesInUse(a.argOrNull(0)) }
            router.register("storage.$name.set") { ctx, a ->
                val items = a.argOrNull(0) as? JSONObject
                    ?: throw ApiException(ErrorCodes.BAD_REQUEST, "set 需要一个对象")
                announce(ctx, name, area(ctx, name).set(items))
            }
            router.register("storage.$name.remove") { ctx, a ->
                announce(ctx, name, area(ctx, name).remove(a.argOrNull(0)))
            }
            router.register("storage.$name.clear") { ctx, _ ->
                announce(ctx, name, area(ctx, name).clear())
            }
        }
    }

    private fun announce(ctx: CallContext, areaName: String, changes: Map<String, StorageChange>): Any? {
        if (changes.isNotEmpty()) {
            val obj = JSONObject()
            for ((k, c) in changes) {
                val one = JSONObject()
                c.oldValue?.let { one.put("oldValue", it) }
                c.newValue?.let { one.put("newValue", it) }
                obj.put(k, one)
            }
            val sink = if (areaName == "session") trustedEvents else events
            sink.emit(
                ctx.ext.spec.id,
                "storage.onChanged",
                JSONObject().put("areaName", areaName).put("changes", obj),
            )
        }
        return null
    }

    companion object {
        const val LOCAL_QUOTA = 10L * 1024 * 1024
        const val SESSION_QUOTA = 10L * 1024 * 1024
        const val SYNC_QUOTA = 102_400L
        const val SYNC_ITEM_QUOTA = 8_192L
    }
}
