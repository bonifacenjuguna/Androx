package com.androx.app.core

import com.androx.app.core.migrate.FlowMigration
import com.androx.app.core.model.Blocks
import com.androx.app.core.model.CheckIds
import com.androx.app.core.model.ConstraintNode
import com.androx.app.core.model.Flow
import com.androx.app.core.model.FlowEditor
import com.androx.app.core.model.FlowsFile
import com.androx.app.core.model.GroupMode
import com.androx.app.core.model.OpIds
import com.androx.app.core.model.P
import com.androx.app.core.model.TriggerIds
import com.androx.app.core.model.allSteps
import com.androx.app.data.JsonFile
import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.Comparison
import com.androx.app.model.Condition
import com.androx.app.model.EditorKind
import com.androx.app.model.Trigger
import com.androx.app.model.VariableMode
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationTest {
    private fun counter(): () -> String {
        var n = 0
        return { "id${++n}" }
    }

    private val hydration = Automation(
        id = "a1",
        name = "Hydration reminder",
        trigger = Trigger.Interval(minutes = 90, startAt = 1000L),
        condition = Condition.MatchAll(
            listOf(
                Condition.TimeWindow(9, 0, 21, 0),
                Condition.MatchAny(listOf(Condition.Charging(false), Condition.BatteryLevel(Comparison.GT, 30))),
            ),
        ),
        actions = listOf(
            Action.ShowNotification("Drink", "Time for water #{{index}}"),
            Action.SetVariable("count", "1", VariableMode.ADD),
            Action.IfThen(Condition.Variable("count", Comparison.GTE, "8"), then = listOf(Action.Speak("Enough!")), otherwise = listOf(Action.PlaySound)),
            Action.Repeat(2, listOf(Action.Vibrate(200), Action.Wait(1))),
        ),
        icon = "coffee",
        createdAt = 5L,
        updatedAt = 6L,
    )

    @Test
    fun triggerBecomesAListOfOne() {
        val flow = FlowMigration.fromAutomation(hydration, counter())
        assertEquals("a1", flow.id)
        assertEquals(1, flow.triggers.size)
        assertEquals(TriggerIds.INTERVAL, flow.triggers[0].event.typeId)
        assertEquals(90, P(flow.triggers[0].event.params).int("minutes"))
        assertEquals(1000L, P(flow.triggers[0].event.params).long("startAt"))
        assertEquals("coffee", flow.icon)
        assertEquals(FlowEditor.STEPS, flow.editor)
    }

    @Test
    fun conditionGroupsBecomeConstraintGroups() {
        val flow = FlowMigration.fromAutomation(hydration, counter())
        val root = flow.constraints as ConstraintNode.Group
        assertEquals(GroupMode.ALL, root.mode)
        assertEquals(2, root.items.size)
        val inner = root.items[1] as ConstraintNode.Group
        assertEquals(GroupMode.ANY, inner.mode)
        val first = (root.items[0] as ConstraintNode.Rule).check
        assertEquals(CheckIds.TIME_WINDOW, first.typeId)
        assertEquals(21, P(first.params).int("toHour"))
    }

    @Test
    fun actionsBecomeStepsIncludingBlocks() {
        val flow = FlowMigration.fromAutomation(hydration, counter())
        assertEquals(4, flow.steps.size)
        assertEquals(OpIds.NOTIFY, flow.steps[0].op.typeId)
        assertEquals("ADD", P(flow.steps[1].op.params).str("mode"))
        val ifStep = flow.steps[2]
        assertEquals(OpIds.IF, ifStep.op.typeId)
        assertEquals(1, ifStep.blocks[Blocks.THEN]!!.size)
        assertEquals(OpIds.SOUND, ifStep.blocks[Blocks.ELSE]!![0].op.typeId)
        val repeat = flow.steps[3]
        assertEquals(2, P(repeat.op.params).int("times"))
        assertEquals(2, repeat.blocks[Blocks.BODY]!!.size)
        // 4 top-level + 1 then + 1 else + 2 body
        assertEquals(8, flow.allSteps().count())
    }

    @Test
    fun migrationIsRepeatable() {
        val a = FlowMigration.fromAutomation(hydration, counter())
        val b = FlowMigration.fromAutomation(hydration, counter())
        assertEquals(a, b)
    }

    @Test
    fun stepIdsAreUnique() {
        val flow = FlowMigration.fromAutomation(hydration, counter())
        val ids = flow.allSteps().map { it.id }.toList() + flow.triggers.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun scriptEditorBecomesCodeFlow() {
        val a = Automation(
            id = "s1", name = "Script", editor = EditorKind.SCRIPT,
            actions = listOf(Action.RunScript("androx.log('hi')")),
        )
        val flow = FlowMigration.fromAutomation(a, counter())
        assertEquals(FlowEditor.CODE, flow.editor)
        assertEquals("androx.log('hi')", flow.script)
        assertTrue(flow.steps.isEmpty())
    }

    @Test
    fun unconditionalManualFlow() {
        val flow = FlowMigration.fromAutomation(Automation(id = "m", name = "M"), counter())
        assertEquals(TriggerIds.MANUAL, flow.triggers[0].event.typeId)
        assertNull(flow.constraints)
    }

    @Test
    fun legacyFileFromV001LoadsAndMigrates() {
        // A real automations.json written by v0.0.1 (note the legacy "type" discriminators and enum names).
        val text = JsonFile.json.encodeToString(ListSerializer(Automation.serializer()), listOf(hydration))
        val loaded = JsonFile.json.decodeFromString(ListSerializer(Automation.serializer()), text)
        val flows = FlowMigration.fromAutomations(loaded, counter())
        assertEquals(1, flows.size)
        assertEquals("Hydration reminder", flows[0].name)
    }

    @Test
    fun v2FileRoundTrips() {
        val flow = FlowMigration.fromAutomation(hydration, counter())
        val json = JsonFile.json
        val text = json.encodeToString(FlowsFile.serializer(), FlowsFile(flows = listOf(flow)))
        val back = json.decodeFromString(FlowsFile.serializer(), text)
        assertEquals(listOf(flow), back.flows)
    }

    @Test
    fun newerFilesWithUnknownKeysStillLoad() {
        val text = """{"version":3,"flows":[{"id":"x","name":"Future","someNewField":{"a":1},"triggers":[{"id":"t","event":{"typeId":"geofence.enter","params":{"r":100}},"extra":true}]}],"other":1}"""
        val back = JsonFile.json.decodeFromString(FlowsFile.serializer(), text)
        assertEquals("geofence.enter", back.flows[0].triggers[0].event.typeId)
    }
}
