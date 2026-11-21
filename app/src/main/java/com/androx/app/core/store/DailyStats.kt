package com.androx.app.core.store

import com.androx.app.model.ExecutionStatus
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class DayStat(
    val success: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val durationMs: Long = 0L,
) {
    val runs: Int get() = success + failed + skipped
}

/** Per day, per Flow counts. Never trimmed, so streaks and insights stay correct even when run history is cut. */
@Serializable
data class DailyStatsFile(
    val version: Int = 1,
    /** `yyyy-MM-dd` → Flow id → counts. */
    val days: Map<String, Map<String, DayStat>> = emptyMap(),
)

object DailyStats {
    /** Rough guess used for the "time saved" figure. Always shown as an estimate. */
    const val ESTIMATED_SECONDS_SAVED_PER_RUN = 30

    fun record(file: DailyStatsFile, date: LocalDate, flowId: String, status: ExecutionStatus, durationMs: Long): DailyStatsFile {
        val key = date.toString()
        val day = file.days[key].orEmpty()
        val old = day[flowId] ?: DayStat()
        val updated = when (status) {
            ExecutionStatus.SUCCESS -> old.copy(success = old.success + 1, durationMs = old.durationMs + durationMs.coerceAtLeast(0))
            ExecutionStatus.FAILED -> old.copy(failed = old.failed + 1, durationMs = old.durationMs + durationMs.coerceAtLeast(0))
            ExecutionStatus.SKIPPED -> old.copy(skipped = old.skipped + 1)
        }
        return file.copy(days = file.days + (key to (day + (flowId to updated))))
    }

    private fun combine(a: DayStat, b: DayStat) =
        DayStat(a.success + b.success, a.failed + b.failed, a.skipped + b.skipped, a.durationMs + b.durationMs)

    fun forDay(file: DailyStatsFile, date: LocalDate, flowId: String? = null): DayStat {
        val day = file.days[date.toString()].orEmpty()
        return if (flowId != null) day[flowId] ?: DayStat() else day.values.fold(DayStat(), ::combine)
    }

    fun total(file: DailyStatsFile, from: LocalDate, to: LocalDate, flowId: String? = null): DayStat {
        var acc = DayStat()
        var d = from
        while (!d.isAfter(to)) {
            acc = combine(acc, forDay(file, d, flowId))
            d = d.plusDays(1)
        }
        return acc
    }

    /** Runs per day for the last [days] days, oldest first (the 7-day chart). */
    fun series(file: DailyStatsFile, today: LocalDate, days: Int = 7, flowId: String? = null): List<DayStat> =
        (days - 1 downTo 0).map { forDay(file, today.minusDays(it.toLong()), flowId) }

    /** Share of runs that succeeded, ignoring skipped runs. Null when nothing ran. */
    fun successRate(stat: DayStat): Double? {
        val denominator = stat.success + stat.failed
        return if (denominator == 0) null else stat.success.toDouble() / denominator
    }

    /**
     * Consecutive days (ending today, or yesterday if nothing ran yet today) on which at least one Flow
     * succeeded and none failed.
     */
    fun streakDays(file: DailyStatsFile, today: LocalDate): Int {
        fun good(d: LocalDate): Boolean {
            val s = forDay(file, d)
            return s.success > 0 && s.failed == 0
        }
        var d = if (forDay(file, today).runs == 0) today.minusDays(1) else today
        var n = 0
        while (good(d)) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    /** The Flow with the most runs in the range, with its run count. Null when nothing ran. */
    fun busiestFlow(file: DailyStatsFile, from: LocalDate, to: LocalDate): Pair<String, Int>? {
        val counts = HashMap<String, Int>()
        var d = from
        while (!d.isAfter(to)) {
            file.days[d.toString()]?.forEach { (id, s) -> counts[id] = (counts[id] ?: 0) + s.runs }
            d = d.plusDays(1)
        }
        return counts.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })?.let { it.key to it.value }
    }

    fun estimatedSecondsSaved(stat: DayStat): Long = stat.success.toLong() * ESTIMATED_SECONDS_SAVED_PER_RUN
}
