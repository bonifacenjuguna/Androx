@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androx.app.R
import com.androx.app.model.EditorKind
import com.androx.app.ui.components.EmptyState
import com.androx.app.ui.components.kindLabel
import com.androx.app.ui.components.rememberNotificationGate
import kotlinx.coroutines.launch

/**
 * Full-screen editor following the Material 3 "full-screen dialog" pattern: close (X) on the left, Save on the right.
 * The three tabs are three views of the same automation.
 */
@Composable
fun EditorScreen(vm: EditorViewModel, onClose: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gate = rememberNotificationGate()
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = vm.dirty) { confirmDiscard = true }

    Scaffold(
        // Insets are applied explicitly below so the keyboard and the navigation bar are not counted twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding().imePadding()) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "New automation" else "Edit automation") },
                navigationIcon = {
                    IconButton(onClick = { if (vm.dirty) confirmDiscard = true else onClose() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    IconButton(
                        enabled = !vm.busy && !vm.loading && !vm.notFound,
                        onClick = { gate.ensure(vm.needsNotifications()) { vm.saveAndRun() } },
                    ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Save and run now") }
                    TextButton(
                        enabled = !vm.busy && !vm.loading && !vm.notFound,
                        onClick = { vm.save { scope.launch { onClose() } } },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().imePadding()) {
            when {
                vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                vm.notFound -> EmptyState(
                    icon = painterResource(R.drawable.ic_bolt),
                    title = "This automation no longer exists",
                    message = "It may have been deleted.",
                    modifier = Modifier.align(Alignment.Center),
                ) { Button(onClick = onClose) { Text("Close") } }
                else -> Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = vm.name,
                        onValueChange = { vm.changeName(it) },
                        label = { Text("Name") },
                        singleLine = true,
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (vm.enabled) "On" else "Off",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = vm.enabled,
                                    onCheckedChange = { vm.changeEnabled(it) },
                                    modifier = Modifier.semantics { contentDescription = "Automation enabled" },
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                    )
                    PrimaryTabRow(selectedTabIndex = vm.tab.ordinal) {
                        EditorKind.values().forEach { kind ->
                            Tab(
                                selected = vm.tab == kind,
                                onClick = { vm.selectTab(kind) },
                                text = { Text(kindLabel(kind)) },
                            )
                        }
                    }
                    AnimatedContent(
                        targetState = vm.tab,
                        modifier = Modifier.weight(1f),
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "editor-tab",
                    ) { kind ->
                        when (kind) {
                            EditorKind.BUILDER -> BuilderTab(vm, onOpenScript = { vm.selectTab(EditorKind.SCRIPT) })
                            EditorKind.SCRIPT -> ScriptTab(vm)
                            EditorKind.WORKFLOW -> WorkflowTab(vm) { message -> scope.launch { snackbar.showSnackbar(message) } }
                        }
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this automation haven't been saved.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onClose() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}
