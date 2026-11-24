package com.androx.app

import android.app.Application
import com.androx.app.core.FlowAdapter
import com.androx.app.core.engine.NotifyingValueStore
import com.androx.app.core.hub.EventBus
import com.androx.app.core.hub.FlowEvents
import com.androx.app.core.hub.TimedEntries
import com.androx.app.core.hub.TriggerHub
import com.androx.app.core.net.NetworkGate
import com.androx.app.core.net.NetworkServices
import com.androx.app.core.net.PollRunner
import com.androx.app.core.engine.FlowEngine
import com.androx.app.core.engine.asValues
import com.androx.app.data.AutomationRepository
import com.androx.app.data.FlowRepository
import com.androx.app.data.HistoryRepository
import com.androx.app.data.InternetSettingsRepository
import com.androx.app.data.PollStateRepository
import com.androx.app.data.RequestLogRepository
import com.androx.app.data.VaultRepository
import com.androx.app.data.SettingsRepository
import com.androx.app.data.StatsRepository
import com.androx.app.data.TriggerLogRepository
import com.androx.app.data.VariableRepository
import com.androx.app.platform.AlarmScheduler
import com.androx.app.core.state.CompositeStateReader
import com.androx.app.core.state.LegacyStateReader
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.androx.app.platform.AndroidDeviceBridge
import com.androx.app.platform.AndroidNetworkProbe
import com.androx.app.platform.AskNotifier
import com.androx.app.platform.KeystoreCipher
import com.androx.app.platform.OkHttpPort
import com.androx.app.platform.AndroidStateReader
import com.androx.app.platform.AndroidSystemActions
import com.androx.app.platform.ForegroundTracker
import com.androx.app.platform.ListenerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Hand-rolled dependency container: one instance for the whole process, created by [AndroxApp]. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app)
    val scheduler = AlarmScheduler(app)
    val variables = VariableRepository(app.filesDir, scope)
    val history = HistoryRepository(app.filesDir, scope)
    val stats = StatsRepository(app.filesDir, scope)
    val triggerLog = TriggerLogRepository(app.filesDir, scope)

    /** Every event enters here: system broadcasts, the Listener, notification buttons, finished Flows, changed variables. */
    val bus = EventBus()

    /** Flows and folders (`flows.json`). Migrates v0.0.1 data on the first launch after updating. */
    val flows = FlowRepository(app.filesDir, scope)

    /** The view of [flows] the current screens use. */
    val automations = AutomationRepository(flows)

    private val bridge = AndroidDeviceBridge(app)

    /** Knows whether an Androx screen is showing, so "Open link" can fall back to a tappable notification. */
    private val foreground = ForegroundTracker().also { app.registerActivityLifecycleCallbacks(it) }

    /** Device, apps and notification actions; also answers "which special access is granted?" for the pickers. */
    val system = AndroidSystemActions(app, foreground)

    /** Which Flows are running and how often each ran; also lets a Flow switch another on or off. */
    private val activity = FlowActivity(flows, history)

    // ── internet (spec §14): rules, secrets and the one gate every request goes through ──

    /** The internet rules. Off until the person turns it on; "Block internet" stops everything at once. */
    val internetSettings = InternetSettingsRepository(app.filesDir)

    /** Every request, ping and lookup Androx made, without secrets. */
    val requestLog = RequestLogRepository(app.filesDir, scope) { internetSettings.state.value.logRetentionDays }

    /** API keys and tokens, encrypted with a key that never leaves the Keystore. */
    val vault = VaultRepository(app.filesDir, KeystoreCipher())

    private fun onWifi(): Boolean {
        val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private val gate = NetworkGate(
        settings = { internetSettings.state.value },
        onWifi = ::onWifi,
        http = OkHttpPort(),
        probe = AndroidNetworkProbe(),
        recent = { requestLog.state.value.entries },
        log = requestLog::add,
        onAsk = AskNotifier(app)::ask,
    )

    private val poller = PollRunner(gate, vault.reader, PollStateRepository(app.filesDir))

    private val stateReader = CompositeStateReader(AndroidStateReader(app), LegacyStateReader(bridge))

    private val engine = FlowEngine(
        device = bridge,
        // Saved variables, reporting real changes so a Flow can wait for "Variable changed".
        variables = NotifyingValueStore(variables.asValues()) { name, old, new -> bus.emit(FlowEvents.variableChanged(name, old, new)) },
        findFlow = { id -> flows.get(id) },
        state = stateReader,
        flowInfo = activity,
        flowControl = activity,
        system = system,
        net = NetworkServices(gate, vault.reader),
    )
    val runner = AutomationRunner(flows, history, variables, settings, engine, stats, activity, bus, stateReader, triggerLog::add, scope, poller)

    /** Finds the Flows that want each event and starts them, behind the cooldown, hold time and loop guards. */
    val hub = TriggerHub(
        scope = scope,
        bus = bus,
        flows = { flows.state.value.flows },
        fire = { flow, entry, event -> runner.fireFromEvent(flow, entry, event).outcome },
        log = triggerLog::add,
    )

    /** The opt-in always-on Listener. */
    val listener = ListenerController(app, settings)

    /** Arms one alarm per time-based trigger of every enabled Flow. */
    fun rearmAlarms() = scheduler.rescheduleEntries(TimedEntries.from(flows.state.value))

    /** True once everything the UI needs from disk has been read. */
    val isReady: Boolean
        get() = flows.loaded.value && history.loaded.value

    init {
        // Keep the system's alarms in line with the saved Flows: once after loading (the system forgets alarms after
        // reboots, updates and force-stops) and after every change.
        flows.observe { file ->
            scheduler.rescheduleEntries(TimedEntries.from(file))
            listener.sync(file)
        }
        hub.start()
        // Turning the Listener on or off in Settings takes effect straight away.
        scope.launch {
            settings.settings.map { it.listenerEnabled }.distinctUntilChanged().collect { listener.apply() }
        }
    }
}
