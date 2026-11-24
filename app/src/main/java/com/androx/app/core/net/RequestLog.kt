package com.androx.app.core.net

import kotlinx.serialization.Serializable

/** One request in the log (spec §14.9). The URL is stored already redacted; headers and bodies are never stored. */
@Serializable
data class RequestLogEntry(
    val at: Long,
    val method: String,
    val url: String,
    /** 0 when the request never got a response (blocked, offline, timed out). */
    val status: Int = 0,
    val millis: Long = 0L,
    val bytes: Int = 0,
    val flowId: String = "",
    val flowName: String = "",
    /** Why it failed or was refused. Empty when it worked. */
    val error: String = "",
)

@Serializable
data class RequestLogFile(val entries: List<RequestLogEntry> = emptyList())

object RequestLogs {
    const val CAP = 500

    /** Newest first. Drops lines older than [retentionDays] (0 keeps everything) and anything past [CAP]. */
    fun add(file: RequestLogFile, entry: RequestLogEntry, retentionDays: Int, cap: Int = CAP): RequestLogFile {
        val limit = if (retentionDays <= 0) Long.MIN_VALUE else entry.at - retentionDays * 24L * 60 * 60 * 1000
        return file.copy(entries = (listOf(entry) + file.entries).filter { it.at >= limit }.take(cap))
    }

    /** Requests in the last hour that actually left the phone. Refused ones (status 0, no time spent) don't count toward the cap. */
    fun sentLastHour(entries: List<RequestLogEntry>, now: Long): Int =
        entries.count { now - it.at < 3_600_000L && (it.status > 0 || it.millis > 0) }
}
