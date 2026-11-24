package com.androx.app.core.hub

import com.androx.app.core.Event
import com.androx.app.core.TriggerIds
import com.androx.app.core.Value
import com.androx.app.core.params
import com.androx.app.core.spec.SpecCatalog
import com.androx.app.core.state.FixedStateReader
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventTriggerSpecsTest {
    private val catalog = SpecCatalog.default
    private fun spec(id: String) = catalog.triggers[id]!!
    private fun event(id: String, vararg values: Pair<String, Value>) = Event(id, values.toMap())
    private fun matches(id: String, p: JsonObject, e: Event) = spec(id).matches(p, e)

    @Test
    fun theEventTriggersAreRegisteredAndComplete() {
        assertEquals(21, catalog.triggers.all().size)
        for (s in catalog.triggers.all()) {
            assertTrue(s.id, s.name.isNotBlank() && s.description.isNotBlank() && s.iconKey.isNotBlank())
            s.summary(s.defaultParams()) // never throws on a fresh entry
            if (s.id != TriggerIds.ONCE && s.id != TriggerIds.POLL_URL) assertTrue("${s.id}: ${s.problems(s.defaultParams())}", s.problems(s.defaultParams()).isEmpty())
        }
    }

    @Test
    fun exactlyTheDeviceEventsNeedTheListener() {
        val need = catalog.triggers.all().filter { it.needsListener }.map { it.id }.toSet()
        assertEquals(
            setOf(
                TriggerIds.SCREEN, TriggerIds.POWER, TriggerIds.BATTERY_SAVER, TriggerIds.AIRPLANE, TriggerIds.HEADPHONES,
                TriggerIds.BATTERY_LEVEL, TriggerIds.NETWORK, TriggerIds.RINGER, TriggerIds.DND, TriggerIds.UNLOCKED,
            ),
            need,
        )
    }

    @Test
    fun onOffTriggersMatchTheirDirection() {
        val off = event(TriggerIds.SCREEN, "screen.on" to Value.Bool(false))
        val on = event(TriggerIds.SCREEN, "screen.on" to Value.Bool(true))
        assertTrue(matches(TriggerIds.SCREEN, params("state" to "off"), off))
        assertFalse(matches(TriggerIds.SCREEN, params("state" to "off"), on))
        assertTrue(matches(TriggerIds.SCREEN, params("state" to "on"), on))
        assertTrue(matches(TriggerIds.SCREEN, params("state" to "any"), off))
        assertTrue(matches(TriggerIds.POWER, params("state" to "on"), event(TriggerIds.POWER, "power.connected" to Value.Bool(true))))
        assertFalse(matches(TriggerIds.HEADPHONES, params("state" to "on"), event(TriggerIds.HEADPHONES, "headphones.connected" to Value.Bool(false))))
        assertFalse(matches(TriggerIds.AIRPLANE, params("state" to "on"), Event(TriggerIds.AIRPLANE))) // no value: no match
    }

    @Test
    fun batteryLevelFiresOnlyWhenItCrossesTheLine() {
        fun e(level: Int, before: Int) = event(TriggerIds.BATTERY_LEVEL, "battery.level" to Value.Num(level.toDouble()), "battery.previous" to Value.Num(before.toDouble()))
        val below = params("direction" to "below", "percent" to 20)
        assertTrue(matches(TriggerIds.BATTERY_LEVEL, below, e(20, 21)))
        assertTrue(matches(TriggerIds.BATTERY_LEVEL, below, e(15, 25)))
        assertFalse(matches(TriggerIds.BATTERY_LEVEL, below, e(19, 20))) // already below
        assertFalse(matches(TriggerIds.BATTERY_LEVEL, below, e(30, 29)))
        val above = params("direction" to "above", "percent" to 80)
        assertTrue(matches(TriggerIds.BATTERY_LEVEL, above, e(80, 79)))
        assertFalse(matches(TriggerIds.BATTERY_LEVEL, above, e(81, 80)))
        assertFalse(matches(TriggerIds.BATTERY_LEVEL, below, Event(TriggerIds.BATTERY_LEVEL)))
    }

    @Test
    fun networkMatchesChangeAndType() {
        fun e(change: String, type: String) = event(TriggerIds.NETWORK, "network.change" to Value.Text(change), "network.type" to Value.Text(type))
        assertTrue(matches(TriggerIds.NETWORK, params("change" to "connected", "type" to "wifi"), e("connected", "wifi")))
        assertFalse(matches(TriggerIds.NETWORK, params("change" to "connected", "type" to "wifi"), e("connected", "mobile")))
        assertFalse(matches(TriggerIds.NETWORK, params("change" to "lost", "type" to "any"), e("connected", "wifi")))
        assertTrue(matches(TriggerIds.NETWORK, params("change" to "any", "type" to "any"), e("lost", "vpn")))
    }

    @Test
    fun ringerDndAndTimeChangeMatchTheirChoice() {
        assertTrue(matches(TriggerIds.RINGER, params("mode" to "silent"), event(TriggerIds.RINGER, "ringer.mode" to Value.Text("silent"))))
        assertFalse(matches(TriggerIds.RINGER, params("mode" to "silent"), event(TriggerIds.RINGER, "ringer.mode" to Value.Text("normal"))))
        assertTrue(matches(TriggerIds.DND, params("filter" to "any"), event(TriggerIds.DND, "dnd.filter" to Value.Text("alarms"))))
        assertTrue(matches(TriggerIds.TIME_CHANGE, params("what" to "timezone"), event(TriggerIds.TIME_CHANGE, "time.change" to Value.Text("timezone"))))
        assertFalse(matches(TriggerIds.TIME_CHANGE, params("what" to "time"), event(TriggerIds.TIME_CHANGE, "time.change" to Value.Text("timezone"))))
    }

    @Test
    fun notificationButtonsMatchByNameAndLabelWhenGiven() {
        val e = FlowEvents.notificationButton("backup", "Pause")
        assertTrue(matches(TriggerIds.NOTIFICATION_BUTTON, params(), e))
        assertTrue(matches(TriggerIds.NOTIFICATION_BUTTON, params("notificationId" to "BACKUP"), e))
        assertTrue(matches(TriggerIds.NOTIFICATION_BUTTON, params("notificationId" to "backup", "button" to "pause"), e))
        assertFalse(matches(TriggerIds.NOTIFICATION_BUTTON, params("button" to "Stop"), e))
        assertFalse(matches(TriggerIds.NOTIFICATION_BUTTON, params("notificationId" to "other"), e))
    }

    @Test
    fun flowFinishedMatchesFlowAndResult() {
        fun e(id: String, status: String) = event(TriggerIds.FLOW_FINISHED, "flow.id" to Value.Text(id), "flow.status" to Value.Text(status))
        assertTrue(matches(TriggerIds.FLOW_FINISHED, params("flowId" to "a", "when" to "failed"), e("a", "failed")))
        assertFalse(matches(TriggerIds.FLOW_FINISHED, params("flowId" to "a", "when" to "failed"), e("a", "success")))
        assertFalse(matches(TriggerIds.FLOW_FINISHED, params("flowId" to "a", "when" to "finished"), e("b", "success")))
        assertTrue(matches(TriggerIds.FLOW_FINISHED, params("flowId" to "", "when" to "success"), e("zzz", "success")))
        assertFalse(matches(TriggerIds.FLOW_FINISHED, params("when" to "finished"), e("a", "skipped"))) // skipped runs aren't "finished"
    }

    @Test
    fun variableChangedMatchesByNameOrAny() {
        val e = FlowEvents.variableChanged("count", Value.Text("1"), Value.Text("2"))
        assertTrue(matches(TriggerIds.VARIABLE_CHANGED, params(), e))
        assertTrue(matches(TriggerIds.VARIABLE_CHANGED, params("name" to "COUNT"), e))
        assertFalse(matches(TriggerIds.VARIABLE_CHANGED, params("name" to "other"), e))
    }

    @Test
    fun calledByAnotherFlowNeverMatchesAnEvent() {
        assertFalse(matches(TriggerIds.CALLED, params(), Event(TriggerIds.CALLED)))
    }

    @Test
    fun holdChecksReadTheLiveState() {
        fun reader(vararg v: Pair<String, Value>) = FixedStateReader(v.toMap())
        val screenOff = spec(TriggerIds.SCREEN).holdCheck!!
        assertTrue(screenOff(params("state" to "off"), reader("device.screen" to Value.Bool(false))))
        assertFalse(screenOff(params("state" to "off"), reader("device.screen" to Value.Bool(true))))
        assertFalse(screenOff(params("state" to "off"), reader())) // can't read it: don't fire
        assertTrue(screenOff(params("state" to "any"), reader()))

        val battery = spec(TriggerIds.BATTERY_LEVEL).holdCheck!!
        assertTrue(battery(params("direction" to "below", "percent" to 20), reader("battery.level" to Value.Num(15.0))))
        assertFalse(battery(params("direction" to "below", "percent" to 20), reader("battery.level" to Value.Num(40.0))))
        assertTrue(battery(params("direction" to "above", "percent" to 80), reader("battery.level" to Value.Num(90.0))))

        val ringer = spec(TriggerIds.RINGER).holdCheck!!
        assertTrue(ringer(params("mode" to "silent"), reader("device.ringer" to Value.Text("silent"))))
        assertFalse(ringer(params("mode" to "silent"), reader("device.ringer" to Value.Text("normal"))))
    }

    @Test
    fun onlyStateLikeTriggersSupportHold() {
        val supported = catalog.triggers.all().filter { it.supportsHold }.map { it.id }.toSet()
        assertEquals(
            setOf(TriggerIds.SCREEN, TriggerIds.POWER, TriggerIds.BATTERY_SAVER, TriggerIds.AIRPLANE, TriggerIds.HEADPHONES, TriggerIds.BATTERY_LEVEL, TriggerIds.RINGER, TriggerIds.DND),
            supported,
        )
    }

    @Test
    fun summariesReadNaturally() {
        assertEquals("Screen turns off", catalog.triggers.summarize(TriggerIds.SCREEN, params("state" to "off")))
        assertEquals("Power connected", catalog.triggers.summarize(TriggerIds.POWER, params("state" to "on")))
        assertEquals("Battery drops below 15%", catalog.triggers.summarize(TriggerIds.BATTERY_LEVEL, params("direction" to "below", "percent" to 15)))
        assertEquals("Ringer becomes silent", catalog.triggers.summarize(TriggerIds.RINGER, params("mode" to "silent")))
        assertEquals("Any notification button", catalog.triggers.summarize(TriggerIds.NOTIFICATION_BUTTON, params()))
        assertEquals("Button: backup · Pause", catalog.triggers.summarize(TriggerIds.NOTIFICATION_BUTTON, params("notificationId" to "backup", "button" to "Pause")))
        assertEquals("Night mode fails", catalog.triggers.summarize(TriggerIds.FLOW_FINISHED, params("flowName" to "Night mode", "flowId" to "x", "when" to "failed")))
        assertEquals("count changes", catalog.triggers.summarize(TriggerIds.VARIABLE_CHANGED, params("name" to "count")))
        assertNotNull(catalog.triggers[TriggerIds.CALLED])
    }
}
