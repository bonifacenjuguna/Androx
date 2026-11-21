package com.androx.app.core

import com.androx.app.data.JsonFile

/** Soft delete rules (spec §10): Flows move to Trash, can be restored, and leave on their own after 30 days. */
object Trash {
    const val AUTO_EMPTY_DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun moveToTrash(flow: Flow, now: Long): Flow = if (flow.inTrash) flow else flow.copy(deletedAt = now, enabled = false, updatedAt = now)

    /** Restored Flows come back switched off, so nothing starts running by surprise. */
    fun restore(flow: Flow, now: Long): Flow = flow.copy(deletedAt = null, enabled = false, updatedAt = now)

    /** Flows that have sat in Trash for longer than [days]. */
    fun expired(flows: List<Flow>, now: Long, days: Int = AUTO_EMPTY_DAYS): List<Flow> =
        flows.filter { f -> f.deletedAt?.let { now - it > days * DAY_MS } == true }

    fun active(flows: List<Flow>): List<Flow> = flows.filterNot { it.inTrash }
    fun trashed(flows: List<Flow>): List<Flow> = flows.filter { it.inTrash }

    /** Days left before a trashed Flow is removed for good. */
    fun daysLeft(flow: Flow, now: Long, days: Int = AUTO_EMPTY_DAYS): Int {
        val at = flow.deletedAt ?: return days
        return (days - ((now - at) / DAY_MS).toInt()).coerceIn(0, days)
    }

    /** Size of the trashed Flows as stored, for the drawer subtitle ("Trash · 33 KB"). */
    fun sizeBytes(flows: List<Flow>): Long =
        trashed(flows).sumOf { JsonFile.json.encodeToString(Flow.serializer(), it).toByteArray().size.toLong() }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
