package com.androx.app.data

import com.androx.app.core.hub.TriggerLogEntry
import com.androx.app.core.hub.TriggerLogFile
import com.androx.app.core.hub.TriggerLogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** The Trigger log on disk (`trigger_log.json`), newest first, capped at 200 lines. */
class TriggerLogRepository(dir: File, private val scope: CoroutineScope) {
    private val store = PersistentState(
        JsonFile(File(dir, "trigger_log.json"), TriggerLogFile.serializer()) { TriggerLogFile() },
        scope,
        TriggerLogFile(),
    )
    val state: StateFlow<TriggerLogFile> get() = store.state

    /** Safe to call from anywhere; the write happens in the background. */
    fun add(entry: TriggerLogEntry) {
        scope.launch(Dispatchers.IO) { store.update { TriggerLogs.add(it, entry) } }
    }

    suspend fun clear() {
        store.update { TriggerLogFile() }
    }
}
