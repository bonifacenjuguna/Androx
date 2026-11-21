package com.androx.app.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal

/**
 * A typed value (spec §5.3): text, number, true/false, list or dictionary.
 *
 * Variables written by v0.0.0 and v0.0.1 are plain text; [fromStored] reads them back so that numbers saved as
 * text still work in math, and [render] turns any value back into the text a `{{template}}` needs.
 */
sealed interface Value {
    data class Text(val value: String) : Value
    data class Num(val value: Double) : Value
    data class Bool(val value: Boolean) : Value
    data class Items(val items: List<Value>) : Value
    data class Dict(val entries: Map<String, Value>) : Value

    /** The type name shown in the UI and stored with typed variables. */
    val typeName: String
        get() = when (this) {
            is Text -> "text"
            is Num -> "number"
            is Bool -> "boolean"
            is Items -> "list"
            is Dict -> "dictionary"
        }

    /** Text form used in `{{values}}`, notifications and logs. Lists and dictionaries render as compact JSON. */
    fun render(): String = when (this) {
        is Text -> value
        is Num -> formatNumber(value)
        is Bool -> value.toString()
        is Items, is Dict -> toJson().toString()
    }

    /** Best-effort number: numbers as themselves, numeric text parsed, booleans 1/0. Null when it isn't numeric. */
    fun asNumberOrNull(): Double? = when (this) {
        is Num -> value
        is Text -> parseNumber(value)
        is Bool -> if (value) 1.0 else 0.0
        is Items -> null
        is Dict -> null
    }

    /** Truthiness used by conditions: "true"/"yes"/"on"/"1" and non-zero numbers are true. */
    fun asBoolean(): Boolean = when (this) {
        is Bool -> value
        is Num -> value != 0.0
        is Text -> value.trim().lowercase() in TRUE_WORDS
        is Items -> items.isNotEmpty()
        is Dict -> entries.isNotEmpty()
    }

    fun toJson(): JsonElement = when (this) {
        is Text -> JsonPrimitive(value)
        is Num -> if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 9e15) {
            JsonPrimitive(value.toLong())
        } else {
            JsonPrimitive(value)
        }
        is Bool -> JsonPrimitive(value)
        is Items -> JsonArray(items.map { it.toJson() })
        is Dict -> JsonObject(entries.mapValues { it.value.toJson() })
    }

    companion object {
        private val TRUE_WORDS = setOf("true", "yes", "on", "1")

        fun of(text: String): Value = Text(text)
        fun of(number: Number): Value = Num(number.toDouble())
        fun of(flag: Boolean): Value = Bool(flag)

        /** Reads a stored variable: text stays text, but the caller can still do math on it through [asNumberOrNull]. */
        fun fromStored(text: String): Value = Text(text)

        fun parseNumber(text: String): Double? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val d = t.toDoubleOrNull() ?: return null
            return if (d.isNaN() || d.isInfinite()) null else d
        }

        fun fromJson(element: JsonElement): Value = when (element) {
            is JsonNull -> Text("")
            is JsonArray -> Items(element.map { fromJson(it) })
            is JsonObject -> Dict(element.mapValues { fromJson(it.value) })
            is JsonPrimitive -> when {
                element.isString -> Text(element.content)
                element.booleanOrNull != null -> Bool(element.booleanOrNull!!)
                element.doubleOrNull != null -> Num(element.doubleOrNull!!)
                else -> Text(element.content)
            }
        }

        /** Parses JSON text into a value. Throws [IllegalArgumentException] with a readable message on bad input. */
        fun parseJson(text: String): Value = try {
            fromJson(com.androx.app.data.JsonFile.json.parseToJsonElement(text))
        } catch (e: Exception) {
            throw IllegalArgumentException("That isn't valid JSON: ${e.message?.take(80)}")
        }

        fun formatNumber(d: Double): String {
            if (d.isNaN()) return "NaN"
            if (d.isInfinite()) return if (d > 0) "∞" else "-∞"
            if (d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
        }
    }
}

/** Looks a dotted path such as `user.items.0.name` up inside a list/dictionary value. Null when any step is missing. */
fun Value.path(path: String): Value? {
    var current: Value = this
    if (path.isBlank()) return current
    for (part in path.split('.')) {
        current = when (current) {
            is Value.Dict -> current.entries[part] ?: return null
            is Value.Items -> current.items.getOrNull(part.toIntOrNull() ?: return null) ?: return null
            else -> return null
        }
    }
    return current
}
