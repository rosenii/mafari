package dev.dsh.miuixbrowser

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 系统默认浏览器：首次启动的询问弹窗与「设置 → 高级 → 设为默认浏览器」共用。
 * 走 RoleManager 的浏览器角色（Android 10+，本应用 minSdk 33）：弹系统自己的确认窗，不需要任何额外权限。
 * 清单里 MainActivity 已声明 VIEW + BROWSABLE + http / https，满足角色资格。
 */
object DefaultBrowser {
    fun isDefault(context: Context): Boolean = runCatching {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
    }.getOrDefault(false)

    /** 申请默认浏览器的 Intent：优先系统角色弹窗；角色不可用或已持有时退回系统「默认应用」设置页 */
    fun requestIntent(context: Context): Intent {
        val rm = context.getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
            return rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
        }
        return settingsIntent()
    }

    /** 系统「默认应用」设置页（用户在系统里拒绝过多次后，角色弹窗不再出现，只能去这里改） */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
}
