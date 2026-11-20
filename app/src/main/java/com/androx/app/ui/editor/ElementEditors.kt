@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.editor

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.Comparison
import com.androx.app.model.Condition
import com.androx.app.model.FlashlightMode
import com.androx.app.model.RingerMode
import com.androx.app.model.Trigger
import com.androx.app.model.VariableMode
import com.androx.app.model.VolumeStream
import com.androx.app.model.buildCondition
import com.androx.app.model.describeDays
import com.androx.app.model.describeInterval
import com.androx.app.model.formatClock
import com.androx.app.model.formatDateTime
import com.androx.app.model.label
import com.androx.app.model.MIN_INTERVAL_MINUTES
import com.androx.app.model.ringerWord
import com.androx.app.model.rules
import com.androx.app.model.summary
import com.androx.app.model.volumeStreamLabel
import com.androx.app.ui.components.AddActionSheet
import com.androx.app.ui.components.AddRuleSheet
import com.androx.app.ui.components.ActionKind
import com.androx.app.ui.components.ConditionKind
import com.androx.app.ui.components.DropdownField
import com.androx.app.ui.components.TriggerKind
import com.androx.app.ui.components.create
import com.androx.app.ui.components.kind
import com.androx.app.ui.theme.CodeTextStyle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")
private val dayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

private val templateTokens = listOf("battery", "charging", "time", "date", "datetime", "weekday", "wifi", "random")

@Composable
fun IntField(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(4)
            text = digits
            digits.toIntOrNull()?.let { onValue(it.coerceIn(range)) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A text field for content that supports {{tokens}}, with chips that insert them. */
@Composable
private fun TemplateField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            label = { Text(label) },
            singleLine = singleLine,
            minLines = minLines,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            templateTokens.forEach { token ->
                AssistChip(onClick = { onValue(value + "{{" + token + "}}") }, label = { Text(token) })
            }
        }
        Hint("Tap a chip to insert a live value, or use {{yourVariable}}.")
    }
}

// ---------------------------------------------------------------- time & day pickers

@Composable
fun TimeField(label: String, hour: Int, minute: Int, onChange: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }, modifier = modifier) {
        Text("$label: ${formatClock(hour, minute)}")
    }
    if (picking) {
        val context = LocalContext.current
        val state = rememberTimePickerState(hour, minute, DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { picking = false; onChange(state.hour, state.minute) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
fun DayPicker(days: List<Int>, onChange: (List<Int>) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        dayLabels.forEachIndexed { index, letter ->
            val day = index + 1
            val selected = days.isEmpty() || day in days
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) {
                        role = Role.Checkbox
                        this.selected = days.isNotEmpty() && day in days
                        contentDescription = dayNames[index]
                    }
                    .clickable {
                        val current = days.ifEmpty { (1..7).toList() }
                        val next = if (day in current) current - day else current + day
                        // Selecting all seven (or none) both mean "every day".
                        onChange(if (next.size == 7 || next.isEmpty()) emptyList() else next.sorted())
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        letter,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Hint(describeDays(days))
}

// ---------------------------------------------------------------- Trigger

@Composable
fun TriggerEditor(trigger: Trigger, onChange: (Trigger) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TriggerKind.values().forEach { kind ->
                FilterChip(
                    selected = trigger.kind() == kind,
                    onClick = { if (trigger.kind() != kind) onChange(kind.create()) },
                    label = { Text(kind.label) },
                    leadingIcon = { Icon(painterResource(kind.icon), contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        Hint(trigger.kind().summary)
        when (trigger) {
            Trigger.Manual -> Unit
            Trigger.DeviceBoot -> Hint("Runs shortly after the phone finishes starting. Android may delay it while the device is still settling.")
            is Trigger.Schedule -> {
                TimeField("Time", trigger.hour, trigger.minute, { h, m -> onChange(trigger.copy(hour = h, minute = m)) }, Modifier.fillMaxWidth())
                DayPicker(trigger.days) { onChange(trigger.copy(days = it)) }
            }
            is Trigger.Interval -> IntervalFields(trigger, onChange)
            is Trigger.Once -> OnceFields(trigger, onChange)
        }
    }
}

@Composable
private fun IntervalFields(trigger: Trigger.Interval, onChange: (Trigger) -> Unit) {
    val inHours = trigger.minutes >= 60 && trigger.minutes % 60 == 0
    var unitHours by remember { mutableStateOf(inHours) }
    val shown = if (unitHours) trigger.minutes / 60 else trigger.minutes
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        androidx.compose.runtime.key(unitHours) {
            IntField(
                label = "Every",
                value = shown.coerceAtLeast(1),
                range = 1..999,
                onValue = { n ->
                    val minutes = (if (unitHours) n * 60 else n).coerceAtLeast(MIN_INTERVAL_MINUTES)
                    // Restarting the rhythm from now keeps "every N" predictable after an edit.
                    onChange(trigger.copy(minutes = minutes, startAt = System.currentTimeMillis()))
                },
                modifier = Modifier.weight(1f),
            )
        }
        DropdownField(
            label = "Unit",
            options = listOf("minutes", "hours"),
            selected = if (unitHours) "hours" else "minutes",
            optionText = { it },
            onSelect = { choice ->
                val toHours = choice == "hours"
                if (toHours != unitHours) {
                    unitHours = toHours
                    val minutes = if (toHours) (trigger.minutes / 60).coerceAtLeast(1) * 60 else trigger.minutes
                    onChange(trigger.copy(minutes = minutes.coerceAtLeast(MIN_INTERVAL_MINUTES), startAt = System.currentTimeMillis()))
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
    Hint("${describeInterval(trigger.minutes)}. The shortest interval is $MIN_INTERVAL_MINUTES minutes. Android may delay runs to save battery.")
}

@Composable
private fun OnceFields(trigger: Trigger.Once, onChange: (Trigger) -> Unit) {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(trigger.atMillis).atZone(zone)
    var pickingDate by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.weight(1f)) {
            Text(at.toLocalDate().toString())
        }
        TimeField("Time", at.hour, at.minute, { h, m ->
            val millis = at.toLocalDate().atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
            onChange(trigger.copy(atMillis = millis))
        }, Modifier.weight(1f))
    }
    Hint("Runs once on ${formatDateTime(trigger.atMillis)}, then turns itself off.")
    if (trigger.atMillis <= System.currentTimeMillis()) {
        Text("That time has already passed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = at.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickingDate = false
                    val picked = state.selectedDateMillis
                    if (picked != null) {
                        val date: LocalDate = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        val millis = date.atTime(at.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
                        onChange(trigger.copy(atMillis = millis))
                    }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

// ---------------------------------------------------------------- Conditions

/**
 * The "only if" section: a list of rules plus an All / Any switch when there is more than one.
 * [condition] null means "always run".
 */
@Composable
fun ConditionsSection(condition: Condition?, onChange: (Condition?) -> Unit, required: Boolean = false) {
    val rules = condition?.rules().orEmpty()
    val matchAny = condition is Condition.MatchAny
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rules.forEachIndexed { index, rule ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 16.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(rule.kind().icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Text(
                            rule.kind().label,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        if (!(required && rules.size == 1)) {
                            IconButton(onClick = {
                                val next = rules.filterIndexed { i, _ -> i != index }
                                onChange(buildCondition(next, matchAny))
                            }) { Icon(Icons.Filled.Close, contentDescription = "Remove rule ${index + 1}") }
                        }
                    }
                    Column(Modifier.padding(end = 12.dp)) {
                        RuleEditor(rule) { updated ->
                            onChange(buildCondition(rules.mapIndexed { i, r -> if (i == index) updated else r }, matchAny))
                        }
                    }
                }
            }
        }
        if (rules.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Run when", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterChip(selected = !matchAny, onClick = { onChange(buildCondition(rules, false)) }, label = { Text("all rules pass") })
                FilterChip(selected = matchAny, onClick = { onChange(buildCondition(rules, true)) }, label = { Text("any rule passes") })
            }
        }
        OutlinedButton(onClick = { adding = true }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Add rule", modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (adding) {
        AddRuleSheet(
            onPick = { kind -> adding = false; onChange(buildCondition(rules + kind.create(), matchAny)) },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun BoolField(label: String, value: Boolean, trueText: String, falseText: String, onChange: (Boolean) -> Unit) {
    DropdownField(
        label = label,
        options = listOf(true, false),
        selected = value,
        optionText = { if (it) trueText else falseText },
        onSelect = onChange,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RuleEditor(rule: Condition, onChange: (Condition) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (rule) {
            is Condition.BatteryLevel -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DropdownField("Is", Comparison.values().toList(), rule.op, { it.symbol }, { onChange(rule.copy(op = it)) }, Modifier.weight(1f))
                IntField("Percent", rule.percent, 0..100, { onChange(rule.copy(percent = it)) }, Modifier.weight(1f))
            }
            is Condition.Charging -> BoolField("Device is", rule.charging, "Charging", "Not charging") { onChange(rule.copy(charging = it)) }
            is Condition.TimeWindow -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeField("From", rule.fromHour, rule.fromMinute, { h, m -> onChange(rule.copy(fromHour = h, fromMinute = m)) }, Modifier.weight(1f))
                    TimeField("To", rule.toHour, rule.toMinute, { h, m -> onChange(rule.copy(toHour = h, toMinute = m)) }, Modifier.weight(1f))
                }
                Hint("A window such as 22:00 to 07:00 continues past midnight.")
            }
            is Condition.DaysOfWeek -> DayPicker(rule.days) { onChange(rule.copy(days = it)) }
            is Condition.WifiConnected -> BoolField("Wi-Fi is", rule.connected, "Connected", "Not connected") { onChange(rule.copy(connected = it)) }
            is Condition.ScreenOn -> BoolField("Screen is", rule.on, "On", "Off") { onChange(rule.copy(on = it)) }
            is Condition.Ringer -> DropdownField(
                label = "Ringer is",
                options = RingerMode.values().toList(),
                selected = rule.mode,
                optionText = { ringerWord(it) },
                onSelect = { onChange(rule.copy(mode = it)) },
                modifier = Modifier.fillMaxWidth(),
            )
            is Condition.PowerSaver -> BoolField("Battery Saver is", rule.on, "On", "Off") { onChange(rule.copy(on = it)) }
            is Condition.AirplaneMode -> BoolField("Airplane mode is", rule.on, "On", "Off") { onChange(rule.copy(on = it)) }
            is Condition.Variable -> {
                OutlinedTextField(
                    value = rule.name,
                    onValueChange = { onChange(rule.copy(name = it.trim())) },
                    label = { Text("Variable name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DropdownField("Is", Comparison.values().toList(), rule.op, { it.symbol }, { onChange(rule.copy(op = it)) }, Modifier.weight(1f))
                    OutlinedTextField(
                        value = rule.value,
                        onValueChange = { onChange(rule.copy(value = it)) },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Hint("Numbers are compared as numbers; anything else as text. An unset variable counts as empty.")
            }
            // Composite rules are flattened by ConditionsSection and never reach here.
            is Condition.MatchAll, is Condition.MatchAny -> Hint(rule.summary())
        }
    }
}

// ---------------------------------------------------------------- Action

fun newAction(kind: ActionKind): Action = kind.create()

@Composable
fun ActionEditor(
    action: Action,
    others: List<Automation>,
    onChange: (Action) -> Unit,
    onOpenScript: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (action) {
            is Action.ShowNotification -> {
                OutlinedTextField(
                    value = action.title,
                    onValueChange = { onChange(action.copy(title = it)) },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TemplateField(action.message, { onChange(action.copy(message = it)) }, "Message", minLines = 2)
            }
            is Action.ShowToast -> TemplateField(action.message, { onChange(action.copy(message = it)) }, "Message", singleLine = true)
            is Action.Speak -> TemplateField(action.text, { onChange(action.copy(text = it)) }, "What to say", minLines = 2)
            is Action.CopyToClipboard -> TemplateField(action.text, { onChange(action.copy(text = it)) }, "Text to copy", minLines = 2)
            is Action.Vibrate -> {
                Text("${action.durationMs} ms", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = action.durationMs.toFloat(),
                    onValueChange = { onChange(action.copy(durationMs = (it / 50f).toInt() * 50)) },
                    valueRange = 50f..2000f,
                )
            }
            is Action.Wait -> {
                Text("${action.seconds} second${if (action.seconds == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = action.seconds.toFloat(),
                    onValueChange = { onChange(action.copy(seconds = it.toInt().coerceIn(1, 10))) },
                    valueRange = 1f..10f,
                    steps = 8,
                )
                Hint("Up to 10 seconds per wait and 30 seconds in total per run.")
            }
            is Action.Flashlight -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FlashlightMode.values().forEach { mode ->
                    FilterChip(
                        selected = action.mode == mode,
                        onClick = { onChange(action.copy(mode = mode)) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            is Action.SetVolume -> {
                DropdownField(
                    label = "Volume",
                    options = VolumeStream.values().toList(),
                    selected = action.stream,
                    optionText = { volumeStreamLabel(it) },
                    onSelect = { onChange(action.copy(stream = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${action.percent}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = action.percent.toFloat(),
                    onValueChange = { onChange(action.copy(percent = (it / 5f).toInt() * 5)) },
                    valueRange = 0f..100f,
                )
                Hint("Android can refuse ring and notification changes while Do Not Disturb is on.")
            }
            Action.PlaySound -> Hint("Plays the notification sound your phone is set to. Silent mode is respected by Android.")
            is Action.SetVariable -> {
                OutlinedTextField(
                    value = action.name,
                    onValueChange = { onChange(action.copy(name = it.trim())) },
                    label = { Text("Variable name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownField(
                    label = "Operation",
                    options = VariableMode.values().toList(),
                    selected = action.mode,
                    optionText = {
                        when (it) {
                            VariableMode.SET -> "Set to"
                            VariableMode.ADD -> "Add (number)"
                            VariableMode.SUBTRACT -> "Subtract (number)"
                            VariableMode.MULTIPLY -> "Multiply (number)"
                            VariableMode.APPEND -> "Append text"
                            VariableMode.DELETE -> "Remove variable"
                        }
                    },
                    onSelect = { onChange(action.copy(mode = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (action.mode != VariableMode.DELETE) {
                    TemplateField(action.value, { onChange(action.copy(value = it)) }, "Value", singleLine = true)
                }
            }
            is Action.RunAutomation -> {
                if (others.isEmpty()) {
                    Hint("Save another automation first, then pick it here.")
                } else {
                    DropdownField(
                        label = "Automation",
                        options = others,
                        selected = others.firstOrNull { it.id == action.automationId },
                        placeholder = if (action.automationId.isBlank()) "Choose…" else action.automationName,
                        optionText = { it.name },
                        onSelect = { onChange(Action.RunAutomation(it.id, it.name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("Runs its rules and actions right away, even if it is turned off.")
                }
            }
            is Action.WriteLog -> TemplateField(action.message, { onChange(action.copy(message = it)) }, "Log message", singleLine = true)
            is Action.RunScript -> {
                if (onOpenScript != null) {
                    Text(
                        action.summary().ifBlank { "(empty script)" },
                        style = CodeTextStyle,
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onOpenScript) { Text("Open in Script editor") }
                } else {
                    OutlinedTextField(
                        value = action.source,
                        onValueChange = { onChange(action.copy(source = it)) },
                        label = { Text("Script") },
                        textStyle = CodeTextStyle,
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            is Action.IfThen -> {
                Text("If", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                ConditionsSection(action.condition, { onChange(action.copy(condition = it ?: Condition.BatteryLevel())) }, required = true)
                NestedActions("Then", action.then, others) { onChange(action.copy(then = it)) }
                NestedActions("Otherwise", action.otherwise, others) { onChange(action.copy(otherwise = it)) }
            }
            is Action.Repeat -> {
                IntField("Times", action.times, 1..20, { onChange(action.copy(times = it)) }, Modifier.fillMaxWidth())
                Hint("Use {{index}} for the round number. Up to 20 rounds.")
                NestedActions("Each round", action.actions, others) { onChange(action.copy(actions = it)) }
            }
        }
    }
}

/** A titled list of smaller action cards, used inside If / else and Repeat. */
@Composable
private fun NestedActions(title: String, actions: List<Action>, others: List<Automation>, onChange: (List<Action>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (actions.isEmpty()) Hint("Nothing here yet.")
        actions.forEachIndexed { index, nested ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(nested.kind().icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(nested.label(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        IconButton(enabled = index > 0, onClick = { onChange(actions.moved(index, -1)) }) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                        }
                        IconButton(enabled = index < actions.lastIndex, onClick = { onChange(actions.moved(index, 1)) }) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                        }
                        IconButton(onClick = { onChange(actions.filterIndexed { i, _ -> i != index }) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove")
                        }
                    }
                    Column(Modifier.padding(end = 8.dp)) {
                        ActionEditor(nested, others, { updated -> onChange(actions.mapIndexed { i, a -> if (i == index) updated else a }) })
                    }
                }
            }
        }
        TextButton(onClick = { adding = true }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Add action", modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (adding) {
        AddActionSheet(onPick = { kind -> adding = false; onChange(actions + kind.create()) }, onDismiss = { adding = false }, allowLogic = false)
    }
}

private fun List<Action>.moved(index: Int, delta: Int): List<Action> {
    val to = index + delta
    if (to !in indices) return this
    val list = toMutableList()
    list.add(to, list.removeAt(index))
    return list
}
