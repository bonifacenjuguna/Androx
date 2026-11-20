@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androx.app.R
import com.androx.app.data.Accent
import com.androx.app.data.ThemeMode
import com.androx.app.platform.Permissions
import com.androx.app.ui.MainViewModel
import com.androx.app.ui.components.AndroxWordmark
import com.androx.app.ui.components.CenteredContent
import com.androx.app.ui.components.ConfirmDialog
import com.androx.app.ui.components.PreferenceGroup
import com.androx.app.ui.components.PreferenceRow
import com.androx.app.ui.components.SectionLabel
import com.androx.app.ui.components.SwitchRow
import com.androx.app.ui.components.TonalCard
import com.androx.app.ui.components.rememberNotificationGate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val historyLimits = listOf(50, 100, 250, 500)

private val whatsNew = listOf(
    "A new Home: what's next, how things are going, quick runs and ideas.",
    "New triggers: every N minutes or hours, one time, and when the device starts.",
    "Rules can be combined: all or any of battery, time of day, days, Wi-Fi, screen, ringer and more.",
    "New actions: vibrate, toast, speak, play sound, flashlight, volume, clipboard and wait.",
    "If / else and Repeat let one automation make decisions and loop.",
    "Swipe an automation to run it or delete it, with Undo.",
    "Back up and restore everything as a JSON file.",
    "Accent colours, haptics and a refreshed look throughout.",
)

private fun accentColor(accent: Accent, scheme: androidx.compose.material3.ColorScheme): Color =
    if (accent == Accent.ANDROX) scheme.onSurface else Color.hsl(accent.hue, 0.55f, 0.5f)

@Composable
fun SettingsScreen(vm: MainViewModel, onOpenVariables: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val variables by vm.variables.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val automations by vm.automations.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val gate = rememberNotificationGate()
    var status by remember { mutableStateOf(Permissions.status(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = Permissions.status(context) }
    var showTheme by remember { mutableStateOf(false) }
    var showAccent by remember { mutableStateOf(false) }
    var showLimit by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(context.contentResolver, uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestore = uri
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.0.1"
    }

    val themeSummary = when (settings.themeMode) {
        ThemeMode.SYSTEM -> "System default"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val usingDynamic = dynamicAvailable && settings.dynamicColor

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { LargeTopAppBar(title = { Text("Settings") }, scrollBehavior = scroll) },
    ) { padding ->
        CenteredContent(Modifier.padding(top = padding.calculateTopPadding())) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item { SectionLabel("Appearance") }
                item {
                    PreferenceGroup {
                        PreferenceRow(
                            title = "Theme",
                            summary = themeSummary,
                            icon = painterResource(R.drawable.mdi_theme_light_dark),
                            onClick = { showTheme = true },
                        )
                        if (dynamicAvailable) {
                            SwitchRow(
                                title = "Dynamic colour",
                                summary = "Use colours from your wallpaper",
                                icon = painterResource(R.drawable.mdi_palette_outline),
                                checked = settings.dynamicColor,
                                onCheckedChange = { c -> vm.updateSettings { it.copy(dynamicColor = c) } },
                            )
                        }
                        if (!usingDynamic) {
                            PreferenceRow(
                                title = "Accent colour",
                                summary = settings.accent.label,
                                icon = painterResource(R.drawable.mdi_palette_outline),
                                onClick = { showAccent = true },
                            )
                        }
                        SwitchRow(
                            title = "Pure black",
                            summary = "True black background in the dark theme",
                            icon = painterResource(R.drawable.ic_dark_mode),
                            checked = settings.pureBlack,
                            onCheckedChange = { c -> vm.updateSettings { it.copy(pureBlack = c) } },
                        )
                        SwitchRow(
                            title = "Haptic feedback",
                            summary = "A light tap for switches and running automations",
                            icon = painterResource(R.drawable.mdi_vibrate),
                            checked = settings.haptics,
                            onCheckedChange = { c -> vm.updateSettings { it.copy(haptics = c) } },
                        )
                    }
                }

                item { SectionLabel("Permissions") }
                item {
                    PreferenceGroup {
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
                            onAction = { if (needsPrompt) gate.requestNow() else Permissions.openNotificationSettings(context) },
                        )
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
                }

                item { SectionLabel("Data") }
                item {
                    PreferenceGroup {
                        PreferenceRow(
                            title = "Variables",
                            summary = if (variables.isEmpty()) "None stored" else "${variables.size} stored, shared by all automations",
                            icon = painterResource(R.drawable.mdi_variable),
                            onClick = onOpenVariables,
                        )
                        PreferenceRow(
                            title = "Keep last runs",
                            summary = "${settings.historyLimit} records",
                            icon = painterResource(R.drawable.ic_history),
                            onClick = { showLimit = true },
                        )
                        PreferenceRow(
                            title = "Back up",
                            summary = "Save ${automations.size} automation${if (automations.size == 1) "" else "s"} and ${variables.size} variable${if (variables.size == 1) "" else "s"} to a file",
                            icon = painterResource(R.drawable.mdi_file_export_outline),
                            onClick = {
                                val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                exporter.launch("androx-backup-$stamp.json")
                            },
                        )
                        PreferenceRow(
                            title = "Restore",
                            summary = "Load automations and variables from a backup file",
                            icon = painterResource(R.drawable.mdi_file_import_outline),
                            onClick = { importer.launch(arrayOf("*/*")) },
                        )
                        PreferenceRow(
                            title = "Clear history",
                            summary = if (history.isEmpty()) "Nothing to clear" else "${history.size} records",
                            icon = painterResource(R.drawable.mdi_delete_outline),
                            onClick = { confirmClear = true },
                            enabled = history.isNotEmpty(),
                        )
                    }
                }

                item { SectionLabel("About") }
                item {
                    TonalCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AndroxWordmark(height = 28.dp)
                            Text("Version $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "Automations that run on this device. Nothing is sent anywhere.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                item {
                    PreferenceGroup(Modifier.padding(top = 12.dp)) {
                        PreferenceRow(
                            title = "What's new",
                            summary = "Changes in version $version",
                            icon = painterResource(R.drawable.mdi_star_four_points_outline),
                            onClick = { showNew = true },
                        )
                        PreferenceRow(
                            title = "JavaScript",
                            summary = "Runs in an embedded Rhino interpreter that can only reach the androx API",
                            icon = painterResource(R.drawable.ic_code),
                        )
                        PreferenceRow(
                            title = "Icons",
                            summary = "Material Design Icons, Apache License 2.0",
                            icon = painterResource(R.drawable.mdi_information_outline),
                        )
                    }
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
    if (showAccent) {
        val scheme = MaterialTheme.colorScheme
        AlertDialog(
            onDismissRequest = { showAccent = false },
            title = { Text("Accent colour") },
            text = {
                Column {
                    Accent.values().forEach { accent ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                vm.updateSettings { it.copy(accent = accent) }
                                showAccent = false
                            },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(accentColor(accent, scheme)), contentAlignment = Alignment.Center) {
                                if (settings.accent == accent) {
                                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = scheme.surface, modifier = Modifier.size(18.dp))
                                }
                            }
                            Text(accent.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAccent = false }) { Text("Cancel") } },
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
    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            icon = { Icon(painterResource(R.drawable.mdi_star_four_points_outline), contentDescription = null) },
            title = { Text("What's new in $version") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    whatsNew.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = { showNew = false }) { Text("Got it") } },
        )
    }
    pendingRestore?.let { uri ->
        ConfirmDialog(
            icon = painterResource(R.drawable.mdi_file_import_outline),
            title = "Restore from this file?",
            message = "Automations with the same ID are replaced. Everything else on this device is kept.",
            confirmLabel = "Restore",
            onConfirm = { pendingRestore = null; vm.importFrom(context.contentResolver, uri) },
            onDismiss = { pendingRestore = null },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            icon = painterResource(R.drawable.mdi_delete_outline),
            title = "Clear history?",
            message = "All ${history.size} records will be removed. Your automations are not affected.",
            confirmLabel = "Clear",
            onConfirm = { vm.clearHistory(); confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
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
