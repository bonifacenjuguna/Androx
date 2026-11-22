package com.androx.app

import com.androx.app.core.Event
import com.androx.app.core.Flow
import com.androx.app.core.RunLimits
import com.androx.app.core.TriggerIds
import com.androx.app.core.ConcurrencyPolicy
import com.androx.app.core.engine.FlowEngine
import com.androx.app.data.FlowRepository
import com.androx.app.data.HistoryRepository
import com.androx.app.data.SettingsRepository
import com.androx.app.data.StatsRepository
import com.androx.app.data.VariableRepository
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.RunSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Glue between the pure engine and the app's storage: waits for data, applies run rules, runs, records history. */
class AutomationRunner(
    private val flows: FlowRepository,
    private val history: HistoryRepository,
    private val variables: VariableRepository,
    private val settings: SettingsRepository,
    private val engine: FlowEngine,
    private val stats: StatsRepository,
    private val activity: FlowActivity,
) {
    private val timedTypes = setOf(TriggerIds.SCHEDULE, TriggerIds.INTERVAL, TriggerIds.ONCE)
    private val active: MutableSet<String> get() = activity.running
    private val queues = ConcurrentHashMap<String, Mutex>()

    private suspend fun awaitData() {
        flows.awaitLoaded()
        history.awaitLoaded()
        variables.awaitLoaded()
    }

    /** Manual run. Works for enabled and disabled Flows alike. Returns null if the id no longer exists or is in Trash. */
    suspend fun runManually(id: String): ExecutionRecord? {
        awaitData()
        val flow = flows.get(id)?.takeIf { !it.inTrash } ?: return null
        return runAndRecord(flow, RunSource.MANUAL)
    }

    /** Runs a Flow because of an event, such as a tap on a notification button. Works for turned-off Flows too, like a manual run. */
    suspend fun runForEvent(id: String, event: Event): ExecutionRecord? {
        awaitData()
        val flow = flows.get(id)?.takeIf { !it.inTrash } ?: return null
        return runAndRecord(flow, RunSource.MANUAL, event)
    }

    /** A dry run for the editor: describes every step, changes nothing, and is not added to History. */
    suspend fun simulate(id: String): ExecutionRecord? {
        awaitData()
        val flow = flows.get(id)?.takeIf { !it.inTrash } ?: return null
        return engine.simulate(flow)
    }

    /** Called by the alarm receiver. Ignores Flows that were disabled or re-configured since scheduling. */
    suspend fun runScheduled(id: String): ExecutionRecord? {
        awaitData()
        val flow = flows.get(id) ?: return null
        if (!flow.enabled || flow.inTrash) return null
        val timed = flow.triggers.firstOrNull { it.enabled && it.typeId in timedTypes } ?: return null
        val record = runAndRecord(flow, RunSource.SCHEDULE)
        // A one-time Flow has done its job: turn it off so it isn't offered as "scheduled" any more.
        if (timed.typeId == TriggerIds.ONCE) flows.setEnabled(id, false)
        return record
    }

    /** Called when the device has finished starting. Runs every enabled "when the device starts" Flow. */
    suspend fun runOnBoot(): List<ExecutionRecord> {
        awaitData()
        return flows.state.value.flows
            .filter { f -> f.enabled && !f.inTrash && f.triggers.any { it.enabled && it.typeId == TriggerIds.BOOT } }
            .map { runAndRecord(it, RunSource.BOOT) }
    }

    private suspend fun runAndRecord(flow: Flow, source: RunSource, event: Event? = null): ExecutionRecord {
        val now = System.currentTimeMillis()
        val limit = flow.runOptions.runLimitPerHour
        if (RunLimits.exceeded(history.state.value, flow.id, limit, now)) {
            return store(skipped(flow, source, now, "Run limit reached: at most $limit per hour."))
        }
        return when (flow.runOptions.concurrency) {
            ConcurrencyPolicy.SKIP -> {
                if (!active.add(flow.id)) return store(skipped(flow, source, now, "This Flow is already running."))
                try {
                    store(engine.execute(flow, source, event))
                } finally {
                    active.remove(flow.id)
                }
            }
            // REPLACE joins the queue until the trigger hub can cancel a running Flow safely.
            ConcurrencyPolicy.QUEUE, ConcurrencyPolicy.REPLACE ->
                queues.getOrPut(flow.id) { Mutex() }.withLock { store(engine.execute(flow, source, event)) }
        }
    }

    private fun skipped(flow: Flow, source: RunSource, now: Long, why: String) = ExecutionRecord(
        id = UUID.randomUUID().toString(),
        automationId = flow.id,
        automationName = flow.name,
        startedAt = now,
        durationMs = 0L,
        status = ExecutionStatus.SKIPPED,
        source = source,
        message = why,
        logs = emptyList(),
    )

    private suspend fun store(record: ExecutionRecord): ExecutionRecord {
        history.add(record, settings.settings.value.historyLimit)
        // Daily stats are kept forever (history is trimmed), so streaks and insights stay accurate.
        runCatching { stats.record(record.automationId, record.startedAt, record.status, record.durationMs) }
        return record
    }
}
