@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androx.app.R
import com.androx.app.model.Trigger
import com.androx.app.ui.MainViewModel
import com.androx.app.ui.components.AndroxMark
import com.androx.app.ui.components.EmptyState
import com.androx.app.ui.components.IconBadge
import com.androx.app.ui.components.formatTimestamp
import com.androx.app.ui.components.transparentListItemColors
import com.androx.app.ui.history.HistoryRow

/**
 * A compact overview, built from plain list rows rather than a dashboard: what is automated, what runs next and what
 * ran last. Creating things is the FAB's job, owned by the root Scaffold.
 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onNew: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val automations by vm.automations.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    val enabled = automations.count { it.enabled }
    val now = System.currentTimeMillis()
    val next = automations
        .filter { it.enabled && it.trigger is Trigger.Schedule }
        .map { it to (it.trigger as Trigger.Schedule).nextRunMillis(now) }
        .minByOrNull { it.second }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AndroxMark(Modifier.width(28.dp).height(14.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Androx")
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 88.dp),
        ) {
            if (automations.isEmpty() && history.isEmpty()) {
                item {
                    EmptyState(
                        icon = painterResource(R.drawable.ic_androx_mark),
                        title = "Automate your phone",
                        message = "Triggers, conditions and actions that run on this device. Nothing leaves it.",
                        modifier = Modifier.padding(top = 64.dp),
                    ) {
                        Button(onClick = onNew) { Text("Create automation") }
                        TextButton(onClick = { vm.addSamples() }) { Text("Add samples") }
                    }
                }
                return@LazyColumn
            }

            item {
                ListItem(
                    leadingContent = { IconBadge(painterResource(R.drawable.ic_bolt)) },
                    headlineContent = {
                        Text(
                            when (automations.size) {
                                0 -> "No automations yet"
                                1 -> "1 automation"
                                else -> "${automations.size} automations"
                            }
                        )
                    },
                    supportingContent = {
                        Text(
                            if (automations.isEmpty()) "Tap + to create one with the Builder, JavaScript or the workflow editor."
                            else "$enabled on · ${automations.size - enabled} off"
                        )
                    },
                    colors = transparentListItemColors(),
                    modifier = Modifier.clickable { if (automations.isEmpty()) onNew() else onOpenAutomations() },
                )
            }
            if (next != null) {
                item {
                    ListItem(
                        leadingContent = { IconBadge(painterResource(R.drawable.ic_schedule)) },
                        headlineContent = { Text("Next scheduled run") },
                        supportingContent = { Text("${next.first.name} · ${formatTimestamp(next.second)}") },
                        colors = transparentListItemColors(),
                        modifier = Modifier.clickable { onOpenAutomations() },
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Recent activity",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (history.isNotEmpty()) TextButton(onClick = onOpenHistory) { Text("See all") }
                }
            }
            if (history.isEmpty()) {
                item {
                    Text(
                        "Runs will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(history.take(3), key = { it.id }) { record -> HistoryRow(record) }
            }
        }
    }
}
