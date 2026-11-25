/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject

/** Runtime state consumed by Mafari's extension shortcut sheet, not merely stored in JS. */
data class WebExtActionState(val title: String = "", val badgeText: String = "", val badgeColor: String = "#D93025", val popup: String? = null)

class ActionApi {
    val states = mutableStateMapOf<String, WebExtActionState>()
    fun state(ext: InstalledExtension) = states.getOrPut(ext.spec.id) { WebExtActionState(
        title = ext.spec.action?.title.orEmpty(), popup = ext.spec.action?.popup) }

    fun register(router: ApiRouter) {
        fun registerPrefix(prefix: String) {
            router.register("$prefix.setBadgeText") { c, a -> update(c.ext) { it.copy(badgeText = a.optJSONObject(0)?.optString("text").orEmpty()) }; null }
            router.register("$prefix.setBadgeBackgroundColor") { c, a -> update(c.ext) { it.copy(badgeColor = normalizeColor(a.optJSONObject(0)?.opt("color")) ?: it.badgeColor) }; null }
            router.register("$prefix.setTitle") { c, a -> update(c.ext) { it.copy(title = a.optJSONObject(0)?.optString("title").orEmpty()) }; null }
            router.register("$prefix.setPopup") { c, a -> update(c.ext) { it.copy(popup = a.optJSONObject(0)?.optString("popup")?.takeIf(String::isNotBlank)) }; null }
            router.register("$prefix.getTitle") { c, _ -> state(c.ext).title }
            router.register("$prefix.getBadgeText") { c, _ -> state(c.ext).badgeText }
        }
        registerPrefix("action")
        registerPrefix("browserAction")
    }
    private fun normalizeColor(value: Any?): String? = when (value) {
        is org.json.JSONArray -> if (value.length() >= 3) runCatching {
            "#%02X%02X%02X".format(value.optInt(0).coerceIn(0, 255), value.optInt(1).coerceIn(0, 255), value.optInt(2).coerceIn(0, 255))
        }.getOrNull() else null
        null, org.json.JSONObject.NULL -> null
        else -> value.toString()
    }
    private fun update(ext: InstalledExtension, f: (WebExtActionState) -> WebExtActionState) { states[ext.spec.id] = f(state(ext)) }
}
