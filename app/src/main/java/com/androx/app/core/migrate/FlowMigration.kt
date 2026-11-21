package com.androx.app.core.migrate

import com.androx.app.core.model.Blocks
import com.androx.app.core.model.CheckIds
import com.androx.app.core.model.Check
import com.androx.app.core.model.ConstraintNode
import com.androx.app.core.model.Event
import com.androx.app.core.model.Flow
import com.androx.app.core.model.FlowEditor
import com.androx.app.core.model.GroupMode
import com.androx.app.core.model.Op
import com.androx.app.core.model.OpIds
import com.androx.app.core.model.Step
import com.androx.app.core.model.TriggerEntry
import com.androx.app.core.model.TriggerIds
import com.androx.app.core.model.WiringMode
import com.androx.app.core.model.params
import com.androx.app.data.JsonFile
import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.Condition
import com.androx.app.model.EditorKind
import com.androx.app.model.Trigger
import com.androx.app.model.WorkflowGraph
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Turns v0.0.0 / v0.0.1 automations into v2 Flows. Pure and idempotent: running it twice on the same input
 * gives the same Flow (ids come from [newId], which tests replace with a counter).
 */
object FlowMigration {
    fun fromAutomation(a: Automation, newId: () -> String = { UUID.randomUUID().toString() }): Flow {
        val trigger = TriggerEntry(id = newId(), event = event(a.trigger))
        val constraints = a.condition?.let { constraint(it) }
        val asCode = a.editor == EditorKind.SCRIPT && a.actions.size == 1 && a.actions[0] is Action.RunScript
        val steps = if (asCode) emptyList() else a.actions.map { step(it, newId) }
        val script = if (asCode) (a.actions[0] as Action.RunScript).source else null
        val canvas: JsonObject? = a.graph?.let {
            JsonFile.json.encodeToJsonElement(WorkflowGraph.serializer(), it) as? JsonObject
        }
        return Flow(
            id = a.id,
            name = a.name,
            icon = a.icon,
            enabled = a.enabled,
            editor = when (a.editor) {
                EditorKind.BUILDER -> FlowEditor.STEPS
                EditorKind.SCRIPT -> if (asCode) FlowEditor.CODE else FlowEditor.STEPS
                EditorKind.WORKFLOW -> FlowEditor.CANVAS
            },
            wiring = WiringMode.STRUCTURED,
            triggers = listOf(trigger),
            constraints = constraints,
            steps = steps,
            script = script,
            canvas = canvas,
            createdAt = a.createdAt,
            updatedAt = a.updatedAt,
        )
    }

    fun fromAutomations(list: List<Automation>, newId: () -> String = { UUID.randomUUID().toString() }): List<Flow> =
        list.map { fromAutomation(it, newId) }

    fun event(t: Trigger): Event = when (t) {
        Trigger.Manual -> Event(TriggerIds.MANUAL)
        is Trigger.Schedule -> Event(TriggerIds.SCHEDULE, params("hour" to t.hour, "minute" to t.minute, "days" to t.days))
        is Trigger.Interval -> Event(TriggerIds.INTERVAL, params("minutes" to t.minutes, "startAt" to t.startAt))
        is Trigger.Once -> Event(TriggerIds.ONCE, params("atMillis" to t.atMillis))
        Trigger.DeviceBoot -> Event(TriggerIds.BOOT)
    }

    private fun rule(id: String, p: JsonObject = JsonObject(emptyMap())): ConstraintNode =
        ConstraintNode.Rule(Check(id, p))

    fun constraint(c: Condition): ConstraintNode = when (c) {
        is Condition.BatteryLevel -> rule(CheckIds.BATTERY_LEVEL, params("op" to c.op, "percent" to c.percent))
        is Condition.Charging -> rule(CheckIds.BATTERY_CHARGING, params("charging" to c.charging))
        is Condition.Variable -> rule(CheckIds.VARIABLE_COMPARE, params("name" to c.name, "op" to c.op, "value" to c.value))
        is Condition.TimeWindow -> rule(
            CheckIds.TIME_WINDOW,
            params("fromHour" to c.fromHour, "fromMinute" to c.fromMinute, "toHour" to c.toHour, "toMinute" to c.toMinute),
        )
        is Condition.DaysOfWeek -> rule(CheckIds.WEEKDAY, params("days" to c.days))
        is Condition.WifiConnected -> rule(CheckIds.WIFI, params("connected" to c.connected))
        is Condition.ScreenOn -> rule(CheckIds.SCREEN, params("on" to c.on))
        is Condition.Ringer -> rule(CheckIds.RINGER, params("mode" to c.mode))
        is Condition.PowerSaver -> rule(CheckIds.BATTERY_SAVER, params("on" to c.on))
        is Condition.AirplaneMode -> rule(CheckIds.AIRPLANE, params("on" to c.on))
        is Condition.MatchAll -> ConstraintNode.Group(GroupMode.ALL, c.items.map { constraint(it) })
        is Condition.MatchAny -> ConstraintNode.Group(GroupMode.ANY, c.items.map { constraint(it) })
    }

    fun step(a: Action, newId: () -> String = { UUID.randomUUID().toString() }): Step {
        fun plain(typeId: String, p: JsonObject = JsonObject(emptyMap())) = Step(newId(), Op(typeId, p))
        return when (a) {
            is Action.ShowNotification -> plain(OpIds.NOTIFY, params("title" to a.title, "message" to a.message))
            is Action.SetVariable -> plain(OpIds.VARIABLE_SET, params("name" to a.name, "value" to a.value, "mode" to a.mode))
            is Action.RunAutomation -> plain(OpIds.RUN_FLOW, params("flowId" to a.automationId, "flowName" to a.automationName))
            is Action.WriteLog -> plain(OpIds.LOG, params("message" to a.message))
            is Action.RunScript -> plain(OpIds.SCRIPT, params("source" to a.source))
            is Action.Vibrate -> plain(OpIds.VIBRATE, params("durationMs" to a.durationMs))
            is Action.ShowToast -> plain(OpIds.TOAST, params("message" to a.message))
            is Action.CopyToClipboard -> plain(OpIds.CLIPBOARD, params("text" to a.text))
            is Action.Speak -> plain(OpIds.SPEAK, params("text" to a.text))
            is Action.Wait -> plain(OpIds.WAIT, params("seconds" to a.seconds))
            is Action.Flashlight -> plain(OpIds.FLASHLIGHT, params("mode" to a.mode))
            is Action.SetVolume -> plain(OpIds.VOLUME, params("stream" to a.stream, "percent" to a.percent))
            Action.PlaySound -> plain(OpIds.SOUND)
            is Action.IfThen -> Step(
                id = newId(),
                op = Op(OpIds.IF),
                condition = constraint(a.condition),
                blocks = mapOf(
                    Blocks.THEN to a.then.map { step(it, newId) },
                    Blocks.ELSE to a.otherwise.map { step(it, newId) },
                ),
            )
            is Action.Repeat -> Step(
                id = newId(),
                op = Op(OpIds.REPEAT, params("times" to a.times)),
                blocks = mapOf(Blocks.BODY to a.actions.map { step(it, newId) }),
            )
        }
    }
}
