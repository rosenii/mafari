/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.ui.shouldShowAddressDownload
import dev.dsh.miuixbrowser.ui.shouldShowAddressMedia
import dev.dsh.miuixbrowser.ui.shouldShowAddressReader
import dev.dsh.miuixbrowser.ui.shouldShowAddressScripts
import dev.dsh.miuixbrowser.ui.shouldShowAddressTranslate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 地址栏最左侧图标的优先级：下载 > 媒体 > 翻译 > 阅读 > 脚本（共用同一个位置）。 */
class AddressMediaIconTest {
    @Test fun mediaShownWhenSniffed() {
        assertTrue(shouldShowAddressMedia(mediaVisible = true, editing = false))
    }

    @Test fun mediaHiddenWithoutSniffOrWhileEditing() {
        // 没嗅探到（或媒体嗅探关闭时外层传 false）不显示；输入网址时让位给输入框
        assertFalse(shouldShowAddressMedia(mediaVisible = false, editing = false))
        assertFalse(shouldShowAddressMedia(mediaVisible = true, editing = true))
    }

    @Test fun downloadOutranksMedia() {
        assertFalse(shouldShowAddressMedia(mediaVisible = true, editing = false, downloadVisible = true))
        assertTrue(shouldShowAddressDownload(downloadVisible = true, editing = false))
    }

    @Test fun mediaOutranksTranslate() {
        assertFalse(shouldShowAddressTranslate(translateVisible = true, editing = false, mediaVisible = true))
        assertFalse(shouldShowAddressTranslate(translateVisible = true, editing = false, downloadVisible = true))
        assertTrue(shouldShowAddressTranslate(translateVisible = true, editing = false))
    }

    @Test fun mediaOutranksReaderAndScripts() {
        assertFalse(shouldShowAddressReader(readerVisible = true, editing = false, mediaVisible = true))
        assertTrue(shouldShowAddressReader(readerVisible = true, editing = false, mediaVisible = false))
        assertFalse(shouldShowAddressScripts(readerVisible = false, scriptsRunning = true, editing = false, mediaVisible = true))
        assertTrue(shouldShowAddressScripts(readerVisible = false, scriptsRunning = true, editing = false, mediaVisible = false))
    }
}
