@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androx.app.R
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.RunSource
import com.androx.app.ui.MainViewModel
import com.androx.app.ui.components.AndroxTopBar
import com.androx.app.ui.components.EmptyState
import com.androx.app.ui.components.SectionHeader
import com.androx.app.ui.components.dayLabel
import com.androx.app.ui.components.formatTime
import com.androx.app.ui.components.formatTimestamp
import com.androx.app.ui.components.transparentListItemColors

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    var onlyProblems by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val shown = if (onlyProblems) history.filter { it.status == ExecutionStatus.FAILED } else history
    val byDay = shown.groupBy { dayLabel(it.startedAt) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AndroxTopBar(
                title = "History",
                scrollBehavior = scroll,
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear history")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 16.dp),
        ) {
            if (history.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = painterResource(R.drawable.ic_history),
                            title = "No runs yet",
                            message = "When an automation runs, the result shows up here.",
                        )
                    }
                }
                return@LazyColumn
            }
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = !onlyProblems, onClick = { onlyProblems = false }, label = { Text("All runs") })
                    FilterChip(selected = onlyProblems, onClick = { onlyProblems = true }, label = { Text("Failed") })
                }
            }
            if (shown.isEmpty()) {
                item {
                    EmptyState(
                        icon = painterResource(R.drawable.ic_history),
                        title = "No failed runs",
                        message = "Everything that ran recently finished without errors.",
                        modifier = Modifier.padding(top = 48.dp),
                    )
                }
            } else {
                byDay.forEach { (day, records) ->
                    item(key = "day-$day") { SectionHeader(day) }
                    items(records, key = { it.id }) { record ->
                        HistoryRow(record, Modifier.animateItem(), showDay = false)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Clear history?") },
            text = { Text("All ${history.size} records will be removed. Your automations are not affected.") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/**
 * A run as a list row. Tap to expand the message and log output underneath it.
 * [showDay] adds "Today," / "Yesterday," to the time; the grouped History list leaves it out because the header says it.
 */
@Composable
fun HistoryRow(record: ExecutionRecord, modifier: Modifier = Modifier, showDay: Boolean = true) {
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val failed = record.status == ExecutionStatus.FAILED
    val (container, onContainer, icon) = when (record.status) {
        ExecutionStatus.SUCCESS -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Filled.Check)
        ExecutionStatus.FAILED -> Triple(scheme.errorContainer, scheme.onErrorContainer, Icons.Filled.Warning)
        ExecutionStatus.SKIPPED -> Triple(scheme.surfaceVariant, scheme.onSurfaceVariant, Icons.Filled.Info)
    }
    val statusText = when (record.status) {
        ExecutionStatus.SUCCESS -> "Succeeded"
        ExecutionStatus.FAILED -> "Failed"
        ExecutionStatus.SKIPPED -> "Skipped"
    }
    val sourceText = if (record.source == RunSource.MANUAL) "Manual" else "Scheduled"
    val whenText = if (showDay) formatTimestamp(record.startedAt) else formatTime(record.startedAt)
    val message = record.message

    Column(modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { StatusBadge(icon, container, onContainer) },
            headlineContent = { Text(record.automationName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    Text("$whenText · $sourceText · ${record.durationMs} ms")
                    if (!expanded && !message.isNullOrBlank()) {
                        Text(
                            message,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (failed) scheme.error else scheme.onSurfaceVariant,
                        )
                    }
                }
            },
            trailingContent = {
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed) scheme.error else scheme.onSurfaceVariant,
                )
            },
            colors = transparentListItemColors(),
            modifier = Modifier.clickable { expanded = !expanded },
        )
        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier.padding(start = 72.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!message.isNullOrBlank()) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (failed) scheme.error else scheme.onSurface,
                    )
                }
                if (record.logs.isNotEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(scheme.surfaceContainerHigh)
                            .padding(12.dp)
                    ) {
                        Text(
                            record.logs.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                } else if (message.isNullOrBlank()) {
                    Text("No details recorded.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(icon: ImageVector, container: Color, content: Color) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(rememberVectorPainter(icon), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
    }
}
