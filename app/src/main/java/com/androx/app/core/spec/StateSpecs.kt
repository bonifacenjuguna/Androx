package com.androx.app.core.spec

import com.androx.app.core.CheckIds
import com.androx.app.core.Compare
import com.androx.app.core.CompareOp
import com.androx.app.core.bool
import com.androx.app.core.enum
import com.androx.app.core.has
import com.androx.app.core.str
import com.androx.app.core.state.StateProvider
import com.androx.app.core.state.StateType
import kotlinx.serialization.json.JsonObject

/** Turns a [StateProvider] into its constraint entry, so a new piece of device state needs no extra UI or spec code. */
object StateSpecs {

    fun typeId(provider: StateProvider) = CheckIds.STATE_PREFIX + provider.id

    private fun unitSuffix(p: StateProvider) = if (p.unit.isBlank()) "" else " (${p.unit})"

    private fun opChoices(ops: List<CompareOp>) = ops.map { Choice(it.name, it.label) }

    fun constraintSpec(p: StateProvider): Spec {
        val schema: List<ParamSpec> = when (p.type) {
            StateType.BOOLEAN -> listOf(ParamSpec.Toggle("is", "${p.name}: ${p.yes}", default = true))
            StateType.NUMBER -> listOf(
                ParamSpec.Pick("op", "Is", opChoices(Compare.numericOps), CompareOp.LT.name),
                ParamSpec.Text("value", "Value" + unitSuffix(p), required = true, default = "0", templated = false),
                ParamSpec.Text("value2", "And" + unitSuffix(p), help = "Only used for \"between\".", default = "0", templated = false),
            )
            StateType.CHOICE -> listOf(
                ParamSpec.Pick("op", "Is", opChoices(Compare.equalityOps), CompareOp.EQ.name),
                ParamSpec.Pick("value", "Value", p.choices.map { Choice(it.value, it.label) }, p.choices.first().value),
            )
            StateType.TEXT -> listOf(
                ParamSpec.Pick("op", "Is", opChoices(Compare.textOps), CompareOp.EQ.name),
                ParamSpec.Text("value", "Value", templated = false),
            )
        }
        return Spec(
            kind = SpecKind.CONSTRAINT,
            id = typeId(p),
            category = p.category,
            name = p.name,
            description = p.description,
            iconKey = p.iconKey,
            synonyms = p.synonyms,
            params = schema,
            permissions = p.permissions,
            minSdk = p.minSdk,
            outputs = listOf(OutputSpec(p.id, p.name, p.type.name.lowercase())),
            summary = { params -> summary(p, params) },
            validate = { params -> validate(p, params) },
        )
    }

    /** "Battery temperature > 40 °C", "Headphones: connected". Never throws. */
    fun summary(p: StateProvider, params: JsonObject): String = when (p.type) {
        StateType.BOOLEAN -> "${p.name}: ${if (params.bool("is", true)) p.yes else p.no}"
        StateType.NUMBER -> {
            val op = params.enum("op", CompareOp.LT)
            "${p.name} ${Compare.describe(op, params.str("value", "0"), params.str("value2", "0"), p.unit)}"
        }
        StateType.CHOICE -> {
            val op = params.enum("op", CompareOp.EQ)
            val value = params.str("value", p.choices.firstOrNull()?.value.orEmpty())
            val label = p.choices.firstOrNull { it.value == value }?.label ?: value
            "${p.name} ${op.symbol} $label"
        }
        StateType.TEXT -> "${p.name} ${Compare.describe(params.enum("op", CompareOp.EQ), params.str("value"))}"
    }

    private fun validate(p: StateProvider, params: JsonObject): List<String> {
        if (p.type != StateType.NUMBER) return emptyList()
        val problems = ArrayList<String>()
        val a = params.str("value", "0").trim().toDoubleOrNull()
        if (a == null) problems += "Value must be a number."
        if (params.has("op") && params.enum("op", CompareOp.LT) == CompareOp.BETWEEN) {
            val b = params.str("value2", "0").trim().toDoubleOrNull()
            if (b == null) problems += "The second value must be a number."
            else if (a != null && a > b) problems += "The first value must not be higher than the second."
        }
        return problems
    }
}
