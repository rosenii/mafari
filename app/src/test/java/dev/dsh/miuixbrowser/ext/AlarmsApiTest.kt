/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AlarmsApiTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeTimer : WakeTimer {
        var delay: Long? = null
        override fun schedule(delayMs: Long, task: Runnable) { delay = delayMs }
        override fun cancel() { delay = null }
    }

    private class Recorder : EventSink {
        val events = ArrayList<Triple<String, String, JSONObject>>()
        override fun emit(extId: String, event: String, payload: JSONObject) { events.add(Triple(extId, event, payload)) }
        fun names() = events.filter { it.second == AlarmService.EVENT }.map { it.third.getString("name") }
    }

    private val min = 60_000L
    private val id = "demo@x.org"
    private var clock = 1_000_000_000L
    private lateinit var dir: File
    private lateinit var timer: FakeTimer
    private lateinit var sink: Recorder
    private lateinit var svc: AlarmService

    private fun newService() = AlarmService({ dir }, sink, timer, { clock })

    @Before fun setUp() {
        dir = tmp.newFolder("data")
        timer = FakeTimer()
        sink = Recorder()
        svc = newService()
    }

    private fun info(json: String) = JSONObject(json)

    private fun code(block: () -> Unit): String? = try {
        block(); null
    } catch (e: ApiException) {
        e.code
    }

    private fun stored(): JSONArray = JSONObject(File(dir, "alarms.json").readText()).getJSONArray("alarms")

    // ───── create 校验 ─────

    @Test fun createValidation() {
        assertEquals(ErrorCodes.BAD_REQUEST, code { svc.create(id, "a", info("""{"when":1,"delayInMinutes":1}""")) })
        assertEquals(ErrorCodes.BAD_REQUEST, code { svc.create(id, "a", info("{}")) })
        assertEquals(ErrorCodes.BAD_REQUEST, code { svc.create(id, "a", null) })
        assertEquals(ErrorCodes.BAD_REQUEST, code { svc.create(id, 5, info("""{"delayInMinutes":1}""")) })
        assertEquals(ErrorCodes.BAD_REQUEST, code { svc.create(id, "a", info("""{"delayInMinutes":"1"}""")) })
        assertEquals(ErrorCodes.BAD_REQUEST, code { svc.create(id, "a", info("""{"periodInMinutes":0}""")) })
        assertEquals(ErrorCodes.QUOTA, code { svc.create(id, "x".repeat(257), info("""{"delayInMinutes":1}""")) })
        assertNull(code { svc.create(id, "x".repeat(256), info("""{"delayInMinutes":1}""")) })
        assertEquals(1, svc.getAll(id).size)
    }

    @Test fun capsAlarmsPerExtension() {
        for (i in 0 until AlarmService.MAX_ALARMS_PER_EXTENSION) svc.create(id, "a$i", info("""{"delayInMinutes":5}"""))
        assertEquals(ErrorCodes.QUOTA, code { svc.create(id, "one-more", info("""{"delayInMinutes":5}""")) })
        // 覆盖已有名字不算新增
        assertNull(code { svc.create(id, "a0", info("""{"delayInMinutes":6}""")) })
        assertEquals(AlarmService.MAX_ALARMS_PER_EXTENSION, svc.getAll(id).size)
    }

    @Test fun minimumIsThirtySecondsAndFractionsAreAllowed() {
        svc.create(id, "tiny", info("""{"delayInMinutes":0.1}"""))
        assertEquals(clock + 30_000, svc.get(id, "tiny")!!.scheduledTime)
        svc.create(id, "frac", info("""{"delayInMinutes":1.5,"periodInMinutes":0.25}"""))
        val a = svc.get(id, "frac")!!
        assertEquals(clock + 90_000, a.scheduledTime)
        assertEquals(0.5, a.periodInMinutes!!, 0.0)
    }

    @Test fun firstFireTimeFollowsChromeRules() {
        svc.create(id, "when", info("""{"when":${clock + 5 * min}}"""))
        assertEquals(clock + 5 * min, svc.get(id, "when")!!.scheduledTime)
        svc.create(id, "period", info("""{"periodInMinutes":2}"""))
        assertEquals(clock + 2 * min, svc.get(id, "period")!!.scheduledTime)
        svc.create(id, "both", info("""{"delayInMinutes":1,"periodInMinutes":2}"""))
        assertEquals(clock + min, svc.get(id, "both")!!.scheduledTime)
        assertEquals(2.0, svc.get(id, "both")!!.periodInMinutes!!, 0.0)
    }

    // ───── get / clear ─────

    @Test fun sameNameOverwritesAndDefaultNameIsEmpty() {
        svc.create(id, "n", info("""{"delayInMinutes":1}"""))
        svc.create(id, "n", info("""{"delayInMinutes":5}"""))
        assertEquals(1, svc.getAll(id).size)
        assertEquals(clock + 5 * min, svc.get(id, "n")!!.scheduledTime)
        svc.create(id, null, info("""{"delayInMinutes":1}"""))
        assertNotNull(svc.get(id, null))
        assertEquals("", svc.get(id, null)!!.name)
    }

    @Test fun getAndClearOnMissingName() {
        assertNull(svc.get(id, "nope"))
        assertFalse(svc.clear(id, "nope"))
        assertFalse(svc.clearAll(id))
        svc.create(id, "a", info("""{"delayInMinutes":1}"""))
        svc.create(id, "b", info("""{"delayInMinutes":1}"""))
        assertTrue(svc.clear(id, "a"))
        assertNull(svc.get(id, "a"))
        assertTrue(svc.clearAll(id))
        assertTrue(svc.getAll(id).isEmpty())
    }

    @Test fun alarmsAreIsolatedPerExtension() {
        svc.create(id, "a", info("""{"delayInMinutes":1}"""))
        assertNull(svc.get("other@x.org", "a"))
    }

    // ───── 就绪 / 排期 ─────

    @Test fun nothingIsScheduledWhileExtensionNotReadyOrWithoutAlarms() {
        assertNull(timer.delay)
        svc.create(id, "a", info("""{"delayInMinutes":1}"""))
        assertNull("没就绪的拓展不参与排期", timer.delay)
        svc.markReady(id)
        assertEquals(min, timer.delay)
        svc.clearAll(id)
        assertNull("没有闹钟就什么都不挂", timer.delay)
    }

    @Test fun sleepsAtMostTenMinutesAndTracksTheEarliestAlarm() {
        svc.markReady(id)
        svc.create(id, "far", info("""{"delayInMinutes":60}"""))
        assertEquals(AlarmService.MAX_SLEEP_MS, timer.delay)
        svc.create(id, "near", info("""{"delayInMinutes":2}"""))
        assertEquals(2 * min, timer.delay)
        svc.clear(id, "near")
        assertEquals(AlarmService.MAX_SLEEP_MS, timer.delay)
    }

    @Test fun markUnreadyStopsFiringAndScheduling() {
        svc.markReady(id)
        svc.create(id, "a", info("""{"delayInMinutes":1}"""))
        svc.markUnready(id)
        assertNull(timer.delay)
        clock += 2 * min
        svc.onWake()
        assertTrue(sink.events.isEmpty())
        assertNotNull("闹钟还在", svc.get(id, "a"))
    }

    @Test fun pastWhenFiresOnTheNextWake() {
        svc.markReady(id)
        svc.create(id, "late", info("""{"when":${clock - 5}}"""))
        assertEquals(0L, timer.delay)
        svc.onWake()
        assertEquals(listOf("late"), sink.names())
        assertNull(svc.get(id, "late"))
    }

    // ───── 触发 ─────

    @Test fun oneShotFiresOnceAndIsDeletedAndPersisted() {
        svc.markReady(id)
        svc.create(id, "once", info("""{"delayInMinutes":1}"""))
        svc.onWake()
        assertTrue("没到点", sink.events.isEmpty())
        clock += min
        svc.onWake()
        assertEquals(listOf("once"), sink.names())
        assertEquals(id, sink.events[0].first)
        assertNull(svc.get(id, "once"))
        assertEquals(0, stored().length())
        svc.onWake()
        assertEquals(1, sink.events.size)
    }

    @Test fun periodicKeepsItsPhaseWhenWokenSlightlyLate() {
        svc.markReady(id)
        svc.create(id, "p", info("""{"periodInMinutes":1}"""))
        val first = clock + min
        clock = first + 200 // 晚醒 200ms
        svc.onWake()
        assertEquals(listOf("p"), sink.names())
        assertEquals(first + min, svc.get(id, "p")!!.scheduledTime)
        assertEquals(first + min, stored().getJSONObject(0).getLong("scheduledTime"))
    }

    @Test fun periodicFarBehindWhileRunningRestartsFromNow() {
        svc.markReady(id)
        svc.create(id, "p", info("""{"periodInMinutes":1}"""))
        clock += 30 * min // 设备休眠很久
        svc.onWake()
        assertEquals("只触发一次，不按错过的周期数逐次补发", listOf("p"), sink.names())
        assertEquals(clock + min, svc.get(id, "p")!!.scheduledTime)
    }

    @Test fun alarmPayloadShape() {
        svc.markReady(id)
        svc.create(id, "p", info("""{"delayInMinutes":1,"periodInMinutes":3}"""))
        clock += min
        svc.onWake()
        val o = sink.events[0].third
        assertEquals("p", o.getString("name"))
        assertEquals(3.0, o.getDouble("periodInMinutes"), 0.0)
        assertTrue(o.has("scheduledTime"))
        svc.create(id, "q", info("""{"delayInMinutes":1}"""))
        assertFalse(svc.get(id, "q")!!.toJson().has("periodInMinutes"))
    }

    // ───── 持久化与启动补偿 ─────

    @Test fun persistedAlarmsComeBackAfterRestartAndFutureOnesAreKept() {
        svc.create(id, "later", info("""{"delayInMinutes":10,"periodInMinutes":10}"""))
        val svc2 = newService()
        svc2.markReady(id)
        assertTrue(sink.events.isEmpty())
        val a = svc2.get(id, "later")!!
        assertEquals(clock + 10 * min, a.scheduledTime)
        assertEquals(10.0, a.periodInMinutes!!, 0.0)
        assertEquals(10 * min, timer.delay)
    }

    @Test fun missedAlarmsAreCompensatedExactlyOnce() {
        svc.create(id, "once", info("""{"delayInMinutes":1}"""))
        svc.create(id, "every", info("""{"periodInMinutes":1}"""))
        clock += 100 * min // 进程被杀了很久
        val svc2 = newService()
        svc2.markReady(id)
        assertEquals(setOf("once", "every"), sink.names().toSet())
        assertEquals("各只补一次", 2, sink.names().size)
        assertNull("一次性补发后删除", svc2.get(id, "once"))
        assertEquals("周期闹钟从当前时间重排", clock + min, svc2.get(id, "every")!!.scheduledTime)
        assertEquals(1, stored().length())
        assertEquals(min, timer.delay)
    }

    @Test fun notReadyExtensionDoesNotFireEvenIfLoaded() {
        svc.create(id, "a", info("""{"delayInMinutes":1}"""))
        clock += 5 * min
        val svc2 = newService()
        assertNotNull(svc2.get(id, "a")) // 读 API 会加载，但不触发
        svc2.onWake()
        assertTrue(sink.events.isEmpty())
    }

    @Test fun corruptFileIsTreatedAsEmpty() {
        File(dir, "alarms.json").writeText("{not json")
        svc.markReady(id)
        assertTrue(svc.getAll(id).isEmpty())
        svc.create(id, "ok", info("""{"delayInMinutes":1}"""))
        assertEquals(1, stored().length())
    }

    @Test fun writeFailureDoesNotBreakInProcessAlarms() {
        // data 目录位置被一个普通文件占住：mkdirs / 写入都会失败
        val blocked = File(tmp.root, "blocked").apply { writeText("x") }
        val logs = ArrayList<String>()
        val s = AlarmService({ File(blocked, "sub") }, sink, timer, { clock }, { logs.add(it) })
        s.markReady(id)
        s.create(id, "a", info("""{"delayInMinutes":1}"""))
        assertNotNull(s.get(id, "a"))
        clock += min
        s.onWake()
        assertEquals(listOf("a"), sink.names())
        assertTrue(logs.isNotEmpty())
    }

    @Test fun resetClearsMemoryAndFile() {
        svc.markReady(id)
        svc.create(id, "a", info("""{"delayInMinutes":1}"""))
        svc.reset(id)
        assertTrue(svc.getAll(id).isEmpty())
        assertFalse(File(dir, "alarms.json").exists())
        assertNull(timer.delay)
    }

    @Test fun forgetKeepsTheFileSoReEnableRestoresAlarms() {
        svc.markReady(id)
        svc.create(id, "a", info("""{"delayInMinutes":1}"""))
        svc.forget(id)
        assertNull(timer.delay)
        val svc2 = newService()
        svc2.markReady(id)
        assertNotNull(svc2.get(id, "a"))
    }

    // ───── 经 ApiRouter ─────

    @Test fun routerHandlersReturnChromeShapedResults() = runBlocking {
        val ext = TestExt.make(tmp.newFolder("ext"), granted = setOf("alarms"))
        val router = ApiRouter()
        svc.register(router)
        fun ctx(api: String) = CallContext(ext, Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(ext.spec.id), true), api)
        suspend fun call(api: String, args: String) = router.find(api)!!.handle(ctx(api), JSONArray(args))

        assertNull(call("alarms.create", """["a",{"delayInMinutes":1}]"""))
        assertNull("默认名是空串，不是 a", call("alarms.get", "[null]"))
        assertEquals("a", (call("alarms.get", """["a"]""") as JSONObject).getString("name"))
        assertNull(call("alarms.get", """["missing"]"""))
        assertEquals(false, call("alarms.clear", """["missing"]"""))
        val all = call("alarms.getAll", "[]") as JSONArray
        assertEquals(1, all.length())
        assertEquals("a", all.getJSONObject(0).getString("name"))
        assertEquals(true, call("alarms.clear", """["a"]"""))
        assertEquals(false, call("alarms.clearAll", "[]"))
    }

    @Test fun permissionTableRequiresAlarmsAndKeepsContentScriptsOut() {
        val ext = TestExt.make(tmp.newFolder("ext2"), manifest = """{"manifest_version":3,"name":"D","version":"1","permissions":["alarms"]}""",
            granted = setOf("alarms"))
        val page = Sender(SenderKind.EXTENSION_PAGE, ExtensionOrigins.originFor(ext.spec.id), true)
        PermissionChecker.require(ext, "alarms.create", page)
        val cs = Sender(SenderKind.CONTENT_SCRIPT, "https://a.com", true, 1)
        assertEquals(ErrorCodes.DENIED_PERMISSION, code { PermissionChecker.require(ext, "alarms.create", cs) })
        val noPerm = TestExt.make(tmp.newFolder("ext3"), granted = setOf("storage"))
        assertEquals(ErrorCodes.DENIED_PERMISSION, code { PermissionChecker.require(noPerm, "alarms.create", page) })
    }
}
