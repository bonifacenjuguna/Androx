package com.androx.app.core

import com.androx.app.data.BackupFile
import com.androx.app.data.JsonFile
import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.Condition
import com.androx.app.model.EditorKind
import com.androx.app.model.Trigger
import com.androx.app.model.WorkflowGraph
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * v1 → v2 (spec §5.2). Pure functions, deterministic ids, so running them twice gives the same result
 * ("idempotent") and a Flow keeps the id of the automation it came from, which keeps "Run automation" references,
 * history and alarms attached to the right thing.
 */
object FlowMigration {

    fun migrateAll(automations: List<Automation>): List<Flow> = automations.map(::migrate)

    fun migrate(a: Automation): Flow {
        val bodyScript = singleScript(a)
        val type = when {
            a.editor == EditorKind.SCRIPT && bodyScript != null -> FlowType.CODE
            a.editor == EditorKind.WORKFLOW -> FlowType.CANVAS
            else -> FlowType.STEPS
        }
        return Flow(
            id = a.id,
            name = a.name,
            icon = a.icon,
            enabled = a.enabled,
            type = type,
            wiring = WiringMode.STRUCTURED,
            triggers = listOf(triggerEntry(a.id, a.trigger)),
            constraints = a.condition?.let { constraintGroup(it) },
            steps = if (type == FlowType.CODE) emptyList() else steps(a.actions, "s"),
            script = if (type == FlowType.CODE) bodyScript else null,
            legacyGraph = a.graph?.let { legacyGraph(it) },
            createdAt = a.createdAt,
            updatedAt = a.updatedAt,
        )
    }

    /** A Script automation in v1 is exactly one "run script" action. Anything else can't be shown as Code. */
    private fun singleScript(a: Automation): String? =
        (a.actions.singleOrNull() as? Action.RunScript)?.source

    private fun legacyGraph(g: WorkflowGraph): JsonElement =
        JsonFile.json.encodeToJsonElement(WorkflowGraph.serializer(), g)

    // ── triggers ────────────────────────────────────────────────────────────────────────────

    fun triggerEntry(flowId: String, t: Trigger): TriggerEntry {
        val (typeId, p) = when (t) {
            Trigger.Manual -> TriggerIds.MANUAL to EmptyParams
            is Trigger.Schedule -> TriggerIds.SCHEDULE to params("hour" to t.hour, "minute" to t.minute, "days" to t.days)
            is Trigger.Interval -> TriggerIds.INTERVAL to params("minutes" to t.minutes, "startAt" to t.startAt)
            is Trigger.Once -> TriggerIds.ONCE to params("atMillis" to t.atMillis)
            Trigger.DeviceBoot -> TriggerIds.BOOT to EmptyParams
        }
        return TriggerEntry(id = "$flowId-t0", typeId = typeId, params = p)
    }

    // ── constraints ─────────────────────────────────────────────────────────────────────────

    /** A single rule becomes an All-group of one; All / Any groups map to the same logic, nested groups included. */
    fun constraintGroup(c: Condition): ConstraintGroup = when (c) {
        is Condition.MatchAll -> group(LogicOp.ALL, c.items)
        is Condition.MatchAny -> group(LogicOp.ANY, c.items)
        else -> ConstraintGroup(LogicOp.ALL, rules = listOf(check(c)))
    }

    private fun group(op: LogicOp, items: List<Condition>): ConstraintGroup {
        val rules = mutableListOf<Check>()
        val groups = mutableListOf<ConstraintGroup>()
        for (item in items) {
            when (item) {
                is Condition.MatchAll -> groups += group(LogicOp.ALL, item.items)
                is Condition.MatchAny -> groups += group(LogicOp.ANY, item.items)
                else -> rules += check(item)
            }
        }
        return ConstraintGroup(op, rules, groups)
    }

    /** Only called for leaf conditions (never a group). */
    private fun check(c: Condition): Check = when (c) {
        is Condition.BatteryLevel -> Check(CheckIds.BATTERY_LEVEL, params("op" to c.op, "percent" to c.percent))
        is Condition.Charging -> Check(CheckIds.CHARGING, params("charging" to c.charging))
        is Condition.Variable -> Check(CheckIds.VARIABLE, params("name" to c.name, "op" to c.op, "value" to c.value))
        is Condition.TimeWindow -> Check(
            CheckIds.TIME_WINDOW,
            params("fromHour" to c.fromHour, "fromMinute" to c.fromMinute, "toHour" to c.toHour, "toMinute" to c.toMinute),
        )
        is Condition.DaysOfWeek -> Check(CheckIds.DAYS_OF_WEEK, params("days" to c.days))
        is Condition.WifiConnected -> Check(CheckIds.WIFI_CONNECTED, params("connected" to c.connected))
        is Condition.ScreenOn -> Check(CheckIds.SCREEN_ON, params("on" to c.on))
        is Condition.Ringer -> Check(CheckIds.RINGER_MODE, params("mode" to c.mode))
        is Condition.PowerSaver -> Check(CheckIds.BATTERY_SAVER, params("on" to c.on))
        is Condition.AirplaneMode -> Check(CheckIds.AIRPLANE_MODE, params("on" to c.on))
        // Groups are handled by group(); reaching here means a group was passed as a leaf.
        is Condition.MatchAll -> Check(CheckIds.VARIABLE, EmptyParams)
        is Condition.MatchAny -> Check(CheckIds.VARIABLE, EmptyParams)
    }

    // ── steps ───────────────────────────────────────────────────────────────────────────────

    /** [path] makes ids deterministic: "s0", "s1", nested "s1.then0". */
    fun steps(actions: List<Action>, path: String): List<Step> =
        actions.mapIndexed { i, action -> step(action, "$path$i") }

    private fun step(a: Action, id: String): Step {
        fun plain(typeId: String, p: JsonObject = EmptyParams) = Step(id, Op(typeId, p))
        return when (a) {
            is Action.ShowNotification -> plain(OpIds.NOTIFY, params("title" to a.title, "message" to a.message))
            is Action.SetVariable -> plain(OpIds.SET_VARIABLE, params("name" to a.name, "value" to a.value, "mode" to a.mode))
            is Action.RunAutomation -> plain(OpIds.RUN_FLOW, params("flowId" to a.automationId, "flowName" to a.automationName))
            is Action.WriteLog -> plain(OpIds.WRITE_LOG, params("message" to a.message))
            is Action.RunScript -> plain(OpIds.RUN_SCRIPT, params("source" to a.source))
            is Action.Vibrate -> plain(OpIds.VIBRATE, params("durationMs" to a.durationMs))
            is Action.ShowToast -> plain(OpIds.TOAST, params("message" to a.message))
            is Action.CopyToClipboard -> plain(OpIds.CLIPBOARD, params("text" to a.text))
            is Action.Speak -> plain(OpIds.SPEAK, params("text" to a.text))
            is Action.Wait -> plain(OpIds.WAIT, params("seconds" to a.seconds))
            is Action.Flashlight -> plain(OpIds.FLASHLIGHT, params("mode" to a.mode))
            is Action.SetVolume -> plain(OpIds.SET_VOLUME, params("stream" to a.stream, "percent" to a.percent))
            Action.PlaySound -> plain(OpIds.PLAY_SOUND)
            is Action.IfThen -> Step(
                id = id,
                op = Op(OpIds.IF, params("condition" to JsonObject(emptyMap()))),
                options = StepOptions(onlyIf = null),
                blocks = mapOf(
                    OpIds.BLOCK_THEN to steps(a.then, "$id.then"),
                    OpIds.BLOCK_ELSE to steps(a.otherwise, "$id.else"),
                ),
            ).withIfCondition(a.condition)
            is Action.Repeat -> Step(
                id = id,
                op = Op(OpIds.REPEAT, params("times" to a.times)),
                blocks = mapOf(OpIds.BLOCK_BODY to steps(a.actions, "$id.body")),
            )
        }
    }

    /** An If step stores its condition as a constraint group in the step's `onlyIf`-independent "condition" param. */
    private fun Step.withIfCondition(c: Condition): Step {
        val group = JsonFile.json.encodeToJsonElement(ConstraintGroup.serializer(), constraintGroup(c))
        return copy(op = op.copy(params = params("condition" to group)))
    }

    /** Reads the condition of an If step written by [step]. Null when the step has none (always true). */
    fun ifCondition(step: Step): ConstraintGroup? {
        val element = step.op.params["condition"] ?: return null
        if (element is JsonPrimitive) return null
        return runCatching { JsonFile.json.decodeFromJsonElement(ConstraintGroup.serializer(), element) }.getOrNull()
    }
}

/** What a backup file contained, whichever version wrote it. */
data class ImportedBackup(
    val flows: List<Flow>,
    val folders: List<Folder>,
    val variables: Map<String, JsonElement>,
    val sourceVersion: Int,
)

@kotlinx.serialization.Serializable
data class BackupFileV2(
    val app: String = "androx",
    val version: Int = 2,
    val exportedAt: Long = 0L,
    val flows: List<Flow> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val variables: Map<String, JsonElement> = emptyMap(),
)

/** Reads v0.0.0, v0.0.1 (version 1) and v0.0.2 (version 2) backups. */
object BackupCodec {
    private val pretty = kotlinx.serialization.json.Json(from = JsonFile.json) { prettyPrint = true }

    fun encode(flows: List<Flow>, folders: List<Folder>, variables: Map<String, JsonElement>, now: Long): String =
        pretty.encodeToString(
            BackupFileV2.serializer(),
            BackupFileV2(exportedAt = now, flows = flows, folders = folders, variables = variables),
        )

    /** Throws [IllegalArgumentException] if [text] isn't an Androx backup. */
    fun decode(text: String): ImportedBackup {
        val root = try {
            JsonFile.json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw IllegalArgumentException("That file isn't an Androx backup.")
        val app = (root["app"] as? JsonPrimitive)?.content
        require(app == "androx") { "That file isn't an Androx backup." }
        val version = (root["version"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
        return if (version >= 2) {
            val file = JsonFile.json.decodeFromJsonElement(BackupFileV2.serializer(), root)
            ImportedBackup(file.flows, file.folders, file.variables, version)
        } else {
            val file: BackupFile = JsonFile.json.decodeFromJsonElement(BackupFile.serializer(), root)
            ImportedBackup(
                flows = FlowMigration.migrateAll(file.automations),
                folders = emptyList(),
                variables = file.variables.mapValues { JsonPrimitive(it.value) },
                sourceVersion = version,
            )
        }
    }
}
