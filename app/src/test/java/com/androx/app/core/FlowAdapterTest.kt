package com.androx.app.core

import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.Comparison
import com.androx.app.model.Condition
import com.androx.app.model.EditorKind
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.FlashlightMode
import com.androx.app.model.NodeElement
import com.androx.app.model.RingerMode
import com.androx.app.model.RunSource
import com.androx.app.model.Trigger
import com.androx.app.model.VariableMode
import com.androx.app.model.VolumeStream
import com.androx.app.model.WfEdge
import com.androx.app.model.WfNode
import com.androx.app.model.WorkflowGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowAdapterTest {
    private fun auto(
        trigger: Trigger = Trigger.Manual,
        condition: Condition? = null,
        actions: List<Action> = emptyList(),
        editor: EditorKind = EditorKind.BUILDER,
        graph: WorkflowGraph? = null,
    ) = Automation("a1", "Test", true, trigger, condition, actions, editor, graph, "coffee", 10L, 20L)

    private fun roundTrip(a: Automation) = FlowAdapter.toAutomation(FlowMigration.migrate(a))

    @Test
    fun builderAutomationsSurviveTheRoundTripExactly() {
        val a = auto(
            trigger = Trigger.Schedule(7, 30, listOf(1, 2, 3)),
            condition = Condition.MatchAll(listOf(Condition.BatteryLevel(Comparison.GT, 30), Condition.Ringer(RingerMode.VIBRATE))),
            actions = listOf(
                Action.ShowNotification("Hi", "there"), Action.SetVariable("n", "1", VariableMode.ADD), Action.RunAutomation("x", "X"),
                Action.WriteLog("l"), Action.Vibrate(250), Action.ShowToast("t"), Action.CopyToClipboard("c"), Action.Speak("s"),
                Action.Wait(3), Action.Flashlight(FlashlightMode.ON), Action.SetVolume(VolumeStream.ALARM, 40), Action.PlaySound,
                Action.IfThen(
                    Condition.MatchAny(listOf(Condition.Charging(false), Condition.ScreenOn(true))),
                    listOf(Action.Wait(1)),
                    listOf(Action.Repeat(2, listOf(Action.PlaySound))),
                ),
            ),
        )
        assertEquals(a, roundTrip(a))
    }

    @Test
    fun everyTriggerRoundTrips() {
        for (t in listOf(Trigger.Manual, Trigger.Schedule(8, 5, emptyList()), Trigger.Interval(90, 123L), Trigger.Once(999L), Trigger.DeviceBoot)) {
            assertEquals(t, roundTrip(auto(trigger = t)).trigger)
        }
    }

    @Test
    fun everyConditionLeafRoundTrips() {
        val leaves = listOf(
            Condition.BatteryLevel(Comparison.LTE, 15), Condition.Charging(false), Condition.Variable("v", Comparison.NEQ, "3"),
            Condition.TimeWindow(22, 30, 6, 0), Condition.DaysOfWeek(listOf(6, 7)), Condition.WifiConnected(false),
            Condition.ScreenOn(false), Condition.Ringer(RingerMode.NORMAL), Condition.PowerSaver(true), Condition.AirplaneMode(false),
        )
        for (c in leaves) assertEquals(c, roundTrip(auto(condition = c)).condition)
        assertEquals(Condition.MatchAny(leaves.take(2)), roundTrip(auto(condition = Condition.MatchAny(leaves.take(2)))).condition)
    }

    @Test
    fun scriptAndWorkflowEditorsRoundTrip() {
        val script = auto(editor = EditorKind.SCRIPT, actions = listOf(Action.RunScript("androx.log('x')")))
        assertEquals(script, roundTrip(script))
        val graph = WorkflowGraph(
            nodes = listOf(WfNode("n1", 1f, 2f, NodeElement.TriggerNode(Trigger.Manual)), WfNode("n2", 1f, 90f, NodeElement.ActionNode(Action.PlaySound))),
            edges = listOf(WfEdge("n1", "n2")),
        )
        val wf = auto(editor = EditorKind.WORKFLOW, actions = listOf(Action.PlaySound), graph = graph)
        assertEquals(wf, roundTrip(wf))
    }

    // ── merge: edits never lose what the old screens can't show ─────────────────────────────

    private fun richFlow(): Flow {
        val base = FlowMigration.migrate(auto(trigger = Trigger.Schedule(7, 0), actions = listOf(Action.ShowToast("hi"), Action.Wait(2))))
        return base.copy(
            folderId = "folder1",
            pinned = true,
            triggers = base.triggers + TriggerEntry("a1-t1", "boot"),
            constraints = ConstraintGroup(LogicOp.ALL, listOf(Check(CheckIds.CHARGING, params("charging" to true), negate = true))),
            runOptions = RunOptions(timeoutSeconds = 9, runLimitPerHour = 4),
        )
    }

    @Test
    fun editingOnlyTheNameKeepsEverythingElse() {
        val flow = richFlow()
        val edited = FlowAdapter.toAutomation(flow).copy(name = "Renamed", updatedAt = 99L)
        val merged = FlowAdapter.merge(flow, edited)
        assertEquals("Renamed", merged.name)
        assertEquals(99L, merged.updatedAt)
        assertEquals(flow.copy(name = "Renamed", updatedAt = 99L), merged)
        assertEquals(2, merged.triggers.size)
        assertEquals("folder1", merged.folderId)
        assertEquals(9, merged.runOptions.timeoutSeconds)
        assertTrue(merged.constraints!!.rules.single().negate)
    }

    @Test
    fun changingTheTriggerReplacesOnlyTheFirstOne() {
        val flow = richFlow()
        val edited = FlowAdapter.toAutomation(flow).copy(trigger = Trigger.Interval(30, 5L))
        val merged = FlowAdapter.merge(flow, edited)
        assertEquals(listOf("a1-t0", "a1-t1"), merged.triggers.map { it.id })
        assertEquals(TriggerIds.INTERVAL, merged.triggers[0].typeId)
        assertEquals(TriggerIds.BOOT, merged.triggers[1].typeId)
        assertEquals(flow.steps, merged.steps)
        assertEquals(flow.constraints, merged.constraints)
    }

    @Test
    fun changingActionsReplacesTheSteps() {
        val flow = richFlow()
        val edited = FlowAdapter.toAutomation(flow).copy(actions = listOf(Action.PlaySound))
        val merged = FlowAdapter.merge(flow, edited)
        assertEquals(listOf(OpIds.PLAY_SOUND), merged.steps.map { it.op.typeId })
        assertEquals(flow.triggers, merged.triggers)
    }

    @Test
    fun changingTheConditionReplacesTheConstraints() {
        val flow = richFlow()
        val edited = FlowAdapter.toAutomation(flow).copy(condition = Condition.BatteryLevel(Comparison.LT, 10))
        val merged = FlowAdapter.merge(flow, edited)
        assertFalse(merged.constraints!!.rules.single().negate)
        assertEquals(CheckIds.BATTERY_LEVEL, merged.constraints!!.rules.single().typeId)
    }

    @Test
    fun stepsFromANewerVersionAreNotOverwrittenByARename() {
        val flow = FlowMigration.migrate(auto()).copy(steps = listOf(Step("s0", Op("from_the_future", params("x" to 1)))))
        val shown = FlowAdapter.toAutomation(flow)
        assertEquals(Action.WriteLog("Unavailable step: from_the_future"), shown.actions.single())
        val merged = FlowAdapter.merge(flow, shown.copy(name = "Renamed"))
        assertEquals("from_the_future", merged.steps.single().op.typeId)
    }

    @Test
    fun aNewAutomationBecomesANewFlow() {
        val flows = FlowAdapter.upsert(emptyList(), auto(actions = listOf(Action.PlaySound)))
        assertEquals(1, flows.size)
        assertEquals("a1", flows[0].id)
        val again = FlowAdapter.upsert(flows, auto(actions = listOf(Action.Wait(1))))
        assertEquals(1, again.size)
        assertEquals(OpIds.WAIT, again[0].steps.single().op.typeId)
    }

    @Test
    fun savingATrashedFlowBringsItBack() {
        val trashed = Trash.moveToTrash(FlowMigration.migrate(auto()), 5L)
        val back = FlowAdapter.upsert(listOf(trashed), auto())
        assertFalse(back.single().inTrash)
        assertTrue(back.single().enabled)
    }

    @Test
    fun trashedFlowsAreHiddenFromTheScreens() {
        val live = FlowMigration.migrate(auto())
        val gone = Trash.moveToTrash(FlowMigration.migrate(auto().copy(id = "b")), 5L)
        assertEquals(listOf("a1"), FlowAdapter.activeAutomations(FlowsFile(flows = listOf(live, gone))).map { it.id })
    }

    @Test
    fun negatedRulesAreAppliedWhereTheOldModelCanExpressThem() {
        fun flowWith(vararg checks: Check, op: LogicOp = LogicOp.ALL) =
            FlowMigration.migrate(auto()).copy(constraints = ConstraintGroup(op, checks.toList()))
        val notCharging = Check(CheckIds.CHARGING, params("charging" to true), negate = true)
        assertEquals(Condition.Charging(false), FlowAdapter.toAutomation(flowWith(notCharging)).condition)
        val notLow = Check(CheckIds.BATTERY_LEVEL, params("op" to Comparison.LT, "percent" to 20), negate = true)
        assertEquals(Condition.BatteryLevel(Comparison.GTE, 20), FlowAdapter.toAutomation(flowWith(notLow)).condition)
        val none = FlowAdapter.toAutomation(flowWith(Check(CheckIds.WIFI_CONNECTED, params("connected" to true)), op = LogicOp.NONE)).condition
        assertEquals(Condition.MatchAll(listOf(Condition.WifiConnected(false))), none)
    }

    @Test
    fun aFlowWithNoTriggersShowsAsManual() {
        val flow = FlowMigration.migrate(auto()).copy(triggers = emptyList())
        assertEquals(Trigger.Manual, FlowAdapter.toAutomation(flow).trigger)
    }

    // ── run limits ──────────────────────────────────────────────────────────────────────────

    private fun rec(id: String, at: Long, status: ExecutionStatus = ExecutionStatus.SUCCESS) =
        ExecutionRecord("r$at", id, "n", at, 1, status, RunSource.MANUAL)

    @Test
    fun runLimitsCountOnlyRealRunsOfThatFlowInTheLastHour() {
        val hour = 60L * 60 * 1000
        val now = 10 * hour
        val history = listOf(
            rec("a", now - 10), rec("a", now - 20), rec("a", now - 30, ExecutionStatus.SKIPPED),
            rec("a", now - hour - 1), rec("b", now - 5), rec("a", now - 40, ExecutionStatus.FAILED),
        )
        assertEquals(3, RunLimits.runsInLastHour(history, "a", now))
        assertTrue(RunLimits.exceeded(history, "a", 3, now))
        assertFalse(RunLimits.exceeded(history, "a", 4, now))
        assertFalse(RunLimits.exceeded(history, "a", 0, now))
    }
}
