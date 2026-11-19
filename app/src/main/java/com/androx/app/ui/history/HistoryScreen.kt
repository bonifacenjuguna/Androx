@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.history

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androx.app.R
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.RunSource
import com.androx.app.ui.MainViewModel
import com.androx.app.ui.components.CenteredContent
import com.androx.app.ui.components.ConfirmDialog
import com.androx.app.ui.components.EmptyState
import com.androx.app.ui.components.SectionLabel
import com.androx.app.ui.components.StatusTile
import com.androx.app.ui.components.dayLabel
import com.androx.app.ui.components.formatClockTime
import com.androx.app.ui.components.formatTimestamp
import com.androx.app.ui.components.transparentItemColors

private val filterLabels = listOf("All", "Succeeded", "Failed", "Skipped")

private fun statusWord(status: ExecutionStatus): String = when (status) {
    ExecutionStatus.SUCCESS -> "Succeeded"
    ExecutionStatus.FAILED -> "Failed"
    ExecutionStatus.SKIPPED -> "Skipped"
}

private fun sourceWord(source: RunSource): String = if (source == RunSource.MANUAL) "Manual" else "Scheduled"

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ExecutionRecord?>(null) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    val shown = when (filter) {
        1 -> history.filter { it.status == ExecutionStatus.SUCCESS }
        2 -> history.filter { it.status == ExecutionStatus.FAILED }
        3 -> history.filter { it.status == ExecutionStatus.SKIPPED }
        else -> history
    }
    val groups = shown.groupBy { dayLabel(it.startedAt) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("History") },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Clear history") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                enabled = history.isNotEmpty(),
                                onClick = { menu = false; confirmClear = true },
                            )
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        CenteredContent {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            ) {
                if (history.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            filterLabels.forEachIndexed { index, label ->
                                FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(label) })
                            }
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        EmptyState(
                            icon = painterResource(R.drawable.ic_history),
                            title = if (history.isEmpty()) "No runs yet" else "No matching runs",
                            message = if (history.isEmpty()) "When an automation runs, the result appears here."
                            else "Try a different filter.",
                            modifier = Modifier.padding(top = 48.dp),
                        )
                    }
                } else {
                    groups.forEach { (day, records) ->
                        item(key = "day-$day") { SectionLabel(day) }
                        items(records, key = { it.id }) { record ->
                            RunRow(record, onClick = { selected = record }, showDay = false, modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }

    selected?.let { record -> RunDetailsSheet(record) { selected = null } }

    if (confirmClear) {
        ConfirmDialog(
            icon = rememberVectorPainter(Icons.Filled.Delete),
            title = "Clear history?",
            message = "All ${history.size} records will be removed. Your automations are not affected.",
            confirmLabel = "Clear",
            onConfirm = { vm.clearHistory(); confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
}

/** One finished run as a list row. Used by History and by Home's recent activity. */
@Composable
fun RunRow(record: ExecutionRecord, onClick: () -> Unit, modifier: Modifier = Modifier, showDay: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val whenText = if (showDay) formatTimestamp(record.startedAt) else formatClockTime(record.startedAt)
    ListItem(
        leadingContent = { StatusTile(record.status) },
        headlineContent = { Text(record.automationName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text("$whenText · ${statusWord(record.status)} · ${sourceWord(record.source)}")
                val message = record.message
                if (!message.isNullOrBlank()) {
                    Text(
                        message,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (record.status == ExecutionStatus.FAILED) scheme.error else scheme.onSurfaceVariant,
                    )
                }
            }
        },
        colors = transparentItemColors(),
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun RunDetailsSheet(record: ExecutionRecord, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val message = record.message
    val logText = record.logs.joinToString("\n")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusTile(record.status)
                Column {
                    Text(record.automationName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${statusWord(record.status)} · ${formatTimestamp(record.startedAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Text(
                        "${sourceWord(record.source)} run · ${record.durationMs} ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (!message.isNullOrBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (record.status == ExecutionStatus.FAILED) scheme.errorContainer else scheme.surfaceContainerHigh,
                ) {
                    SelectionContainer {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (record.status == ExecutionStatus.FAILED) scheme.onErrorContainer else scheme.onSurface,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        )
                    }
                }
            }
            if (record.logs.isNotEmpty()) {
                Text("Log", style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHighest) {
                    SelectionContainer {
                        Text(
                            logText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (record.logs.isNotEmpty() || !message.isNullOrBlank()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(listOfNotNull(message, logText.ifBlank { null }).joinToString("\n\n"))) }) {
                        Text("Copy")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
