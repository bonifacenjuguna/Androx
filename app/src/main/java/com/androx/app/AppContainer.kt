package com.androx.app

import android.app.Application
import com.androx.app.core.FlowAdapter
import com.androx.app.core.engine.FlowEngine
import com.androx.app.core.engine.asValues
import com.androx.app.data.AutomationRepository
import com.androx.app.data.FlowRepository
import com.androx.app.data.HistoryRepository
import com.androx.app.data.SettingsRepository
import com.androx.app.data.StatsRepository
import com.androx.app.data.VariableRepository
import com.androx.app.platform.AlarmScheduler
import com.androx.app.platform.AndroidDeviceBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-rolled dependency container: one instance for the whole process, created by [AndroxApp]. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app)
    val scheduler = AlarmScheduler(app)
    val variables = VariableRepository(app.filesDir, scope)
    val history = HistoryRepository(app.filesDir, scope)
    val stats = StatsRepository(app.filesDir, scope)

    /** Flows and folders (`flows.json`). Migrates v0.0.1 data on the first launch after updating. */
    val flows = FlowRepository(app.filesDir, scope)

    /** The view of [flows] the current screens use. */
    val automations = AutomationRepository(flows)

    private val bridge = AndroidDeviceBridge(app)
    private val engine = FlowEngine(
        device = bridge,
        variables = variables.asValues(),
        findFlow = { id -> flows.get(id) },
    )
    val runner = AutomationRunner(flows, history, variables, settings, engine, stats)

    /** True once everything the UI needs from disk has been read. */
    val isReady: Boolean
        get() = flows.loaded.value && history.loaded.value

    init {
        // Keep the system's alarms in line with the saved Flows: once after loading (the system forgets alarms after
        // reboots, updates and force-stops) and after every change.
        flows.observe { file -> scheduler.reschedule(FlowAdapter.activeAutomations(file)) }
    }
}
