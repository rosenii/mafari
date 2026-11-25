/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtensionLifecycleTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Timer : WakeTimer {
        var delay: Long? = null
        override fun schedule(delayMs: Long, task: Runnable) { delay = delayMs }
        override fun cancel() { delay = null }
    }

    private class Recorder : EventSink {
        val events = ArrayList<Pair<String, JSONObject>>()
        override fun emit(extId: String, event: String, payload: JSONObject) { events.add(event to payload) }
        fun names() = events.map { it.first }
    }

    private val id = "demo@x.org"
    private var clock = 5_000_000L
    private lateinit var sink: Recorder
    private lateinit var alarms: AlarmService
    private lateinit var life: ExtensionLifecycle

    @Before fun setUp() {
        sink = Recorder()
        val dir = tmp.newFolder("data")
        alarms = AlarmService({ dir }, sink, Timer(), { clock })
        life = ExtensionLifecycle(sink, alarms) { it.run() }
    }

    private fun missAnAlarm() {
        alarms.create(id, "a", JSONObject("""{"delayInMinutes":1}"""))
        clock += 10 * 60_000L
    }

    @Test fun startupEventComesBeforeCompensatedAlarms() {
        missAnAlarm()
        life.expectStartup(id)
        life.onBackgroundReady(id)
        assertEquals(listOf("runtime.onStartup", "alarms.onAlarm"), sink.names())
    }

    @Test fun nothingIsDeliveredBeforeTheBackgroundIsReady() {
        missAnAlarm()
        life.expectStartup(id)
        alarms.onWake()
        assertTrue(sink.events.isEmpty())
    }

    @Test fun installedEventCarriesReasonAndPreviousVersion() {
        life.expectInstalled(id, null)
        life.onBackgroundReady(id)
        assertEquals("install", sink.events[0].second.getString("reason"))
        assertFalse(sink.events[0].second.has("previousVersion"))

        sink.events.clear()
        life.expectInstalled(id, "1.2")
        life.onBackgroundReady(id)
        assertEquals("runtime.onInstalled", sink.events[0].first)
        assertEquals("update", sink.events[0].second.getString("reason"))
        assertEquals("1.2", sink.events[0].second.getString("previousVersion"))
    }

    @Test fun installAndStartupAreMutuallyExclusive() {
        life.expectStartup(id)
        life.expectInstalled(id, null)
        life.onBackgroundReady(id)
        assertEquals(listOf("runtime.onInstalled"), sink.names())
    }

    @Test fun reEnableFiresNoRuntimeEventButStillCompensatesAlarms() {
        missAnAlarm()
        life.onBackgroundReady(id) // 没有 expect：重新启用
        assertEquals(listOf("alarms.onAlarm"), sink.names())
    }

    @Test fun readyEventsFireOnlyOncePerExpectation() {
        life.expectStartup(id)
        life.onBackgroundReady(id)
        life.onBackgroundReady(id)
        assertEquals(listOf("runtime.onStartup"), sink.names())
    }

    @Test fun cancelDropsPendingStartEvent() {
        life.expectStartup(id)
        life.cancel(id)
        life.onBackgroundReady(id)
        assertTrue(sink.events.isEmpty())
    }

    @Test fun goneMakesAlarmsWaitForTheNextReady() {
        life.onBackgroundReady(id)
        alarms.create(id, "a", JSONObject("""{"delayInMinutes":1}"""))
        life.onBackgroundGone(id)
        clock += 5 * 60_000L
        alarms.onWake()
        assertTrue(sink.events.isEmpty())
        life.onBackgroundReady(id)
        assertEquals(listOf("alarms.onAlarm"), sink.names())
    }
}
