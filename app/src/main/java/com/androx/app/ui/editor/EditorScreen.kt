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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import com.androx.app.R
import com.androx.app.model.EditorKind
import com.androx.app.ui.components.ConfirmDialog
import com.androx.app.ui.components.EmptyState
import com.androx.app.ui.components.kindLabel
import com.androx.app.ui.components.rememberNotificationGate
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(vm: EditorViewModel, onClose: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gate = rememberNotificationGate()
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val ready = !vm.busy && !vm.loading && !vm.notFound

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = vm.dirty) { confirmDiscard = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "New automation" else "Edit automation") },
                navigationIcon = {
                    IconButton(onClick = { if (vm.dirty) confirmDiscard = true else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(enabled = ready, onClick = { gate.ensure(vm.needsNotifications()) { vm.saveAndRun() } }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Save and run now")
                    }
                    TextButton(enabled = ready, onClick = { vm.save { scope.launch { onClose() } } }) { Text("Save") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Enabled") },
                                leadingIcon = { if (vm.enabled) Icon(Icons.Filled.Check, contentDescription = "On") },
                                onClick = { menu = false; vm.changeEnabled(!vm.enabled) },
                            )
                            if (!vm.isNew) {
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    onClick = { menu = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                vm.notFound -> EmptyState(
                    icon = painterResource(R.drawable.ic_bolt),
                    title = "Automation not found",
                    message = "It may have been deleted.",
                    modifier = Modifier.align(Alignment.Center),
                ) { Button(onClick = onClose) { Text("Close") } }
                else -> Column(Modifier.fillMaxSize()) {
                    TextField(
                        value = vm.name,
                        onValueChange = { vm.changeName(it) },
                        placeholder = { Text("Automation name") },
                        textStyle = MaterialTheme.typography.titleLarge,
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TabRow(selectedTabIndex = vm.tab.ordinal) {
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
        ConfirmDialog(
            title = "Discard changes?",
            message = "Your edits to this automation haven't been saved.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            onConfirm = { confirmDiscard = false; onClose() },
            onDismiss = { confirmDiscard = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            icon = rememberVectorPainter(Icons.Filled.Delete),
            title = "Delete automation?",
            message = "This removes “${vm.name.ifBlank { "this automation" }}” and its schedule. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; vm.delete { scope.launch { onClose() } } },
            onDismiss = { confirmDelete = false },
        )
    }
}
