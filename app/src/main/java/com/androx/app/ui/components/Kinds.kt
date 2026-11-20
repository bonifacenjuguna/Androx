@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.androx.app.R
import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.Condition
import com.androx.app.model.EditorKind
import com.androx.app.model.FlashlightMode
import com.androx.app.model.Trigger

@Composable
fun kindIcon(kind: EditorKind): Painter = when (kind) {
    EditorKind.BUILDER -> rememberVectorPainter(Icons.Filled.Build)
    EditorKind.SCRIPT -> painterResource(R.drawable.ic_code)
    EditorKind.WORKFLOW -> painterResource(R.drawable.ic_workflow)
}

fun kindLabel(kind: EditorKind): String = when (kind) {
    EditorKind.BUILDER -> "Builder"
    EditorKind.SCRIPT -> "JavaScript"
    EditorKind.WORKFLOW -> "Workflow"
}

// ------------------------------------------------------------------ triggers

enum class TriggerKind(val label: String, val summary: String, @DrawableRes val icon: Int) {
    MANUAL("Manual", "Runs only when you tap Run", R.drawable.mdi_gesture_tap),
    SCHEDULE("Schedule", "At a set time on chosen days", R.drawable.mdi_calendar_clock),
    INTERVAL("Interval", "Every few minutes or hours", R.drawable.mdi_timelapse),
    ONCE("Once", "One time, on a date you pick", R.drawable.mdi_alarm),
    BOOT("Device starts", "Each time the phone finishes booting", R.drawable.mdi_power),
}

fun Trigger.kind(): TriggerKind = when (this) {
    Trigger.Manual -> TriggerKind.MANUAL
    is Trigger.Schedule -> TriggerKind.SCHEDULE
    is Trigger.Interval -> TriggerKind.INTERVAL
    is Trigger.Once -> TriggerKind.ONCE
    Trigger.DeviceBoot -> TriggerKind.BOOT
}

fun TriggerKind.create(now: Long = System.currentTimeMillis()): Trigger = when (this) {
    TriggerKind.MANUAL -> Trigger.Manual
    TriggerKind.SCHEDULE -> Trigger.Schedule()
    TriggerKind.INTERVAL -> Trigger.Interval(minutes = 60, startAt = now)
    TriggerKind.ONCE -> Trigger.Once(atMillis = now + 60 * 60_000L)
    TriggerKind.BOOT -> Trigger.DeviceBoot
}

// ------------------------------------------------------------------ conditions

enum class ConditionKind(val label: String, val summary: String, @DrawableRes val icon: Int) {
    BATTERY("Battery level", "Above or below a percentage", R.drawable.mdi_battery),
    CHARGING("Charging", "Plugged in or not", R.drawable.mdi_battery_charging),
    TIME_WINDOW("Time of day", "Between two times, even past midnight", R.drawable.mdi_clock_outline),
    DAYS("Days of the week", "Only on the days you choose", R.drawable.mdi_calendar_today),
    WIFI("Wi-Fi", "Connected to Wi-Fi or not", R.drawable.mdi_wifi),
    SCREEN("Screen", "On or off", R.drawable.mdi_cellphone),
    RINGER("Ringer mode", "Ring, vibrate or silent", R.drawable.mdi_volume_high),
    POWER_SAVER("Battery Saver", "On or off", R.drawable.mdi_battery_alert_variant_outline),
    AIRPLANE("Airplane mode", "On or off", R.drawable.mdi_airplane),
    VARIABLE("Variable", "Compare a stored value", R.drawable.mdi_variable),
}

/** Kind of a single (non-composite) rule. Composite conditions report the kind of their first rule. */
fun Condition.kind(): ConditionKind = when (this) {
    is Condition.BatteryLevel -> ConditionKind.BATTERY
    is Condition.Charging -> ConditionKind.CHARGING
    is Condition.TimeWindow -> ConditionKind.TIME_WINDOW
    is Condition.DaysOfWeek -> ConditionKind.DAYS
    is Condition.WifiConnected -> ConditionKind.WIFI
    is Condition.ScreenOn -> ConditionKind.SCREEN
    is Condition.Ringer -> ConditionKind.RINGER
    is Condition.PowerSaver -> ConditionKind.POWER_SAVER
    is Condition.AirplaneMode -> ConditionKind.AIRPLANE
    is Condition.Variable -> ConditionKind.VARIABLE
    is Condition.MatchAll -> items.firstOrNull()?.kind() ?: ConditionKind.BATTERY
    is Condition.MatchAny -> items.firstOrNull()?.kind() ?: ConditionKind.BATTERY
}

fun ConditionKind.create(): Condition = when (this) {
    ConditionKind.BATTERY -> Condition.BatteryLevel()
    ConditionKind.CHARGING -> Condition.Charging()
    ConditionKind.TIME_WINDOW -> Condition.TimeWindow()
    ConditionKind.DAYS -> Condition.DaysOfWeek()
    ConditionKind.WIFI -> Condition.WifiConnected()
    ConditionKind.SCREEN -> Condition.ScreenOn()
    ConditionKind.RINGER -> Condition.Ringer()
    ConditionKind.POWER_SAVER -> Condition.PowerSaver()
    ConditionKind.AIRPLANE -> Condition.AirplaneMode()
    ConditionKind.VARIABLE -> Condition.Variable()
}

// ------------------------------------------------------------------ actions

enum class ActionKind(val label: String, val summary: String, @DrawableRes val icon: Int, val group: String) {
    NOTIFY("Show notification", "Post a notification on this device", R.drawable.mdi_bell_outline, "Tell me"),
    TOAST("Show toast", "A short message at the bottom of the screen", R.drawable.mdi_message_text_outline, "Tell me"),
    VIBRATE("Vibrate", "Buzz the phone", R.drawable.mdi_vibrate, "Tell me"),
    SPEAK("Speak", "Read text aloud", R.drawable.mdi_volume_medium, "Tell me"),
    SOUND("Play sound", "The default notification sound", R.drawable.mdi_music_note, "Tell me"),
    FLASHLIGHT("Flashlight", "Turn the torch on, off or toggle it", R.drawable.mdi_flashlight, "Device"),
    VOLUME("Set volume", "Media, ring, alarm or notification", R.drawable.mdi_volume_high, "Device"),
    CLIPBOARD("Copy to clipboard", "Put text on the clipboard", R.drawable.mdi_content_copy, "Device"),
    IF("If / else", "Do different things depending on a check", R.drawable.mdi_source_branch, "Logic"),
    REPEAT("Repeat", "Run a few actions several times", R.drawable.mdi_repeat, "Logic"),
    WAIT("Wait", "Pause for a few seconds", R.drawable.mdi_timer_sand, "Logic"),
    VARIABLE("Set variable", "Store, change or remove a shared value", R.drawable.mdi_variable, "Data"),
    RUN_AUTOMATION("Run automation", "Start another saved automation", R.drawable.mdi_play_circle_outline, "Data"),
    LOG("Write to log", "Add a line to this run's details", R.drawable.mdi_script_text_outline, "Data"),
    SCRIPT("Run JavaScript", "Use the androx scripting API", R.drawable.mdi_code_braces, "Advanced"),
}

fun Action.kind(): ActionKind = when (this) {
    is Action.ShowNotification -> ActionKind.NOTIFY
    is Action.ShowToast -> ActionKind.TOAST
    is Action.Vibrate -> ActionKind.VIBRATE
    is Action.Speak -> ActionKind.SPEAK
    Action.PlaySound -> ActionKind.SOUND
    is Action.Flashlight -> ActionKind.FLASHLIGHT
    is Action.SetVolume -> ActionKind.VOLUME
    is Action.CopyToClipboard -> ActionKind.CLIPBOARD
    is Action.IfThen -> ActionKind.IF
    is Action.Repeat -> ActionKind.REPEAT
    is Action.Wait -> ActionKind.WAIT
    is Action.SetVariable -> ActionKind.VARIABLE
    is Action.RunAutomation -> ActionKind.RUN_AUTOMATION
    is Action.WriteLog -> ActionKind.LOG
    is Action.RunScript -> ActionKind.SCRIPT
}

fun ActionKind.create(): Action = when (this) {
    ActionKind.NOTIFY -> Action.ShowNotification("Androx", "")
    ActionKind.TOAST -> Action.ShowToast()
    ActionKind.VIBRATE -> Action.Vibrate()
    ActionKind.SPEAK -> Action.Speak()
    ActionKind.SOUND -> Action.PlaySound
    ActionKind.FLASHLIGHT -> Action.Flashlight(FlashlightMode.TOGGLE)
    ActionKind.VOLUME -> Action.SetVolume()
    ActionKind.CLIPBOARD -> Action.CopyToClipboard()
    ActionKind.IF -> Action.IfThen()
    ActionKind.REPEAT -> Action.Repeat()
    ActionKind.WAIT -> Action.Wait()
    ActionKind.VARIABLE -> Action.SetVariable()
    ActionKind.RUN_AUTOMATION -> Action.RunAutomation()
    ActionKind.LOG -> Action.WriteLog()
    ActionKind.SCRIPT -> Action.RunScript()
}

// ------------------------------------------------------------------ automation icons

/** Icons the user can give an automation. The key is what gets stored. */
val automationIconChoices: List<Pair<String, Int>> = listOf(
    "bolt" to R.drawable.ic_bolt,
    "sun" to R.drawable.mdi_white_balance_sunny,
    "sunset" to R.drawable.mdi_weather_sunset,
    "moon" to R.drawable.mdi_weather_night,
    "sleep" to R.drawable.mdi_sleep,
    "home" to R.drawable.mdi_home_outline,
    "work" to R.drawable.mdi_briefcase_outline,
    "coffee" to R.drawable.mdi_coffee_outline,
    "battery" to R.drawable.mdi_battery,
    "bell" to R.drawable.mdi_bell_outline,
    "volume" to R.drawable.mdi_volume_high,
    "flash" to R.drawable.mdi_flashlight,
    "wifi" to R.drawable.mdi_wifi,
    "rocket" to R.drawable.mdi_rocket_launch_outline,
    "bulb" to R.drawable.mdi_lightbulb_outline,
    "star" to R.drawable.mdi_star_four_points_outline,
    "code" to R.drawable.mdi_code_braces,
    "cog" to R.drawable.mdi_cog_outline,
)

@DrawableRes
fun automationIconRes(automation: Automation): Int {
    val chosen = automationIconChoices.firstOrNull { it.first == automation.icon }?.second
    return chosen ?: automation.trigger.kind().icon
}

@DrawableRes
fun automationIconRes(key: String?, trigger: Trigger): Int =
    automationIconChoices.firstOrNull { it.first == key }?.second ?: trigger.kind().icon

// ------------------------------------------------------------------ sheets

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
}

/** Bottom sheet that lets the user pick one of the three ways to create an automation. */
@Composable
fun NewAutomationSheet(onPick: (EditorKind) -> Unit, onDismiss: () -> Unit, onTemplates: (() -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            SheetTitle("Create automation")
            PreferenceRow(
                title = "Builder",
                summary = "Choose a trigger, rules and actions",
                icon = kindIcon(EditorKind.BUILDER),
                onClick = { onPick(EditorKind.BUILDER) },
            )
            PreferenceRow(
                title = "JavaScript",
                summary = "Write a script with the androx API",
                icon = kindIcon(EditorKind.SCRIPT),
                onClick = { onPick(EditorKind.SCRIPT) },
            )
            PreferenceRow(
                title = "Visual workflow",
                summary = "Place nodes on a canvas and connect them",
                icon = kindIcon(EditorKind.WORKFLOW),
                onClick = { onPick(EditorKind.WORKFLOW) },
            )
            if (onTemplates != null) {
                PreferenceRow(
                    title = "Start from an idea",
                    summary = "Ready-made automations you can tweak",
                    icon = painterResource(R.drawable.mdi_lightbulb_outline),
                    onClick = onTemplates,
                )
            }
        }
    }
}

/** Bottom sheet for choosing the type of a new action, grouped like the system's own pickers. */
@Composable
fun AddActionSheet(onPick: (ActionKind) -> Unit, onDismiss: () -> Unit, allowLogic: Boolean = true) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetTitle("Add action")
            val kinds = ActionKind.values().filter { allowLogic || (it != ActionKind.IF && it != ActionKind.REPEAT) }
            kinds.map { it.group }.distinct().forEach { group ->
                SectionLabel(group)
                kinds.filter { it.group == group }.forEach { kind ->
                    PreferenceRow(
                        title = kind.label,
                        summary = kind.summary,
                        icon = painterResource(kind.icon),
                        onClick = { onPick(kind) },
                    )
                }
            }
        }
    }
}

/** Bottom sheet for choosing the type of a new rule. */
@Composable
fun AddRuleSheet(onPick: (ConditionKind) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetTitle("Add rule")
            ConditionKind.values().forEach { kind ->
                PreferenceRow(
                    title = kind.label,
                    summary = kind.summary,
                    icon = painterResource(kind.icon),
                    onClick = { onPick(kind) },
                )
            }
        }
    }
}
