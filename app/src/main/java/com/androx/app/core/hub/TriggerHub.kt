package com.androx.app.core.hub

import com.androx.app.core.Event
import com.androx.app.core.Flow
import com.androx.app.core.TriggerEntry
import com.androx.app.core.spec.SpecCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The one place events are delivered (spec §5.6). Reads the [EventBus], finds every enabled Flow with a matching trigger
 * and hands each to [fire], which applies the guards and runs it. Every event the Listener receives is written to the
 * Trigger log, even when no Flow wanted it; quiet internal events (variable changes…) are logged only when one did.
 */
class TriggerHub(
    private val scope: CoroutineScope,
    private val bus: EventBus,
    private val flows: () -> List<Flow>,
    private val fire: suspend (Flow, TriggerEntry, Event) -> String,
    private val log: (TriggerLogEntry) -> Unit,
    private val catalog: SpecCatalog = SpecCatalog.default,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun start(): Job = scope.launch {
        bus.events.collect { event -> scope.launch { handle(event) } }
    }

    /** The Flows and trigger entries that want [event]. */
    fun matching(event: Event): List<Pair<Flow, TriggerEntry>> {
        val spec = catalog.triggers[event.typeId] ?: return emptyList()
        return flows()
            .filter { it.enabled && !it.inTrash }
            .flatMap { flow ->
                flow.triggers
                    .filter { it.enabled && it.typeId == event.typeId && spec.matches(it.params, event) }
                    .map { flow to it }
            }
    }

    suspend fun handle(event: Event) {
        val matches = matching(event)
        val results = coroutineScope {
            matches.map { (flow, entry) ->
                async { "${flow.name}: ${runCatching { fire(flow, entry, event) }.getOrElse { "failed to start: ${it.message ?: it.javaClass.simpleName}" }}" }
            }.awaitAll()
        }
        val noisy = catalog.triggers[event.typeId]?.needsListener == true
        if (matches.isNotEmpty() || noisy) {
            log(TriggerLogEntry(if (event.at == 0L) clock() else event.at, event.typeId, TriggerLogs.describe(event, catalog), results))
        }
    }
}
