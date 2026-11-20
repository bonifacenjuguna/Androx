package com.androx.app

import com.androx.app.data.Backup
import com.androx.app.engine.DeviceBridge
import com.androx.app.engine.ExecutionEngine
import com.androx.app.engine.RhinoScriptRunner
import com.androx.app.engine.VariableStore
import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.Comparison
import com.androx.app.model.Condition
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.FlashlightMode
import com.androx.app.model.RunSource
import com.androx.app.model.Trigger
import com.androx.app.model.VariableMode
import com.androx.app.model.buildCondition
import com.androx.app.model.describeFlow
import com.androx.app.model.isTimed
import com.androx.app.model.nextFire
import com.androx.app.model.rules
import com.androx.app.data.JsonFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

private class Device2(var battery: Int = 80, var charging: Boolean = false, var wifi: Boolean = false) : DeviceBridge {
    val toasts = mutableListOf<String>()
    val torch = mutableListOf<FlashlightMode>()
    override fun batteryLevel() = battery
    override fun isCharging() = charging
    override fun showNotification(title: String, message: String) {}
    override fun isWifiConnected() = wifi
    override fun showToast(message: String) { toasts += message }
    override fun setFlashlight(mode: FlashlightMode): Boolean { torch += mode; return mode == FlashlightMode.ON }
}

private class Vars2 : VariableStore {
    val map = mutableMapOf<String, String>()
    override fun get(name: String) = map[name]
    override fun set(name: String, value: String) { map[name] = value }
    override fun remove(name: String) { map.remove(name) }
}

class FeatureTest {
    private val device = Device2()
    private val vars = Vars2()
    private val engine = ExecutionEngine(device, vars, { null }, RhinoScriptRunner(timeoutMillis = 400))

    private fun run(vararg actions: Action, condition: Condition? = null) = runBlocking {
        engine.execute(Automation(id = "x", name = "X", condition = condition, actions = actions.toList()), RunSource.MANUAL)
    }

    // ---- triggers

    @Test
    fun intervalFiresOnAFixedRhythmFromItsStart() {
        val t = Trigger.Interval(minutes = 30, startAt = 1_000_000L)
        val step = 30 * 60_000L
        assertEquals(1_000_000L + step, t.nextRunMillis(0))
        assertEquals(1_000_000L + step, t.nextRunMillis(1_000_000L + step - 1))
        assertEquals(1_000_000L + 2 * step, t.nextRunMillis(1_000_000L + step))
        assertEquals(1_000_000L + 3 * step, t.nextRunMillis(1_000_000L + 2 * step + 5))
    }

    @Test
    fun intervalNeverFiresMoreOftenThanTheMinimum() {
        val t = Trigger.Interval(minutes = 1, startAt = 0)
        assertEquals(5 * 60_000L, t.nextRunMillis(0))
    }

    @Test
    fun onceFiresOnlyInTheFuture() {
        val t = Trigger.Once(atMillis = 5_000)
        assertEquals(5_000L, t.nextFire(1_000))
        assertNull(t.nextFire(5_000))
        assertNull(t.nextFire(9_000))
    }

    @Test
    fun onlyTimedTriggersGetAlarms() {
        assertNull(Trigger.Manual.nextFire(0))
        assertNull(Trigger.DeviceBoot.nextFire(0))
        assertFalse(Trigger.Manual.isTimed())
        assertFalse(Trigger.DeviceBoot.isTimed())
        assertTrue(Trigger.Interval().isTimed())
        assertTrue(Trigger.Once().isTimed())
        val zone = ZoneId.of("UTC")
        val after = ZonedDateTime.of(2026, 1, 5, 6, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertNotNull(Trigger.Schedule(7, 30).nextFire(after, zone))
    }

    @Test
    fun newTriggersSurviveJson() {
        val a = Automation(id = "1", name = "t", trigger = Trigger.Interval(45, 123L), icon = "bolt")
        val back = JsonFile.json.decodeFromString(Automation.serializer(), JsonFile.json.encodeToString(Automation.serializer(), a))
        assertEquals(a, back)
        val boot = a.copy(trigger = Trigger.DeviceBoot)
        assertEquals(boot, JsonFile.json.decodeFromString(Automation.serializer(), JsonFile.json.encodeToString(Automation.serializer(), boot)))
    }

    // ---- rules

    @Test
    fun combinedRulesMatchAllOrAny() {
        val low = Condition.BatteryLevel(Comparison.LT, 20)
        val wifi = Condition.WifiConnected(true)
        device.battery = 10
        device.wifi = false
        assertEquals(ExecutionStatus.SKIPPED, run(Action.WriteLog("x"), condition = Condition.MatchAll(listOf(low, wifi))).status)
        assertEquals(ExecutionStatus.SUCCESS, run(Action.WriteLog("x"), condition = Condition.MatchAny(listOf(low, wifi))).status)
    }

    @Test
    fun rulesFlattenAndRebuild() {
        val a = Condition.Charging(true)
        val b = Condition.ScreenOn(false)
        assertEquals(listOf(a), a.rules())
        assertEquals(Condition.MatchAll(listOf(a, b)), buildCondition(listOf(a, b), matchAny = false))
        assertEquals(Condition.MatchAny(listOf(a, b)), buildCondition(listOf(a, b), matchAny = true))
        assertEquals(a, buildCondition(listOf(a), matchAny = true))
        assertNull(buildCondition(emptyList(), false))
    }

    @Test
    fun timeWindowCanWrapPastMidnight() {
        // Whatever the clock says, a window covering all day (from == to) always passes.
        assertEquals(ExecutionStatus.SUCCESS, run(Action.WriteLog("x"), condition = Condition.TimeWindow(0, 0, 0, 0)).status)
        assertEquals(ExecutionStatus.SUCCESS, run(Action.WriteLog("x"), condition = Condition.DaysOfWeek(emptyList())).status)
    }

    // ---- actions

    @Test
    fun ifThenRunsTheMatchingBranch() {
        device.charging = true
        val branch = Action.IfThen(
            condition = Condition.Charging(true),
            then = listOf(Action.ShowToast("charging")),
            otherwise = listOf(Action.ShowToast("not charging")),
        )
        assertEquals(ExecutionStatus.SUCCESS, run(branch).status)
        assertEquals(listOf("charging"), device.toasts)
        device.charging = false
        run(branch)
        assertEquals(listOf("charging", "not charging"), device.toasts)
    }

    @Test
    fun repeatRunsEachRoundWithItsIndex() {
        val r = run(Action.Repeat(3, listOf(Action.ShowToast("round {{index}}"))))
        assertEquals(ExecutionStatus.SUCCESS, r.status)
        assertEquals(listOf("round 1", "round 2", "round 3"), device.toasts)
    }

    @Test
    fun repeatIsCapped() {
        run(Action.Repeat(500, listOf(Action.ShowToast("x"))))
        assertEquals(ExecutionEngine.MAX_REPEAT, device.toasts.size)
    }

    @Test
    fun newVariableOperationsWork() {
        run(
            Action.SetVariable("n", "10", VariableMode.SET),
            Action.SetVariable("n", "3", VariableMode.SUBTRACT),
            Action.SetVariable("n", "4", VariableMode.MULTIPLY),
            Action.SetVariable("s", "a", VariableMode.APPEND),
            Action.SetVariable("s", "b", VariableMode.APPEND),
        )
        assertEquals("28", vars.map["n"])
        assertEquals("ab", vars.map["s"])
        run(Action.SetVariable("n", "", VariableMode.DELETE))
        assertNull(vars.map["n"])
    }

    @Test
    fun unsupportedDeviceActionsFailTheRunInsteadOfCrashing() {
        val r = run(Action.Vibrate(200))
        assertEquals(ExecutionStatus.FAILED, r.status)
    }

    @Test
    fun flashlightReportsItsState() {
        run(Action.Flashlight(FlashlightMode.ON))
        assertEquals(listOf(FlashlightMode.ON), device.torch)
    }

    // ---- language

    @Test
    fun theSentenceReadsNaturally() {
        val s = describeFlow(Trigger.Manual, null, listOf(Action.ShowNotification("a", "b"), Action.Vibrate()))
        assertEquals("When you tap Run, show a notification and vibrate.", s)
    }

    // ---- backup

    @Test
    fun backupRoundTrips() {
        val a = Automation(
            id = "1", name = "One", trigger = Trigger.Once(99),
            condition = Condition.MatchAny(listOf(Condition.ScreenOn(true), Condition.Ringer())),
            actions = listOf(Action.Repeat(2, listOf(Action.Wait(1))), Action.IfThen()),
        )
        val text = Backup.encode(listOf(a), mapOf("k" to "v"), now = 5)
        val back = Backup.decode(text)
        assertEquals(listOf(a), back.automations)
        assertEquals(mapOf("k" to "v"), back.variables)
        assertEquals(5L, back.exportedAt)
    }

    @Test(expected = Exception::class)
    fun backupRejectsOtherJson() {
        Backup.decode("""{"app":"something-else"}""")
    }
}
