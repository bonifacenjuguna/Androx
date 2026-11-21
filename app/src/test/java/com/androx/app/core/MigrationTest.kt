package com.androx.app.core

import com.androx.app.data.JsonFile
import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.Comparison
import com.androx.app.model.Condition
import com.androx.app.model.EditorKind
import com.androx.app.model.FlashlightMode
import com.androx.app.model.RingerMode
import com.androx.app.model.Trigger
import com.androx.app.model.VariableMode
import com.androx.app.model.VolumeStream
import com.androx.app.model.WfEdge
import com.androx.app.model.WfNode
import com.androx.app.model.NodeElement
import com.androx.app.model.WorkflowGraph
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationTest {
    private fun auto(
        trigger: Trigger = Trigger.Manual,
        condition: Condition? = null,
        actions: List<Action> = emptyList(),
        editor: EditorKind = EditorKind.BUILDER,
        graph: WorkflowGraph? = null,
    ) = Automation(
        id = "a1", name = "Test", enabled = true, trigger = trigger, condition = condition, actions = actions,
        editor = editor, graph = graph, icon = "coffee", createdAt = 10L, updatedAt = 20L,
    )

    @Test
    fun metadataIsKeptAndIdIsTheSame() {
        val f = FlowMigration.migrate(auto())
        assertEquals("a1", f.id)
        assertEquals("Test", f.name)
        assertEquals("coffee", f.icon)
        assertEquals(10L, f.createdAt)
        assertEquals(20L, f.updatedAt)
        assertTrue(f.enabled)
        assertNull(f.deletedAt)
        assertEquals(FlowType.STEPS, f.type)
    }

    @Test
    fun everyTriggerBecomesAListOfOne() {
        val cases = listOf(
            Trigger.Manual to TriggerIds.MANUAL,
            Trigger.Schedule(7, 45, listOf(1, 3)) to TriggerIds.SCHEDULE,
            Trigger.Interval(90, 1_000L) to TriggerIds.INTERVAL,
            Trigger.Once(5_000L) to TriggerIds.ONCE,
            Trigger.DeviceBoot to TriggerIds.BOOT,
        )
        for ((trigger, id) in cases) {
            val f = FlowMigration.migrate(auto(trigger = trigger))
            assertEquals(1, f.triggers.size)
            assertEquals(id, f.triggers[0].typeId)
        }
        val schedule = FlowMigration.migrate(auto(trigger = Trigger.Schedule(7, 45, listOf(1, 3)))).triggers[0].params
        assertEquals(7, schedule.int("hour"))
        assertEquals(45, schedule.int("minute"))
        assertEquals(listOf(1, 3), schedule.intList("days"))
        val interval = FlowMigration.migrate(auto(trigger = Trigger.Interval(90, 1_000L))).triggers[0].params
        assertEquals(90, interval.int("minutes"))
        assertEquals(1_000L, interval.long("startAt"))
    }

    @Test
    fun singleConditionBecomesGroupOfOne() {
        val f = FlowMigration.migrate(auto(condition = Condition.BatteryLevel(Comparison.GTE, 80)))
        val g = f.constraints!!
        assertEquals(LogicOp.ALL, g.op)
        assertEquals(1, g.rules.size)
        assertEquals(CheckIds.BATTERY_LEVEL, g.rules[0].typeId)
        assertEquals(Comparison.GTE, g.rules[0].params.enum("op", Comparison.LT))
        assertEquals(80, g.rules[0].params.int("percent"))
        assertEquals(false, g.rules[0].negate)
    }

    @Test
    fun nestedConditionGroupsKeepTheirLogic() {
        val cond = Condition.MatchAny(
            listOf(
                Condition.Charging(true),
                Condition.MatchAll(listOf(Condition.WifiConnected(true), Condition.ScreenOn(false))),
            ),
        )
        val g = FlowMigration.migrate(auto(condition = cond)).constraints!!
        assertEquals(LogicOp.ANY, g.op)
        assertEquals(1, g.rules.size)
        assertEquals(1, g.groups.size)
        assertEquals(LogicOp.ALL, g.groups[0].op)
        assertEquals(2, g.groups[0].rules.size)
        assertEquals(3, g.allRules().size)
    }

    @Test
    fun noConditionStaysNull() {
        assertNull(FlowMigration.migrate(auto()).constraints)
    }

    @Test
    fun everyLeafConditionMapsToARegisteredCheck() {
        val all = listOf(
            Condition.BatteryLevel(), Condition.Charging(), Condition.Variable("x", Comparison.EQ, "1"),
            Condition.TimeWindow(), Condition.DaysOfWeek(), Condition.WifiConnected(), Condition.ScreenOn(),
            Condition.Ringer(RingerMode.VIBRATE), Condition.PowerSaver(), Condition.AirplaneMode(),
        )
        val g = FlowMigration.migrate(auto(condition = Condition.MatchAll(all))).constraints!!
        assertEquals(all.size, g.rules.size)
        assertEquals(all.size, g.rules.map { it.typeId }.toSet().size)
    }

    @Test
    fun actionsBecomeStepsWithStablePathIds() {
        val actions = listOf(
            Action.ShowNotification("Hi", "there"),
            Action.Wait(3),
            Action.Repeat(2, listOf(Action.Vibrate(100), Action.ShowToast("x"))),
        )
        val f = FlowMigration.migrate(auto(actions = actions))
        assertEquals(listOf("s0", "s1", "s2"), f.steps.map { it.id })
        assertEquals(OpIds.NOTIFY, f.steps[0].op.typeId)
        assertEquals("there", f.steps[0].op.params.str("message"))
        val repeat = f.steps[2]
        assertEquals(OpIds.REPEAT, repeat.op.typeId)
        assertEquals(2, repeat.op.params.int("times"))
        assertEquals(listOf("s2.body0", "s2.body1"), repeat.blocks[OpIds.BLOCK_BODY]!!.map { it.id })
        assertEquals(5, f.allSteps().size)
    }

    @Test
    fun ifStepKeepsConditionAndBothBranches() {
        val a = Action.IfThen(
            condition = Condition.MatchAll(listOf(Condition.Charging(true), Condition.BatteryLevel(Comparison.LT, 50))),
            then = listOf(Action.Speak("charging")),
            otherwise = listOf(Action.Speak("not charging"), Action.PlaySound),
        )
        val step = FlowMigration.migrate(auto(actions = listOf(a))).steps.single()
        assertEquals(OpIds.IF, step.op.typeId)
        assertEquals(1, step.blocks[OpIds.BLOCK_THEN]!!.size)
        assertEquals(2, step.blocks[OpIds.BLOCK_ELSE]!!.size)
        val cond = FlowMigration.ifCondition(step)
        assertNotNull(cond)
        assertEquals(2, cond!!.rules.size)
    }

    @Test
    fun everyActionTypeMigrates() {
        val actions = listOf(
            Action.ShowNotification(), Action.SetVariable("n", "1", VariableMode.ADD), Action.RunAutomation("id2", "Other"),
            Action.WriteLog("hi"), Action.RunScript(), Action.Vibrate(), Action.ShowToast("t"), Action.CopyToClipboard("c"),
            Action.Speak("s"), Action.Wait(1), Action.Flashlight(FlashlightMode.ON), Action.SetVolume(VolumeStream.RING, 30),
            Action.PlaySound, Action.IfThen(), Action.Repeat(),
        )
        val f = FlowMigration.migrate(auto(actions = actions))
        assertEquals(actions.size, f.steps.size)
        assertEquals("id2", f.steps[2].op.params.str("flowId"))
        assertEquals("Other", f.steps[2].op.params.str("flowName"))
    }

    @Test
    fun scriptAutomationBecomesCodeFlow() {
        val f = FlowMigration.migrate(auto(editor = EditorKind.SCRIPT, actions = listOf(Action.RunScript("androx.log('x')"))))
        assertEquals(FlowType.CODE, f.type)
        assertEquals("androx.log('x')", f.script)
        assertTrue(f.steps.isEmpty())
    }

    @Test
    fun scriptEditorWithMoreThanOneActionStaysSteps() {
        val f = FlowMigration.migrate(auto(editor = EditorKind.SCRIPT, actions = listOf(Action.RunScript("a"), Action.Wait(1))))
        assertEquals(FlowType.STEPS, f.type)
        assertNull(f.script)
        assertEquals(2, f.steps.size)
    }

    @Test
    fun workflowBecomesCanvasAndKeepsItsLayout() {
        val graph = WorkflowGraph(
            nodes = listOf(WfNode("n1", 10f, 20f, NodeElement.TriggerNode(Trigger.Manual)), WfNode("n2", 10f, 140f, NodeElement.ActionNode(Action.PlaySound))),
            edges = listOf(WfEdge("n1", "n2")),
        )
        val f = FlowMigration.migrate(auto(editor = EditorKind.WORKFLOW, actions = listOf(Action.PlaySound), graph = graph))
        assertEquals(FlowType.CANVAS, f.type)
        assertEquals(WiringMode.STRUCTURED, f.wiring)
        assertNotNull(f.legacyGraph)
        val back = JsonFile.json.decodeFromJsonElement(WorkflowGraph.serializer(), f.legacyGraph!!)
        assertEquals(graph, back)
    }

    @Test
    fun migrationIsIdempotent() {
        val a = auto(
            trigger = Trigger.Schedule(8, 0),
            condition = Condition.MatchAny(listOf(Condition.Charging(), Condition.ScreenOn())),
            actions = listOf(Action.IfThen(then = listOf(Action.Wait(1))), Action.Repeat(2, listOf(Action.PlaySound))),
        )
        assertEquals(FlowMigration.migrate(a), FlowMigration.migrate(a))
    }

    @Test
    fun flowSurvivesAJsonRoundTrip() {
        val f = FlowMigration.migrate(
            auto(
                trigger = Trigger.Interval(30, 5L),
                condition = Condition.MatchAll(listOf(Condition.Charging(), Condition.Ringer(RingerMode.SILENT))),
                actions = listOf(Action.IfThen(then = listOf(Action.Vibrate(200))), Action.SetVariable("a", "b", VariableMode.APPEND)),
            ),
        )
        val text = JsonFile.json.encodeToString(FlowsFile.serializer(), FlowsFile(flows = listOf(f)))
        val back = JsonFile.json.decodeFromString(FlowsFile.serializer(), text)
        assertEquals(listOf(f), back.flows)
        assertEquals(FLOW_SCHEMA_VERSION, back.schemaVersion)
    }

    @Test
    fun unknownFieldsAndTypesFromANewerFileDoNotCrashLoading() {
        val text = """{"schemaVersion":3,"extra":1,"flows":[{"id":"x","name":"N","futureField":true,
            "triggers":[{"id":"x-t0","typeId":"from_the_future","params":{"a":1}}],
            "steps":[{"id":"s0","op":{"typeId":"also_future","params":{}}}]}]}"""
        val file = JsonFile.json.decodeFromString(FlowsFile.serializer(), text)
        assertEquals("from_the_future", file.flows[0].triggers[0].typeId)
        assertEquals("also_future", file.flows[0].steps[0].op.typeId)
    }

    @Test
    fun version1BackupImportsAsFlows() {
        val automations = listOf(auto(trigger = Trigger.Schedule(9, 30), actions = listOf(Action.ShowToast("hello"))))
        val v1 = JsonFile.json.encodeToString(
            com.androx.app.data.BackupFile.serializer(),
            com.androx.app.data.BackupFile(version = 1, exportedAt = 1L, automations = automations, variables = mapOf("count" to "3")),
        )
        val imported = BackupCodec.decode(v1)
        assertEquals(1, imported.sourceVersion)
        assertEquals(1, imported.flows.size)
        assertEquals("a1", imported.flows[0].id)
        assertEquals("3", (imported.variables["count"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun version2BackupRoundTrips() {
        val flow = FlowMigration.migrate(auto(actions = listOf(Action.ShowToast("x"))))
        val folder = Folder("f1", "Morning", "blue", 1, false)
        val text = BackupCodec.encode(listOf(flow), listOf(folder), mapOf("n" to kotlinx.serialization.json.JsonPrimitive(5)), now = 99L)
        val back = BackupCodec.decode(text)
        assertEquals(2, back.sourceVersion)
        assertEquals(listOf(flow), back.flows)
        assertEquals(listOf(folder), back.folders)
        assertEquals(1, back.variables.size)
    }

    @Test
    fun foreignAndBrokenFilesAreRejectedWithAReadableMessage() {
        for (text in listOf("not json", """{"app":"other","version":1}""", "[]")) {
            try {
                BackupCodec.decode(text)
                throw AssertionError("expected rejection for: $text")
            } catch (e: IllegalArgumentException) {
                assertEquals("That file isn't an Androx backup.", e.message)
            }
        }
    }

    @Test
    fun legacyListSerializerStillReadsV1AutomationsFile() {
        val text = JsonFile.json.encodeToString(ListSerializer(Automation.serializer()), listOf(auto()))
        assertEquals(1, JsonFile.json.decodeFromString(ListSerializer(Automation.serializer()), text).size)
    }
}
