package com.androx.app.data

import com.androx.app.core.Flow
import com.androx.app.core.FlowsFile
import com.androx.app.core.Trash
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The source of truth for Flows and folders (`flows.json`). Loads in the background; on the first launch after
 * updating from v0.0.1 it migrates `automations.json` (see [FlowStorage]). Flows that have been in Trash for more than
 * 30 days are removed on load.
 *
 * Listeners run synchronously on the thread that changed the data, before [awaitLoaded] returns for the first time, so
 * anything derived from the data (alarms, the screens' lists) is never one step behind.
 */
class FlowRepository(
    dir: File,
    scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val storage = FlowStorage(dir)
    private val _state = MutableStateFlow(FlowsFile())
    val state: StateFlow<FlowsFile> = _state.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val ready = CompletableDeferred<Unit>()
    private val writeMutex = Mutex()
    private val listeners = CopyOnWriteArrayList<(FlowsFile) -> Unit>()
    private val lock = Any()

    /** True when `flows.json` was damaged and the last-good copy (or an empty file) was used. */
    @Volatile
    var recoveredFromBackup: Boolean = false
        private set

    init {
        scope.launch(Dispatchers.IO) {
            var file = runCatching { storage.loadOrMigrate() }.getOrDefault(FlowsFile())
            recoveredFromBackup = storage.hadRecovery
            val expired = Trash.expired(file.flows, clock())
            if (expired.isNotEmpty()) {
                val gone = expired.map { it.id }.toSet()
                file = file.copy(flows = file.flows.filterNot { it.id in gone })
                runCatching { storage.save(file) }
            }
            synchronized(lock) {
                _state.value = file
                notifyListeners(file)
                _loaded.value = true
            }
            ready.complete(Unit)
        }
    }

    suspend fun awaitLoaded() = ready.await()

    /** Calls [listener] after every change, and once right away if the data has already loaded. */
    fun observe(listener: (FlowsFile) -> Unit) {
        synchronized(lock) {
            listeners += listener
            if (_loaded.value) listener(_state.value)
        }
    }

    private fun notifyListeners(file: FlowsFile) {
        for (l in listeners) runCatching { l(file) }
    }

    fun get(id: String): Flow? = _state.value.flows.firstOrNull { it.id == id }

    suspend fun update(transform: (FlowsFile) -> FlowsFile): FlowsFile {
        ready.await()
        return writeMutex.withLock {
            val next = transform(_state.value)
            _state.value = next
            withContext(Dispatchers.IO) { storage.save(next) }
            notifyListeners(next)
            next
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val now = clock()
        update { f -> f.copy(flows = f.flows.map { if (it.id == id) it.copy(enabled = enabled, updatedAt = now) else it }) }
    }

    /**
     * Turns one trigger off (a finished "Once"). The Flow itself is switched off too when that was its last enabled
     * trigger, like a one-time automation always did.
     */
    suspend fun disableTrigger(flowId: String, entryId: String) {
        val now = clock()
        update { f ->
            f.copy(
                flows = f.flows.map { flow ->
                    if (flow.id != flowId) {
                        flow
                    } else {
                        val triggers = flow.triggers.map { if (it.id == entryId) it.copy(enabled = false) else it }
                        flow.copy(triggers = triggers, enabled = flow.enabled && triggers.any { it.enabled }, updatedAt = now)
                    }
                },
            )
        }
    }

    suspend fun moveToTrash(id: String) {
        val now = clock()
        update { f -> f.copy(flows = f.flows.map { if (it.id == id) Trash.moveToTrash(it, now) else it }) }
    }

    suspend fun restore(id: String) {
        val now = clock()
        update { f -> f.copy(flows = f.flows.map { if (it.id == id) Trash.restore(it, now) else it }) }
    }

    /** Permanently removes a Flow (only from Trash screens). */
    suspend fun deleteForever(id: String) {
        update { f -> f.copy(flows = f.flows.filterNot { it.id == id }) }
    }
}
