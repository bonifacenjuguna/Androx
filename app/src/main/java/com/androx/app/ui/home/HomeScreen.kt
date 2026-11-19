@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androx.app.R
import com.androx.app.model.Automation
import com.androx.app.model.EditorKind
import com.androx.app.model.Trigger
import com.androx.app.model.summary
import com.androx.app.ui.MainViewModel
import com.androx.app.ui.components.AndroxMark
import com.androx.app.ui.components.CenteredContent
import com.androx.app.ui.components.EmptyState
import com.androx.app.ui.components.IconTile
import com.androx.app.ui.components.NewAutomationSheet
import com.androx.app.ui.components.PreferenceRow
import com.androx.app.ui.components.SectionLabel
import com.androx.app.ui.components.formatTimestamp
import com.androx.app.ui.components.kindIcon
import com.androx.app.ui.components.mayNotify
import com.androx.app.ui.components.rememberNotificationGate
import com.androx.app.ui.components.transparentItemColors
import com.androx.app.ui.history.RunRow

/** Home is a short, list-first overview: what is on, what runs next, what just happened. */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onCreate: (EditorKind) -> Unit,
    onEdit: (Automation) -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val automations by vm.automations.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val gate = rememberNotificationGate()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    val enabled = automations.filter { it.enabled }
    val now = System.currentTimeMillis()
    val next = enabled
        .filter { it.trigger is Trigger.Schedule }
        .map { it to (it.trigger as Trigger.Schedule).nextRunMillis(now) }
        .minByOrNull { it.second }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AndroxMark(Modifier.width(30.dp).height(15.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Androx")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Add sample automations") }, onClick = { menu = false; vm.addSamples() })
                            DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onOpenSettings() })
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        CenteredContent {
            if (automations.isEmpty()) {
                EmptyState(
                    icon = painterResource(R.drawable.ic_bolt),
                    title = "No automations yet",
                    message = "Automations run on this device and nothing leaves it. Create one with the Builder, JavaScript or a visual workflow.",
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Button(onClick = { showSheet = true }) { Text("Create automation") }
                    TextButton(onClick = { vm.addSamples() }) { Text("Add sample automations") }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
                ) {
                    item {
                        ListItem(
                            headlineContent = {
                                Text("${enabled.size} of ${automations.size} automation${if (automations.size == 1) "" else "s"} on")
                            },
                            supportingContent = {
                                Text(
                                    if (next != null) "Next: ${next.first.name}, ${formatTimestamp(next.second)}"
                                    else "Nothing is scheduled"
                                )
                            },
                            leadingContent = { IconTile(painterResource(R.drawable.ic_bolt)) },
                            colors = transparentItemColors(),
                            modifier = Modifier.clickable(onClick = onOpenAutomations),
                        )
                    }
                    item { SectionLabel("Quick actions") }
                    item {
                        PreferenceRow(
                            title = "Create automation",
                            icon = rememberVectorPainter(Icons.Filled.Add),
                            onClick = { showSheet = true },
                        )
                    }
                    item {
                        PreferenceRow(
                            title = "View history",
                            summary = if (history.isEmpty()) "No runs yet" else "${history.size} recorded run${if (history.size == 1) "" else "s"}",
                            icon = painterResource(R.drawable.ic_history),
                            onClick = onOpenHistory,
                        )
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("Turned on", Modifier.weight(1f))
                            TextButton(onClick = onOpenAutomations, modifier = Modifier.padding(end = 4.dp)) { Text("See all") }
                        }
                    }
                    if (enabled.isEmpty()) {
                        item {
                            Text(
                                "Every automation is turned off.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        enabled.take(4).forEach { automation ->
                            item(key = "auto-" + automation.id) {
                                ListItem(
                                    headlineContent = { Text(automation.name) },
                                    supportingContent = { Text(automation.trigger.summary()) },
                                    leadingContent = { IconTile(kindIcon(automation.editor)) },
                                    trailingContent = {
                                        IconButton(onClick = { gate.ensure(automation.mayNotify()) { vm.run(automation) } }) {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = "Run ${automation.name}")
                                        }
                                    },
                                    colors = transparentItemColors(),
                                    modifier = Modifier.clickable { onEdit(automation) },
                                )
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("Recent activity", Modifier.weight(1f))
                            if (history.isNotEmpty()) {
                                TextButton(onClick = onOpenHistory, modifier = Modifier.padding(end = 4.dp)) { Text("See all") }
                            }
                        }
                    }
                    if (history.isEmpty()) {
                        item {
                            Text(
                                "Runs show up here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        history.take(3).forEach { record ->
                            item(key = "run-" + record.id) { RunRow(record, onClick = onOpenHistory) }
                        }
                    }
                }
            }
        }
    }

    if (showSheet) {
        NewAutomationSheet(onPick = { showSheet = false; onCreate(it) }, onDismiss = { showSheet = false })
    }
}
