package com.androx.app.core.spec

import com.androx.app.core.Event
import com.androx.app.core.has
import com.androx.app.core.int
import com.androx.app.core.intList
import com.androx.app.core.state.DeviceStateReader
import com.androx.app.core.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class SpecKind { TRIGGER, CONSTRAINT, ACTION }

/** Picker sections. Colours come from Material You roles in the UI, never from here. */
enum class SpecCategory(val label: String) {
    FLOW_CONTROL("Flow control"),
    VARIABLES("Variables and data"),
    NOTIFY("Notify and tell"),
    DEVICE("Device and settings"),
    SOUND("Sound and volume"),
    BATTERY("Battery and power"),
    CONNECTIVITY("Connectivity"),
    DATE_TIME("Date and time"),
    LOGIC("Logic and Flows"),
    SCRIPTING("Scripting"),
    LOGGING("Logging"),
}

/** Edition availability (spec §5.7, §22). Full has everything; Play-safe omits what Google Play restricts. */
enum class EditionReq { BOTH, FULL_ONLY }
enum class Edition { FULL, PLAY_SAFE }

enum class Reliability(val label: String) {
    EXACT("Exact"),
    GOOD("Reliable"),
    BEST_EFFORT("Best effort"),
    NEEDS_LISTENER("Needs the Listener"),
}

enum class BatteryCost(val label: String) { NONE("None"), LOW("Low"), MEDIUM("Medium"), HIGH("High") }

/** What the user sees as the state of an entry in a picker (spec §13.1 "status badges"). */
enum class SpecStatus(val label: String) {
    READY("Ready"),
    NEEDS_PERMISSION("Needs permission"),
    USES_LISTENER("Uses the Listener"),
    NOT_IN_EDITION("Not in this edition"),
    UNSUPPORTED("Needs a newer Android"),
}

/** A permission or special access an entry needs. [id] is matched against the set the app reports as granted. */
data class PermissionNeed(val id: String, val label: String)

object Perm {
    val NOTIFICATIONS = PermissionNeed(com.androx.app.core.Permissions.NOTIFICATIONS, "Notifications")
    val EXACT_ALARMS = PermissionNeed(com.androx.app.core.Permissions.EXACT_ALARMS, "Exact alarms")
    val DND_ACCESS = PermissionNeed(com.androx.app.core.Permissions.DND_ACCESS, "Do Not Disturb access")
    val WRITE_SETTINGS = PermissionNeed(com.androx.app.core.Permissions.WRITE_SETTINGS, "Modify system settings")
}

data class Choice(val value: String, val label: String)

/** A value an entry exposes to later steps and text fields (`{{battery.temp}}`, `{{sms.sender}}`). */
data class OutputSpec(val key: String, val label: String, val type: String = "text")

/** Typed settings schema: drives the settings screen, validation and the summary, so nothing is hand-written twice. */
sealed interface ParamSpec {
    val key: String
    val label: String
    val help: String

    data class Text(
        override val key: String,
        override val label: String,
        override val help: String = "",
        val required: Boolean = false,
        val multiline: Boolean = false,
        val default: String = "",
        /** True when `{{insert}}` chips make sense in this field. */
        val templated: Boolean = true,
    ) : ParamSpec

    data class Num(
        override val key: String,
        override val label: String,
        override val help: String = "",
        val default: Int = 0,
        val min: Int? = null,
        val max: Int? = null,
        val unit: String = "",
    ) : ParamSpec

    data class Toggle(
        override val key: String,
        override val label: String,
        override val help: String = "",
        val default: Boolean = false,
    ) : ParamSpec

    data class Pick(
        override val key: String,
        override val label: String,
        val options: List<Choice>,
        val default: String,
        override val help: String = "",
    ) : ParamSpec

    /** Hour and minute stored under two keys. [key] is the hour key; the minute lives in [minuteKey]. */
    data class TimeOfDay(
        override val key: String,
        val minuteKey: String,
        override val label: String,
        override val help: String = "",
        val defaultHour: Int = 8,
        val defaultMinute: Int = 0,
    ) : ParamSpec

    /** ISO days 1 (Monday) … 7 (Sunday). Empty list means every day. */
    data class Days(
        override val key: String,
        override val label: String,
        override val help: String = "",
    ) : ParamSpec

    data class DateTime(
        override val key: String,
        override val label: String,
        override val help: String = "",
    ) : ParamSpec

    data class VariableName(
        override val key: String,
        override val label: String,
        override val help: String = "",
    ) : ParamSpec

    data class Code(
        override val key: String,
        override val label: String,
        override val help: String = "",
    ) : ParamSpec

    /** A set of whole numbers in [min]..[max] (days of the month, months). [labels] name them from [min] upwards. */
    data class IntSet(
        override val key: String,
        override val label: String,
        val min: Int,
        val max: Int,
        val labels: List<String> = emptyList(),
        override val help: String = "",
    ) : ParamSpec

    /** A nested All / Any / None constraint group, edited with the logic builder. Stored as a [com.androx.app.core.ConstraintGroup]. */
    data class Logic(
        override val key: String,
        override val label: String,
        override val help: String = "",
    ) : ParamSpec

    data class FlowRef(
        override val key: String,
        override val label: String,
        override val help: String = "",
        /** Companion key that caches the Flow's name for display. */
        val nameKey: String = "flowName",
        /** False for optional choices such as a notification button's Flow. */
        val required: Boolean = true,
    ) : ParamSpec
}

/** Checks [params] against [schema]. Returns plain-English problems; an empty list means valid. */
fun validateParams(schema: List<ParamSpec>, params: JsonObject): List<String> {
    val problems = mutableListOf<String>()
    for (p in schema) {
        when (p) {
            is ParamSpec.Text -> if (p.required && params.str(p.key).isBlank()) problems += "${p.label} can't be empty."
            is ParamSpec.Num -> {
                if (params.has(p.key)) {
                    val v = params.int(p.key, p.default)
                    if (p.min != null && v < p.min) problems += "${p.label} must be at least ${p.min}${unit(p)}."
                    if (p.max != null && v > p.max) problems += "${p.label} must be at most ${p.max}${unit(p)}."
                }
            }
            is ParamSpec.Pick -> {
                val v = params.str(p.key, p.default)
                if (p.options.none { it.value.equals(v, ignoreCase = true) }) problems += "${p.label} has an unknown value \"$v\"."
            }
            is ParamSpec.TimeOfDay -> {
                val h = params.int(p.key, p.defaultHour)
                val m = params.int(p.minuteKey, p.defaultMinute)
                if (h !in 0..23 || m !in 0..59) problems += "${p.label} isn't a valid time."
            }
            is ParamSpec.Days -> if (params.intList(p.key).any { it !in 1..7 }) problems += "${p.label} has an invalid day."
            is ParamSpec.VariableName -> if (params.str(p.key).isBlank()) problems += "${p.label} can't be empty."
            is ParamSpec.FlowRef -> if (p.required && params.str(p.key).isBlank()) problems += "Choose a Flow."
            is ParamSpec.IntSet -> if (params.intList(p.key).any { it !in p.min..p.max }) problems += "${p.label} has a value outside ${p.min}–${p.max}."
            is ParamSpec.Toggle, is ParamSpec.DateTime, is ParamSpec.Code, is ParamSpec.Logic -> Unit
        }
    }
    return problems
}

private fun unit(p: ParamSpec.Num) = if (p.unit.isBlank()) "" else " ${p.unit}"

/**
 * One self-describing entry (spec §5.1). UI, search, summaries, permissions, docs and the catalogue are all
 * generated from these, so adding a trigger, constraint or action means adding one [Spec] and one executor.
 */
data class Spec(
    val kind: SpecKind,
    val id: String,
    val category: SpecCategory,
    val name: String,
    val description: String,
    /** Key of a Material Design Icons drawable, without the `mdi_` prefix. */
    val iconKey: String,
    val synonyms: List<String> = emptyList(),
    val params: List<ParamSpec> = emptyList(),
    val permissions: List<PermissionNeed> = emptyList(),
    val needsListener: Boolean = false,
    val reliability: Reliability = Reliability.GOOD,
    val batteryCost: BatteryCost = BatteryCost.NONE,
    val minSdk: Int = 26,
    val edition: EditionReq = EditionReq.BOTH,
    val outputs: List<OutputSpec> = emptyList(),
    /** Names of the child blocks a flow-control step owns ("then", "else", "body"). Empty for ordinary entries. */
    val blocks: List<String> = emptyList(),
    /** Plain-English one-liner for these settings ("Battery < 20%"). Must never throw. */
    val summary: (JsonObject) -> String,
    /** Extra validation on top of the schema. */
    val validate: (JsonObject) -> List<String> = { emptyList() },
    /** Set on entries kept only so older Flows keep working; pickers hide them and offer this id instead. */
    val supersededBy: String? = null,
    /** Triggers only: does [Event] satisfy these settings? (for example "battery crossed below 20%"). */
    val matches: (JsonObject, Event) -> Boolean = { _, _ -> true },
    /** Triggers only: is the condition still true right now? Present means "only after holding for N minutes" is supported. */
    val holdCheck: ((JsonObject, DeviceStateReader) -> Boolean)? = null,
) {
    val supportsHold: Boolean get() = holdCheck != null

    fun problems(params: JsonObject): List<String> = validateParams(this.params, params) + validate(params)

    /** Where this entry stands on this device and build. */
    fun status(edition: Edition, sdk: Int, granted: Set<String>): SpecStatus = when {
        this.edition == EditionReq.FULL_ONLY && edition == Edition.PLAY_SAFE -> SpecStatus.NOT_IN_EDITION
        sdk < minSdk -> SpecStatus.UNSUPPORTED
        permissions.any { it.id !in granted } -> SpecStatus.NEEDS_PERMISSION
        needsListener -> SpecStatus.USES_LISTENER
        else -> SpecStatus.READY
    }

    /** Defaults for a freshly added entry, taken from the schema. */
    fun defaultParams(): JsonObject {
        val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for (p in params) {
            when (p) {
                is ParamSpec.Text -> if (p.default.isNotEmpty()) map[p.key] = JsonPrimitive(p.default)
                is ParamSpec.Num -> map[p.key] = JsonPrimitive(p.default)
                is ParamSpec.Toggle -> map[p.key] = JsonPrimitive(p.default)
                is ParamSpec.Pick -> map[p.key] = JsonPrimitive(p.default)
                is ParamSpec.TimeOfDay -> {
                    map[p.key] = JsonPrimitive(p.defaultHour)
                    map[p.minuteKey] = JsonPrimitive(p.defaultMinute)
                }
                is ParamSpec.Days, is ParamSpec.DateTime, is ParamSpec.VariableName, is ParamSpec.Code, is ParamSpec.FlowRef, is ParamSpec.IntSet, is ParamSpec.Logic -> Unit
            }
        }
        return JsonObject(map)
    }
}
