@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.androx.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun AndroxMark(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Icon(painterResource(R.drawable.ic_androx_mark), contentDescription = "Androx", modifier = modifier, tint = tint)
}

/** Material 3 exposed dropdown used for every "pick one" field. */
@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    optionText: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.let(optionText) ?: placeholder,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionText(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** "Today", "Yesterday" or a localised date. */
fun dayLabel(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> dateFormatter.format(date)
    }
}

fun formatClockTime(millis: Long): String = timeFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** "Today, 14:32", "Yesterday, 09:05" or "12 Nov 2026, 18:00". */
fun formatTimestamp(millis: Long): String = "${dayLabel(millis)}, ${formatClockTime(millis)}"

/** "in 2 h 14 min", "in 35 min", "in under a minute". */
fun formatCountdown(targetMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val minutes = ((targetMillis - nowMillis) / 60_000L).coerceAtLeast(0)
    val days = minutes / (60 * 24)
    val hours = (minutes % (60 * 24)) / 60
    val mins = minutes % 60
    return when {
        days > 0 -> "in $days d ${hours} h"
        hours > 0 -> "in $hours h $mins min"
        minutes > 0 -> "in $minutes min"
        else -> "in under a minute"
    }
}

fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}
