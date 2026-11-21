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
import com.androx.app.model.WorkflowGraph

/**
 * Bridge between the v0.0.1 screens (which edit an [Automation]) and the v2 [Flow] the app now stores.
 *
 * It exists so the app can switch to Flows without rewriting every screen at once: the screens keep their model, and
 * everything underneath (storage, engine, alarms, Trash, stats) is Flow-based. It goes away as the new screens land.
 *
 * Saving an edit never loses what the old screens can't show: any part the user didn't change (extra triggers,
 * negated rules, run options, folders, steps of types added later) is carried over from the stored Flow untouched.
 */
object FlowAdapter {

    fun activeAutomations(file: FlowsFile): List<Automation> = Trash.active(file.flows).map(::toAutomation)

    // ── Flow → Automation ───────────────────────────────────────────────────────────────────

    fun toAutomation(flow: Flow): Automation {
        val editor = when (flow.type) {
            FlowType.CODE -> EditorKind.SCRIPT
            FlowType.CANVAS -> EditorKind.WORKFLOW
            FlowType.STEPS -> EditorKind.BUILDER
        }
        val actions = if (flow.type == FlowType.CODE) {
            listOf(Action.RunScript(flow.script.orEmpty()))
        } else {
            flow.steps.map(::toAction)
        }
        return Automation(
            id = flow.id,
            name = flow.name,
            enabled = flow.enabled,
            trigger = flow.triggers.firstOrNull { it.enabled }?.let(::toTrigger) ?: Trigger.Manual,
            condition = toCondition(flow.constraints),
            actions = actions,
            editor = editor,
            graph = flow.legacyGraph?.let { runCatching { JsonFile.json.decodeFromJsonElement(WorkflowGraph.serializer(), it) }.getOrNull() },
            icon = flow.icon,
            createdAt = flow.createdAt,
            updatedAt = flow.updatedAt,
        )
    }

    fun toTrigger(t: TriggerEntry): Trigger = when (t.typeId) {
        TriggerIds.SCHEDULE -> Trigger.Schedule(t.params.int("hour", 8), t.params.int("minute", 0), t.params.intList("days"))
        TriggerIds.INTERVAL -> Trigger.Interval(t.params.int("minutes", 60), t.params.long("startAt", 0L))
        TriggerIds.ONCE -> Trigger.Once(t.params.long("atMillis", 0L))
        TriggerIds.BOOT -> Trigger.DeviceBoot
        else -> Trigger.Manual
    }

    fun toCondition(g: ConstraintGroup?): Condition? {
        if (g == null || g.isEmpty) return null
        val items = g.rules.map(::toLeaf) + g.groups.mapNotNull { toCondition(it) }
        return when (g.op) {
            LogicOp.ALL -> if (items.size == 1 && g.groups.isEmpty()) items[0] else Condition.MatchAll(items)
            LogicOp.ANY -> Condition.MatchAny(items)
            // "None of" has no v0.0.1 form; "all of the opposite" is the same thing for simple rules.
            LogicOp.NONE -> Condition.MatchAll(g.rules.map { toLeaf(it.copy(negate = !it.negate)) })
        }
    }

    private fun flip(op: Comparison): Comparison = when (op) {
        Comparison.LT -> Comparison.GTE
        Comparison.LTE -> Comparison.GT
        Comparison.EQ -> Comparison.NEQ
        Comparison.NEQ -> Comparison.EQ
        Comparison.GTE -> Comparison.LT
        Comparison.GT -> Comparison.LTE
    }

    /** "Is not" is applied where the old model can express it (switches and comparisons); otherwise it is ignored here. */
    private fun toLeaf(c: Check): Condition {
        val p = c.params
        val not = c.negate
        return when (c.typeId) {
            CheckIds.BATTERY_LEVEL -> {
                val op = p.enum("op", Comparison.LT)
                Condition.BatteryLevel(if (not) flip(op) else op, p.int("percent", 20))
            }
            CheckIds.CHARGING -> Condition.Charging(p.bool("charging", true) != not)
            CheckIds.VARIABLE -> {
                val op = p.enum("op", Comparison.EQ)
                Condition.Variable(p.str("name"), if (not) flip(op) else op, p.str("value"))
            }
            CheckIds.TIME_WINDOW -> Condition.TimeWindow(
                p.int("fromHour", 22), p.int("fromMinute", 0), p.int("toHour", 7), p.int("toMinute", 0),
            )
            CheckIds.DAYS_OF_WEEK -> Condition.DaysOfWeek(p.intList("days"))
            CheckIds.WIFI_CONNECTED -> Condition.WifiConnected(p.bool("connected", true) != not)
            CheckIds.SCREEN_ON -> Condition.ScreenOn(p.bool("on", true) != not)
            CheckIds.RINGER_MODE -> Condition.Ringer(p.enum("mode", RingerMode.SILENT))
            CheckIds.BATTERY_SAVER -> Condition.PowerSaver(p.bool("on", true) != not)
            CheckIds.AIRPLANE_MODE -> Condition.AirplaneMode(p.bool("on", true) != not)
            // A check from a newer version: shown as a visible placeholder, and never written back (see [merge]).
            else -> Condition.Variable("unavailable:${c.typeId}", Comparison.EQ, "")
        }
    }

    fun toAction(step: Step): Action {
        val p = step.op.params
        return when (step.op.typeId) {
            OpIds.NOTIFY -> Action.ShowNotification(p.str("title", "Androx"), p.str("message"))
            OpIds.SET_VARIABLE -> Action.SetVariable(p.str("name"), p.str("value"), p.enum("mode", VariableMode.SET))
            OpIds.RUN_FLOW -> Action.RunAutomation(p.str("flowId"), p.str("flowName"))
            OpIds.WRITE_LOG -> Action.WriteLog(p.str("message"))
            OpIds.RUN_SCRIPT -> Action.RunScript(p.str("source"))
            OpIds.VIBRATE -> Action.Vibrate(p.int("durationMs", 400))
            OpIds.TOAST -> Action.ShowToast(p.str("message"))
            OpIds.CLIPBOARD -> Action.CopyToClipboard(p.str("text"))
            OpIds.SPEAK -> Action.Speak(p.str("text"))
            OpIds.WAIT -> Action.Wait(p.int("seconds", 2))
            OpIds.FLASHLIGHT -> Action.Flashlight(p.enum("mode", FlashlightMode.TOGGLE))
            OpIds.SET_VOLUME -> Action.SetVolume(p.enum("stream", VolumeStream.MEDIA), p.int("percent", 50))
            OpIds.PLAY_SOUND -> Action.PlaySound
            OpIds.IF -> Action.IfThen(
                condition = toCondition(FlowMigration.ifCondition(step)) ?: Condition.MatchAll(emptyList()),
                then = step.blocks[OpIds.BLOCK_THEN].orEmpty().map(::toAction),
                otherwise = step.blocks[OpIds.BLOCK_ELSE].orEmpty().map(::toAction),
            )
            OpIds.REPEAT -> Action.Repeat(p.int("times", 3), step.blocks[OpIds.BLOCK_BODY].orEmpty().map(::toAction))
            else -> Action.WriteLog("Unavailable step: ${step.op.typeId}")
        }
    }

    // ── Automation → Flow ───────────────────────────────────────────────────────────────────

    /** The Flow to store for an edited [auto]. [existing] is the stored Flow with the same id, or null for a new one. */
    fun merge(existing: Flow?, auto: Automation): Flow {
        val fresh = FlowMigration.migrate(auto)
        if (existing == null) return fresh
        val old = toAutomation(existing)

        val sameEditor = auto.editor == old.editor
        val sameBody = sameEditor && auto.actions == old.actions
        val sameTrigger = auto.trigger == old.trigger
        val sameCondition = auto.condition == old.condition
        val sameGraph = auto.graph == old.graph

        val triggers = if (sameTrigger) {
            existing.triggers
        } else {
            val first = fresh.triggers.first().copy(id = existing.triggers.firstOrNull()?.id ?: fresh.triggers.first().id)
            listOf(first) + existing.triggers.drop(1)
        }
        return existing.copy(
            name = auto.name,
            icon = auto.icon,
            enabled = auto.enabled,
            type = if (sameEditor) existing.type else fresh.type,
            wiring = if (sameEditor) existing.wiring else fresh.wiring,
            triggers = triggers,
            constraints = if (sameCondition) existing.constraints else fresh.constraints,
            steps = if (sameBody) existing.steps else fresh.steps,
            script = if (sameBody) existing.script else fresh.script,
            legacyGraph = if (sameGraph) existing.legacyGraph else fresh.legacyGraph,
            createdAt = if (existing.createdAt != 0L) existing.createdAt else auto.createdAt,
            updatedAt = auto.updatedAt,
            deletedAt = null,
        )
    }

    /** Insert or replace by id. A Flow that was in Trash comes back (matches "import replaces"). */
    fun upsert(flows: List<Flow>, auto: Automation): List<Flow> {
        val existing = flows.firstOrNull { it.id == auto.id }
        val merged = merge(existing, auto)
        return if (existing == null) flows + merged else flows.map { if (it.id == auto.id) merged else it }
    }
}
