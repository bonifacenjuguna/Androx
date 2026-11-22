package com.androx.app.core.hub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** "After firing, ignore this trigger for N seconds" (spec §15.1). Keyed by Flow and trigger entry. */
class CooldownGuard {
    private val last = ConcurrentHashMap<String, Long>()

    fun ready(key: String, seconds: Int, now: Long): Boolean {
        if (seconds <= 0) return true
        val at = last[key] ?: return true
        return now - at >= seconds * 1_000L
    }

    fun mark(key: String, now: Long) {
        last[key] = now
    }
}

/**
 * "Only fire once it has held for N minutes" (for example battery under 20% for 5 minutes). When the event arrives a
 * timer starts; when it ends the condition is checked again and the Flow runs only if it still holds. A second event for
 * the same trigger while waiting is ignored. Timers live in memory: they exist only while the Listener keeps the app
 * running, which is also the only time such events arrive.
 */
class HoldTracker(private val scope: CoroutineScope) {
    private val jobs = ConcurrentHashMap<String, Job>()

    fun isHolding(key: String): Boolean = jobs[key]?.isActive == true

    /** Returns false when [key] is already waiting. */
    fun begin(key: String, minutes: Int, stillHolds: () -> Boolean, onHeld: suspend () -> Unit): Boolean {
        if (isHolding(key)) return false
        val job = scope.launch {
            delay(minutes.coerceAtLeast(1) * 60_000L)
            jobs.remove(key)
            if (stillHolds()) onHeld()
        }
        jobs[key] = job
        return true
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}

/**
 * Stops a Flow that keeps starting itself (a "variable changed" Flow that changes that variable, a Flow that starts
 * when any Flow finishes). More than [max] starts in a minute are refused until the minute is up.
 */
class EventLoopGuard(private val max: Int = 20, private val windowMs: Long = 60_000L) {
    private val starts = ConcurrentHashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(flowId: String, now: Long): Boolean {
        val q = starts.getOrPut(flowId) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
        if (q.size >= max) return false
        q.addLast(now)
        return true
    }
}
