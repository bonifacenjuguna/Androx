package com.androx.app.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Parameters of every trigger, constraint and action are a plain JSON object (spec §5.2 generic carriers).
 * These helpers read them forgivingly, so a file written by a newer or older build never crashes a screen.
 */
val EmptyParams = JsonObject(emptyMap())

fun JsonObject.str(key: String, default: String = ""): String {
    val e = this[key] ?: return default
    return if (e is JsonPrimitive && e !is JsonNull) e.content else default
}

fun JsonObject.int(key: String, default: Int = 0): Int {
    val e = this[key] as? JsonPrimitive ?: return default
    return e.intOrNull ?: e.doubleOrNull?.toInt() ?: e.content.trim().toIntOrNull() ?: default
}

fun JsonObject.long(key: String, default: Long = 0L): Long {
    val e = this[key] as? JsonPrimitive ?: return default
    return e.longOrNull ?: e.doubleOrNull?.toLong() ?: e.content.trim().toLongOrNull() ?: default
}

fun JsonObject.bool(key: String, default: Boolean = false): Boolean {
    val e = this[key] as? JsonPrimitive ?: return default
    return e.booleanOrNull ?: when (e.content.trim().lowercase()) {
        "true", "yes", "on", "1" -> true
        "false", "no", "off", "0" -> false
        else -> default
    }
}

fun JsonObject.intList(key: String): List<Int> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()

/** Reads an enum stored by name (case-insensitive). Unknown names fall back to [default]. */
inline fun <reified E : Enum<E>> JsonObject.enum(key: String, default: E): E {
    val name = str(key, "")
    return enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: default
}

fun JsonObject.has(key: String): Boolean = this[key].let { it != null && it !is JsonNull }

/** Builds parameters from plain Kotlin values. Supports text, numbers, booleans, enums, int lists and nested JSON. */
fun params(vararg pairs: Pair<String, Any?>): JsonObject {
    val map = LinkedHashMap<String, JsonElement>()
    for ((k, v) in pairs) {
        map[k] = when (v) {
            null -> JsonNull
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Int -> JsonPrimitive(v)
            is Long -> JsonPrimitive(v)
            is Double -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Enum<*> -> JsonPrimitive(v.name)
            is List<*> -> JsonArray(v.map { item ->
                when (item) {
                    is Int -> JsonPrimitive(item)
                    is Long -> JsonPrimitive(item)
                    is String -> JsonPrimitive(item)
                    is Boolean -> JsonPrimitive(item)
                    is JsonElement -> item
                    else -> JsonPrimitive(item.toString())
                }
            })
            else -> JsonPrimitive(v.toString())
        }
    }
    return JsonObject(map)
}
