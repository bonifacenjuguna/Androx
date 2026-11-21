package com.androx.app.core.store

import com.androx.app.core.model.Flow

/** Soft delete rules. A trashed Flow never runs; it can be restored for 30 days. */
object TrashPolicy {
    const val RETENTION_DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun trash(flow: Flow, now: Long): Flow = flow.copy(deletedAt = now, enabled = false, updatedAt = now)

    /** Restored Flows come back switched off so nothing starts running by surprise. */
    fun restore(flow: Flow, now: Long): Flow = flow.copy(deletedAt = null, enabled = false, updatedAt = now)

    fun isExpired(flow: Flow, now: Long): Boolean {
        val at = flow.deletedAt ?: return false
        return now - at >= RETENTION_DAYS * DAY_MS
    }

    fun daysLeft(flow: Flow, now: Long): Int {
        val at = flow.deletedAt ?: return RETENTION_DAYS
        val left = RETENTION_DAYS - ((now - at) / DAY_MS).toInt()
        return left.coerceIn(0, RETENTION_DAYS)
    }

    /** Splits the trash into what stays and what is removed for good. */
    fun purge(trash: List<Flow>, now: Long): Pair<List<Flow>, List<Flow>> = trash.partition { !isExpired(it, now) }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
