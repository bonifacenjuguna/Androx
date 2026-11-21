package com.androx.app.core.registry

import com.androx.app.core.model.P
import com.androx.app.core.value.Value
import com.androx.app.core.value.ValueType
import kotlinx.serialization.json.JsonObject

enum class Edition { FULL, PLAY_SAFE }

enum class Reliability(val label: String) {
    RELIABLE("Reliable"),
    BEST_EFFORT("Best effort"),
    NEEDS_LISTENER("Uses the Listener"),
}

enum class BatteryCost(val label: String) {
    NONE("None"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
}

/** What a trigger, constraint or action needs before it can work. Drives status badges and permission fixes. */
sealed interface Requirement {
    data class RuntimePermission(val permission: String, val label: String) : Requirement
    data class SpecialAccess(val kind: String, val label: String) : Requirement
    data object Listener : Requirement
    data object Internet : Requirement
    data class Hardware(val feature: String, val label: String) : Requirement
    data object Shizuku : Requirement
}

enum class FieldType {
    TEXT, MULTILINE, NUMBER, BOOLEAN, CHOICE, DAYS, TIME, DURATION_MINUTES, VARIABLE_NAME, SECRET, JSON, FLOW, CONNECTION, COMPARISON,
}

data class Choice(val value: String, val label: String)

/** One setting of a spec. The editor UI, validation and the plain-English summary are generated from these. */
data class FieldSpec(
    val key: String,
    val label: String,
    val type: FieldType,
    val default: Value? = null,
    val required: Boolean = false,
    val help: String = "",
    val choices: List<Choice> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    /** Whether \`{{values}}\` can be inserted into this field. */
    val templated: Boolean = false,
    /** Hidden unless Advanced mode is on. */
    val advanced: Boolean = false,
)

/** A value a trigger or action exposes to later steps, e.g. \`{{http.status}}\`. */
data class OutputSpec(val key: String, val label: String, val type: ValueType)

/** Common description of every registry entry. */
interface Spec {
    val id: String
    val category: String
    val name: String
    val description: String
    /** Key of a bundled icon (see the icon registry). */
    val icon: String
    val synonyms: List<String>
    val requires: List<Requirement>
    val minSdk: Int
    val editions: Set<Edition>
    val fields: List<FieldSpec>
    val outputs: List<OutputSpec>
    val reliability: Reliability
    val batteryCost: BatteryCost

    /** One plain-English sentence, e.g. "Every 1 h 30 min". */
    fun summarize(p: P): String

    /** Problems with the current settings. Empty means valid. */
    fun validate(p: P): List<String>
}

private fun defaultValidate(fields: List<FieldSpec>, p: P): List<String> {
    val problems = mutableListOf<String>()
    for (f in fields) {
        if (f.required && p.str(f.key).isBlank() && f.type != FieldType.BOOLEAN && f.type != FieldType.DAYS) {
            problems += "\${f.label} is empty."
        }
        if (f.type == FieldType.NUMBER || f.type == FieldType.DURATION_MINUTES) {
            if (p.has(f.key)) {
                val n = p.double(f.key, Double.NaN)
                if (n.isNaN()) problems += "\${f.label} must be a number."
                else {
                    if (f.min != null && n < f.min) problems += "\${f.label} must be at least \${f.min.toLong()}."
                    if (f.max != null && n > f.max) problems += "\${f.label} must be at most \${f.max.toLong()}."
                }
            }
        }
    }
    return problems
}

/** Shared implementation so a new entry is one declaration. */
abstract class BaseSpec(
    final override val id: String,
    final override val category: String,
    final override val name: String,
    final override val description: String,
    final override val icon: String,
    final override val synonyms: List<String> = emptyList(),
    final override val requires: List<Requirement> = emptyList(),
    final override val minSdk: Int = 26,
    final override val editions: Set<Edition> = setOf(Edition.FULL, Edition.PLAY_SAFE),
    final override val fields: List<FieldSpec> = emptyList(),
    final override val outputs: List<OutputSpec> = emptyList(),
    final override val reliability: Reliability = Reliability.RELIABLE,
    final override val batteryCost: BatteryCost = BatteryCost.NONE,
) : Spec {
    override fun validate(p: P): List<String> = defaultValidate(fields, p)
}

class TriggerSpec(
    id: String,
    category: String,
    name: String,
    description: String,
    icon: String,
    synonyms: List<String> = emptyList(),
    requires: List<Requirement> = emptyList(),
    minSdk: Int = 26,
    editions: Set<Edition> = setOf(Edition.FULL, Edition.PLAY_SAFE),
    fields: List<FieldSpec> = emptyList(),
    outputs: List<OutputSpec> = emptyList(),
    reliability: Reliability = Reliability.RELIABLE,
    batteryCost: BatteryCost = BatteryCost.NONE,
    /** True for triggers driven by AlarmManager (the next fire time is computed from the settings). */
    val timed: Boolean = false,
    private val summary: (P) -> String,
) : BaseSpec(id, category, name, description, icon, synonyms, requires, minSdk, editions, fields, outputs, reliability, batteryCost) {
    override fun summarize(p: P): String = summary(p)
}

class ConstraintSpec(
    id: String,
    category: String,
    name: String,
    description: String,
    icon: String,
    synonyms: List<String> = emptyList(),
    requires: List<Requirement> = emptyList(),
    minSdk: Int = 26,
    editions: Set<Edition> = setOf(Edition.FULL, Edition.PLAY_SAFE),
    fields: List<FieldSpec> = emptyList(),
    outputs: List<OutputSpec> = emptyList(),
    reliability: Reliability = Reliability.RELIABLE,
    batteryCost: BatteryCost = BatteryCost.NONE,
    private val summary: (P) -> String,
) : BaseSpec(id, category, name, description, icon, synonyms, requires, minSdk, editions, fields, outputs, reliability, batteryCost) {
    override fun summarize(p: P): String = summary(p)
}

class ActionSpec(
    id: String,
    category: String,
    name: String,
    description: String,
    icon: String,
    synonyms: List<String> = emptyList(),
    requires: List<Requirement> = emptyList(),
    minSdk: Int = 26,
    editions: Set<Edition> = setOf(Edition.FULL, Edition.PLAY_SAFE),
    fields: List<FieldSpec> = emptyList(),
    outputs: List<OutputSpec> = emptyList(),
    reliability: Reliability = Reliability.RELIABLE,
    batteryCost: BatteryCost = BatteryCost.NONE,
    /** Control-flow steps list the names of their child blocks ("then", "else", …). */
    val blocks: List<String> = emptyList(),
    /** Whether the step's own \`condition\` is its test (if/while) rather than an optional gate. */
    val conditionIsTest: Boolean = false,
    private val summary: (P) -> String,
) : BaseSpec(id, category, name, description, icon, synonyms, requires, minSdk, editions, fields, outputs, reliability, batteryCost) {
    override fun summarize(p: P): String = summary(p)
}

/** Settings of a spec read from a stored params object. */
fun Spec.summaryOf(params: JsonObject): String = summarize(P(params))

fun Spec.problemsOf(params: JsonObject): List<String> = validate(P(params))
