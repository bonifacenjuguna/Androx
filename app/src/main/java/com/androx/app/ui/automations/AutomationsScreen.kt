@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.automations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androx.app.R
import com.androx.app.model.Automation
import com.androx.app.model.subtitle
import com.androx.app.ui.MainViewModel
import com.androx.app.ui.components.AndroxTopBar
import com.androx.app.ui.components.EmptyState
import com.androx.app.ui.components.IconBadge
import com.androx.app.ui.components.kindIcon
import com.androx.app.ui.components.kindLabel
import com.androx.app.ui.components.mayNotify
import com.androx.app.ui.components.rememberNotificationGate

private enum class Filter(val label: String) { ALL("All"), ON("On"), OFF("Off") }

@Composable
fun AutomationsScreen(vm: MainViewModel, onNew: () -> Unit, onEdit: (Automation) -> Unit) {
    val automations by vm.automations.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var toDelete by remember { mutableStateOf<Automation?>(null) }
    val gate = rememberNotificationGate()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    val shown = when (filter) {
        Filter.ALL -> automations
        Filter.ON -> automations.filter { it.enabled }
        Filter.OFF -> automations.filter { !it.enabled }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { AndroxTopBar("Automations", scroll) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 88.dp),
        ) {
            if (automations.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = painterResource(R.drawable.ic_bolt),
                            title = "No automations yet",
                            message = "Create one with the Builder, JavaScript or the visual workflow editor, or start from three ready-made samples.",
                        ) {
                            Button(onClick = onNew) { Text("Create automation") }
                            TextButton(onClick = { vm.addSamples() }) { Text("Add samples") }
                        }
                    }
                }
                return@LazyColumn
            }
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Filter.values().forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    EmptyState(
                        icon = painterResource(R.drawable.ic_bolt),
                        title = "Nothing here",
                        message = "No automations match this filter.",
                        modifier = Modifier.padding(top = 48.dp),
                    )
                }
            } else {
                items(shown, key = { it.id }) { automation ->
                    AutomationRow(
                        automation = automation,
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

    toDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Delete “${target.name}”?") },
            text = { Text("This removes the automation and its schedule. Past runs stay in History.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(target); toDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

/** One automation as a standard two-line list row: kind icon, name + summary, switch and an overflow menu. */
@Composable
private fun AutomationRow(
    automation: Automation,
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
        leadingContent = {
            IconBadge(kindIcon(automation.editor), Modifier.semantics { contentDescription = kindLabel(automation.editor) })
        },
        headlineContent = { Text(automation.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(automation.subtitle(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
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
                            leadingIcon = { Icon(painterResource(R.drawable.ic_copy), contentDescription = null) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            headlineColor = if (automation.enabled) scheme.onSurface else scheme.onSurfaceVariant,
        ),
        modifier = modifier.clickable(onClick = onEdit),
    )
}
