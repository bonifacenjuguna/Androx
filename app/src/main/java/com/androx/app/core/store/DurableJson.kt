package com.androx.app.core.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

data class Loaded<T>(val value: T, val recovered: Boolean)

/**
 * A typed JSON document on top of [SafeFile]. Unknown keys are ignored, so a file written by a newer version
 * still opens; known fields keep their defaults, so a file from an older version opens too.
 */
class DurableJson<T>(
    file: File,
    private val serializer: KSerializer<T>,
    private val fallback: () -> T,
    private val json: Json = DEFAULT_JSON,
) {
    companion object {
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }
    }

    private val safe = SafeFile(file) { text ->
        try {
            json.decodeFromString(serializer, text)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun read(): Loaded<T> {
        val r = safe.read()
        val text = r.text ?: return Loaded(fallback(), r.recovered)
        return try {
            Loaded(json.decodeFromString(serializer, text), r.recovered)
        } catch (e: Exception) {
            Loaded(fallback(), true)
        }
    }

    fun write(value: T) = safe.write(json.encodeToString(serializer, value))

    fun hasDamage(): Boolean = safe.hasDamage()
}
