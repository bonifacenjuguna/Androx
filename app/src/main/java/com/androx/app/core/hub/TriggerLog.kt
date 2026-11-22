package com.androx.app.core.hub

import com.androx.app.core.Event
import com.androx.app.core.spec.SpecCatalog
import kotlinx.serialization.Serializable

/** One line of the Trigger log: an event Androx received and what became of it, even when no Flow ran (spec §5.6). */
@Serializable
data class TriggerLogEntry(
    val at: Long,
    val typeId: String,
    val title: String,
    /** One line per Flow that listens for this event, such as "Night mode: ran: success". Empty when none did. */
    val results: List<String> = emptyList(),
)

@Serializable
data class TriggerLogFile(val entries: List<TriggerLogEntry> = emptyList())

object TriggerLogs {
    const val CAP = 200

    /** Newest first, never more than [CAP]. */
    fun add(file: TriggerLogFile, entry: TriggerLogEntry, cap: Int = CAP): TriggerLogFile =
        file.copy(entries = (listOf(entry) + file.entries).take(cap))

    /** "Screen (screen.on=false)" for the log line title. */
    fun describe(event: Event, catalog: SpecCatalog = SpecCatalog.default): String {
        val name = catalog.triggers[event.typeId]?.name ?: event.typeId
        if (event.values.isEmpty()) return name
        return name + event.values.entries.take(3).joinToString(prefix = " (", postfix = ")") { "${it.key}=${it.value.render().take(30)}" }
    }
}
