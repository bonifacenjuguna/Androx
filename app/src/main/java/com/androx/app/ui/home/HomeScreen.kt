@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androx.app.R
import com.androx.app.model.Automation
import com.androx.app.model.EditorKind
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.isTimed
import com.androx.app.model.nextFire
import com.androx.app.model.summary
import com.androx.app.platform.Permissions
import com.androx.app.samples.Ideas
import com.androx.app.ui.MainViewModel
import com.androx.app.ui.components.ActivityBars
import com.androx.app.ui.components.AndroxWordmark
import com.androx.app.ui.components.CenteredContent
import com.androx.app.ui.components.IconTile
import com.androx.app.ui.components.InfoBanner
import com.androx.app.ui.components.LocalHaptics
import com.androx.app.ui.components.NewAutomationSheet
import com.androx.app.ui.components.SectionHeader
import com.androx.app.ui.components.StatTile
import com.androx.app.ui.components.TonalCard
import com.androx.app.ui.components.automationIconChoices
import com.androx.app.ui.components.automationIconRes
import com.androx.app.ui.components.formatCountdown
import com.androx.app.ui.components.formatTimestamp
import com.androx.app.ui.components.greeting
import com.androx.app.ui.components.mayNotify
import com.androx.app.ui.components.rememberNotificationGate
import com.androx.app.ui.history.RunRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Home is an at-a-glance dashboard: what's next, how things are going, quick runs and ideas. */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onCreate: (EditorKind) -> Unit,
    onEdit: (Automation) -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTemplates: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val automations by vm.automations.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var permissions by remember { mutableStateOf(Permissions.status(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissions = Permissions.status(context) }
    val gate = rememberNotificationGate()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val enabled = automations.filter { it.enabled }
    val next = enabled
        .mapNotNull { a -> a.trigger.nextFire(now)?.let { a to it } }
        .minByOrNull { it.second }
    val startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val runsToday = history.count { it.startedAt >= startOfToday }
    val weekAgo = LocalDate.now(zone).minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
    val lastWeek = history.filter { it.startedAt >= weekAgo }
    val finished = lastWeek.filter { it.status != ExecutionStatus.SKIPPED }
    val successRate = if (finished.isEmpty()) null else finished.count { it.status == ExecutionStatus.SUCCESS } * 100 / finished.size
    val failedWeek = lastWeek.count { it.status == ExecutionStatus.FAILED }
    val days = (6 downTo 0).map { LocalDate.now(zone).minusDays(it.toLong()) }
    val perDay = days.map { day ->
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        history.count { it.startedAt in from until to }
    }
    val dayLabels = days.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }

    val needsNotifications = enabled.any { it.mayNotify() }
    val needsExact = enabled.any { it.trigger.isTimed() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { AndroxWordmark(height = 22.dp) },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Browse ideas") }, onClick = { menu = false; onOpenTemplates() })
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(greeting(LocalDateTime.now().hour), style = MaterialTheme.typography.headlineMedium)
                        Text(
                            when {
                                automations.isEmpty() -> "Let's set up your first automation."
                                else -> "${enabled.size} of ${automations.size} on · $runsToday run${if (runsToday == 1) "" else "s"} today"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (automations.isEmpty()) {
                    item {
                        TonalCard(
                            Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                        ) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Automate your phone", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Triggers, rules and actions that run on this device. Nothing leaves it.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { showSheet = true }) { Text("Create") }
                                    OutlinedButton(onClick = onOpenTemplates) { Text("Browse ideas") }
                                }
                            }
                        }
                    }
                } else {
                    item {
                        UpNextCard(
                            next = next,
                            onRun = { a -> haptics.press(); gate.ensure(a.mayNotify()) { vm.run(a) } },
                            onOpen = onEdit,
                            onCreate = { showSheet = true },
                        )
                    }
                }

                if (needsNotifications && !permissions.notificationsAllowed) {
                    item {
                        InfoBanner(
                            icon = painterResource(R.drawable.mdi_bell_sleep_outline),
                            title = "Notifications are off",
                            message = "Some automations show notifications, so they will fail until you allow them.",
                            actionLabel = "Open settings",
                            onAction = onOpenSettings,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                if (needsExact && permissions.exactAlarmsApply && !permissions.exactAlarmsAllowed) {
                    item {
                        InfoBanner(
                            icon = painterResource(R.drawable.mdi_alarm_check),
                            title = "Scheduled runs may be late",
                            message = "Exact alarms aren't allowed, so Android can delay timed automations.",
                            actionLabel = "Fix in settings",
                            onAction = onOpenSettings,
                            modifier = Modifier.padding(horizontal = 16.dp),
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }

                if (history.isNotEmpty()) {
                    item {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile(runsToday.toString(), "Runs today", Modifier.weight(1f))
                            StatTile(
                                if (successRate == null) "–" else "$successRate%",
                                "Success, 7 days",
                                Modifier.weight(1f),
                                container = MaterialTheme.colorScheme.secondaryContainer,
                                content = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            StatTile(
                                failedWeek.toString(),
                                "Failed, 7 days",
                                Modifier.weight(1f),
                                container = if (failedWeek > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
                                content = if (failedWeek > 0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    item {
                        TonalCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Activity", style = MaterialTheme.typography.titleSmall)
                                ActivityBars(perDay, dayLabels)
                            }
                        }
                    }
                }

                if (enabled.isNotEmpty()) {
                    item { SectionHeader("Quick run", actionLabel = "See all", onAction = onOpenAutomations) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(enabled.take(8), key = { it.id }) { automation ->
                                QuickRunCard(automation, onRun = { haptics.press(); gate.ensure(automation.mayNotify()) { vm.run(automation) } }, onOpen = { onEdit(automation) })
                            }
                        }
                    }
                }

                item { SectionHeader("Ideas", actionLabel = "See all", onAction = onOpenTemplates) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(Ideas.all.take(6), key = { it.id }) { idea ->
                            TonalCard(Modifier.width(240.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconTile(painterResource(automationIconChoices.firstOrNull { it.first == idea.icon }?.second ?: R.drawable.ic_bolt))
                                    Text(idea.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        idea.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.height(48.dp),
                                    )
                                    FilledTonalButton(onClick = { vm.addIdea(idea) }) { Text("Add") }
                                }
                            }
                        }
                    }
                }

                if (history.isNotEmpty()) {
                    item { SectionHeader("Recent activity", actionLabel = "See all", onAction = onOpenHistory) }
                    items(history.take(3), key = { "run-" + it.id }) { record ->
                        RunRow(record, onClick = { vm.focusRun(record.id) })
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
private fun UpNextCard(
    next: Pair<Automation, Long>?,
    onRun: (Automation) -> Unit,
    onOpen: (Automation) -> Unit,
    onCreate: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    TonalCard(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        container = scheme.primaryContainer,
        content = scheme.onPrimaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        onClick = if (next != null) ({ onOpen(next.first) }) else null,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (next != null) {
                val (automation, at) = next
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IconTile(
                        painterResource(automationIconRes(automation)),
                        container = scheme.surface.copy(alpha = 0.6f),
                        content = scheme.onPrimaryContainer,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Up next", style = MaterialTheme.typography.labelLarge)
                        Text(automation.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${formatCountdown(at)} · ${formatTimestamp(at)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onRun(automation) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Run now", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        automation.trigger.summary(),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(start = 8.dp),
                    )
                }
            } else {
                Text("Nothing scheduled", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Give an automation a schedule, interval or one-time trigger and it will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onCreate) { Text("Create automation", color = scheme.onPrimaryContainer) }
            }
        }
    }
}

@Composable
private fun QuickRunCard(automation: Automation, onRun: () -> Unit, onOpen: () -> Unit) {
    TonalCard(Modifier.width(156.dp), onClick = onOpen) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconTile(painterResource(automationIconRes(automation)))
            Text(
                automation.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(40.dp),
            )
            Text(
                automation.trigger.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FilledTonalButton(onClick = onRun, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Run", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
