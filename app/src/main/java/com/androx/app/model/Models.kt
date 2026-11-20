package com.androx.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Which editor an automation was last saved from. The data model itself is shared by all three. */
enum class EditorKind { BUILDER, SCRIPT, WORKFLOW }

enum class Comparison(val symbol: String) {
    LT("<"),
    LTE("≤"),
    EQ("="),
    NEQ("≠"),
    GTE("≥"),
    GT(">");

    /** [cmp] is the result of compareTo(left, right). */
    fun test(cmp: Int): Boolean = when (this) {
        LT -> cmp < 0
        LTE -> cmp <= 0
        EQ -> cmp == 0
        NEQ -> cmp != 0
        GTE -> cmp >= 0
        GT -> cmp > 0
    }
}

enum class VariableMode { SET, ADD, SUBTRACT, MULTIPLY, APPEND, DELETE }

enum class RingerMode { NORMAL, VIBRATE, SILENT }

enum class FlashlightMode { ON, OFF, TOGGLE }

enum class VolumeStream { MEDIA, RING, ALARM, NOTIFICATION }

/** Shortest repeat interval. Android's own limits (battery saver, Doze) can still delay runs. */
const val MIN_INTERVAL_MINUTES = 5

/** What starts an automation. */
@Serializable
sealed interface Trigger {
    @Serializable
    @SerialName("manual")
    data object Manual : Trigger

    /** Runs at [hour]:[minute]. [days] uses ISO numbering (1 = Monday … 7 = Sunday); empty means every day. */
    @Serializable
    @SerialName("schedule")
    data class Schedule(
        val hour: Int = 8,
        val minute: Int = 0,
        val days: List<Int> = emptyList(),
    ) : Trigger {
        fun nextRunMillis(afterMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
            val startDate = Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDate()
            val time = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
            for (offset in 0L..7L) {
                val date = startDate.plusDays(offset)
                if (days.isNotEmpty() && date.dayOfWeek.value !in days) continue
                val candidate = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
                if (candidate > afterMillis) return candidate
            }
            return afterMillis + 24L * 60 * 60 * 1000
        }
    }

    /**
     * Repeats every [minutes]. Runs happen at [startAt] + n × interval so the rhythm never drifts or restarts
     * when other automations are edited. [startAt] is set to "now" when the trigger is created.
     */
    @Serializable
    @SerialName("interval")
    data class Interval(
        val minutes: Int = 60,
        val startAt: Long = 0L,
    ) : Trigger {
        fun nextRunMillis(afterMillis: Long): Long {
            val step = minutes.coerceAtLeast(MIN_INTERVAL_MINUTES) * 60_000L
            val first = startAt + step
            if (afterMillis < first) return first
            val n = (afterMillis - startAt) / step + 1
            return startAt + n * step
        }
    }

    /** Runs once at [atMillis] and then turns itself off. */
    @Serializable
    @SerialName("once")
    data class Once(val atMillis: Long = 0L) : Trigger

    /** Runs when the device finishes starting up. */
    @Serializable
    @SerialName("boot")
    data object DeviceBoot : Trigger
}

/** The next time an alarm should fire for this trigger, or null if it isn't time-based (or has expired). */
fun Trigger.nextFire(afterMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long? = when (this) {
    Trigger.Manual, Trigger.DeviceBoot -> null
    is Trigger.Schedule -> nextRunMillis(afterMillis, zone)
    is Trigger.Interval -> nextRunMillis(afterMillis)
    is Trigger.Once -> if (atMillis > afterMillis) atMillis else null
}

/** True for triggers that are driven by AlarmManager. */
fun Trigger.isTimed(): Boolean = this is Trigger.Schedule || this is Trigger.Interval || this is Trigger.Once

/** An optional guard evaluated after the trigger fires. */
@Serializable
sealed interface Condition {
    @Serializable
    @SerialName("battery")
    data class BatteryLevel(val op: Comparison = Comparison.LT, val percent: Int = 20) : Condition

    @Serializable
    @SerialName("charging")
    data class Charging(val charging: Boolean = true) : Condition

    @Serializable
    @SerialName("variable")
    data class Variable(
        val name: String = "",
        val op: Comparison = Comparison.EQ,
        val value: String = "",
    ) : Condition

    /** True between the two times. A window such as 22:00 → 07:00 wraps past midnight. */
    @Serializable
    @SerialName("time_window")
    data class TimeWindow(
        val fromHour: Int = 22,
        val fromMinute: Int = 0,
        val toHour: Int = 7,
        val toMinute: Int = 0,
    ) : Condition

    /** True on the given days (ISO: 1 = Monday … 7 = Sunday). Empty means every day. */
    @Serializable
    @SerialName("days")
    data class DaysOfWeek(val days: List<Int> = listOf(1, 2, 3, 4, 5)) : Condition

    @Serializable
    @SerialName("wifi")
    data class WifiConnected(val connected: Boolean = true) : Condition

    @Serializable
    @SerialName("screen")
    data class ScreenOn(val on: Boolean = true) : Condition

    @Serializable
    @SerialName("ringer")
    data class Ringer(val mode: RingerMode = RingerMode.SILENT) : Condition

    @Serializable
    @SerialName("power_saver")
    data class PowerSaver(val on: Boolean = true) : Condition

    @Serializable
    @SerialName("airplane")
    data class AirplaneMode(val on: Boolean = true) : Condition

    /** Passes only if every rule passes. */
    @Serializable
    @SerialName("all")
    data class MatchAll(val items: List<Condition> = emptyList()) : Condition

    /** Passes if at least one rule passes. */
    @Serializable
    @SerialName("any")
    data class MatchAny(val items: List<Condition> = emptyList()) : Condition
}

/** The individual rules of a condition (a single rule is its own list of one). */
fun Condition.rules(): List<Condition> = when (this) {
    is Condition.MatchAll -> items
    is Condition.MatchAny -> items
    else -> listOf(this)
}

/** Combines rules back into one condition. Null when there are none. */
fun buildCondition(rules: List<Condition>, matchAny: Boolean): Condition? = when {
    rules.isEmpty() -> null
    rules.size == 1 -> rules[0]
    matchAny -> Condition.MatchAny(rules)
    else -> Condition.MatchAll(rules)
}

const val DEFAULT_SCRIPT = """// Androx script. The only things you can use are the `androx` API and plain JavaScript.
const runs = Number(androx.getVar("runs") || 0) + 1;
androx.setVar("runs", runs);
androx.notify("Androx script", "Run #" + runs + " · battery " + androx.battery() + "%");
"""

/** One step. Actions run in order; the first failure stops the run. */
@Serializable
sealed interface Action {
    @Serializable
    @SerialName("notify")
    data class ShowNotification(val title: String = "Androx", val message: String = "") : Action

    @Serializable
    @SerialName("set_variable")
    data class SetVariable(
        val name: String = "",
        val value: String = "",
        val mode: VariableMode = VariableMode.SET,
    ) : Action

    @Serializable
    @SerialName("run_automation")
    data class RunAutomation(val automationId: String = "", val automationName: String = "") : Action

    @Serializable
    @SerialName("log")
    data class WriteLog(val message: String = "") : Action

    @Serializable
    @SerialName("script")
    data class RunScript(val source: String = DEFAULT_SCRIPT) : Action

    @Serializable
    @SerialName("vibrate")
    data class Vibrate(val durationMs: Int = 400) : Action

    @Serializable
    @SerialName("toast")
    data class ShowToast(val message: String = "") : Action

    @Serializable
    @SerialName("clipboard")
    data class CopyToClipboard(val text: String = "") : Action

    @Serializable
    @SerialName("speak")
    data class Speak(val text: String = "") : Action

    /** Pauses the run. A single run may wait at most 30 seconds in total. */
    @Serializable
    @SerialName("wait")
    data class Wait(val seconds: Int = 2) : Action

    @Serializable
    @SerialName("flashlight")
    data class Flashlight(val mode: FlashlightMode = FlashlightMode.TOGGLE) : Action

    @Serializable
    @SerialName("volume")
    data class SetVolume(val stream: VolumeStream = VolumeStream.MEDIA, val percent: Int = 50) : Action

    @Serializable
    @SerialName("sound")
    data object PlaySound : Action

    /** Branches: runs [then] if the condition passes, otherwise [otherwise]. */
    @Serializable
    @SerialName("if")
    data class IfThen(
        val condition: Condition = Condition.BatteryLevel(),
        val then: List<Action> = emptyList(),
        val otherwise: List<Action> = emptyList(),
    ) : Action

    /** Runs [actions] [times] times. `{{index}}` holds the round number (1-based). */
    @Serializable
    @SerialName("repeat")
    data class Repeat(val times: Int = 3, val actions: List<Action> = emptyList()) : Action
}

/**
 * The single representation every editor reads and writes: Trigger → optional Condition → Actions.
 * [graph] only carries the visual layout (and any nodes the user left unconnected) so nothing is lost
 * when switching between the Builder, Script and Workflow editors.
 */
@Serializable
data class Automation(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Trigger = Trigger.Manual,
    val condition: Condition? = null,
    val actions: List<Action> = emptyList(),
    val editor: EditorKind = EditorKind.BUILDER,
    val graph: WorkflowGraph? = null,
    /** Key of a user-chosen icon (see the icon picker). Null means the icon of the trigger is shown. */
    val icon: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

enum class ExecutionStatus { SUCCESS, FAILED, SKIPPED }
enum class RunSource { MANUAL, SCHEDULE, BOOT }

@Serializable
data class ExecutionRecord(
    val id: String,
    val automationId: String,
    val automationName: String,
    val startedAt: Long,
    val durationMs: Long,
    val status: ExecutionStatus,
    val source: RunSource,
    val message: String? = null,
    val logs: List<String> = emptyList(),
)
