package dev.dsh.miuixbrowser

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * 油猴脚本的加密库存储（和书签、历史、GM 值同在 SQLCipher 整库加密的 history.db 里）。
 *
 * 一行一个脚本，主键是脚本 id（GM 值也按这个 id 存，所以 id 必须稳定）；`pos` 是列表顺序。
 *  - 写入：[sync] 只写和上次落盘不同的行、删掉消失的行，单事务；调用方在 [HistoryDb.io] 线程上串行执行。
 *  - 读取：脚本代码可能很大，而 CursorWindow 单行上限约 2MB，所以超过 [CHUNK] 字符的代码按 substr 分块读出。
 *  - 备份：[itemsToJson] 导出成旧格式（和以前 SharedPreferences「mafari_ext」里 scripts 一致），新旧版本的备份可互相恢复。
 *
 * 列名用 match_rule 而不是 match：MATCH 是 SQLite 的关键字。
 */
internal object ScriptDb {

    const val CREATE_SCRIPTS =
        "CREATE TABLE IF NOT EXISTS userscripts(" +
            "id TEXT PRIMARY KEY NOT NULL, " +
            "pos INTEGER NOT NULL, " +
            "name TEXT NOT NULL, " +
            "code TEXT NOT NULL, " +
            "enabled INTEGER NOT NULL, " +
            "match_rule TEXT NOT NULL, " +
            "source_url TEXT NOT NULL, " +
            "run_at TEXT NOT NULL, " +
            "icon TEXT NOT NULL, " +
            "updated_at INTEGER NOT NULL, " +
            "error TEXT NOT NULL)"

    const val CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_userscripts_pos ON userscripts(pos)"

    /** 一次读出的代码字符数上限（UTF-8 最多 4 字节 / 字符，约 1.2MB，低于 CursorWindow 单行上限）。 */
    private const val CHUNK = 300_000

    /** 已落盘的一行：位置 + 内容（data class，直接比较就能知道这行要不要重写）。 */
    data class Row(val pos: Int, val script: UserScript)

    class Loaded(val items: List<UserScript>, val changed: Boolean)

    fun rows(items: List<UserScript>): Map<String, Row> {
        val out = LinkedHashMap<String, Row>(items.size)
        items.forEachIndexed { i, s -> out[s.id] = Row(i, s) }
        return out
    }

    /** 按列表顺序读出全部脚本。 */
    fun load(db: SQLiteDatabase): List<UserScript> {
        class Head(val script: UserScript, val codeLength: Int, val inlineCode: String?)
        val heads = ArrayList<Head>()
        db.rawQuery(
            "SELECT id,name,enabled,match_rule,source_url,run_at,icon,updated_at,error,length(code)," +
                "CASE WHEN length(code)<=$CHUNK THEN code ELSE NULL END FROM userscripts ORDER BY pos",
            emptyArray<String>(),
        ).use { c ->
            while (c.moveToNext()) {
                heads.add(
                    Head(
                        UserScript(
                            id = c.getString(0),
                            name = c.getString(1),
                            code = "",
                            enabled = c.getInt(2) != 0,
                            match = c.getString(3),
                            sourceUrl = c.getString(4),
                            runAt = c.getString(5),
                            icon = c.getString(6),
                            updatedAt = c.getLong(7),
                            error = c.getString(8),
                        ),
                        c.getInt(9),
                        if (c.isNull(10)) null else c.getString(10),
                    ),
                )
            }
        }
        // 游标关掉之后再读大代码，避免两个游标同时占着窗口
        return heads.map { h -> h.script.copy(code = h.inlineCode ?: readCode(db, h.script.id, h.codeLength)) }
    }

    private fun readCode(db: SQLiteDatabase, id: String, length: Int): String {
        val sb = StringBuilder(length)
        var pos = 1
        while (pos <= length) {
            db.rawQuery("SELECT substr(code,$pos,$CHUNK) FROM userscripts WHERE id=?", arrayOf(id)).use { c ->
                if (c.moveToFirst()) sb.append(c.getString(0))
            }
            pos += CHUNK
        }
        return sb.toString()
    }

    /** id 为空或重复的脚本补发新 id（重复的只保留第一条的原 id），保证主键唯一。 */
    fun dedupeIds(list: List<UserScript>): Loaded {
        val seen = HashSet<String>()
        var changed = false
        val out = list.map { s ->
            if (s.id.isBlank() || !seen.add(s.id)) {
                changed = true
                val fresh = s.copy(id = java.util.UUID.randomUUID().toString())
                seen.add(fresh.id)
                fresh
            } else {
                s
            }
        }
        return Loaded(out, changed)
    }

    /** 迁移 / 恢复：整表替换，单事务；返回落盘后的行（作为下次 [sync] 的基线）。 */
    fun replaceAll(db: SQLiteDatabase, items: List<UserScript>): Map<String, Row> {
        inTransaction(db) {
            db.execSQL("DELETE FROM userscripts")
            items.forEachIndexed { i, s -> upsert(db, i, s) }
        }
        return rows(items)
    }

    /**
     * 把 [items] 和上次落盘的 [prev] 比对，只写有变化的行、只删消失的行，单事务。
     * 返回新基线；抛异常时事务回滚，调用方保留旧基线。
     */
    fun sync(db: SQLiteDatabase, items: List<UserScript>, prev: Map<String, Row>): Map<String, Row> {
        val next = rows(items)
        inTransaction(db) {
            for (id in prev.keys) {
                if (id !in next) db.execSQL("DELETE FROM userscripts WHERE id=?", arrayOf<Any?>(id))
            }
            for ((id, row) in next) {
                if (prev[id] != row) upsert(db, row.pos, row.script)
            }
        }
        return next
    }

    /** 备份恢复前清空（恢复语义是整体替换）。 */
    fun clearAll(context: Context) {
        val db = HistoryDb.get(context) ?: return
        runCatching { db.execSQL("DELETE FROM userscripts") }
    }

    // ------------------------------------------------------------------ 旧格式 JSON（备份 / 旧存储）

    fun itemsToJson(items: List<UserScript>): String {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("code", it.code)
                    .put("enabled", it.enabled)
                    .put("match", it.match)
                    .put("sourceUrl", it.sourceUrl)
                    .put("runAt", it.runAt)
                    .put("icon", it.icon)
                    .put("updatedAt", it.updatedAt)
                    .put("error", it.error),
            )
        }
        return array.toString()
    }

    // ------------------------------------------------------------------ 内部

    private fun upsert(db: SQLiteDatabase, pos: Int, s: UserScript) {
        db.execSQL(
            "INSERT OR REPLACE INTO userscripts(id,pos,name,code,enabled,match_rule,source_url,run_at,icon,updated_at,error) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                s.id, pos.toLong(), s.name, s.code, if (s.enabled) 1L else 0L,
                s.match, s.sourceUrl, s.runAt, s.icon, s.updatedAt, s.error,
            ),
        )
    }

    private inline fun inTransaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
