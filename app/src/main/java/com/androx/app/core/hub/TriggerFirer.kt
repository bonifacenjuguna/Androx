package com.androx.app.core.hub

import com.androx.app.core.ConstraintGroup
import com.androx.app.core.Event
import com.androx.app.core.Flow
import com.androx.app.core.TriggerEntry
import com.androx.app.core.engine.CheckResult
import com.androx.app.core.spec.SpecCatalog
import com.androx.app.core.state.DeviceStateReader
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.RunSource

/** What happened when a trigger fired: a plain-English outcome for the Trigger log, and the run if there was one. */
data class FireResult(val outcome: String, val record: ExecutionRecord? = null)

/**
 * Everything that sits between "a trigger matched" and "the Flow runs" (spec §15.1): a loop guard, the trigger's own
 * constraints, the cooldown, and the hold time. Pure Kotlin around lambdas, so each rule is unit tested.
 */
class TriggerFirer(
    private val catalog: SpecCatalog,
    private val cooldown: CooldownGuard,
    private val holds: HoldTracker,
    private val loops: EventLoopGuard,
    private val checkConstraints: (ConstraintGroup?, Event?, RunSource) -> CheckResult,
    private val state: () -> DeviceStateReader,
    private val flowById: (String) -> Flow?,
    private val run: suspend (Flow, RunSource, Event?) -> ExecutionRecord?,
    private val log: (TriggerLogEntry) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun fire(flow: Flow, entry: TriggerEntry, event: Event, source: RunSource): FireResult {
        val key = "${flow.id}/${entry.id}"

        val gate = entry.constraints
        if (gate != null && !gate.isEmpty) {
            val r = checkConstraints(gate, event, source)
            if (!r.passed) return FireResult("skipped: ${r.why}")
        }
        if (!cooldown.ready(key, entry.cooldownSeconds, clock())) {
            return FireResult("skipped: cooldown, it ran less than ${entry.cooldownSeconds} s ago")
        }
        val check = catalog.triggers[entry.typeId]?.holdCheck
        if (entry.holdMinutes > 0 && check != null && source == RunSource.EVENT) {
            val started = holds.begin(
                key, entry.holdMinutes,
                stillHolds = { check(entry.params, state()) },
                onHeld = { finishHold(flow.id, flow.name, entry, event, key) },
            )
            return FireResult(if (started) "waiting ${entry.holdMinutes} min to see if it still holds" else "already waiting to see if it still holds")
        }
        return start(flow, event, source, key)
    }

    private suspend fun start(flow: Flow, event: Event, source: RunSource, key: String): FireResult {
        if (!loops.allow(flow.id, clock())) {
            return FireResult("stopped: this Flow keeps starting itself, so it is paused for a minute")
        }
        cooldown.mark(key, clock())
        val record = run(flow, source, event)
        return FireResult("ran: ${record?.status?.name?.lowercase() ?: "nothing to run"}", record)
    }

    private suspend fun finishHold(flowId: String, flowName: String, entry: TriggerEntry, event: Event, key: String) {
        val flow = flowById(flowId)?.takeIf { it.enabled && !it.inTrash }
        val outcome = if (flow == null) {
            FireResult("dropped: the Flow was turned off while waiting")
        } else {
            start(flow, event, RunSource.EVENT, key)
        }
        log(TriggerLogEntry(clock(), entry.typeId, "Held for ${entry.holdMinutes} min", listOf("$flowName: ${outcome.outcome}")))
    }
}
