package com.androx.app.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Builds a params object from plain Kotlin values. Null values are left out; enums are stored by name. */
fun params(vararg pairs: Pair<String, Any?>): JsonObject {
    val map = LinkedHashMap<String, JsonElement>()
    for ((k, v) in pairs) {
        if (v != null) map[k] = toJsonElement(v)
    }
    return JsonObject(map)
}

private fun toJsonElement(v: Any): JsonElement = when (v) {
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Double -> JsonPrimitive(v)
    is Float -> JsonPrimitive(v.toDouble())
    is Enum<*> -> JsonPrimitive(v.name)
    is Iterable<*> -> JsonArray(v.filterNotNull().map { toJsonElement(it) })
    else -> JsonPrimitive(v.toString())
}

/** Typed, forgiving reader for a params object. Missing or wrong-typed keys fall back to the default. */
class P(val o: JsonObject) {
    private fun prim(k: String): JsonPrimitive? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    fun has(k: String): Boolean = o[k] != null && o[k] !is JsonNull

    fun str(k: String, d: String = ""): String = prim(k)?.content ?: d

    fun int(k: String, d: Int = 0): Int = prim(k)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: d

    fun long(k: String, d: Long = 0L): Long = prim(k)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: d

    fun double(k: String, d: Double = 0.0): Double = prim(k)?.doubleOrNull ?: d

    fun bool(k: String, d: Boolean = false): Boolean = prim(k)?.let { it.booleanOrNull ?: when (it.content.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    } } ?: d

    fun ints(k: String): List<Int> =
        (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.let { p -> p.intOrNull } }.orEmpty()

    fun strings(k: String): List<String> =
        (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }.orEmpty()

    fun <E : Enum<E>> enum(k: String, values: Array<E>, d: E): E {
        val name = prim(k)?.content ?: return d
        return values.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: d
    }

    fun obj(k: String): JsonObject? = o[k] as? JsonObject
}
