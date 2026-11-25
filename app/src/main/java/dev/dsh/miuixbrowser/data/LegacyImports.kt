/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.data

import dev.dsh.miuixbrowser.crypto.SecureStore
import dev.dsh.miuixbrowser.util.Diag
import dev.dsh.miuixbrowser.util.hostOf
import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray

/**
 * 库首次打开后，把旧版存在 SharedPreferences 里的数据一次性导入加密库（与表结构迁移 [Migrations] 无关：这是数据搬家，
 * 备份恢复写回的旧格式值同样在下次启动时被导入）。任何一项失败只记日志，不影响库就绪。
 */
internal object LegacyImports {

    private const val LEGACY_PREFS = "mafari_history"
    private const val LEGACY_KEY = "entries"

    fun run(context: Context, db: SQLiteDatabase) {
        runCatching { importHistory(context, db) }
            .onFailure { Diag.log("history db: migrate failed: ${it.message}") }
        runCatching { importGmValues(context, db) }
            .onFailure { Diag.log("history db: gm migrate failed: ${it.message}") }
    }

    /**
     * 旧版把整份历史加密成一个字符串存在 SharedPreferences「mafari_history」的 entries 里：
     * 首次打开时自动导入（同网址保留较新的一条），导入成功后删除旧值。
     */
    private fun importHistory(context: Context, db: SQLiteDatabase) {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(LEGACY_KEY, null) ?: return
        val raw = SecureStore.read(context, stored, SecureStore.PURPOSE_HISTORY) ?: return
        val array = JSONArray(raw)
        db.beginTransaction()
        try {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val url = o.optString("url")
                if (url.isBlank()) continue
                HistoryDao.upsert(db, url, o.optString("title").ifBlank { hostOf(url) }, o.optLong("time"))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        prefs.edit().remove(LEGACY_KEY).commit()
    }

    /** 旧版 GM 值存在 SharedPreferences「userscript_gm」（键 = 脚本id::名字）：导入后逐个删除。 */
    private fun importGmValues(context: Context, db: SQLiteDatabase) {
        val prefs = context.getSharedPreferences("userscript_gm", Context.MODE_PRIVATE)
        val legacy = prefs.all.filter { it.key.contains("::") && it.value is String }
        if (legacy.isEmpty()) return
        db.beginTransaction()
        try {
            for ((key, value) in legacy) {
                val i = key.indexOf("::")
                db.execSQL(
                    "INSERT OR REPLACE INTO gm_values(sid,k,v) VALUES(?,?,?)",
                    arrayOf<Any?>(key.substring(0, i), key.substring(i + 2), value as String),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val editor = prefs.edit()
        legacy.keys.forEach { editor.remove(it) }
        editor.commit()
    }
}
