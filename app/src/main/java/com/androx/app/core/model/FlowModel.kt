package com.androx.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Which editor a Flow uses. Chosen when the Flow is created, shown as a chip on its card. */
enum class FlowEditor { STEPS, CODE, CANVAS }

/** Canvas wiring: structured maps onto the Steps tree; free-form allows any wiring (loops, jumps). */
enum class WiringMode { STRUCTURED, FREE_FORM }

enum class ConcurrencyPolicy { SKIP, QUEUE, REPLACE }

enum class ErrorPolicy { STOP, CONTINUE }

enum class GroupMode { ALL, ANY, NONE }

val EMPTY_PARAMS = JsonObject(emptyMap())

/** Something that happens. `typeId` is looked up in the trigger registry; `params` are its settings. */
@Serializable
data class Event(val typeId: String, val params: JsonObject = EMPTY_PARAMS)

/** A yes/no question about the device or the Flow, evaluated when it matters. */
@Serializable
data class Check(val typeId: String, val params: JsonObject = EMPTY_PARAMS, val negate: Boolean = false)

/** One thing to do. `typeId` is looked up in the action registry. */
@Serializable
data class Op(val typeId: String, val params: JsonObject = EMPTY_PARAMS)

/** A tree of constraints: single rules and nested All / Any / None groups. */
@Serializable
sealed interface ConstraintNode {
    @Serializable
    @SerialName("rule")
    data class Rule(val check: Check) : ConstraintNode

    @Serializable
    @SerialName("group")
    data class Group(val mode: GroupMode = GroupMode.ALL, val items: List<ConstraintNode> = emptyList()) : ConstraintNode
}

/** Options every step has. */
@Serializable
data class StepOptions(
    val continueOnError: Boolean = false,
    val retries: Int = 0,
    val retryDelayMs: Long = 1_000L,
    /** 0 means no step-specific timeout. */
    val timeoutMs: Long = 0L,
    val disabled: Boolean = false,
    val note: String = "",
    /** Name of a variable that receives the step's result ("" = none). */
    val saveResultTo: String = "",
)

/**
 * A step. Plain steps just have an [op]. Control-flow steps (if, repeat, for each, try …) keep their
 * child steps in named [blocks] ("then", "else", "body", "try", "catch"), so new control steps need no schema change.
 * [condition] is the test of an `if`/`while`/`wait until`, and the "only if" gate of any other step.
 */
@Serializable
data class Step(
    val id: String,
    val op: Op,
    val options: StepOptions = StepOptions(),
    val condition: ConstraintNode? = null,
    val blocks: Map<String, List<Step>> = emptyMap(),
)

/** One of the several triggers of a Flow (any of them starts it). */
@Serializable
data class TriggerEntry(
    val id: String,
    val event: Event,
    /** Extra constraints only for this trigger. */
    val constraints: ConstraintNode? = null,
    /** Minimum seconds between two firings of this trigger. */
    val cooldownSec: Int = 0,
    /** The condition behind the trigger must hold this many minutes before it fires (0 = immediately). */
    val holdMinutes: Int = 0,
    val enabled: Boolean = true,
)

@Serializable
data class RunOptions(
    val timeoutSec: Int = 120,
    val concurrency: ConcurrencyPolicy = ConcurrencyPolicy.SKIP,
    val onError: ErrorPolicy = ErrorPolicy.STOP,
    /** Maximum runs per hour (0 = no limit). */
    val runLimitPerHour: Int = 0,
    val stepBudget: Int = 5_000,
    val waitBudgetSec: Int = 30,
)

const val FLOW_SCHEMA_VERSION = 2

/**
 * The v2 Flow. Triggers are a list (any-of), constraints are a tree, the body is a step tree (Steps and
 * structured Canvas), a script (Code) or a free-form graph ([canvas]).
 */
@Serializable
data class Flow(
    val id: String,
    val name: String,
    val description: String = "",
    val icon: String? = null,
    val folderId: String? = null,
    val pinned: Boolean = false,
    val enabled: Boolean = true,
    val editor: FlowEditor = FlowEditor.STEPS,
    val wiring: WiringMode = WiringMode.STRUCTURED,
    val triggers: List<TriggerEntry> = emptyList(),
    val constraints: ConstraintNode? = null,
    val steps: List<Step> = emptyList(),
    /** JavaScript source for Code Flows. */
    val script: String? = null,
    /** Canvas layout (node positions) and, for free-form wiring, the graph itself. */
    val canvas: JsonObject? = null,
    val run: RunOptions = RunOptions(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Set when the Flow is in the Trash. */
    val deletedAt: Long? = null,
    val schemaVersion: Int = FLOW_SCHEMA_VERSION,
)

@Serializable
data class FlowsFile(val version: Int = FLOW_SCHEMA_VERSION, val flows: List<Flow> = emptyList())

/** Every step in the tree, depth first. */
fun List<Step>.walk(): Sequence<Step> = asSequence().flatMap { step ->
    sequenceOf(step) + step.blocks.values.asSequence().flatMap { it.walk() }
}

fun Flow.allSteps(): Sequence<Step> = steps.walk()

fun Flow.isInTrash(): Boolean = deletedAt != null
