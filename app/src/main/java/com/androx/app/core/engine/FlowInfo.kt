package com.androx.app.core.engine

/** What the engine may ask about other Flows (for the "Flow state" check and "Turn a Flow on or off"). */
interface FlowInfo {
    fun isRunning(flowId: String): Boolean
    fun lastRunAt(flowId: String): Long?
    fun runsSince(flowId: String, sinceMillis: Long): Int

    /** Null when no such Flow exists. */
    fun isEnabled(flowId: String): Boolean?
}

/** What the engine may change about other Flows. Returns false when no such Flow exists. */
interface FlowControl {
    suspend fun setEnabled(flowId: String, enabled: Boolean): Boolean
}

/** Used when the engine runs without an app around it (tests, simulations). */
object NoFlowInfo : FlowInfo {
    override fun isRunning(flowId: String) = false
    override fun lastRunAt(flowId: String): Long? = null
    override fun runsSince(flowId: String, sinceMillis: Long) = 0
    override fun isEnabled(flowId: String): Boolean? = null
}
