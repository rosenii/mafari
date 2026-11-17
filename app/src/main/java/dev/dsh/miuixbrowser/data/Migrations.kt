package dev.dsh.miuixbrowser.data

import dev.dsh.miuixbrowser.i18n.tr

import dev.dsh.miuixbrowser.util.Diag
import net.zetetic.database.sqlcipher.SQLiteDatabase
import dev.dsh.miuixbrowser.download.DownloadDb

/**
 * 表结构迁移注册表。版本号存在库的 PRAGMA user_version 里。
 *
 * 以后新增 / 修改表、索引、列：在 [STEPS] 末尾追加一个 version = 上一步 + 1 的 [Step]，别改已发布的旧步骤。
 * [apply] 只执行 user_version 之后的步骤，每步一个事务，成功后立刻写回 user_version；
 * 某步失败会回滚并向上抛（该库本次启动按「打不开」降级，下次启动从失败的那一步重试），不会留下半截结构。
 *
 * 各 *Db 对象里的 CREATE_* 常量仍由它们自己持有（表结构和读写代码放在一起），这里只负责「何时、按什么顺序」执行。
 */
internal object Migrations {

    class Step(val version: Int, val name: String, val run: (SQLiteDatabase) -> Unit)

    /** v1 = 引入迁移注册表之前的全部结构（原先每次升级都重跑的那串 CREATE IF NOT EXISTS）。老库 user_version 已是 1，会直接跳过。 */
    private val STEPS: List<Step> = listOf(
        Step(1, "baseline schema") { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS history(url TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, time INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_time ON history(time DESC)")
            // 油猴脚本的 GM_setValue 数据（见 GmStore）
            db.execSQL("CREATE TABLE IF NOT EXISTS gm_values(sid TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, PRIMARY KEY(sid,k)) WITHOUT ROWID")
            // 隐私报告：网页请求摄像头 / 麦克风 / 定位的记录（见 PrivacyStats）
            db.execSQL("CREATE TABLE IF NOT EXISTS perm_events(id INTEGER PRIMARY KEY AUTOINCREMENT, host TEXT NOT NULL, time INTEGER NOT NULL, kinds TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_perm_time ON perm_events(time)")
            // 下载记录（见 DownloadDb / Downloader）
            db.execSQL(DownloadDb.CREATE_TABLE)
            // 书签（见 BookmarkDb / FavoritesStore；Floccus 同步读写的就是这份）
            db.execSQL(BookmarkDb.CREATE_BOOKMARKS)
            db.execSQL(BookmarkDb.CREATE_FOLDERS)
            db.execSQL(BookmarkDb.CREATE_META)
            // 油猴脚本（见 ScriptDb / ExtensionStore）
            db.execSQL(ScriptDb.CREATE_SCRIPTS)
            db.execSQL(ScriptDb.CREATE_INDEX)
            // 密码（见 PasswordDb）：整库加密之上，密码 / 备注列还用加密套件逐条加密
            db.execSQL(PasswordDb.CREATE_PASSWORDS)
            db.execSQL(PasswordDb.CREATE_ORIGIN_INDEX)
            db.execSQL(PasswordDb.CREATE_TOMBSTONES)
            // 标签列表（见 TabDb / BrowserState）：只存普通标签，无痕标签不落盘
            db.execSQL(TabDb.CREATE_TABS)
            TabDb.migrate(db) // 旧库补 Gecko 会话状态列
            db.execSQL(TabDb.CREATE_INDEX)
        },
        // 书签「同层置顶」：bookmarks 表加 pinned 列
        Step(2, "bookmark pinned column") { db -> BookmarkDb.addPinnedColumn(db) },
        // 密码 ↔ 原生 App 的关联（系统自动填充用，见 PasswordDb.listForPackage）
        Step(3, "password app links") { db ->
            db.execSQL(PasswordDb.CREATE_PASSWORD_APPS)
            db.execSQL(PasswordDb.CREATE_PASSWORD_APPS_INDEX)
        },
    )

    /** 当前代码期望的结构版本。 */
    val LATEST: Int = STEPS.last().version

    init {
        // 开发期自检：版本号必须从 1 开始连续递增
        check(STEPS.withIndex().all { (i, s) -> s.version == i + 1 }) { tr("Migrations.STEPS 版本号必须连续递增") }
    }

    fun apply(db: SQLiteDatabase) {
        val current = versionOf(db)
        if (current >= LATEST) {
            Diag.log("history db open: schema v$current up to date, DDL skipped")
            return
        }
        for (step in STEPS) {
            if (step.version <= current) continue
            db.beginTransaction()
            try {
                step.run(db)
                db.execSQL("PRAGMA user_version=${step.version}")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Diag.log("history db: migrated to v${step.version} (${step.name})")
        }
    }

    private fun versionOf(db: SQLiteDatabase): Int =
        runCatching {
            db.rawQuery("PRAGMA user_version", emptyArray<String>()).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }.getOrDefault(0)
}
