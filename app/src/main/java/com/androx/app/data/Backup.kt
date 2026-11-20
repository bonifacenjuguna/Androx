package com.androx.app.data

import com.androx.app.model.Automation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The file written by Settings → Back up. Plain JSON so people can read, diff and edit it. */
@Serializable
data class BackupFile(
    val app: String = "androx",
    val version: Int = 1,
    val exportedAt: Long = 0L,
    val automations: List<Automation> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
)

object Backup {
    private val pretty = Json(from = JsonFile.json) { prettyPrint = true }

    fun encode(automations: List<Automation>, variables: Map<String, String>, now: Long): String =
        pretty.encodeToString(BackupFile.serializer(), BackupFile(exportedAt = now, automations = automations, variables = variables))

    /** Throws if [text] isn't an Androx backup. */
    fun decode(text: String): BackupFile {
        val file = JsonFile.json.decodeFromString(BackupFile.serializer(), text)
        require(file.app == "androx") { "Not an Androx backup" }
        return file
    }
}
