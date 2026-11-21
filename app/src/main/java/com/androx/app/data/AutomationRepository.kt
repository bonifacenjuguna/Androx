package com.androx.app.data

import com.androx.app.core.FlowAdapter
import com.androx.app.core.FlowsFile
import com.androx.app.model.Automation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the v0.0.1 screens talk to. It looks like the old repository, but everything is stored as Flows underneath
 * ([FlowRepository]); deleting moves a Flow to Trash instead of erasing it. Replaced as the new screens land.
 */
class AutomationRepository(private val flows: FlowRepository) {
    private val _state = MutableStateFlow<List<Automation>>(emptyList())
    val state: StateFlow<List<Automation>> = _state.asStateFlow()
    val loaded: StateFlow<Boolean> get() = flows.loaded

    init {
        // Updated synchronously with every change, so `state.value` is right as soon as loading completes.
        flows.observe { file: FlowsFile -> _state.value = FlowAdapter.activeAutomations(file) }
    }

    suspend fun awaitLoaded() = flows.awaitLoaded()

    fun get(id: String): Automation? = _state.value.firstOrNull { it.id == id }

    suspend fun upsert(automation: Automation) {
        flows.update { f -> f.copy(flows = FlowAdapter.upsert(f.flows, automation)) }
    }

    suspend fun addAll(items: List<Automation>) = upsertAll(items)

    /** Inserts new Flows and replaces ones that already exist (matched by id). */
    suspend fun upsertAll(items: List<Automation>) {
        flows.update { f -> f.copy(flows = items.fold(f.flows) { acc, item -> FlowAdapter.upsert(acc, item) }) }
    }

    /** Moves the Flow to Trash. It can be restored for 30 days. */
    suspend fun delete(id: String) = flows.moveToTrash(id)

    suspend fun setEnabled(id: String, enabled: Boolean) = flows.setEnabled(id, enabled)
}
