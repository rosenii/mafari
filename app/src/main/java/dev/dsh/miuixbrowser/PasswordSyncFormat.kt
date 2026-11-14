package dev.dsh.miuixbrowser

import org.json.JSONObject

/**
 * Firefox 同步（`passwords` 集合）的记录格式 ↔ [PasswordEntry]。只做数据转换，不含任何同步代码。
 *
 * 记录 payload（解密后的 JSON，时间均为毫秒）：
 *   { "id", "hostname", "formSubmitURL", "httpRealm", "username", "password",
 *     "usernameField", "passwordField", "timeCreated", "timePasswordChanged", "timeLastUsed", "timesUsed" }
 *  - 网页表单登录：formSubmitURL 为表单提交来源，空字符串表示「任意表单」，httpRealm 为 null；
 *  - HTTP 认证登录：httpRealm 为 realm，formSubmitURL 为 null；
 *  - 已删除：{ "id", "deleted": true }。
 * 备注（[PasswordEntry.note]）不在 Firefox 的记录里，只保存在本地，所以 [toRecord] 不输出它、[fromRecord] 读到的备注为空
 * （合并时 [PasswordDb] 对「密码没变」的记录不会动本地备注）。
 *
 * 将来接同步时：下载的 payload 走 [fromRecord] → [PasswordDb.mergeRemote]；
 * 上传走 [PasswordDb.pendingUpload] → [toRecord]，成功后 [PasswordDb.markSynced]；删除走 [PasswordDb.tombstones] → [deletedRecord]。
 */
object PasswordSyncFormat {

    fun toRecord(e: PasswordEntry): String = JSONObject().apply {
        put("id", e.guid)
        put("hostname", e.origin)
        if (e.httpRealm != null) {
            put("formSubmitURL", JSONObject.NULL)
            put("httpRealm", e.httpRealm)
        } else {
            put("formSubmitURL", e.formOrigin ?: "")
            put("httpRealm", JSONObject.NULL)
        }
        put("username", e.username)
        put("password", e.password)
        put("usernameField", e.usernameField)
        put("passwordField", e.passwordField)
        put("timeCreated", e.timeCreated)
        put("timePasswordChanged", e.timePasswordChanged)
        put("timeLastUsed", e.timeLastUsed)
        put("timesUsed", e.timesUsed)
    }.toString()

    fun deletedRecord(guid: String): String = JSONObject().put("id", guid).put("deleted", true).toString()

    /** 解析一条 payload。删除标记、缺 id / 站点 / 密码、站点不合法的返回 null（调用方按 [isDeleted] 另行处理删除）。 */
    fun fromRecord(json: String): PasswordEntry? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optBoolean("deleted", false)) return null
        val id = o.optString("id")
        val password = o.optString("password")
        val origin = PasswordCsv.originOf(o.optString("hostname")) ?: return null
        if (id.isBlank() || password.isEmpty()) return null
        val realm = if (o.isNull("httpRealm")) null else o.optString("httpRealm").takeIf { it.isNotEmpty() }
        val formRaw = if (o.isNull("formSubmitURL")) "" else o.optString("formSubmitURL")
        return PasswordEntry(
            guid = id,
            origin = origin,
            formOrigin = if (realm != null) null else formRaw.takeIf { it.isNotEmpty() }?.let { PasswordCsv.originOf(it) },
            httpRealm = realm,
            username = o.optString("username"),
            password = password,
            usernameField = o.optString("usernameField"),
            passwordField = o.optString("passwordField"),
            timeCreated = o.optLong("timeCreated"),
            timeLastUsed = o.optLong("timeLastUsed"),
            timePasswordChanged = o.optLong("timePasswordChanged"),
            timesUsed = o.optInt("timesUsed"),
        )
    }

    /** payload 是删除标记时返回被删记录的 id，否则 null。 */
    fun isDeleted(json: String): String? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return if (o.optBoolean("deleted", false)) o.optString("id").takeIf { it.isNotEmpty() } else null
    }
}
