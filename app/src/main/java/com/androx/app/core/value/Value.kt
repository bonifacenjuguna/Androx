package com.androx.app.core.value

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.floor

enum class ValueType(val label: String) {
    TEXT("Text"),
    NUMBER("Number"),
    BOOLEAN("True / false"),
    LIST("List"),
    DICTIONARY("Dictionary"),
}

/**
 * A typed value. Variables, trigger data, step outputs and template values are all [Value]s.
 * Text that looks like a number is read as a number by maths, so variables saved by v0.0.0 / v0.0.1
 * (which were all text) keep working unchanged.
 */
sealed interface Value {
    data class Text(val v: String) : Value
    data class Num(val v: Double) : Value
    data class Bool(val v: Boolean) : Value
    data class ListV(val items: List<Value>) : Value
    data class Dict(val entries: Map<String, Value>) : Value

    val type: ValueType
        get() = when (this) {
            is Text -> ValueType.TEXT
            is Num -> ValueType.NUMBER
            is Bool -> ValueType.BOOLEAN
            is ListV -> ValueType.LIST
            is Dict -> ValueType.DICTIONARY
        }

    /** What `{{value}}` shows. Lists and dictionaries render as compact JSON. */
    fun asText(): String = when (this) {
        is Text -> v
        is Num -> formatNumber(v)
        is Bool -> v.toString()
        is ListV, is Dict -> compactJson.encodeToString(JsonElement.serializer(), toJson())
    }

    /** A number if this value can be read as one (text is trimmed and parsed). */
    fun asNumber(): Double? = when (this) {
        is Num -> v
        is Text -> v.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        is Bool -> if (v) 1.0 else 0.0
        else -> null
    }

    fun asBool(): Boolean = when (this) {
        is Bool -> v
        is Num -> v != 0.0
        is Text -> when (v.trim().lowercase()) {
            "", "false", "0", "no", "off", "null" -> false
            else -> true
        }
        is ListV -> items.isNotEmpty()
        is Dict -> entries.isNotEmpty()
    }

    fun toJson(): JsonElement = when (this) {
        is Text -> JsonPrimitive(v)
        is Num -> if (v == floor(v) && abs(v) < 1e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)
        is Bool -> JsonPrimitive(v)
        is ListV -> JsonArray(items.map { it.toJson() })
        is Dict -> JsonObject(entries.mapValues { it.value.toJson() })
    }

    /** Number of characters, items or entries. */
    fun length(): Int = when (this) {
        is ListV -> items.size
        is Dict -> entries.size
        else -> asText().length
    }

    companion object {
        val EMPTY = Text("")
        internal val compactJson = Json { encodeDefaults = true }

        fun of(text: String): Value = Text(text)
        fun of(number: Number): Value = Num(number.toDouble())
        fun of(flag: Boolean): Value = Bool(flag)

        fun fromJson(e: JsonElement): Value = when (e) {
            is JsonNull -> Text("")
            is JsonPrimitive -> when {
                e.isString -> Text(e.content)
                e.booleanOrNull != null -> Bool(e.boolean)
                e.doubleOrNull != null -> Num(e.double)
                else -> Text(e.content)
            }
            is JsonArray -> ListV(e.map { fromJson(it) })
            is JsonObject -> Dict(e.mapValues { fromJson(it.value) })
        }

        /** Parses JSON text into a value. Null if the text is not valid JSON. */
        fun parseJson(text: String): Value? = try {
            fromJson(compactJson.parseToJsonElement(text))
        } catch (e: Exception) {
            null
        }

        /** Converts a plain Kotlin value (as used by script hosts and tests). Unknown types become text. */
        fun from(any: Any?): Value = when (any) {
            null -> Text("")
            is Value -> any
            is String -> Text(any)
            is Boolean -> Bool(any)
            is Number -> Num(any.toDouble())
            is List<*> -> ListV(any.map { from(it) })
            is Map<*, *> -> Dict(any.entries.associate { it.key.toString() to from(it.value) })
            else -> Text(any.toString())
        }
    }
}

fun formatNumber(value: Double): String = when {
    value.isNaN() -> "NaN"
    value.isInfinite() -> if (value > 0) "Infinity" else "-Infinity"
    value == floor(value) && abs(value) < 1e15 -> value.toLong().toString()
    else -> value.toString()
}

private sealed interface Seg {
    data class Key(val k: String) : Seg
    data class Idx(val i: Int) : Seg
}

private fun segments(path: String): List<Seg>? {
    val out = mutableListOf<Seg>()
    val sb = StringBuilder()
    fun flush() {
        if (sb.isNotEmpty()) {
            out += Seg.Key(sb.toString())
            sb.clear()
        }
    }
    var i = 0
    while (i < path.length) {
        val c = path[i]
        when (c) {
            '.' -> {
                flush()
                i++
            }
            '[' -> {
                flush()
                val end = path.indexOf(']', i)
                if (end < 0) return null
                val n = path.substring(i + 1, end).trim().toIntOrNull() ?: return null
                out += Seg.Idx(n)
                i = end + 1
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    flush()
    return out
}

/**
 * Looks inside a value with a path such as `items[0].name` or `user.address.city`.
 * Negative indexes count from the end (`items[-1]`). Null when anything on the way is missing.
 */
fun Value.at(path: String): Value? {
    val segs = segments(path.trim()) ?: return null
    var cur: Value = this
    for (seg in segs) {
        cur = when (seg) {
            is Seg.Key -> when (val c = cur) {
                is Value.Dict -> c.entries[seg.k] ?: return null
                is Value.ListV -> {
                    val idx = seg.k.toIntOrNull() ?: return null
                    c.items.getOrNull(idx) ?: return null
                }
                else -> return null
            }
            is Seg.Idx -> when (val c = cur) {
                is Value.ListV -> {
                    val idx = if (seg.i < 0) c.items.size + seg.i else seg.i
                    c.items.getOrNull(idx) ?: return null
                }
                else -> return null
            }
        }
    }
    return cur
}
