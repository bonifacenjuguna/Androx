@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.automations

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androx.app.R
import com.androx.app.data.AutomationSort
import com.androx.app.model.Automation
import com.androx.app.model.EditorKind
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.Trigger
import com.androx.app.model.nextFire
import com.androx.app.model.subtitle
import com.androx.app.ui.MainViewModel
import com.androx.app.ui.components.AndroxSwitch
import com.androx.app.ui.components.CenteredContent
import com.androx.app.ui.components.EmptyState
import com.androx.app.ui.components.IconTile
import com.androx.app.ui.components.LocalHaptics
import com.androx.app.ui.components.NewAutomationSheet
import com.androx.app.ui.components.Pill
import com.androx.app.ui.components.formatCountdown
import com.androx.app.ui.components.kind
import com.androx.app.ui.components.automationIconRes
import com.androx.app.ui.components.mayNotify
import com.androx.app.ui.components.relativeTime
import com.androx.app.ui.components.rememberNotificationGate

private enum class Filter(val label: String) { ALL("All"), ACTIVE("Active"), INACTIVE("Inactive") }

@Composable
fun AutomationsScreen(
    vm: MainViewModel,
    onCreate: (EditorKind) -> Unit,
    onEdit: (Automation) -> Unit,
    onOpenTemplates: () -> Unit,
) {
    val automations by vm.automations.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val gate = rememberNotificationGate()
    val haptics = LocalHaptics.current
    val listState = rememberLazyListState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var filter by remember { mutableStateOf(Filter.ALL) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }

    val lastRun: Map<String, ExecutionRecord> = remember(history) {
        val map = HashMap<String, ExecutionRecord>()
        history.forEach { if (!map.containsKey(it.automationId)) map[it.automationId] = it }
        map
    }
    val sorted = remember(automations, settings.sort, lastRun) {
        when (settings.sort) {
            AutomationSort.EDITED -> automations.sortedByDescending { it.updatedAt }
            AutomationSort.NAME -> automations.sortedBy { it.name.lowercase() }
            AutomationSort.LAST_RUN -> automations.sortedByDescending { lastRun[it.id]?.startedAt ?: 0L }
            AutomationSort.TRIGGER -> automations.sortedWith(compareBy({ it.trigger.kind().ordinal }, { it.name.lowercase() }))
        }
    }
    val visible = sorted.filter {
        (filter == Filter.ALL || (filter == Filter.ACTIVE) == it.enabled) &&
            (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true))
    }
    val counts = mapOf(
        Filter.ALL to automations.size,
        Filter.ACTIVE to automations.count { it.enabled },
        Filter.INACTIVE to automations.count { !it.enabled },
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = { Text("Automations") },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(
                            if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searching) "Close search" else "Search",
                        )
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(painterResource(R.drawable.mdi_sort_variant), contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            AutomationSort.values().forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    leadingIcon = {
                                        if (settings.sort == option) Icon(painterResource(R.drawable.mdi_check_circle_outline), contentDescription = "Selected")
                                    },
                                    onClick = { sortMenu = false; vm.updateSettings { s -> s.copy(sort = option) } },
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showSheet = true },
                expanded = !listState.canScrollBackward,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New") },
            )
        },
    ) { padding ->
        CenteredContent(Modifier.padding(top = padding.calculateTopPadding())) {
            if (loaded && automations.isEmpty()) {
                EmptyState(
                    icon = painterResource(R.drawable.ic_bolt),
                    title = "No automations yet",
                    message = "Create one, start from an idea, or add samples to see how things work.",
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Button(onClick = { showSheet = true }) { Text("Create automation") }
                    OutlinedButton(onClick = onOpenTemplates) { Text("Browse ideas") }
                    androidx.compose.material3.TextButton(onClick = { vm.addSamples() }) { Text("Add samples") }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 112.dp),
                ) {
                    if (searching) {
                        item {
                            TextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Search automations") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                singleLine = true,
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    item {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Filter.values().forEach { f ->
                                FilterChip(
                                    selected = filter == f,
                                    onClick = { filter = f },
                                    label = { Text("${f.label} · ${counts[f] ?: 0}") },
                                )
                            }
                        }
                    }
                    if (visible.isEmpty() && automations.isNotEmpty()) {
                        item {
                            EmptyState(
                                icon = painterResource(R.drawable.mdi_filter_variant),
                                title = "Nothing matches",
                                message = if (query.isNotBlank()) "No automation is called “${query.trim()}”." else "No ${filter.label.lowercase()} automations.",
                            )
                        }
                    }
                    items(visible, key = { it.id }) { automation ->
                        SwipeRow(
                            automation = automation,
                            lastRun = lastRun[automation.id],
                            onToggle = { vm.setEnabled(automation.id, it) },
                            onOpen = { onEdit(automation) },
                            onRun = { haptics.press(); gate.ensure(automation.mayNotify()) { vm.run(automation) } },
                            onDuplicate = { vm.duplicate(automation) },
                            onDelete = { vm.delete(automation) },
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        NewAutomationSheet(
            onPick = { showSheet = false; onCreate(it) },
            onDismiss = { showSheet = false },
            onTemplates = { showSheet = false; onOpenTemplates() },
        )
    }
}

@Composable
private fun SwipeRow(
    automation: Automation,
    lastRun: ExecutionRecord?,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onRun(); false }
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); true }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        backgroundContent = {
            val direction = state.dismissDirection
            val color by animateColorAsState(
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> scheme.primaryContainer
                    SwipeToDismissBoxValue.EndToStart -> scheme.errorContainer
                    SwipeToDismissBoxValue.Settled -> Color.Transparent
                },
                label = "swipe-bg",
            )
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(color).padding(horizontal = 24.dp),
                contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Icon(Icons.Filled.PlayArrow, contentDescription = "Run", tint = scheme.onPrimaryContainer)
                    SwipeToDismissBoxValue.EndToStart -> Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = scheme.onErrorContainer)
                    SwipeToDismissBoxValue.Settled -> Unit
                }
            }
        },
    ) {
        AutomationCard(automation, lastRun, onToggle, onOpen, onRun, onDuplicate, onDelete)
    }
}

@Composable
private fun AutomationCard(
    automation: Automation,
    lastRun: ExecutionRecord?,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val next = if (automation.enabled) automation.trigger.nextFire(now) else null
    val tint = when (automation.trigger) {
        Trigger.Manual -> scheme.secondaryContainer to scheme.onSecondaryContainer
        Trigger.DeviceBoot -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Surface(color = scheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(onClick = onOpen).padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp).alpha(if (automation.enabled) 1f else 0.62f),
            verticalAlignment = Alignment.Top,
        ) {
            IconTile(painterResource(automationIconRes(automation)), container = tint.first, content = tint.second)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(automation.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    automation.subtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (lastRun != null) {
                        val (container, content) = when (lastRun.status) {
                            ExecutionStatus.SUCCESS -> scheme.secondaryContainer to scheme.onSecondaryContainer
                            ExecutionStatus.FAILED -> scheme.errorContainer to scheme.onErrorContainer
                            ExecutionStatus.SKIPPED -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
                        }
                        val word = when (lastRun.status) {
                            ExecutionStatus.SUCCESS -> "Ran"
                            ExecutionStatus.FAILED -> "Failed"
                            ExecutionStatus.SKIPPED -> "Skipped"
                        }
                        Pill("$word ${relativeTime(lastRun.startedAt)}", container = container, content = content)
                    }
                    if (next != null) {
                        Pill(formatCountdown(next), icon = painterResource(R.drawable.mdi_clock_outline))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                AndroxSwitch(checked = automation.enabled, onCheckedChange = onToggle, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${automation.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Run now") },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                            onClick = { menu = false; onRun() },
                        )
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}
