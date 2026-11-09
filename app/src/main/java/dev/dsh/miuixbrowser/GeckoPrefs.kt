package dev.dsh.miuixbrowser

import android.net.Uri
import java.lang.reflect.Modifier

/**
 * Gecko 的 about:config 偏好设置（隐私 / WebRTC / 代理等没有专用 Java API 的开关）。
 *
 * - 期望值先记在 [desired] 里：Gecko 还没启动时不创建运行时，等 [GeckoRuntimeHolder.get] 创建后统一 [flush]；
 *   已启动则立刻写入。所以不用 Gecko 的人完全没有开销，设置变化也不会丢。
 * - 写入走 `GeckoPreferenceController.setGeckoPref`，nightly 的签名偶尔会变，所以用反射按「名字 + 参数类型」
 *   查找；找不到就只记日志，不影响浏览（不会崩）。
 */
object GeckoPrefs {

    private val desired = LinkedHashMap<String, Any>()
    private var branchUser = 1
    private var lookedUp = false
    private var warned = false

    /** 作为 value 传入 [set]：清除用户值，恢复 Gecko 默认 */
    val CLEAR = Any()

    /** 记录并（若 Gecko 已启动）立刻应用一项偏好。value 支持 Boolean / Int / String / [CLEAR] */
    @Synchronized
    fun set(name: String, value: Any) {
        if (desired[name] == value) return
        desired[name] = value
        if (GeckoRuntimeHolder.peek() != null) push(name, value)
    }

    /** Gecko 运行时刚创建后调用：把此前记录的偏好全部写进去 */
    @Synchronized
    fun flush() {
        desired.forEach { (k, v) -> push(k, v) }
    }

    private fun accepts(param: Class<*>, value: Any): Boolean = when (value) {
        is Boolean -> param == java.lang.Boolean.TYPE || param == java.lang.Boolean::class.java
        is Int -> param == Integer.TYPE || param == Int::class.javaObjectType
        is String -> param == String::class.java
        else -> false
    }

    private fun push(name: String, value: Any) {
        runCatching {
            val cls = Class.forName("org.mozilla.geckoview.GeckoPreferenceController")
            if (value === CLEAR) {
                val clear = cls.methods.firstOrNull {
                    it.name == "clearGeckoUserPref" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
                } ?: error("找不到 GeckoPreferenceController.clearGeckoUserPref")
                clear.invoke(if (Modifier.isStatic(clear.modifiers)) null else cls.getDeclaredConstructor().newInstance(), name)
                return@runCatching
            }
            if (!lookedUp) {
                lookedUp = true
                branchUser = runCatching { cls.getField("PREF_BRANCH_USER").getInt(null) }.getOrDefault(1)
            }
            val m = cls.methods.firstOrNull {
                it.name == "setGeckoPref" && it.parameterTypes.size in 2..3 && accepts(it.parameterTypes[1], value)
            } ?: error("找不到 GeckoPreferenceController.setGeckoPref")
            val target = if (Modifier.isStatic(m.modifiers)) null else cls.getDeclaredConstructor().newInstance()
            if (m.parameterTypes.size == 3) m.invoke(target, name, value, branchUser) else m.invoke(target, name, value)
        }.onFailure {
            if (!warned) {
                warned = true
                Diag.log("gecko pref unavailable ($name): ${it.cause?.message ?: it.message}")
            }
        }
    }
}

/**
 * 把 [WebViewProxy] 最终采用的代理规则（`http://host:port` / `socks5://host:port`，含 SOCKS5 认证用的本机中转）
 * 映射到 Gecko 的 `network.proxy.*`。HTTP 代理账号密码在 [GeckoPromptHandler.onAuthPrompt] 里提交。
 */
object GeckoProxy {

    /** network.proxy.type：1 = 手动，5 = 跟随系统（Gecko 默认；关闭代理就回到它，而不是强制直连） */
    private const val PROXY_SYSTEM = 5

    /** rule == null：关闭代理（回到系统设置） */
    fun applyRule(rule: String?) {
        if (rule == null) {
            GeckoPrefs.set("network.proxy.type", PROXY_SYSTEM)
            return
        }
        val uri = Uri.parse(rule)
        val host = uri.host.orEmpty().trim('[', ']')
        val port = uri.port
        if (host.isEmpty() || port !in 1..65535) {
            GeckoPrefs.set("network.proxy.type", PROXY_SYSTEM)
            return
        }
        val socks = uri.scheme.orEmpty().startsWith("socks", ignoreCase = true)
        if (socks) {
            GeckoPrefs.set("network.proxy.socks", host)
            GeckoPrefs.set("network.proxy.socks_port", port)
            GeckoPrefs.set("network.proxy.socks_version", 5)
            GeckoPrefs.set("network.proxy.socks_remote_dns", true)
            GeckoPrefs.set("network.proxy.http", "")
            GeckoPrefs.set("network.proxy.http_port", 0)
            GeckoPrefs.set("network.proxy.ssl", "")
            GeckoPrefs.set("network.proxy.ssl_port", 0)
        } else {
            GeckoPrefs.set("network.proxy.http", host)
            GeckoPrefs.set("network.proxy.http_port", port)
            GeckoPrefs.set("network.proxy.ssl", host)
            GeckoPrefs.set("network.proxy.ssl_port", port)
            GeckoPrefs.set("network.proxy.socks", "")
            GeckoPrefs.set("network.proxy.socks_port", 0)
        }
        // 本机中转（127.0.0.1）本身就是代理地址，不能被「不走代理」名单排除
        GeckoPrefs.set("network.proxy.no_proxies_on", "")
        GeckoPrefs.set("network.proxy.type", 1)
    }
}
