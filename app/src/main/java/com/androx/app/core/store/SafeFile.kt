package com.androx.app.core.store

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class LoadSource { MAIN, BACKUP, NONE }

/** The text read from a [SafeFile] and where it came from. [recovered] is true when the main file was damaged. */
data class LoadResult(val text: String?, val source: LoadSource, val recovered: Boolean)

/**
 * Crash-safe file: every write goes to a temp file, is flushed to disk, keeps the previous good version as
 * `<name>.bak`, and is then moved into place. A damaged file is kept as `<name>.corrupt` and the last good copy is used.
 * [isValid] decides whether text counts as good (for JSON stores: "it parses").
 */
class SafeFile(val file: File, private val isValid: (String) -> Boolean = { true }) {
    val backup: File = File(file.parentFile, file.name + ".bak")
    private val tmp: File = File(file.parentFile, file.name + ".tmp")
    private val corrupt: File = File(file.parentFile, file.name + ".corrupt")

    @Synchronized
    fun write(text: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (file.exists()) {
            val current = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
            if (current != null && current.isNotEmpty() && isValid(current)) {
                runCatching { file.copyTo(backup, overwrite = true) }
            }
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized
    fun read(): LoadResult {
        if (file.exists()) {
            val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
            if (text != null && isValid(text)) return LoadResult(text, LoadSource.MAIN, false)
            runCatching { file.copyTo(corrupt, overwrite = true) }
            if (backup.exists()) {
                val old = runCatching { backup.readText(StandardCharsets.UTF_8) }.getOrNull()
                if (old != null && isValid(old)) return LoadResult(old, LoadSource.BACKUP, true)
            }
            return LoadResult(null, LoadSource.NONE, true)
        }
        // The main file is missing: a crash between "move old away" and "move new in" can leave only a backup.
        if (backup.exists()) {
            val old = runCatching { backup.readText(StandardCharsets.UTF_8) }.getOrNull()
            if (old != null && isValid(old)) return LoadResult(old, LoadSource.BACKUP, true)
        }
        return LoadResult(null, LoadSource.NONE, false)
    }

    fun hasDamage(): Boolean = corrupt.exists()
}
