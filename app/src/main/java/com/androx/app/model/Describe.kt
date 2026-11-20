package com.androx.app.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun formatClock(hour: Int, minute: Int): String = String.format(Locale.US, "%02d:%02d", hour, minute)

fun describeDays(days: List<Int>): String {
    val set = days.filter { it in 1..7 }.toSortedSet()
    return when {
        set.isEmpty() || set.size == 7 -> "Every day"
        set == setOf(1, 2, 3, 4, 5) -> "Weekdays"
        set == setOf(6, 7) -> "Weekends"
        else -> set.joinToString(", ") { DAY_NAMES[it - 1] }
    }
}

/** "Every 15 min", "Every 2 h", "Every 1 h 30 min". */
fun describeInterval(minutes: Int): String {
    val m = minutes.coerceAtLeast(1)
    val h = m / 60
    val rest = m % 60
    return when {
        h == 0 -> "Every $m min"
        rest == 0 -> "Every $h h"
        else -> "Every $h h $rest min"
    }
}

fun formatDateTime(millis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun Trigger.summary(): String = when (this) {
    Trigger.Manual -> "Manual"
    is Trigger.Schedule -> "${formatClock(hour, minute)} · ${describeDays(days)}"
    is Trigger.Interval -> describeInterval(minutes)
    is Trigger.Once -> if (atMillis > 0) "Once · ${formatDateTime(atMillis)}" else "Once"
    Trigger.DeviceBoot -> "When the device starts"
}

fun ringerWord(mode: RingerMode): String = when (mode) {
    RingerMode.NORMAL -> "Ring"
    RingerMode.VIBRATE -> "Vibrate"
    RingerMode.SILENT -> "Silent"
}

fun Condition.summary(): String = when (this) {
    is Condition.BatteryLevel -> "Battery ${op.symbol} $percent%"
    is Condition.Charging -> if (charging) "Device is charging" else "Device is not charging"
    is Condition.Variable -> "$name ${op.symbol} $value"
    is Condition.TimeWindow ->
        "Between ${formatClock(fromHour, fromMinute)} and ${formatClock(toHour, toMinute)}"
    is Condition.DaysOfWeek -> describeDays(days)
    is Condition.WifiConnected -> if (connected) "Wi-Fi is connected" else "Wi-Fi is not connected"
    is Condition.ScreenOn -> if (on) "Screen is on" else "Screen is off"
    is Condition.Ringer -> "Ringer is ${ringerWord(mode).lowercase()}"
    is Condition.PowerSaver -> if (on) "Battery Saver is on" else "Battery Saver is off"
    is Condition.AirplaneMode -> if (on) "Airplane mode is on" else "Airplane mode is off"
    is Condition.MatchAll -> items.joinToString(" and ") { it.summary() }.ifBlank { "No rules" }
    is Condition.MatchAny -> items.joinToString(" or ") { it.summary() }.ifBlank { "No rules" }
}

fun Action.label(): String = when (this) {
    is Action.ShowNotification -> "Show notification"
    is Action.SetVariable -> "Set variable"
    is Action.RunAutomation -> "Run automation"
    is Action.WriteLog -> "Write to log"
    is Action.RunScript -> "Run JavaScript"
    is Action.Vibrate -> "Vibrate"
    is Action.ShowToast -> "Show toast"
    is Action.CopyToClipboard -> "Copy to clipboard"
    is Action.Speak -> "Speak"
    is Action.Wait -> "Wait"
    is Action.Flashlight -> "Flashlight"
    is Action.SetVolume -> "Set volume"
    Action.PlaySound -> "Play sound"
    is Action.IfThen -> "If / else"
    is Action.Repeat -> "Repeat"
}

fun volumeStreamLabel(stream: VolumeStream): String = when (stream) {
    VolumeStream.MEDIA -> "Media"
    VolumeStream.RING -> "Ring"
    VolumeStream.ALARM -> "Alarm"
    VolumeStream.NOTIFICATION -> "Notification"
}

fun Action.summary(): String = when (this) {
    is Action.ShowNotification -> title.ifBlank { "Androx" } + if (message.isNotBlank()) " – $message" else ""
    is Action.SetVariable -> when (mode) {
        VariableMode.SET -> "$name = $value"
        VariableMode.ADD -> "$name += $value"
        VariableMode.SUBTRACT -> "$name -= $value"
        VariableMode.MULTIPLY -> "$name *= $value"
        VariableMode.APPEND -> "$name + \"$value\""
        VariableMode.DELETE -> "Remove $name"
    }
    is Action.RunAutomation -> automationName.ifBlank { automationId.take(8) }
    is Action.WriteLog -> message
    is Action.RunScript -> source.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    is Action.Vibrate -> "$durationMs ms"
    is Action.ShowToast -> message
    is Action.CopyToClipboard -> text
    is Action.Speak -> text
    is Action.Wait -> "$seconds s"
    is Action.Flashlight -> when (mode) {
        FlashlightMode.ON -> "Turn on"
        FlashlightMode.OFF -> "Turn off"
        FlashlightMode.TOGGLE -> "Toggle"
    }
    is Action.SetVolume -> "${volumeStreamLabel(stream)} to $percent%"
    Action.PlaySound -> "Default notification sound"
    is Action.IfThen -> "If ${condition.summary()} · ${then.size} then, ${otherwise.size} else"
    is Action.Repeat -> "$times × ${actions.size} action${if (actions.size == 1) "" else "s"}"
}

/** A short verb phrase used in the plain-language sentence ("show a notification"). */
fun Action.phrase(): String = when (this) {
    is Action.ShowNotification -> "show a notification"
    is Action.SetVariable -> if (mode == VariableMode.DELETE) "remove $name" else "update $name"
    is Action.RunAutomation -> "run ${automationName.ifBlank { "another automation" }}"
    is Action.WriteLog -> "write to the log"
    is Action.RunScript -> "run a script"
    is Action.Vibrate -> "vibrate"
    is Action.ShowToast -> "show a toast"
    is Action.CopyToClipboard -> "copy text"
    is Action.Speak -> "speak"
    is Action.Wait -> "wait $seconds s"
    is Action.Flashlight -> "change the flashlight"
    is Action.SetVolume -> "set ${volumeStreamLabel(stream).lowercase()} volume to $percent%"
    Action.PlaySound -> "play a sound"
    is Action.IfThen -> "branch on ${condition.summary().lowercase()}"
    is Action.Repeat -> "repeat $times times"
}

private fun Trigger.whenPhrase(): String = when (this) {
    Trigger.Manual -> "you tap Run"
    is Trigger.Schedule -> "it's ${formatClock(hour, minute)} (${describeDays(days).lowercase()})"
    is Trigger.Interval -> describeInterval(minutes).lowercase()
    is Trigger.Once -> if (atMillis > 0) "it's ${formatDateTime(atMillis)}" else "the chosen time arrives"
    Trigger.DeviceBoot -> "the device starts"
}

/** "When it's 07:30 (weekdays), and battery < 20%, show a notification and vibrate." */
fun describeFlow(trigger: Trigger, condition: Condition?, actions: List<Action>): String {
    val sb = StringBuilder("When ").append(trigger.whenPhrase())
    if (condition != null) sb.append(", and ").append(condition.summary().replaceFirstChar { it.lowercase() })
    sb.append(", ")
    sb.append(
        when (actions.size) {
            0 -> "do nothing yet"
            1 -> actions[0].phrase()
            2 -> "${actions[0].phrase()} and ${actions[1].phrase()}"
            else -> actions.take(2).joinToString(", ") { it.phrase() } + " and ${actions.size - 2} more"
        }
    )
    return sb.append('.').toString()
}

fun Automation.subtitle(): String {
    val parts = mutableListOf(trigger.summary())
    condition?.let { parts += "if ${it.summary()}" }
    parts += when (actions.size) {
        0 -> "no actions"
        1 -> "1 action"
        else -> "${actions.size} actions"
    }
    return parts.joinToString(" · ")
}
