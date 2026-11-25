/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.script

import org.json.JSONArray
import org.json.JSONObject

/**
 * 通道版注入源码的纯函数部分（不依赖 Android，可直接单测）：值快照的取舍、快照 JSON、shim 包装。
 * 与 GeckoGm 共用同一份运行时与 shim，只是快照里多一个 caps 字段。
 */
internal object GmSnapshot {

    private const val SNAPSHOT_MARK = "__MAFARI_SNAPSHOT__"

    /** 单值超过它不内联（字符数）；与 Gecko 侧一致 */
    const val INLINE_VALUE_MAX = 64 * 1024

    /** 单脚本内联总量上限（字符数）；超出后剩下的（小值优先放，所以是最大的那些）也不内联 */
    const val SNAPSHOT_TOTAL_CAP = 256 * 1024

    /**
     * 值快照的取舍：[entries] 是 (键名, 字符数)；小值优先内联，
     * 单值 > [INLINE_VALUE_MAX] 或累计 > [SNAPSHOT_TOTAL_CAP] 的键转为按需异步拉取。
     * 返回 (要内联的键, 不内联的键)；两者各自保持「按大小升序」的顺序。
     */
    fun partitionValues(entries: List<Pair<String, Int>>): Pair<List<String>, List<String>> {
        val inline = ArrayList<String>()
        val big = ArrayList<String>()
        var size = 0L
        for ((name, len) in entries.sortedBy { it.second }) {
            if (len > INLINE_VALUE_MAX || size + len > SNAPSHOT_TOTAL_CAP) { big.add(name); continue }
            size += len
            inline.add(name)
        }
        return inline to big
    }

    /** shim 的快照对象 JSON：`{id, tok, values:{键:原始字符串}, big:[键…], caps:['stream']}` */
    fun snapshotJson(id: String, token: String, values: Map<String, String>, big: List<String>): String =
        JSONObject()
            .put("id", id).put("tok", token)
            .put("values", JSONObject().also { o -> values.forEach { (k, v) -> o.put(k, v) } })
            .put("big", JSONArray(big))
            .put("caps", JSONArray().put("stream"))
            .toString()

    /**
     * 运行时外面再包一层，把 shim 作为闭包变量 `__mafariBridge` 传入：
     * `(function(__mafariBridge){ 运行时 })( 填好快照的 shim )`。只替换 shim 文末那一个占位符，且不二次扫描快照内容。
     * 与 GeckoGm.buildSource 的包装完全相同。
     */
    fun wrapWithShim(runtime: String, shimTemplate: String, snapshot: String): String {
        val at = shimTemplate.lastIndexOf(SNAPSHOT_MARK)
        require(at >= 0) { "shim 缺少 $SNAPSHOT_MARK 占位符" }
        val shim = shimTemplate.substring(0, at) + snapshot + shimTemplate.substring(at + SNAPSHOT_MARK.length)
        return "(function(__mafariBridge){\n$runtime\n})($shim);"
    }
}
