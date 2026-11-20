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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
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
import com.androx.app.ui.components.Pill
import com.androx.app.ui.components.RingWithLabel
import com.androx.app.ui.components.SectionLabel
import com.androx.app.ui.components.StatusTile
import com.androx.app.ui.components.TonalCard
import com.androx.app.ui.components.dayLabel
import com.androx.app.ui.components.formatClockTime
import com.androx.app.ui.components.formatTimestamp
import com.androx.app.ui.theme.CodeTextStyle

private enum class StatusFilter(val label: String, val status: ExecutionStatus?) {
    ALL("All", null),
    SUCCEEDED("Succeeded", ExecutionStatus.SUCCESS),
    FAILED("Failed", ExecutionStatus.FAILED),
    SKIPPED("Skipped", ExecutionStatus.SKIPPED),
}

fun sourceLabel(source: RunSource): String = when (source) {
    RunSource.MANUAL -> "Manual"
    RunSource.SCHEDULE -> "Scheduled"
    RunSource.BOOT -> "On start"
}

fun formatDuration(ms: Long): String = if (ms < 1000) "$ms ms" else String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    val automations by vm.automations.collectAsStateWithLifecycle()
    val focusId by vm.focusRunId.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var filter by remember { mutableStateOf(StatusFilter.ALL) }
    var automationFilter by remember { mutableStateOf<String?>(null) }
    var automationMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ExecutionRecord?>(null) }

    // A snackbar's "Details" button asks for a particular run.
    LaunchedEffect(focusId, history) {
        val id = focusId ?: return@LaunchedEffect
        history.firstOrNull { it.id == id }?.let { selected = it }
        vm.clearFocusRun()
    }

    val scoped = history.filter { automationFilter == null || it.automationId == automationFilter }
    val visible = scoped.filter { filter.status == null || it.status == filter.status }
    val finished = scoped.filter { it.status != ExecutionStatus.SKIPPED }
    val rate = if (finished.isEmpty()) null else finished.count { it.status == ExecutionStatus.SUCCESS }.toFloat() / finished.size
    val names = history.map { it.automationId to it.automationName }.distinctBy { it.first }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = { Text("History") },
                actions = {
                    if (history.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Clear history") },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    onClick = { menu = false; confirmClear = true },
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        CenteredContent(Modifier.padding(top = padding.calculateTopPadding())) {
            if (history.isEmpty()) {
                EmptyState(
                    icon = painterResource(R.drawable.ic_history),
                    title = "No runs yet",
                    message = "Every time an automation runs, it is recorded here with a step-by-step log.",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        TonalCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                RingWithLabel(rate ?: 0f, if (rate == null) "–" else "${(rate * 100).toInt()}%")
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${scoped.size} run${if (scoped.size == 1) "" else "s"} recorded", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${scoped.count { it.status == ExecutionStatus.SUCCESS }} succeeded · " +
                                            "${scoped.count { it.status == ExecutionStatus.FAILED }} failed · " +
                                            "${scoped.count { it.status == ExecutionStatus.SKIPPED }} skipped",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusFilter.values().forEach { f ->
                                val count = scoped.count { f.status == null || it.status == f.status }
                                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.label} · $count") })
                            }
                            Box {
                                FilterChip(
                                    selected = automationFilter != null,
                                    onClick = { automationMenu = true },
                                    label = { Text(names.firstOrNull { it.first == automationFilter }?.second ?: "Any automation", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                                )
                                DropdownMenu(expanded = automationMenu, onDismissRequest = { automationMenu = false }) {
                                    DropdownMenuItem(text = { Text("Any automation") }, onClick = { automationMenu = false; automationFilter = null })
                                    names.forEach { (id, name) ->
                                        DropdownMenuItem(text = { Text(name) }, onClick = { automationMenu = false; automationFilter = id })
                                    }
                                }
                            }
                        }
                    }
                    if (visible.isEmpty()) {
                        item {
                            EmptyState(
                                icon = painterResource(R.drawable.mdi_filter_variant),
                                title = "Nothing here",
                                message = "No runs match these filters.",
                            )
                        }
                    }
                    var lastDay: String? = null
                    visible.forEach { record ->
                        val day = dayLabel(record.startedAt)
                        if (day != lastDay) {
                            lastDay = day
                            item(key = "day-$day") { SectionLabel(day) }
                        }
                        item(key = record.id) { RunRow(record, onClick = { selected = record }) }
                    }
                }
            }
        }
    }

    selected?.let { record ->
        val canRerun = automations.any { it.id == record.automationId }
        RunDetailsSheet(
            record = record,
            onDismiss = { selected = null },
            onRunAgain = if (canRerun) ({ selected = null; vm.runById(record.automationId, record.automationName) }) else null,
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            icon = painterResource(R.drawable.mdi_delete_outline),
            title = "Clear history?",
            message = "All ${history.size} recorded runs will be removed. Your automations are not affected.",
            confirmLabel = "Clear",
            onConfirm = { confirmClear = false; vm.clearHistory() },
            onDismiss = { confirmClear = false },
        )
    }
}

/** One recorded run as a list row. */
@Composable
fun RunRow(record: ExecutionRecord, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        leadingContent = { StatusTile(record.status) },
        headlineContent = { Text(record.automationName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    "${formatClockTime(record.startedAt)} · ${statusWord(record.status)} · ${sourceLabel(record.source)} · ${formatDuration(record.durationMs)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!record.message.isNullOrBlank()) {
                    Text(record.message, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        colors = com.androx.app.ui.components.transparentItemColors(),
        modifier = modifier.clickable(onClick = onClick),
    )
}

private fun statusWord(status: ExecutionStatus): String = when (status) {
    ExecutionStatus.SUCCESS -> "Succeeded"
    ExecutionStatus.FAILED -> "Failed"
    ExecutionStatus.SKIPPED -> "Skipped"
}

/** Full details and step log of one run. Reused by the editor after "Save and run". */
@Composable
fun RunDetailsSheet(record: ExecutionRecord, onDismiss: () -> Unit, onRunAgain: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusTile(record.status)
                Column(Modifier.weight(1f)) {
                    Text(record.automationName, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatTimestamp(record.startedAt), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(statusWord(record.status))
                Pill(sourceLabel(record.source))
                Pill(formatDuration(record.durationMs))
            }
            if (!record.message.isNullOrBlank()) {
                Surface(
                    color = if (record.status == ExecutionStatus.FAILED) scheme.errorContainer else scheme.surfaceContainerHigh,
                    contentColor = if (record.status == ExecutionStatus.FAILED) scheme.onErrorContainer else scheme.onSurface,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(record.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp).fillMaxWidth())
                }
            }
            if (record.logs.isNotEmpty()) {
                Text("Log", style = MaterialTheme.typography.titleSmall)
                Surface(color = scheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        record.logs.forEach { line ->
                            val isStep = line.trimStart().startsWith("Action ") || line.startsWith("Condition:")
                            Text(
                                line,
                                style = CodeTextStyle,
                                color = if (isStep) scheme.onSurface else scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRunAgain != null) {
                    Button(onClick = onRunAgain) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("Run again", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedButton(onClick = {
                    val text = buildString {
                        append(record.automationName).append(" · ").append(statusWord(record.status)).append('\n')
                        record.message?.let { append(it).append('\n') }
                        record.logs.forEach { append(it).append('\n') }
                    }
                    clipboard.setText(AnnotatedString(text))
                }) { Text("Copy log") }
            }
        }
    }
}
