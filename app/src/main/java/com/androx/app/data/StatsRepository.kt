package com.androx.app.data

import com.androx.app.core.Stats
import com.androx.app.core.StatsFile
import com.androx.app.model.ExecutionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Daily stats on disk (`stats.json`). Written on every run, never trimmed. */
class StatsRepository(dir: File, scope: CoroutineScope) {
    private val store = PersistentState(
        JsonFile(File(dir, "stats.json"), StatsFile.serializer()) { StatsFile() },
        scope,
        StatsFile(),
    )
    val state: StateFlow<StatsFile> get() = store.state

    suspend fun awaitLoaded() = store.awaitLoaded()

    suspend fun record(flowId: String, atMillis: Long, status: ExecutionStatus, durationMs: Long) {
        store.update { Stats.record(it, flowId, atMillis, status, durationMs) }
    }

    suspend fun clear() {
        store.update { StatsFile() }
    }
}
