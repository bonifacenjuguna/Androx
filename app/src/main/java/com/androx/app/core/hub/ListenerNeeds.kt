package com.androx.app.core.hub

import com.androx.app.core.FlowAdapter
import com.androx.app.core.FlowsFile
import com.androx.app.core.Trash
import com.androx.app.core.TriggerIds
import com.androx.app.core.spec.SpecCatalog
import com.androx.app.model.Trigger

/** A Flow trigger that can't work without the Listener, for Settings and the Health card ("what needs it and why"). */
data class ListenerNeed(val flowId: String, val flowName: String, val entryId: String, val typeId: String, val triggerName: String)

object ListenerNeeds {
    fun needed(file: FlowsFile, catalog: SpecCatalog = SpecCatalog.default): List<ListenerNeed> =
        Trash.active(file.flows).filter { it.enabled }.flatMap { flow ->
            flow.triggers.filter { it.enabled && catalog.triggers[it.typeId]?.needsListener == true }
                .map { ListenerNeed(flow.id, flow.name, it.id, it.typeId, catalog.triggers.nameOf(it.typeId)) }
        }

    /** The event types the Listener has to register receivers for, nothing more (the battery cost grows with this list). */
    fun eventTypes(file: FlowsFile, catalog: SpecCatalog = SpecCatalog.default): Set<String> = needed(file, catalog).map { it.typeId }.toSet()
}

/** A time-based trigger that needs an alarm. */
data class TimedEntry(val flowId: String, val entryId: String, val trigger: Trigger)

object TimedEntries {
    /** Every enabled Schedule, Interval and Once trigger of every enabled Flow: a Flow can have several. */
    fun from(file: FlowsFile): List<TimedEntry> =
        Trash.active(file.flows).filter { it.enabled }.flatMap { flow ->
            flow.triggers.filter { it.enabled && it.typeId in TriggerIds.TIMED }.map { TimedEntry(flow.id, it.id, FlowAdapter.toTrigger(it)) }
        }
}
