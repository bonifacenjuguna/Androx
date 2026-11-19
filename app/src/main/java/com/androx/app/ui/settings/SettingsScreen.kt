@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androx.app.R
import com.androx.app.data.ThemeMode
import com.androx.app.platform.Permissions
import com.androx.app.ui.MainViewModel
import com.androx.app.ui.components.AndroxMark
import com.androx.app.ui.components.AndroxTopBar
import com.androx.app.ui.components.RadioRow
import com.androx.app.ui.components.SectionHeader
import com.androx.app.ui.components.rememberNotificationGate
import com.androx.app.ui.components.transparentListItemColors

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> "System default"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val variables by vm.variables.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val gate = rememberNotificationGate()
    var status by remember { mutableStateOf(Permissions.status(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = Permissions.status(context) }
    var showTheme by remember { mutableStateOf(false) }
    var showVariables by remember { mutableStateOf(false) }
    var showLimit by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.0.0"
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { AndroxTopBar("Settings", scroll) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp),
        ) {
            item { SectionHeader("Appearance", Modifier.padding(top = 8.dp)) }
            item {
                PreferenceRow(
                    title = "Theme",
                    summary = themeLabel(settings.themeMode),
                    onClick = { showTheme = true },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    SwitchRow(
                        title = "Dynamic colour",
                        summary = "Use colours from your wallpaper instead of the Androx monochrome palette.",
                        checked = settings.dynamicColor,
                        onChange = { c -> vm.updateSettings { it.copy(dynamicColor = c) } },
                    )
                }
            }
            item {
                SwitchRow(
                    title = "Pure black dark theme",
                    summary = "True black backgrounds in dark mode.",
                    checked = settings.pureBlack,
                    onChange = { c -> vm.updateSettings { it.copy(pureBlack = c) } },
                )
            }

            item { Divided() }
            item { SectionHeader("History and data") }
            item {
                PreferenceRow(
                    title = "Keep last runs",
                    summary = "${settings.historyLimit} records",
                    onClick = { showLimit = true },
                )
            }
            item {
                PreferenceRow(
                    title = "Variables",
                    summary = if (variables.isEmpty()) "None stored yet" else "${variables.size} stored · shared by all automations",
                    onClick = { showVariables = true },
                )
            }
            item {
                PreferenceRow(
                    title = "Clear history",
                    summary = "${history.size} records",
                    onClick = if (history.isNotEmpty()) ({ confirmClear = true }) else null,
                )
            }

            item { Divided() }
            item { SectionHeader("Permissions") }
            item {
                val prompt = status.notificationsNeedRuntimePrompt && !gate.requestedOnce
                PermissionRow(
                    icon = rememberVectorPainter(Icons.Filled.Notifications),
                    title = "Notifications",
                    why = "Needed by the “Show notification” action and by scripts that call androx.notify().",
                    granted = status.notificationsAllowed,
                    hint = if (prompt) "Tap to allow" else "Tap to open settings",
                    onClick = {
                        if (prompt) gate.requestNow() else Permissions.openNotificationSettings(context)
                    },
                )
            }
            item {
                PermissionRow(
                    icon = painterResource(R.drawable.ic_schedule),
                    title = "Exact alarms",
                    why = if (status.exactAlarmsApply) "Lets scheduled automations run at the exact minute. Without it Android may delay them by several minutes."
                    else "Not restricted on this version of Android.",
                    granted = status.exactAlarmsAllowed,
                    hint = if (status.exactAlarmsApply) "Tap to open settings" else null,
                    onClick = { Permissions.openExactAlarmSettings(context) },
                )
            }
            item {
                PermissionRow(
                    icon = painterResource(R.drawable.ic_battery),
                    title = "Battery optimisation",
                    why = "Scheduled runs can be postponed while the phone is idle. Choosing “Unrestricted” for Androx reduces that, but Android still decides.",
                    granted = status.batteryUnrestricted,
                    grantedLabel = "Unrestricted",
                    deniedLabel = "Optimised",
                    hint = "Tap to open settings",
                    onClick = { Permissions.openBatterySettings(context) },
                )
            }

            item { Divided() }
            item { SectionHeader("About") }
            item {
                ListItem(
                    leadingContent = {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            AndroxMark(Modifier.width(28.dp).height(14.dp))
                        }
                    },
                    headlineContent = { Text("Androx") },
                    supportingContent = {
                        Text("Version $version\nJavaScript runs in an embedded Rhino interpreter that can only reach the androx API.")
                    },
                    colors = transparentListItemColors(),
                )
            }
        }
    }

    if (showTheme) {
        AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text("Theme") },
            text = {
                Column {
                    ThemeMode.values().forEach { mode ->
                        RadioRow(
                            label = themeLabel(mode),
                            selected = settings.themeMode == mode,
                            onClick = { vm.updateSettings { it.copy(themeMode = mode) }; showTheme = false },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTheme = false }) { Text("Cancel") } },
        )
    }

    if (showLimit) {
        AlertDialog(
            onDismissRequest = { showLimit = false },
            title = { Text("Keep last runs") },
            text = {
                Column {
                    listOf(50, 100, 250, 500).forEach { n ->
                        RadioRow(
                            label = "$n records",
                            selected = settings.historyLimit == n,
                            onClick = { vm.setHistoryLimit(n); showLimit = false },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLimit = false }) { Text("Cancel") } },
        )
    }

    if (showVariables) {
        AlertDialog(
            onDismissRequest = { showVariables = false },
            title = { Text("Variables") },
            text = {
                if (variables.isEmpty()) {
                    Text("No variables yet. Actions and scripts create them with “Set variable” or androx.setVar().")
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(variables.entries.toList(), key = { it.key }) { (name, value) ->
                            ListItem(
                                headlineContent = { Text(name) },
                                supportingContent = { Text(value.ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace) },
                                trailingContent = {
                                    IconButton(onClick = { vm.deleteVariable(name) }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Delete $name")
                                    }
                                },
                                colors = transparentListItemColors(),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showVariables = false }) { Text("Close") } },
            dismissButton = {
                if (variables.isNotEmpty()) TextButton(onClick = { vm.clearVariables() }) { Text("Clear all") }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All ${history.size} records will be removed.") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Divided() {
    HorizontalDivider(Modifier.padding(top = 8.dp))
}

/** A standard preference row: title, optional summary, whole row tappable. */
@Composable
private fun PreferenceRow(title: String, summary: String? = null, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (summary != null) ({ Text(summary) }) else null,
        colors = transparentListItemColors(),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

/** A switch preference. The whole row toggles, and the Switch itself is just the indicator (one touch target). */
@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = transparentListItemColors(),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun PermissionRow(
    icon: Painter,
    title: String,
    why: String,
    granted: Boolean,
    hint: String?,
    onClick: () -> Unit,
    grantedLabel: String = "Allowed",
    deniedLabel: String = "Not allowed",
) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant) },
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(why)
                Text(
                    (if (granted) grantedLabel else deniedLabel) + if (hint != null) " · $hint" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (granted) scheme.onSurface else scheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        colors = transparentListItemColors(),
        modifier = if (hint != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
