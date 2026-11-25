/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class I18nMessagesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ext(vararg locales: Pair<String, String>): File {
        val root = tmp.newFolder()
        for ((dir, json) in locales) {
            File(root, "_locales/$dir").apply { mkdirs() }.resolve("messages.json").writeText(json)
        }
        return root
    }

    private fun build(root: File, default: String?, device: String) =
        I18nMessages.build(root, default, device, ExtensionInjection.fileReader(root))

    private fun msg(r: I18nMessages.Result, key: String): String? =
        r.json?.getJSONObject("msgs")?.optJSONObject(key)?.getString("m")

    @Test fun noLocalesDir() {
        val r = build(tmp.newFolder(), "en", "en_US")
        assertNull(r.json)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun exactOverridesLanguageOverridesDefault() {
        val root = ext(
            "en" to """{"a":{"message":"en-a"},"b":{"message":"en-b"},"c":{"message":"en-c"}}""",
            "zh" to """{"b":{"message":"zh-b"},"c":{"message":"zh-c"}}""",
            "zh_CN" to """{"c":{"message":"zhcn-c"}}""",
        )
        val r = build(root, "en", "zh_CN")
        assertEquals("en-a", msg(r, "a"))   // 只有默认语言有：回退到它
        assertEquals("zh-b", msg(r, "b"))
        assertEquals("zhcn-c", msg(r, "c"))
        assertEquals("zh_CN", r.json!!.getString("locale"))
    }

    @Test fun keysAndPlaceholderNamesAreLowercased() {
        val root = ext(
            "en" to """{"Hello":{"message":"x ${'$'}Name${'$'}","placeholders":{"Name":{"content":"${'$'}1"}}}}""",
        )
        val e = build(root, "en", "en").json!!.getJSONObject("msgs").getJSONObject("hello")
        assertEquals("${'$'}1", e.getJSONObject("p").getString("name"))
    }

    @Test fun dirMatchingIgnoresCaseAndDash() {
        val root = ext("pt_BR" to """{"a":{"message":"br"}}""", "en" to """{"a":{"message":"en"}}""")
        assertEquals("br", msg(build(root, "en", "pt-br"), "a"))
    }

    @Test fun deviceLanguageWithoutDirFallsBackToSameLanguageRegion() {
        val root = ext("en_US" to """{"a":{"message":"us"}}""", "de" to """{"a":{"message":"de"}}""")
        assertEquals("us", msg(build(root, "de", "en"), "a"))
    }

    @Test fun unrelatedDeviceLanguageUsesDefault() {
        val root = ext("en" to """{"a":{"message":"en"}}""", "de" to """{"a":{"message":"de"}}""")
        assertEquals("en", msg(build(root, "en", "ja_JP"), "a"))
    }

    @Test fun badJsonIsSkippedWithWarning() {
        val root = ext("en" to """{"a":{"message":"en"}}""", "zh" to "{oops")
        val r = build(root, "en", "zh_CN")
        assertEquals("en", msg(r, "a"))
        assertTrue(r.warnings.any { it.contains("zh") })
    }

    @Test fun entriesWithoutStringMessageAreIgnored() {
        val root = ext("en" to """{"a":{"message":1},"b":"x","c":{"message":"ok"}}""")
        val r = build(root, "en", "en")
        assertNull(msg(r, "a"))
        assertNull(msg(r, "b"))
        assertEquals("ok", msg(r, "c"))
    }

    @Test fun unsafeDirectoryNamesAreNotRead() {
        val root = ext("en" to """{"a":{"message":"en"}}""")
        File(root, "_locales/..x").mkdirs()
        File(root, "_locales/a b").mkdirs()
        assertEquals("en", msg(build(root, "en", "en"), "a"))
    }

    @Test fun oversizedTableIsNotInjectedButStillUsableForLocalize() {
        val big = "x".repeat(I18nMessages.MAX_TABLE_CHARS)
        val root = ext("en" to """{"a":{"message":"$big"},"name":{"message":"Name"}}""")
        val r = build(root, "en", "en")
        val warnings = mutableListOf<String>()
        assertNull(I18nMessages.forInjection(r, warnings))
        assertTrue(warnings.isNotEmpty())
        // 完整的表还在：manifest / CSS 展开不受注入上限影响
        assertEquals("Name", I18nMessages.localize("__MSG_name__", r.json!!.getJSONObject("msgs"), "id", "en"))
    }

    private val D = "${'$'}"

    private fun table(vararg locales: Pair<String, String>): JSONObject? {
        val root = ext(*locales)
        return build(root, "en", "en").json?.getJSONObject("msgs")
    }

    @Test fun localizeReplacesMessages() {
        val t = table("en" to """{"extName":{"message":"My Ext"},"d":{"message":"a ${D}${D} b"}}""")
        assertEquals("My Ext!", I18nMessages.localize("__MSG_extName__!", t, "id", "en"))
        assertEquals("My Ext / My Ext", I18nMessages.localize("__MSG_extName__ / __MSG_EXTNAME__", t, "id", "en"))
        assertEquals("a $D b", I18nMessages.localize("__MSG_d__", t, "id", "en"))
    }

    @Test fun localizeDropsUnfilledSubstitutionsAndExpandsPlaceholders() {
        val t = table(
            "en" to """{"m":{"message":"x ${D}1 y ${D}n${D} z","placeholders":{"n":{"content":"[${D}1]"}}}}""",
        )
        assertEquals("x  y [] z", I18nMessages.localize("__MSG_m__", t, "id", "en"))
    }

    @Test fun localizeKeepsUnknownAndHandlesBuiltins() {
        assertEquals("__MSG_nope__", I18nMessages.localize("__MSG_nope__", JSONObject(), "id", "en"))
        assertEquals("__MSG_nope__", I18nMessages.localize("__MSG_nope__", null, "id", "en"))
        assertEquals("id", I18nMessages.localize("__MSG_@@extension_id__", null, "id", "en"))
        assertEquals("rtl", I18nMessages.localize("__MSG_@@bidi_dir__", null, "id", "ar_EG"))
        assertEquals("plain text", I18nMessages.localize("plain text", null, "id", "en"))
    }

    @Test fun localizeManifestUsesDeviceLocaleAndRecurses() {
        val root = ext(
            "en" to """{"extName":{"message":"Hello"},"t":{"message":"Tip"}}""",
            "zh_CN" to """{"extName":{"message":"你好"}}""",
        )
        fun m() = JSONObject(
            """{"name":"__MSG_extName__","default_locale":"en","action":{"default_title":"__MSG_t__"},"x":["__MSG_extName__"]}""",
        )
        val zh = I18nMessages.localizeManifest(m(), root, "id", "zh_CN")
        assertEquals("你好", zh.getString("name"))
        assertEquals("Tip", zh.getJSONObject("action").getString("default_title")) // 缺的回退到 default_locale
        assertEquals("你好", zh.getJSONArray("x").getString(0))
        // 用 default_locale 当设备语言：与设备语言无关
        assertEquals("Hello", I18nMessages.localizeManifest(m(), root, "id", "en").getString("name"))
    }

    @Test fun localizeManifestWithoutPlaceholdersReadsNothing() {
        val m = JSONObject("""{"name":"Plain"}""")
        assertSame(m, I18nMessages.localizeManifest(m, tmp.newFolder(), "id", "en"))
        assertEquals("Plain", m.getString("name"))
    }

    @Test fun manifestParserExpandsNameAndDescription() {
        val root = ext("en" to """{"extName":{"message":"Cool Ext"},"extDesc":{"message":"Does things"}}""")
        val spec = ManifestParser.parse(
            """{"manifest_version":3,"name":"__MSG_extName__","description":"__MSG_extDesc__","version":"1","default_locale":"en"}""",
            "id",
            root,
        )
        assertEquals("Cool Ext", spec.name)
        assertEquals("Does things", spec.description)
        // 不给目录时保持原样
        val raw = ManifestParser.parse("""{"manifest_version":3,"name":"__MSG_extName__","version":"1"}""", "id")
        assertEquals("__MSG_extName__", raw.name)
    }

    @Test fun chromeLocaleFormat() {
        assertEquals("zh_CN", I18nMessages.chromeLocale(Locale("zh", "CN")))
        assertEquals("en", I18nMessages.chromeLocale(Locale("en")))
    }
}
