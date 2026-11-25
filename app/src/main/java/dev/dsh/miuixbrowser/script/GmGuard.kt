/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.script

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * [GmChannel] 的安全判定（纯 JVM，可直接单测）：
 *  - **令牌**：每个脚本一个 128 位随机令牌，只存在原生内存里、只出现在该脚本注入源码的快照中，不落盘、不进日志；
 *    脚本被停用 / 删除（[retain]）时立即作废，已注入页面里的旧脚本从此无法再发起任何请求；
 *  - **origin 范围**：每条消息都要求浏览器给出的 sourceOrigin 在该脚本的匹配范围内（[GmOriginScope]），
 *    偷到令牌也不能在别的 origin 使用；
 *  - **限频**：每个脚本、每类能力一个固定窗口计数器，防止脚本（或被利用的脚本）刷屏——弹标签、通知、下载、剪贴板……
 *    与 Gecko 侧 GeckoGm.allow 同一套口径。
 */
internal class GmGuard(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val rng: SecureRandom = SecureRandom(),
) {
    private val tokens = ConcurrentHashMap<String, String>()
    private val scopes = ConcurrentHashMap<String, GmOriginScope>()

    /** 登记（或更新范围）并返回该脚本的令牌：已有就沿用，保证已打开的页面里快照里的旧令牌继续有效 */
    fun register(id: String, scope: GmOriginScope): String {
        scopes[id] = scope
        return tokens.getOrPut(id) {
            val b = ByteArray(16)
            rng.nextBytes(b)
            b.joinToString("") { "%02x".format(it) }
        }
    }

    /** 只保留 [live] 里的脚本，其余令牌作废 */
    fun retain(live: Set<String>) {
        tokens.keys.retainAll(live)
        scopes.keys.retainAll(live)
        buckets.keys.removeAll { it.substringBefore('|') !in live }
    }

    fun clear() {
        tokens.clear(); scopes.clear(); buckets.clear()
    }

    fun tokenOf(id: String): String? = tokens[id]

    /** 令牌（常量时间比较）与 sourceOrigin 都通过才放行。未知脚本、空令牌一律拒绝 */
    fun authorize(sid: String, tok: String, origin: String): Boolean {
        if (sid.isEmpty() || tok.isEmpty()) return false
        val expect = tokens[sid] ?: return false
        if (!MessageDigest.isEqual(expect.toByteArray(Charsets.UTF_8), tok.toByteArray(Charsets.UTF_8))) return false
        return scopes[sid]?.allows(origin) == true
    }

    private class Bucket { var start = 0L; var count = 0L }
    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** [windowMs] 内最多 [max]（按 [cost] 计）次；[cost] 默认 1，写值按字符数计费，防止「次数不多但每次 2MB」刷爆存储与广播 */
    fun allow(sid: String, kind: String, max: Long, windowMs: Long, cost: Long = 1): Boolean {
        val now = clockMs()
        val b = buckets.getOrPut("$sid|$kind") { Bucket() }
        synchronized(b) {
            if (now - b.start > windowMs) { b.start = now; b.count = 0 }
            if (b.count + cost > max) return false
            b.count += cost
            return true
        }
    }
}
