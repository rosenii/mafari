/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabChangeTrackerTest {

    private val log = ArrayList<String>()
    private val tracker = TabChangeTracker(object : TabEventListener {
        override fun onCreated(tab: TabInfo) { log += "created:${tab.id}" }
        override fun onUpdated(tab: TabInfo, change: TabChange) {
            val parts = listOfNotNull(
                change.status?.let { "status=$it" },
                change.url?.let { "url=$it" },
                change.title?.let { "title=$it" },
                change.favIconUrl?.let { "icon=$it" },
            )
            log += "updated:${tab.id}:" + parts.joinToString(",")
        }
        override fun onMoved(tabId: Int, fromIndex: Int, toIndex: Int) { log += "moved:$tabId:$fromIndex->$toIndex" }
        override fun onRemoved(tabId: Int) { log += "removed:$tabId" }
        override fun onActivated(tabId: Int, previousTabId: Int?) { log += "activated:$tabId<-$previousTabId" }
    })

    private fun tab(
        id: Int,
        active: Boolean = false,
        url: String = "https://a.com/$id",
        title: String = "t$id",
        loading: Boolean = false,
        icon: String? = null,
        index: Int = id - 1,
    ) = TabInfo(id, index, active, url, title, icon, loading, null)

    private fun drain(): List<String> = log.toList().also { log.clear() }

    @Test fun firstUpdateOnlyEstablishesBaseline() {
        tracker.update(listOf(tab(1, active = true), tab(2)))
        assertTrue(drain().isEmpty())
    }

    @Test fun createdAndRemoved() {
        tracker.update(listOf(tab(1, active = true)))
        tracker.update(listOf(tab(1, active = true), tab(2)))
        assertEquals(listOf("created:2"), drain())
        tracker.update(listOf(tab(1, active = true)))
        assertEquals(listOf("removed:2"), drain())
    }

    @Test fun updatedCarriesOnlyChangedFields() {
        tracker.update(listOf(tab(1, active = true)))
        tracker.update(listOf(tab(1, active = true, loading = true)))
        assertEquals(listOf("updated:1:status=loading"), drain())
        tracker.update(listOf(tab(1, active = true, loading = true, url = "https://b.com/", title = "B")))
        assertEquals(listOf("updated:1:url=https://b.com/,title=B"), drain())
        tracker.update(listOf(tab(1, active = true, loading = false, url = "https://b.com/", title = "B")))
        assertEquals(listOf("updated:1:status=complete"), drain())
    }

    @Test fun faviconOnlyWhenItBecomesNonNull() {
        tracker.update(listOf(tab(1, active = true)))
        tracker.update(listOf(tab(1, active = true, icon = "https://a.com/i.png")))
        assertEquals(listOf("updated:1:icon=https://a.com/i.png"), drain())
        tracker.update(listOf(tab(1, active = true, icon = null)))
        assertTrue("cleared icon cannot be expressed, so no event", drain().isEmpty())
    }

    @Test fun reorderEmitsMovedEventsButNoUpdatedEvents() {
        tracker.update(listOf(tab(1, active = true, index = 0), tab(2, index = 1)))
        tracker.update(listOf(tab(2, index = 0), tab(1, active = true, index = 1)))
        assertEquals(listOf("moved:2:1->0", "moved:1:0->1"), drain())
    }

    @Test fun activationReportsPreviousTab() {
        tracker.update(listOf(tab(1, active = true), tab(2)))
        tracker.update(listOf(tab(1), tab(2, active = true)))
        assertEquals(listOf("activated:2<-1"), drain())
    }

    @Test fun closingTheActiveTabOrdersRemovedBeforeActivated() {
        tracker.update(listOf(tab(1), tab(2, active = true)))
        tracker.update(listOf(tab(1, active = true)))
        assertEquals(listOf("removed:2", "activated:1<-null"), drain())
    }

    @Test fun createAndActivateInOneStep() {
        tracker.update(listOf(tab(1, active = true)))
        tracker.update(listOf(tab(1), tab(2, active = true)))
        assertEquals(listOf("created:2", "activated:2<-1"), drain())
    }

    @Test fun noVisibleActiveTabIsNotAnActivation() {
        tracker.update(listOf(tab(1, active = true)))
        // 用户切到了无痕 / Gecko 标签：可见标签里没有前台
        tracker.update(listOf(tab(1)))
        assertTrue(drain().isEmpty())
        // 切回来：之前没有（可见的）前台标签
        tracker.update(listOf(tab(1, active = true)))
        assertEquals(listOf("activated:1<-null"), drain())
    }
}
