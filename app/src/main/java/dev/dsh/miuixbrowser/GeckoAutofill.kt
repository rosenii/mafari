package dev.dsh.miuixbrowser

import android.content.Context
import android.view.View
import android.view.autofill.AutofillManager
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * 让 Gecko 网页里的账号 / 密码输入框能唤出系统「自动填充服务」（Google 密码管理器等）。
 *
 * GeckoView 自己不画任何界面：网页输入框的 Android 自动填充靠 GeckoView 这个 View 把页面里的表单
 * 作为「虚拟结构」交给 [AutofillManager]，而这个桥接默认是否开启取决于 GeckoView 的 setAutofillEnabled 开关
 * 和 View 的 importantForAutofill。本应用此前两者都没设置，WebView 标签走系统 WebView 自带的自动填充所以正常，
 * Gecko 标签就拿不到。这里显式打开；nightly 的 API 偶尔会变，所以 setAutofillEnabled 按名字反射调用，找不到只记日志。
 */
internal object GeckoAutofill {

    @Volatile private var lastState = "未初始化"

    /** 在 GeckoView 创建后、以及 session 挂到 view 上之后各调用一次（幂等） */
    fun enable(view: GeckoView, session: GeckoSession) {
        runCatching { view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES }
        val setter = runCatching {
            view.javaClass.methods.firstOrNull {
                it.name == "setAutofillEnabled" && it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Boolean.TYPE
            }
        }.getOrNull()
        var enabled: String
        if (setter == null) {
            enabled = "GeckoView 没有 setAutofillEnabled"
        } else {
            enabled = runCatching { setter.invoke(view, true); "已开启" }
                .getOrElse { "开启失败 ${it.cause?.message ?: it.message}" }
        }
        val hasDelegate = runCatching {
            session.javaClass.methods.firstOrNull { it.name == "getAutofillDelegate" && it.parameterTypes.isEmpty() }
                ?.invoke(session) != null
        }.getOrNull()
        lastState = "$enabled；session 委托：" + when (hasDelegate) {
            true -> "有"
            false -> "无"
            null -> "未知"
        }
        Diag.log("autofill: $lastState")
    }

    /** 调试页展示：系统自动填充服务是否可用 + GeckoView 侧状态 */
    fun describe(context: Context): String {
        val am = runCatching { context.getSystemService(AutofillManager::class.java) }.getOrNull()
        val sys = when {
            am == null -> "系统不支持自动填充"
            !am.hasEnabledAutofillServices() -> "系统未选择自动填充服务（设置 → 密码和账号 → 自动填充服务）"
            !am.isEnabled -> "系统自动填充已关闭"
            else -> "系统自动填充服务可用"
        }
        return "$sys；$lastState"
    }
}
