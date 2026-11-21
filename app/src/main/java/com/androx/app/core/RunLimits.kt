package com.androx.app.core

import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus

/** Run-rate guard (spec §5.2 run options). Skipped runs don't count; they didn't do anything. */
object RunLimits {
    private const val HOUR_MS = 60L * 60 * 1000

    fun runsInLastHour(history: List<ExecutionRecord>, flowId: String, now: Long): Int =
        history.count { it.automationId == flowId && it.status != ExecutionStatus.SKIPPED && now - it.startedAt < HOUR_MS }

    /** True when another run would go over [limitPerHour]. A limit of 0 means unlimited. */
    fun exceeded(history: List<ExecutionRecord>, flowId: String, limitPerHour: Int, now: Long): Boolean =
        limitPerHour > 0 && runsInLastHour(history, flowId, now) >= limitPerHour
}
