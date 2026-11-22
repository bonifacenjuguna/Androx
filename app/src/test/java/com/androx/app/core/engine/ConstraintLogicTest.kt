package com.androx.app.core.engine

import com.androx.app.core.Check
import com.androx.app.core.CheckIds
import com.androx.app.core.CompareOp
import com.androx.app.core.ConstraintGroup
import com.androx.app.core.Event
import com.androx.app.core.Value
import com.androx.app.core.params
import com.androx.app.core.state.CompositeStateReader
import com.androx.app.core.state.FixedStateReader
import com.androx.app.core.state.LegacyStateReader
import com.androx.app.engine.ActionException
import com.androx.app.engine.ScriptHost
import com.androx.app.engine.ScriptResult
import com.androx.app.engine.ScriptRunner
import com.androx.app.model.RunSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ConstraintLogicTest {
    private val utc = ZoneId.of("UTC")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12) = { ZonedDateTime.of(y, mo, d, h, 0, 0, 0, utc).toInstant().toEpochMilli() }
    private val noScripts = object : ScriptRunner {
        override fun run(source: String, host: ScriptHost): ScriptResult = ScriptResult.Success
    }

    private class Info(
        val running: Set<String> = emptySet(),
        val last: Map<String, Long> = emptyMap(),
        val runs: Map<String, Int> = emptyMap(),
        val enabled: Map<String, Boolean> = emptyMap(),
    ) : FlowInfo {
        override fun isRunning(flowId: String) = flowId in running
        override fun lastRunAt(flowId: String) = last[flowId]
        override fun runsSince(flowId: String, sinceMillis: Long) = runs[flowId] ?: 0
        override fun isEnabled(flowId: String) = enabled[flowId]
    }

    private fun engine(
        state: Map<String, Value> = emptyMap(),
        clock: () -> Long = at(2026, 11, 21),
        info: FlowInfo = NoFlowInfo,
        device: EngineFakeDevice = EngineFakeDevice(),
    ) = FlowEngine(
        device = device, variables = MapValueStore(), findFlow = { null }, scripts = noScripts, clock = clock, zone = { utc },
        state = CompositeStateReader(FixedStateReader(state), LegacyStateReader(device)), flowInfo = info,
    )

    private fun one(typeId: String, p: kotlinx.serialization.json.JsonObject, negate: Boolean = false) =
        ConstraintGroup(rules = listOf(Check(typeId, p, negate)))

    // ── device state ────────────────────────────────────────────────────────────────────────

    @Test
    fun switchesPassOrFailWithAReason() {
        val e = engine(mapOf("device.headphones" to Value.Bool(true)))
        val yes = e.checkConstraints(one("state:device.headphones", params("is" to true)))
        assertTrue(yes.passed)
        assertEquals("headphones: connected (needs connected)", yes.why)
        assertFalse(e.checkConstraints(one("state:device.headphones", params("is" to false))).passed)
    }

    @Test
    fun numbersUseTheirUnitInTheReason() {
        val e = engine(mapOf("battery.temp" to Value.Num(41.0)))
        val r = e.checkConstraints(one("state:battery.temp", params("op" to CompareOp.GT, "value" to "40")))
        assertTrue(r.passed)
        assertEquals("battery temperature is 41 °C (needs > 40 °C)", r.why)
        assertFalse(e.checkConstraints(one("state:battery.temp", params("op" to CompareOp.BETWEEN, "value" to "10", "value2" to "30"))).passed)
    }

    @Test
    fun choicesCompareByValueAndShowTheLabel() {
        val e = engine(mapOf("device.dnd" to Value.Text("priority")))
        val r = e.checkConstraints(one("state:device.dnd", params("op" to CompareOp.EQ, "value" to "priority")))
        assertTrue(r.passed)
        assertTrue(r.why.contains("Priority only"))
        assertFalse(e.checkConstraints(one("state:device.dnd", params("op" to CompareOp.EQ, "value" to "off"))).passed)
    }

    @Test
    fun anUnreadableStateFailsInsteadOfGuessing() {
        val r = engine().checkConstraints(one("state:battery.temp", params("op" to CompareOp.GT, "value" to "1")))
        assertFalse(r.passed)
        assertEquals("battery temperature can't be read right now", r.why)
    }

    @Test
    fun negateAndTheBridgeBackedStatesWork() {
        val e = engine(device = EngineFakeDevice(battery = 15))
        assertTrue(e.checkConstraints(one("state:battery.level", params("op" to CompareOp.LT, "value" to "20"))).passed)
        assertFalse(e.checkConstraints(one("state:battery.level", params("op" to CompareOp.LT, "value" to "20"), negate = true)).passed)
    }

    @Test
    fun anUnknownStateIsRejected() {
        try {
            engine().checkConstraints(one("state:nope", params()))
            fail("expected an error")
        } catch (e: ActionException) {
            assertTrue(e.message!!.contains("isn't available"))
        }
    }

    // ── compare values ──────────────────────────────────────────────────────────────────────

    @Test
    fun compareValuesFillsTemplatesFirst() {
        val e = engine()
        val g = one(CheckIds.COMPARE, params("left" to "{{x}}", "op" to CompareOp.GT, "right" to "3"))
        val r = e.checkConstraints(g, CheckContext(render = { it.replace("{{x}}", "5") }))
        assertTrue(r.passed)
        assertEquals("\"5\" is > 3", r.why)
        assertFalse(e.checkConstraints(g, CheckContext(render = { it.replace("{{x}}", "2") })).passed)
    }

    @Test
    fun compareValuesBetweenAndContains() {
        val e = engine()
        assertTrue(e.checkConstraints(one(CheckIds.COMPARE, params("left" to "7", "op" to CompareOp.BETWEEN, "right" to "5", "right2" to "9"))).passed)
        assertTrue(e.checkConstraints(one(CheckIds.COMPARE, params("left" to "Hello World", "op" to CompareOp.CONTAINS, "right" to "world"))).passed)
    }

    // ── Flow state ──────────────────────────────────────────────────────────────────────────

    @Test
    fun flowStateChecks() {
        val now = at(2026, 11, 21)()
        val info = Info(
            running = setOf("a"),
            last = mapOf("a" to now - 30 * 60_000L, "b" to now - 90 * 60_000L),
            runs = mapOf("a" to 3),
            enabled = mapOf("a" to true, "b" to false),
        )
        val e = engine(info = info)
        fun pass(p: kotlinx.serialization.json.JsonObject) = e.checkConstraints(one(CheckIds.FLOW_STATE, p)).passed
        assertTrue(pass(params("flowId" to "a", "what" to "running")))
        assertFalse(pass(params("flowId" to "b", "what" to "running")))
        assertTrue(pass(params("flowId" to "a", "what" to "enabled")))
        assertFalse(pass(params("flowId" to "b", "what" to "enabled")))
        assertTrue(pass(params("flowId" to "a", "what" to "ran_recently", "minutes" to 60)))
        assertFalse(pass(params("flowId" to "b", "what" to "ran_recently", "minutes" to 60)))
        assertTrue(pass(params("flowId" to "a", "what" to "runs_today", "op" to CompareOp.GTE, "count" to 3)))
        assertFalse(pass(params("flowId" to "a", "what" to "runs_today", "op" to CompareOp.GT, "count" to 3)))
    }

    @Test
    fun flowStateOfAMissingFlowIsAnError() {
        try {
            engine().checkConstraints(one(CheckIds.FLOW_STATE, params("flowId" to "gone", "flowName" to "Old", "what" to "enabled")))
            fail("expected an error")
        } catch (e: ActionException) {
            assertEquals("Flow \"Old\" no longer exists.", e.message)
        }
    }

    // ── started by ──────────────────────────────────────────────────────────────────────────

    @Test
    fun startedByTellsManualTimerBootAndEventsApart() {
        val e = engine()
        fun pass(by: String, source: RunSource?, event: Event? = null, type: String = "") =
            e.checkConstraints(one(CheckIds.TRIGGERED_BY, params("by" to by, "eventType" to type)), CheckContext(source = source, event = event)).passed
        assertTrue(pass("manual", RunSource.MANUAL))
        assertTrue(pass("manual", null))
        assertTrue(pass("timer", RunSource.SCHEDULE))
        assertTrue(pass("boot", RunSource.BOOT))
        assertFalse(pass("manual", RunSource.SCHEDULE))
        assertTrue(pass("event", RunSource.MANUAL, Event("sms")))
        assertTrue(pass("event", RunSource.MANUAL, Event("sms"), type = "SMS"))
        assertFalse(pass("event", RunSource.MANUAL, Event("sms"), type = "wifi"))
    }

    // ── dates ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun dateRangeIncludesBothEndDates() {
        val from = at(2026, 11, 1, 0)()
        val to = at(2026, 11, 30, 0)()
        val g = one(CheckIds.DATE_RANGE, params("fromMillis" to from, "toMillis" to to))
        assertTrue(engine(clock = at(2026, 11, 1, 0)).checkConstraints(g).passed)
        assertTrue(engine(clock = at(2026, 11, 30, 23)).checkConstraints(g).passed)
        assertFalse(engine(clock = at(2026, 12, 1, 1)).checkConstraints(g).passed)
        assertFalse(engine(clock = at(2026, 10, 31, 23)).checkConstraints(g).passed)
        val openEnded = one(CheckIds.DATE_RANGE, params("fromMillis" to from, "toMillis" to 0L))
        assertTrue(engine(clock = at(2030, 1, 1)).checkConstraints(openEnded).passed)
    }

    @Test
    fun dayOfMonthIncludingTheLastDay() {
        val g = one(CheckIds.DAY_OF_MONTH, params("days" to listOf(1, 15), "last" to true))
        assertTrue(engine(clock = at(2026, 11, 15)).checkConstraints(g).passed)
        assertTrue(engine(clock = at(2026, 2, 28)).checkConstraints(g).passed) // last day of a short month
        assertFalse(engine(clock = at(2026, 2, 27)).checkConstraints(g).passed)
        assertFalse(engine(clock = at(2026, 11, 29)).checkConstraints(g).passed)
        val noLast = one(CheckIds.DAY_OF_MONTH, params("days" to listOf(31), "last" to false))
        assertFalse(engine(clock = at(2026, 2, 28)).checkConstraints(noLast).passed)
    }

    @Test
    fun monthOfYear() {
        val g = one(CheckIds.MONTH_OF_YEAR, params("months" to listOf(11, 12)))
        assertTrue(engine(clock = at(2026, 11, 21)).checkConstraints(g).passed)
        assertFalse(engine(clock = at(2026, 6, 21)).checkConstraints(g).passed)
    }
}
