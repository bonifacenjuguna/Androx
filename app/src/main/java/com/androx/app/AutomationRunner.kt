package com.androx.app

import com.androx.app.core.Event
import com.androx.app.core.Flow
import com.androx.app.core.RunLimits
import com.androx.app.core.TriggerEntry
import com.androx.app.core.TriggerIds
import com.androx.app.core.ConcurrencyPolicy
import com.androx.app.core.engine.CheckContext
import com.androx.app.core.engine.FlowEngine
import com.androx.app.core.hub.CooldownGuard
import com.androx.app.core.hub.EventBus
import com.androx.app.core.hub.EventLoopGuard
import com.androx.app.core.hub.FireResult
import com.androx.app.core.hub.FlowEvents
import com.androx.app.core.hub.HoldTracker
import com.androx.app.core.hub.TriggerFirer
import com.androx.app.core.hub.TriggerLogEntry
import com.androx.app.core.spec.SpecCatalog
import com.androx.app.core.state.DeviceStateReader
import com.androx.app.data.FlowRepository
import com.androx.app.data.HistoryRepository
import com.androx.app.data.SettingsRepository
import com.androx.app.data.StatsRepository
import com.androx.app.data.VariableRepository
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.RunSource
import kotlinx.coroutines.CoroutineScope
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
    private val bus: EventBus,
    stateReader: DeviceStateReader,
    triggerLog: (TriggerLogEntry) -> Unit,
    scope: CoroutineScope,
) {
    private val active: MutableSet<String> get() = activity.running
    private val queues = ConcurrentHashMap<String, Mutex>()

    /** Cooldown, hold time, loop guard and per-trigger constraints in front of every trigger-started run. */
    private val firer = TriggerFirer(
        catalog = SpecCatalog.default,
        cooldown = CooldownGuard(),
        holds = HoldTracker(scope),
        loops = EventLoopGuard(),
        checkConstraints = { group, event, source -> engine.checkConstraints(group, CheckContext(source = source, event = event)) },
        state = { stateReader },
        flowById = { id -> flows.get(id) },
        run = { flow, source, event -> runAndRecord(flow, source, event) },
        log = triggerLog,
    )

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

    /** "Test fire" on a trigger: runs the Flow as if that trigger had just fired, with sample event values. */
    suspend fun testFire(flowId: String, entryId: String): FireResult? {
        awaitData()
        val flow = flows.get(flowId)?.takeIf { !it.inTrash } ?: return null
        val entry = flow.triggers.firstOrNull { it.id == entryId } ?: return null
        return firer.fire(flow, entry, FlowEvents.sample(entry.typeId), RunSource.MANUAL)
    }

    /** Called by the trigger hub for an event that matched [entry]. */
    suspend fun fireFromEvent(flow: Flow, entry: TriggerEntry, event: Event): FireResult = firer.fire(flow, entry, event, RunSource.EVENT)

    /**
     * Called by the alarm receiver with the id of the trigger that is due (older alarms carry the Flow's id instead).
     * Ignores Flows and triggers that were turned off or changed since the alarm was armed.
     */
    suspend fun runScheduled(id: String): ExecutionRecord? {
        awaitData()
        val found = findTimed(id) ?: return null
        val (flow, entry) = found
        if (!flow.enabled || flow.inTrash || !entry.enabled) return null
        val result = firer.fire(flow, entry, Event(entry.typeId, at = System.currentTimeMillis()), RunSource.SCHEDULE)
        // A one-time trigger has done its job: turn it off so it isn't offered as "scheduled" any more.
        if (entry.typeId == TriggerIds.ONCE) flows.disableTrigger(flow.id, entry.id)
        return result.record
    }

    private fun findTimed(id: String): Pair<Flow, TriggerEntry>? {
        val all = flows.state.value.flows
        for (flow in all) {
            flow.triggers.firstOrNull { it.id == id && it.typeId in TriggerIds.TIMED }?.let { return flow to it }
        }
        val legacy = all.firstOrNull { it.id == id } ?: return null
        val entry = legacy.triggers.firstOrNull { it.enabled && it.typeId in TriggerIds.TIMED } ?: return null
        return legacy to entry
    }

    /** Called when the device has finished starting. Runs every enabled "when the device starts" Flow. */
    suspend fun runOnBoot(): List<ExecutionRecord> {
        awaitData()
        val results = ArrayList<ExecutionRecord>()
        for (flow in flows.state.value.flows.filter { it.enabled && !it.inTrash }) {
            for (entry in flow.triggers.filter { it.enabled && it.typeId == TriggerIds.BOOT }) {
                firer.fire(flow, entry, Event(TriggerIds.BOOT, at = System.currentTimeMillis()), RunSource.BOOT).record?.let { results += it }
            }
        }
        return results
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
            // REPLACE joins the queue until the hub can cancel a running Flow safely.
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
        // Other Flows may be waiting for this one to finish ("Flow finished" trigger).
        bus.emit(FlowEvents.finished(record))
        return record
    }
}
