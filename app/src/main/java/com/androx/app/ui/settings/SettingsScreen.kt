@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androx.app.R
import com.androx.app.data.ThemeMode
import com.androx.app.platform.Permissions
import com.androx.app.ui.MainViewModel
import com.androx.app.ui.components.CenteredContent
import com.androx.app.ui.components.ConfirmDialog
import com.androx.app.ui.components.PreferenceRow
import com.androx.app.ui.components.SectionLabel
import com.androx.app.ui.components.SwitchRow
import com.androx.app.ui.components.rememberNotificationGate
import com.androx.app.ui.components.transparentItemColors

private val historyLimits = listOf(50, 100, 250, 500)

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

    val themeSummary = when (settings.themeMode) {
        ThemeMode.SYSTEM -> "System default"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Settings") }, scrollBehavior = scroll) },
    ) { padding ->
        CenteredContent {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            ) {
                item { SectionLabel("Appearance", Modifier.padding(top = 8.dp)) }
                item {
                    PreferenceRow(
                        title = "Theme",
                        summary = themeSummary,
                        icon = painterResource(R.drawable.ic_appearance),
                        onClick = { showTheme = true },
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    item {
                        SwitchRow(
                            title = "Dynamic colour",
                            summary = "Use colours from your wallpaper instead of black and white",
                            icon = painterResource(R.drawable.ic_palette),
                            checked = settings.dynamicColor,
                            onCheckedChange = { c -> vm.updateSettings { it.copy(dynamicColor = c) } },
                        )
                    }
                }
                item {
                    SwitchRow(
                        title = "Pure black",
                        summary = "True black background in the dark theme",
                        icon = painterResource(R.drawable.ic_dark_mode),
                        checked = settings.pureBlack,
                        onCheckedChange = { c -> vm.updateSettings { it.copy(pureBlack = c) } },
                    )
                }

                item { Divider() }
                item { SectionLabel("Permissions") }
                item {
                    val needsPrompt = status.notificationsNeedRuntimePrompt && !gate.requestedOnce
                    PermissionRow(
                        title = "Notifications",
                        icon = rememberVectorPainter(Icons.Filled.Notifications),
                        granted = status.notificationsAllowed,
                        grantedText = "Allowed",
                        deniedText = "Not allowed",
                        why = "Needed by “Show notification” and androx.notify().",
                        actionLabel = if (needsPrompt) "Allow" else "Settings",
                        showAction = !status.notificationsAllowed,
                        onAction = {
                            if (needsPrompt) gate.requestNow() else Permissions.openNotificationSettings(context)
                        },
                    )
                }
                item {
                    PermissionRow(
                        title = "Exact alarms",
                        icon = painterResource(R.drawable.ic_schedule),
                        granted = status.exactAlarmsAllowed,
                        grantedText = if (status.exactAlarmsApply) "Allowed" else "Not restricted on this Android version",
                        deniedText = "Not allowed. Scheduled runs may be late.",
                        why = "Lets scheduled automations run at the exact minute.",
                        actionLabel = "Settings",
                        showAction = status.exactAlarmsApply && !status.exactAlarmsAllowed,
                        onAction = { Permissions.openExactAlarmSettings(context) },
                    )
                }
                item {
                    PermissionRow(
                        title = "Battery optimisation",
                        icon = painterResource(R.drawable.ic_battery),
                        granted = status.batteryUnrestricted,
                        grantedText = "Unrestricted",
                        deniedText = "Optimised. Android may postpone scheduled runs.",
                        why = "Choose Unrestricted to reduce delays. Android still decides.",
                        actionLabel = "Settings",
                        showAction = !status.batteryUnrestricted,
                        onAction = { Permissions.openBatterySettings(context) },
                        warnWhenDenied = false,
                    )
                }

                item { Divider() }
                item { SectionLabel("History and data") }
                item {
                    PreferenceRow(
                        title = "Keep last runs",
                        summary = "${settings.historyLimit} records",
                        icon = painterResource(R.drawable.ic_history),
                        onClick = { showLimit = true },
                    )
                }
                item {
                    PreferenceRow(
                        title = "Variables",
                        summary = if (variables.isEmpty()) "None stored" else "${variables.size} stored, shared by all automations",
                        icon = painterResource(R.drawable.ic_code),
                        onClick = { showVariables = true },
                    )
                }
                item {
                    PreferenceRow(
                        title = "Clear history",
                        summary = if (history.isEmpty()) "Nothing to clear" else "${history.size} records",
                        icon = rememberVectorPainter(Icons.Filled.Delete),
                        onClick = { confirmClear = true },
                        enabled = history.isNotEmpty(),
                    )
                }

                item { Divider() }
                item { SectionLabel("About") }
                item {
                    PreferenceRow(
                        title = "Androx",
                        summary = "Version 0.0.0",
                        icon = rememberVectorPainter(Icons.Filled.Info),
                    )
                }
                item {
                    PreferenceRow(
                        title = "JavaScript",
                        summary = "Runs in an embedded Rhino interpreter that can only reach the androx API",
                        icon = painterResource(R.drawable.ic_code),
                    )
                }
            }
        }
    }

    if (showTheme) {
        val modes = listOf(ThemeMode.SYSTEM to "System default", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
        RadioDialog(
            title = "Theme",
            options = modes.map { it.second },
            selectedIndex = modes.indexOfFirst { it.first == settings.themeMode },
            onSelect = { index -> vm.updateSettings { it.copy(themeMode = modes[index].first) }; showTheme = false },
            onDismiss = { showTheme = false },
        )
    }
    if (showLimit) {
        RadioDialog(
            title = "Keep last runs",
            options = historyLimits.map { "$it records" },
            selectedIndex = historyLimits.indexOf(settings.historyLimit),
            onSelect = { index -> vm.setHistoryLimit(historyLimits[index]); showLimit = false },
            onDismiss = { showLimit = false },
        )
    }
    if (showVariables) {
        AlertDialog(
            onDismissRequest = { showVariables = false },
            icon = { Icon(painterResource(R.drawable.ic_code), contentDescription = null) },
            title = { Text("Variables") },
            text = {
                if (variables.isEmpty()) {
                    Text("No variables yet. “Set variable” actions and androx.setVar() create them.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(variables.entries.toList(), key = { it.key }) { entry ->
                            ListItem(
                                headlineContent = { Text(entry.key) },
                                supportingContent = { Text(entry.value.ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace) },
                                trailingContent = {
                                    IconButton(onClick = { vm.deleteVariable(entry.key) }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Delete ${entry.key}")
                                    }
                                },
                                colors = transparentItemColors(),
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

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PermissionRow(
    title: String,
    icon: Painter,
    granted: Boolean,
    grantedText: String,
    deniedText: String,
    why: String,
    actionLabel: String,
    showAction: Boolean,
    onAction: () -> Unit,
    warnWhenDenied: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        PreferenceRow(
            title = title,
            icon = icon,
            onClick = if (showAction) onAction else null,
            trailing = if (showAction) ({ FilledTonalButton(onClick = onAction) { Text(actionLabel) } }) else null,
        )
        // Status and reason sit under the row, aligned with its text, so the row itself stays a one-line title.
        Column(Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp)) {
            Text(
                if (granted) grantedText else deniedText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (!granted && warnWhenDenied) scheme.error else scheme.onSurface,
            )
            Text(why, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RadioDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onSelect(index) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
