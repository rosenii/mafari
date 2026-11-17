package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.i18n.tr
import org.junit.Assert.assertEquals
import org.junit.Test

/** 未调用 I18n.init 时（单元测试环境）按简体中文原样返回，并替换 {0} 占位符 */
class I18nTest {
    @Test fun sourceLanguageIsReturnedAsIs() {
        assertEquals("设置", tr("设置"))
    }

    @Test fun placeholdersAreFilled() {
        assertEquals("共 3 项，点击查看", tr("共 {0} 项，点击查看", 3))
        assertEquals("已更新 2/5 个订阅", tr("已更新 {0}/{1} 个订阅", 2, 5))
    }
}
