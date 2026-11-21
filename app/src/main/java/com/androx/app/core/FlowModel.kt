package com.androx.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Current on-disk format of `flows.json`. Bump together with a migration in [FlowMigration]. */
const val FLOW_SCHEMA_VERSION = 2

/** Which editor a Flow opens in. Chosen when the Flow is created (spec §8.3, §13.1). */
@Serializable
enum class FlowType { STEPS, CODE, CANVAS }

/** Canvas only: structured blocks map to the step tree, free-form allows any wiring (spec §13.4). */
@Serializable
enum class WiringMode { STRUCTURED, FREE_FORM }

/** What happens when a Flow is triggered while it is already running. */
@Serializable
enum class ConcurrencyPolicy { SKIP, QUEUE, REPLACE }

/** How a failing step behaves when the step itself doesn't say. */
@Serializable
enum class OnErrorPolicy { STOP, CONTINUE }

/** How a constraint group combines its members (spec §17.1). */
@Serializable
enum class LogicOp { ALL, ANY, NONE }

/** A single constraint: what to check, with what settings, and whether the result is flipped ("is not"). */
@Serializable
data class Check(
    val typeId: String,
    val params: JsonObject = EmptyParams,
    val negate: Boolean = false,
)

/** Nested All / Any / None groups of [Check]s. An empty group always passes. */
@Serializable
data class ConstraintGroup(
    val op: LogicOp = LogicOp.ALL,
    val rules: List<Check> = emptyList(),
    val groups: List<ConstraintGroup> = emptyList(),
) {
    val isEmpty: Boolean get() = rules.isEmpty() && groups.isEmpty()

    /** Every rule in this group and all nested groups, depth first. */
    fun allRules(): List<Check> = rules + groups.flatMap { it.allRules() }
}

/** One way a Flow can start. Several per Flow, any-of (spec §5.2, §15.1). */
@Serializable
data class TriggerEntry(
    val id: String,
    val typeId: String,
    val params: JsonObject = EmptyParams,
    /** Extra constraints that only gate this trigger. */
    val constraints: ConstraintGroup? = null,
    /** After firing, ignore further events from this trigger for this many seconds. */
    val cooldownSeconds: Int = 0,
    /** Only fire once the condition has held for this many minutes (for example battery under 20% for 5 min). */
    val holdMinutes: Int = 0,
    val enabled: Boolean = true,
)

/** Generic action carrier: what to do and with which settings. */
@Serializable
data class Op(
    val typeId: String,
    val params: JsonObject = EmptyParams,
)

/** Options every step has (spec §5.2). */
@Serializable
data class StepOptions(
    val continueOnError: Boolean = false,
    val retries: Int = 0,
    val retryDelayMs: Long = 1_000L,
    /** 0 means no per-step timeout. */
    val timeoutSeconds: Int = 0,
    val disabled: Boolean = false,
    val note: String = "",
    /** Variable that receives the step's result. Blank means none. */
    val saveResultTo: String = "",
    /** A quick "only if" on this single step (spec §17.1). */
    val onlyIf: ConstraintGroup? = null,
)

/**
 * One step of a Flow. Flow-control steps (If, Repeat, Try…) keep their children in [blocks], keyed by the block's
 * name ("then", "else", "body", "try", "catch"), so the tree never needs a schema change for a new control step.
 */
@Serializable
data class Step(
    val id: String,
    val op: Op,
    val options: StepOptions = StepOptions(),
    val blocks: Map<String, List<Step>> = emptyMap(),
) {
    /** This step and every step nested inside it, depth first. */
    fun flatten(): List<Step> = listOf(this) + blocks.values.flatMap { list -> list.flatMap { it.flatten() } }
}

@Serializable
data class RunOptions(
    /** Whole-run timeout in seconds. */
    val timeoutSeconds: Int = 60,
    val concurrency: ConcurrencyPolicy = ConcurrencyPolicy.SKIP,
    val onError: OnErrorPolicy = OnErrorPolicy.STOP,
    /** Maximum runs per hour; 0 means unlimited. */
    val runLimitPerHour: Int = 0,
    /** Advanced: raises the 30 s per-run wait budget. 0 keeps the default. */
    val waitBudgetSeconds: Int = 0,
)

/** The unit users create: triggers, constraints and a body (spec §5.2). */
@Serializable
data class Flow(
    val id: String,
    val name: String,
    val description: String = "",
    val icon: String? = null,
    val folderId: String? = null,
    val pinned: Boolean = false,
    val enabled: Boolean = true,
    val type: FlowType = FlowType.STEPS,
    val wiring: WiringMode = WiringMode.STRUCTURED,
    val triggers: List<TriggerEntry> = emptyList(),
    /** Gate for the whole Flow. Null means "always". */
    val constraints: ConstraintGroup? = null,
    /** Body for Steps and structured Canvas Flows. */
    val steps: List<Step> = emptyList(),
    /** Body for Code Flows. */
    val script: String? = null,
    /** Free-form Canvas graph. Null until the Canvas editor writes one. */
    val graph: JsonElement? = null,
    /** The v0.0.1 workflow layout, kept untouched so nothing is lost by migration. */
    val legacyGraph: JsonElement? = null,
    val runOptions: RunOptions = RunOptions(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Set when the Flow is in Trash (spec §10). */
    val deletedAt: Long? = null,
) {
    val inTrash: Boolean get() = deletedAt != null

    /** Every step of the body, including nested ones. */
    fun allSteps(): List<Step> = steps.flatMap { it.flatten() }
}

@Serializable
data class Folder(
    val id: String,
    val name: String,
    val colorKey: String? = null,
    val order: Int = 0,
    val collapsed: Boolean = false,
)

/** `flows.json`. */
@Serializable
data class FlowsFile(
    val schemaVersion: Int = FLOW_SCHEMA_VERSION,
    val flows: List<Flow> = emptyList(),
    val folders: List<Folder> = emptyList(),
)

/** A saved copy of a Flow for "Version history with restore" (spec §9). */
@Serializable
data class FlowVersion(
    val flowId: String,
    val savedAt: Long,
    val label: String = "",
    val flow: Flow,
)

/**
 * A trigger firing, as seen by the engine and shown in the Trigger log. [values] are what `{{sms.sender}}`,
 * `{{wifi.ssid}}` and friends read from.
 */
data class Event(
    val typeId: String,
    val values: Map<String, Value> = emptyMap(),
    val at: Long = 0L,
)
