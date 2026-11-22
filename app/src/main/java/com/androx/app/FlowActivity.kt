package com.androx.app

import com.androx.app.core.engine.FlowControl
import com.androx.app.core.engine.FlowInfo
import com.androx.app.data.FlowRepository
import com.androx.app.data.HistoryRepository
import com.androx.app.model.ExecutionStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * What is going on with the Flows right now: which are running, when each last ran, how often today. Feeds the
 * "Flow state" check, and lets a Flow switch another one on or off.
 */
class FlowActivity(
    private val flows: FlowRepository,
    private val history: HistoryRepository,
) : FlowInfo, FlowControl {
    /** Ids of Flows currently running (used to skip a second run of the same Flow). */
    val running: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun isRunning(flowId: String): Boolean = flowId in running

    /** History is newest first. Skipped runs didn't do anything, so they don't count. */
    override fun lastRunAt(flowId: String): Long? =
        history.state.value.firstOrNull { it.automationId == flowId && it.status != ExecutionStatus.SKIPPED }?.startedAt

    override fun runsSince(flowId: String, sinceMillis: Long): Int =
        history.state.value.count { it.automationId == flowId && it.status != ExecutionStatus.SKIPPED && it.startedAt >= sinceMillis }

    override fun isEnabled(flowId: String): Boolean? = flows.get(flowId)?.takeIf { !it.inTrash }?.enabled

    override suspend fun setEnabled(flowId: String, enabled: Boolean): Boolean {
        flows.get(flowId)?.takeIf { !it.inTrash } ?: return false
        flows.setEnabled(flowId, enabled)
        return true
    }
}
