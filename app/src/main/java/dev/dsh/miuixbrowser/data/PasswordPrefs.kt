package dev.dsh.miuixbrowser.data

import dev.dsh.miuixbrowser.engine.EngineSupport
import dev.dsh.miuixbrowser.engine.gecko.GeckoPrefs
import android.content.Context

/**
 * 「设置 → 隐私与安全 → 密码」里三个开关的存取（和其它设置同在 `mafari_settings`）：
 *  - [KEY_SAVE]   保存密码：登录后询问是否保存 / 更新（无痕标签页不保存）；
 *  - [KEY_FILL]   自动填充：点登录框时列出已保存的账号，选一个填入；
 *  - [KEY_VERIFY] 填充前验证身份：填入前先过 [BiometricGate]（生物识别或锁屏密码）。
 *
 * 默认全开：这些功能只在用户主动登录 / 点登录框时才出现，而且密码始终是加密存放的。
 * 三个开关两边共用：Gecko 标签页走 Gecko 自己的登录存储（[GeckoLogins]）；系统 WebView 标签页走系统自动填充服务
 * [MafariAutofillService]（要在系统设置里选中本应用）。lite 版没有 Gecko，只有后一条路，[pushToGecko] 直接跳过。
 */
internal object PasswordPrefs {

    private const val PREFS = "mafari_settings"
    const val KEY_SAVE = "pw_save"
    const val KEY_FILL = "pw_autofill"
    const val KEY_VERIFY = "pw_verify_fill"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SAVE, true)

    fun fillEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_FILL, true)

    fun verifyBeforeFill(context: Context): Boolean = prefs(context).getBoolean(KEY_VERIFY, true)

    fun set(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
        pushToGecko(context)
    }

    /**
     * 把设置落到 Gecko：只有「自动填充开着、且不需要验证身份」时才允许 Gecko 在页面加载时直接填充；
     * 需要验证身份时关掉页面加载填充，填充只走点登录框弹出的选择框（那里会先验证）。
     * Gecko 还没启动时只是记下期望值，运行时创建后由 [GeckoPrefs.flush] 统一写入。
     */
    fun pushToGecko(context: Context) {
        if (!EngineSupport.GECKO_BUNDLED) return // lite 版没有 Gecko 类，别碰
        GeckoPrefs.set("signon.autofillForms", fillEnabled(context) && !verifyBeforeFill(context))
    }
}
