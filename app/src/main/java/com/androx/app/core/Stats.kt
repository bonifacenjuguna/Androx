package com.androx.app.core

import com.androx.app.model.ExecutionStatus
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Counts for one Flow on one day. */
@Serializable
data class DayStat(
    val success: Int = 0,
    val failure: Int = 0,
    val skipped: Int = 0,
    val durationMs: Long = 0L,
) {
    val runs: Int get() = success + failure + skipped
    operator fun plus(o: DayStat) = DayStat(success + o.success, failure + o.failure, skipped + o.skipped, durationMs + o.durationMs)
}

/**
 * The daily stats store (spec §5.4, §12). Never trimmed like run history is, so streaks, sparklines and insights
 * stay accurate. Shape: day (`2026-11-21`) → Flow id → counts.
 */
@Serializable
data class StatsFile(
    val schemaVersion: Int = 1,
    val days: Map<String, Map<String, DayStat>> = emptyMap(),
)

/** Pure functions over [StatsFile], so every number the UI shows is unit tested. */
object Stats {
    /** Rough time one automated run saves a person. Always shown as an estimate (spec §7.3). */
    const val ESTIMATED_SECONDS_SAVED_PER_RUN = 15

    fun dayKey(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()

    fun record(
        file: StatsFile,
        flowId: String,
        atMillis: Long,
        status: ExecutionStatus,
        durationMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): StatsFile {
        val key = dayKey(atMillis, zone)
        val day = file.days[key].orEmpty()
        val old = day[flowId] ?: DayStat()
        val add = when (status) {
            ExecutionStatus.SUCCESS -> DayStat(success = 1, durationMs = durationMs.coerceAtLeast(0))
            ExecutionStatus.FAILED -> DayStat(failure = 1, durationMs = durationMs.coerceAtLeast(0))
            ExecutionStatus.SKIPPED -> DayStat(skipped = 1)
        }
        return file.copy(days = file.days + (key to (day + (flowId to (old + add)))))
    }

    /** Totals for one Flow (or all when [flowId] is null) over the last [days] days including today. Oldest first. */
    fun lastDays(
        file: StatsFile,
        nowMillis: Long,
        days: Int,
        flowId: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DayStat> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return (days - 1 downTo 0).map { back ->
            val key = today.minusDays(back.toLong()).toString()
            val day = file.days[key].orEmpty()
            if (flowId != null) day[flowId] ?: DayStat() else day.values.fold(DayStat()) { acc, s -> acc + s }
        }
    }

    fun total(file: StatsFile, flowId: String? = null): DayStat =
        file.days.values.fold(DayStat()) { acc, day ->
            acc + if (flowId != null) day[flowId] ?: DayStat() else day.values.fold(DayStat()) { a, s -> a + s }
        }

    /** Share of finished runs that succeeded, 0..1. Skipped runs don't count. Null when nothing has run. */
    fun successRate(stat: DayStat): Double? {
        val done = stat.success + stat.failure
        return if (done == 0) null else stat.success.toDouble() / done
    }

    /**
     * Consecutive days with at least one run and no failures, counted back from today. A quiet today doesn't
     * break the streak yet: counting starts from yesterday in that case.
     */
    fun streakDays(file: StatsFile, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        var day: LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        fun good(d: LocalDate): Boolean {
            val total = file.days[d.toString()].orEmpty().values.fold(DayStat()) { a, s -> a + s }
            return total.runs > 0 && total.failure == 0
        }
        if (file.days[day.toString()].orEmpty().isEmpty()) day = day.minusDays(1)
        var streak = 0
        while (good(day) && streak < 3650) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    /** The Flow with the most runs in the last [days] days, with its count. Null when nothing ran. */
    fun busiest(file: StatsFile, nowMillis: Long, days: Int, zone: ZoneId = ZoneId.systemDefault()): Pair<String, Int>? {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val counts = HashMap<String, Int>()
        for (back in 0 until days) {
            file.days[today.minusDays(back.toLong()).toString()]?.forEach { (id, s) -> counts[id] = (counts[id] ?: 0) + s.runs }
        }
        return counts.entries.filter { it.value > 0 }.maxWithOrNull(compareBy<Map.Entry<String, Int>>({ it.value }, { it.key }))?.let { it.key to it.value }
    }

    fun estimatedSecondsSaved(stat: DayStat): Long = stat.success.toLong() * ESTIMATED_SECONDS_SAVED_PER_RUN

    /** Drops stats of Flows that no longer exist and days older than [keepDays]. Only used by "Clear stats", never automatically. */
    fun forget(file: StatsFile, flowIds: Set<String>): StatsFile =
        file.copy(days = file.days.mapValues { (_, day) -> day.filterKeys { it !in flowIds } }.filterValues { it.isNotEmpty() })
}
