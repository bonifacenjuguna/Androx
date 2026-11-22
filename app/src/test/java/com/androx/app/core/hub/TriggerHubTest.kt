package com.androx.app.core.hub

import com.androx.app.core.Check
import com.androx.app.core.CheckIds
import com.androx.app.core.ConstraintGroup
import com.androx.app.core.Event
import com.androx.app.core.Flow
import com.androx.app.core.FlowsFile
import com.androx.app.core.Trash
import com.androx.app.core.TriggerEntry
import com.androx.app.core.TriggerIds
import com.androx.app.core.Value
import com.androx.app.core.engine.CheckResult
import com.androx.app.core.engine.MapValueStore
import com.androx.app.core.engine.NotifyingValueStore
import com.androx.app.core.engine.ShadowValueStore
import com.androx.app.core.params
import com.androx.app.core.spec.SpecCatalog
import com.androx.app.core.state.DeviceStateReader
import com.androx.app.core.state.FixedStateReader
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.RunSource
import com.androx.app.model.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardsTest {
    @Test
    fun cooldownBlocksUntilTheTimeIsUp() {
        val g = CooldownGuard()
        assertTrue(g.ready("a", 30, 0L)) // never fired
        g.mark("a", 1_000L)
        assertFalse(g.ready("a", 30, 30_999L))
        assertTrue(g.ready("a", 30, 31_000L))
        assertTrue(g.ready("a", 0, 1_001L)) // no cooldown set
        assertTrue(g.ready("b", 30, 1_001L)) // other triggers unaffected
    }

    @Test
    fun theLoopGuardAllowsATwentyPerMinuteBurstThenRefuses() {
        val g = EventLoopGuard(max = 20, windowMs = 60_000L)
        repeat(20) { assertTrue(g.allow("f", 1_000L + it)) }
        assertFalse(g.allow("f", 5_000L))
        assertTrue(g.allow("other", 5_000L))
        assertTrue(g.allow("f", 61_100L)) // the window has moved on
    }

    @Test
    fun holdFiresOnlyAfterTheTimeAndOnlyIfItStillHolds() = runTest {
        val tracker = HoldTracker(this)
        var fired = 0
        assertTrue(tracker.begin("k", 5, { true }) { fired++ })
        assertTrue(tracker.isHolding("k"))
        assertFalse(tracker.begin("k", 5, { true }) { fired++ }) // already waiting
        advanceTimeBy(4 * 60_000L)
        runCurrent()
        assertEquals(0, fired)
        advanceTimeBy(60_001L)
        runCurrent()
        assertEquals(1, fired)
        assertFalse(tracker.isHolding("k"))

        var stillTrue = true
        tracker.begin("j", 1, { stillTrue }) { fired++ }
        stillTrue = false
        advanceTimeBy(61_000L)
        runCurrent()
        assertEquals(1, fired) // the condition stopped holding, so nothing ran
    }

    @Test
    fun cancellingAllHoldsStopsThemFiring() = runTest {
        val tracker = HoldTracker(this)
        var fired = 0
        tracker.begin("k", 5, { true }) { fired++ }
        tracker.cancelAll()
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(0, fired)
    }
}

class TriggerFirerTest {
    private val catalog = SpecCatalog.default
    private var now = 1_000_000L
    private val runs = mutableListOf<String>()
    private val logs = mutableListOf<TriggerLogEntry>()
    private val record = ExecutionRecord("r", "f", "Flow", 0L, 1L, ExecutionStatus.SUCCESS, RunSource.EVENT)
    private fun flow(enabled: Boolean = true) = Flow(id = "f", name = "Night mode", enabled = enabled)

    private fun firer(
        scope: CoroutineScope,
        constraints: (ConstraintGroup?, Event?, RunSource) -> CheckResult = { _, _, _ -> CheckResult(true, "ok") },
        state: DeviceStateReader = FixedStateReader(emptyMap()),
        byId: (String) -> Flow? = { flow() },
    ) = TriggerFirer(
        catalog, CooldownGuard(), HoldTracker(scope), EventLoopGuard(), constraints, { state }, byId,
        { f, s, e -> runs += "${f.id}:${s.name}:${e?.typeId}"; record }, { logs += it }, { now },
    )

    private val screenEvent = Event(TriggerIds.SCREEN, mapOf("screen.on" to Value.Bool(false)))
    private fun entry(typeId: String = TriggerIds.SCREEN, cooldown: Int = 0, hold: Int = 0, gate: ConstraintGroup? = null) =
        TriggerEntry("t1", typeId, params("state" to "off"), constraints = gate, cooldownSeconds = cooldown, holdMinutes = hold)

    @Test
    fun aPlainTriggerRunsTheFlow() = runTest {
        val r = firer(this).fire(flow(), entry(), screenEvent, RunSource.EVENT)
        assertEquals("ran: success", r.outcome)
        assertEquals(listOf("f:EVENT:screen"), runs)
    }

    @Test
    fun theTriggersOwnConstraintsGateIt() = runTest {
        val gate = ConstraintGroup(rules = listOf(Check(CheckIds.CHARGING)))
        val failing = firer(this, constraints = { _, _, _ -> CheckResult(false, "device is not charging (needs charging)") })
        val r = failing.fire(flow(), entry(gate = gate), screenEvent, RunSource.EVENT)
        assertEquals("skipped: device is not charging (needs charging)", r.outcome)
        assertTrue(runs.isEmpty())
        // An empty group is no gate at all.
        assertEquals("ran: success", failing.fire(flow(), entry(gate = ConstraintGroup()), screenEvent, RunSource.EVENT).outcome)
    }

    @Test
    fun cooldownStartsWhenItRunsNotWhenItIsSkipped() = runTest {
        val f = firer(this)
        val e = entry(cooldown = 30)
        assertEquals("ran: success", f.fire(flow(), e, screenEvent, RunSource.SCHEDULE).outcome)
        assertTrue(f.fire(flow(), e, screenEvent, RunSource.SCHEDULE).outcome.startsWith("skipped: cooldown"))
        now += 31_000L
        assertEquals("ran: success", f.fire(flow(), e, screenEvent, RunSource.SCHEDULE).outcome)
        assertEquals(2, runs.size)

        // A run skipped by constraints doesn't start the cooldown.
        val g = firer(this, constraints = { _, _, _ -> CheckResult(false, "no") })
        g.fire(flow(), entry(cooldown = 30, gate = ConstraintGroup(rules = listOf(Check(CheckIds.CHARGING)))), screenEvent, RunSource.EVENT)
        assertEquals(2, runs.size)
    }

    private val lowBattery = Event(TriggerIds.BATTERY_LEVEL, mapOf("battery.level" to Value.Num(15.0), "battery.previous" to Value.Num(21.0)))
    private fun lowEntry(hold: Int) = TriggerEntry("t1", TriggerIds.BATTERY_LEVEL, params("direction" to "below", "percent" to 20), holdMinutes = hold)

    @Test
    fun holdWaitsThenRunsWhenTheConditionStillHolds() = runTest {
        val f = firer(this, state = FixedStateReader(mapOf("battery.level" to Value.Num(12.0))))
        val r = f.fire(flow(), lowEntry(5), lowBattery, RunSource.EVENT)
        assertEquals("waiting 5 min to see if it still holds", r.outcome)
        assertEquals("already waiting to see if it still holds", f.fire(flow(), lowEntry(5), lowBattery, RunSource.EVENT).outcome)
        assertTrue(runs.isEmpty())
        advanceTimeBy(5 * 60_000L + 1)
        runCurrent()
        assertEquals(listOf("f:EVENT:battery_level"), runs)
        assertEquals("Held for 5 min", logs.single().title)
        assertEquals(listOf("Night mode: ran: success"), logs.single().results)
    }

    @Test
    fun holdDoesNothingWhenTheBatteryRecovered() = runTest {
        val f = firer(this, state = FixedStateReader(mapOf("battery.level" to Value.Num(60.0))))
        f.fire(flow(), lowEntry(5), lowBattery, RunSource.EVENT)
        advanceTimeBy(6 * 60_000L)
        runCurrent()
        assertTrue(runs.isEmpty())
        assertTrue(logs.isEmpty())
    }

    @Test
    fun holdDropsTheRunIfTheFlowWasTurnedOffMeanwhile() = runTest {
        var enabled = true
        val f = firer(this, state = FixedStateReader(mapOf("battery.level" to Value.Num(12.0))), byId = { flow(enabled) })
        f.fire(flow(), lowEntry(2), lowBattery, RunSource.EVENT)
        enabled = false
        advanceTimeBy(3 * 60_000L)
        runCurrent()
        assertTrue(runs.isEmpty())
        assertEquals("Night mode: dropped: the Flow was turned off while waiting", logs.single().results.single())
    }

    @Test
    fun holdIsIgnoredForTimersAndForTriggersThatCantHold() = runTest {
        val f = firer(this)
        assertEquals("ran: success", f.fire(flow(), lowEntry(5), lowBattery, RunSource.SCHEDULE).outcome)
        assertEquals("ran: success", firer(this).fire(flow(), TriggerEntry("t", TriggerIds.UNLOCKED, holdMinutes = 5), Event(TriggerIds.UNLOCKED), RunSource.EVENT).outcome)
    }

    @Test
    fun aFlowThatKeepsStartingItselfIsPaused() = runTest {
        val f = firer(this)
        repeat(20) { assertEquals("ran: success", f.fire(flow(), entry(), screenEvent, RunSource.EVENT).outcome) }
        assertTrue(f.fire(flow(), entry(), screenEvent, RunSource.EVENT).outcome.startsWith("stopped: this Flow keeps starting itself"))
        assertEquals(20, runs.size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TriggerHubTest {
    private val catalog = SpecCatalog.default
    private val logs = mutableListOf<TriggerLogEntry>()
    private val fired = mutableListOf<String>()

    private fun flow(id: String, vararg triggers: TriggerEntry, enabled: Boolean = true) = Flow(id = id, name = "Flow $id", enabled = enabled, triggers = triggers.toList())
    private fun screen(id: String, state: String, enabled: Boolean = true) = TriggerEntry(id, TriggerIds.SCREEN, params("state" to state), enabled = enabled)
    private val off = Event(TriggerIds.SCREEN, mapOf("screen.on" to Value.Bool(false)))

    private fun hub(scope: CoroutineScope, flows: List<Flow>, bus: EventBus = EventBus(), outcome: String = "ran: success", boom: Boolean = false) = TriggerHub(
        scope, bus, { flows },
        { f, e, _ -> if (boom) error("kaput"); fired += "${f.id}/${e.id}"; outcome },
        { logs += it }, catalog, { 777L },
    )

    @Test
    fun everyEnabledMatchingTriggerFiresOnce() = runTest {
        val flows = listOf(
            flow("a", screen("a1", "off")),
            flow("b", screen("b1", "on")),
            flow("c", screen("c1", "any"), screen("c2", "off")),
            flow("d", screen("d1", "off"), enabled = false),
            flow("e", screen("e1", "off", enabled = false)),
            Flow(id = "t", name = "Trashed", triggers = listOf(screen("t1", "off")), deletedAt = 5L),
        )
        hub(this, flows).handle(off)
        assertEquals(listOf("a/a1", "c/c1", "c/c2"), fired.sorted())
        val line = logs.single()
        assertEquals("Screen (screen.on=false)", line.title)
        assertEquals(3, line.results.size)
        assertEquals(TriggerIds.SCREEN, line.typeId)
        assertEquals(777L, line.at)
    }

    @Test
    fun deviceEventsAreLoggedEvenWhenNoFlowListens() = runTest {
        hub(this, emptyList()).handle(off)
        assertTrue(logs.single().results.isEmpty())
        assertTrue(fired.isEmpty())
    }

    @Test
    fun quietInternalEventsAreLoggedOnlyWhenAFlowWantedThem() = runTest {
        val hub = hub(this, listOf(flow("a", TriggerEntry("a1", TriggerIds.VARIABLE_CHANGED, params("name" to "x")))))
        hub.handle(FlowEvents.variableChanged("other", null, Value.Text("1")))
        assertTrue(logs.isEmpty())
        hub.handle(FlowEvents.variableChanged("x", null, Value.Text("1")))
        assertEquals(listOf("a/a1"), fired)
        assertEquals(1, logs.size)
    }

    @Test
    fun unknownEventTypesAreIgnored() = runTest {
        hub(this, listOf(flow("a", screen("a1", "off")))).handle(Event("from_the_future"))
        assertTrue(fired.isEmpty())
        assertTrue(logs.isEmpty())
    }

    @Test
    fun aFlowThatFailsToStartIsReportedNotThrown() = runTest {
        hub(this, listOf(flow("a", screen("a1", "off"))), boom = true).handle(off)
        assertEquals("Flow a: failed to start: kaput", logs.single().results.single())
    }

    @Test
    fun theOutcomeOfEachFlowIsWrittenToTheLog() = runTest {
        hub(this, listOf(flow("a", screen("a1", "off"))), outcome = "skipped: cooldown").handle(off)
        assertEquals(listOf("Flow a: skipped: cooldown"), logs.single().results)
    }

    @Test
    fun eventsEmittedOnTheBusReachTheHub() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus { 123L }
        val job = hub(this, listOf(flow("a", screen("a1", "off"))), bus).start()
        yield()
        bus.emit(off)
        assertEquals(listOf("a/a1"), fired)
        assertEquals(123L, logs.single().at) // stamped by the bus
        job.cancel()
    }

    @Test
    fun theBusStampsTimeAndNeverBlocks() = runTest(UnconfinedTestDispatcher()) {
        val bus = EventBus { 42L }
        var got: Event? = null
        val job = launch { got = bus.events.first() }
        yield()
        assertTrue(bus.emit(Event("x")))
        assertEquals(42L, got!!.at)
        job.cancel()
        repeat(500) { assertTrue(bus.emit(Event("y"))) } // nobody listening: dropped, no suspension
    }
}

class TriggerSupportTest {
    private fun file(vararg flows: Flow) = FlowsFile(flows = flows.toList())
    private fun entry(id: String, type: String, enabled: Boolean = true, p: kotlinx.serialization.json.JsonObject = params()) = TriggerEntry(id, type, p, enabled = enabled)

    @Test
    fun timedEntriesListEveryTimerOfEveryEnabledFlow() {
        val a = Flow(
            id = "a", name = "A",
            triggers = listOf(
                entry("a-t0", TriggerIds.SCHEDULE, p = params("hour" to 7, "minute" to 30, "days" to listOf(1, 2))),
                entry("a-t1", TriggerIds.INTERVAL, p = params("minutes" to 90, "startAt" to 5L)),
                entry("a-t2", TriggerIds.SCREEN),
                entry("a-t3", TriggerIds.ONCE, enabled = false, p = params("atMillis" to 9L)),
            ),
        )
        val off = Flow(id = "b", name = "B", enabled = false, triggers = listOf(entry("b-t0", TriggerIds.SCHEDULE)))
        val trashed = Trash.moveToTrash(Flow(id = "c", name = "C", triggers = listOf(entry("c-t0", TriggerIds.SCHEDULE))), 1L)
        val timed = TimedEntries.from(file(a, off, trashed))
        assertEquals(listOf("a-t0", "a-t1"), timed.map { it.entryId })
        assertEquals(Trigger.Schedule(7, 30, listOf(1, 2)), timed[0].trigger)
        assertEquals(Trigger.Interval(90, 5L), timed[1].trigger)
        assertTrue(timed.all { it.flowId == "a" })
    }

    @Test
    fun theListenerNeedsOnlyWhatEnabledFlowsUse() {
        val a = Flow(id = "a", name = "A", triggers = listOf(entry("a1", TriggerIds.SCREEN), entry("a2", TriggerIds.BATTERY_LEVEL), entry("a3", TriggerIds.SCHEDULE)))
        val b = Flow(id = "b", name = "B", triggers = listOf(entry("b1", TriggerIds.NETWORK), entry("b2", TriggerIds.POWER, enabled = false)))
        val off = Flow(id = "c", name = "C", enabled = false, triggers = listOf(entry("c1", TriggerIds.HEADPHONES)))
        val f = file(a, b, off)
        assertEquals(setOf(TriggerIds.SCREEN, TriggerIds.BATTERY_LEVEL, TriggerIds.NETWORK), ListenerNeeds.eventTypes(f))
        val needs = ListenerNeeds.needed(f)
        assertEquals(listOf("a1", "a2", "b1"), needs.map { it.entryId })
        assertEquals("Screen", needs.first().triggerName)
        assertTrue(ListenerNeeds.eventTypes(file()).isEmpty())
    }

    @Test
    fun theTriggerLogKeepsTheNewest200() {
        var f = TriggerLogFile()
        for (i in 1..205) f = TriggerLogs.add(f, TriggerLogEntry(i.toLong(), "screen", "e$i"))
        assertEquals(200, f.entries.size)
        assertEquals("e205", f.entries.first().title)
        assertEquals("e6", f.entries.last().title)
    }

    @Test
    fun logTitlesShowTheFirstValues() {
        assertEquals("Phone unlocked", TriggerLogs.describe(Event("unlocked")))
        assertEquals(
            "Network (network.change=lost, network.type=wifi)",
            TriggerLogs.describe(Event(TriggerIds.NETWORK, mapOf("network.change" to Value.Text("lost"), "network.type" to Value.Text("wifi")))),
        )
        assertEquals("mystery", TriggerLogs.describe(Event("mystery")))
    }

    @Test
    fun finishedFlowEventsCarryTheResult() {
        val r = ExecutionRecord("r", "flow-1", "Backup", 1_000L, 250L, ExecutionStatus.FAILED, RunSource.MANUAL, "no network")
        val e = FlowEvents.finished(r)
        assertEquals(TriggerIds.FLOW_FINISHED, e.typeId)
        assertEquals("flow-1", e.values["flow.id"]!!.render())
        assertEquals("Backup", e.values["flow.name"]!!.render())
        assertEquals("failed", e.values["flow.status"]!!.render())
        assertEquals("no network", e.values["flow.message"]!!.render())
        assertEquals(1_250L, e.at)
    }

    @Test
    fun testFireSamplesEveryValueTheTriggerExposes() {
        val e = FlowEvents.sample(TriggerIds.BATTERY_LEVEL)
        assertEquals(setOf("battery.level", "battery.previous"), e.values.keys)
        assertEquals(Value.Num(42.0), e.values["battery.level"])
        val net = FlowEvents.sample(TriggerIds.NETWORK)
        assertEquals(Value.Bool(true), net.values["network.connected"])
        assertTrue(FlowEvents.sample("nope").values.isEmpty())
    }

    @Test
    fun variablesReportOnlyRealChanges() {
        val seen = mutableListOf<String>()
        val base = MapValueStore()
        val store = NotifyingValueStore(base) { name, old, new -> seen += "$name:${old?.render()}->${new?.render()}" }
        store.set("a", Value.Text("1"))
        store.set("a", Value.Text("1")) // same value: no event
        store.set("a", Value.Num(2.0))
        store.remove("a")
        store.remove("a") // already gone
        assertEquals(listOf("a:null->1", "a:1->2", "a:2->null"), seen)
    }

    @Test
    fun aSimulationNeverReportsVariableChanges() {
        val seen = mutableListOf<String>()
        val base = NotifyingValueStore(MapValueStore()) { name, _, _ -> seen += name }
        val shadow = ShadowValueStore(base)
        shadow.set("x", Value.Text("1"))
        shadow.remove("x")
        assertTrue(seen.isEmpty())
        assertNull(base.get("x"))
    }
}
