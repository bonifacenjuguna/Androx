@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.automations

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androx.app.R
import com.androx.app.model.Automation
import com.androx.app.model.EditorKind
import com.androx.app.model.summary
import com.androx.app.ui.MainViewModel
import com.androx.app.ui.components.CenteredContent
import com.androx.app.ui.components.ConfirmDialog
import com.androx.app.ui.components.EmptyState
import com.androx.app.ui.components.IconTile
import com.androx.app.ui.components.NewAutomationSheet
import com.androx.app.ui.components.kindIcon
import com.androx.app.ui.components.mayNotify
import com.androx.app.ui.components.relativeTime
import com.androx.app.ui.components.rememberNotificationGate
import com.androx.app.ui.components.transparentItemColors

private val filterLabels = listOf("All", "Active", "Inactive")

@Composable
fun AutomationsScreen(vm: MainViewModel, onCreate: (EditorKind) -> Unit, onEdit: (Automation) -> Unit) {
    val automations by vm.automations.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Automation?>(null) }
    val gate = rememberNotificationGate()
    val focus = remember { FocusRequester() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(searching) { if (searching) focus.requestFocus() }
    BackHandler(enabled = searching) { searching = false; query = "" }

    val lastRuns = remember(history) { history.groupBy { it.automationId }.mapValues { it.value.first() } }
    val shown = automations
        .filter { filter == 0 || (filter == 1 && it.enabled) || (filter == 2 && !it.enabled) }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search automations") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    } else {
                        Text("Automations")
                    }
                },
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                        }
                    }
                },
                actions = {
                    if (searching) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                        }
                    } else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Add sample automations") }, onClick = { menu = false; vm.addSamples() })
                            }
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New automation")
            }
        },
    ) { padding ->
        CenteredContent {
            if (automations.isEmpty()) {
                EmptyState(
                    icon = painterResource(R.drawable.ic_bolt),
                    title = "No automations yet",
                    message = "Create your first automation to get started.",
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Button(onClick = { showSheet = true }) { Text("Create automation") }
                    TextButton(onClick = { vm.addSamples() }) { Text("Add sample automations") }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 88.dp),
                ) {
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
                    if (shown.isEmpty()) {
                        item {
                            EmptyState(
                                icon = painterResource(R.drawable.ic_bolt),
                                title = "No matches",
                                message = if (query.isNotBlank()) "Nothing is called “${query.trim()}”." else "No automations in this filter.",
                                modifier = Modifier.padding(top = 32.dp),
                            )
                        }
                    } else {
                        items(shown, key = { it.id }) { automation ->
                            val lastRun = lastRuns[automation.id]
                            AutomationRow(
                                automation = automation,
                                summary = buildString {
                                    if (!automation.enabled) append("Off · ")
                                    append(automation.trigger.summary())
                                    append(" · ")
                                    append(if (lastRun == null) "Never run" else "Last run " + relativeTime(lastRun.startedAt))
                                },
                                onEdit = { onEdit(automation) },
                                onToggle = { vm.setEnabled(automation.id, it) },
                                onRun = { gate.ensure(automation.mayNotify()) { vm.run(automation) } },
                                onDuplicate = { vm.duplicate(automation) },
                                onDelete = { toDelete = automation },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        NewAutomationSheet(onPick = { showSheet = false; onCreate(it) }, onDismiss = { showSheet = false })
    }
    toDelete?.let { target ->
        ConfirmDialog(
            icon = rememberVectorPainter(Icons.Filled.Delete),
            title = "Delete automation?",
            message = "“${target.name}” and its schedule will be removed. This can't be undone. Past runs stay in History.",
            confirmLabel = "Delete",
            onConfirm = { vm.delete(target); toDelete = null },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
private fun AutomationRow(
    automation: Automation,
    summary: String,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    ListItem(
        leadingContent = { IconTile(kindIcon(automation.editor)) },
        headlineContent = {
            Text(
                automation.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (automation.enabled) scheme.onSurface else scheme.onSurfaceVariant,
            )
        },
        supportingContent = { Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = automation.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics {
                        contentDescription = if (automation.enabled) "Turn off ${automation.name}" else "Turn on ${automation.name}"
                    },
                )
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${automation.name}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Run now") },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                            onClick = { menu = false; onRun() },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { menu = false; onEdit() },
                        )
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
        },
        colors = transparentItemColors(),
        modifier = modifier.fillMaxWidth().clickable(onClick = onEdit),
    )
}
