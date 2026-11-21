package com.androx.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A small JSON document stored in one file.
 *
 * Safety net (spec §5.4): every write goes to `<name>.tmp` first, the previous good file is kept as
 * `<name>.bak` (the rolling last-good copy), and only then is the temp file moved into place. A crash at any
 * moment leaves either the old or the new file readable. A file that can't be parsed is kept as
 * `<name>.corrupt`, the app falls back to the last-good copy, and [hadRecovery] tells the UI so it can offer
 * the "Recover from backup" path.
 */
class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val fallback: () -> T,
) {
    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }
    }

    private val backup: File get() = File(file.parentFile, file.name + ".bak")

    /** Set when the main file was unreadable and the last-good copy (or the empty default) was used instead. */
    @Volatile
    var hadRecovery: Boolean = false
        private set

    /** True if a last-good copy exists on disk. */
    fun hasBackup(): Boolean = backup.exists()

    fun read(): T {
        hadRecovery = false
        if (!file.exists()) {
            // The main file can only be missing at first launch or if a crash hit between the two renames below.
            return if (backup.exists()) readBackup() ?: fallback() else fallback()
        }
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            runCatching { file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true) }
            hadRecovery = true
            readBackup() ?: fallback()
        }
    }

    /** Reads the rolling last-good copy; null if there is none or it is damaged too. */
    fun readBackup(): T? {
        if (!backup.exists()) return null
        return try {
            json.decodeFromString(serializer, backup.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** Replaces the main file with the last-good copy. Returns false when there is nothing usable to restore. */
    fun restoreFromBackup(): Boolean {
        val value = readBackup() ?: return false
        write(value)
        hadRecovery = false
        return true
    }

    fun write(value: T) {
        file.parentFile?.mkdirs()
        val text = json.encodeToString(serializer, value)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        // Keep the previous file as the last-good copy, but only if it was actually readable.
        if (file.exists() && isReadable(file)) {
            runCatching { file.copyTo(backup, overwrite = true) }
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("Could not save ${file.name}")
        }
    }

    private fun isReadable(f: File): Boolean = try {
        json.decodeFromString(serializer, f.readText())
        true
    } catch (e: Exception) {
        false
    }
}
