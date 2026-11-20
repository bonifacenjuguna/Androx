@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androx.app.R
import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.WorkflowCompiler
import com.androx.app.model.describeFlow
import com.androx.app.model.label
import com.androx.app.model.summary
import com.androx.app.ui.components.AddActionSheet
import com.androx.app.ui.components.CenteredContent
import com.androx.app.ui.components.TonalCard
import com.androx.app.ui.components.create
import com.androx.app.ui.components.kind

@Composable
private fun StepHeader(number: String, title: String, icon: Int, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 20.dp, end = 16.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp).weight(1f))
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** Trigger → rules → actions as a guided form. No code. */
@Composable
fun BuilderTab(vm: EditorViewModel, onOpenScript: () -> Unit) {
    val orphans = remember(vm.graph) { WorkflowCompiler.compile(vm.graph).orphanIds.size }
    var addSheet by remember { mutableStateOf(false) }
    val others = vm.otherAutomations

    CenteredContent {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                TonalCard(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.mdi_star_four_points_outline), contentDescription = null, modifier = Modifier.size(22.dp))
                        Text(describeFlow(vm.trigger, vm.condition, vm.actions), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            item { StepHeader("1", "When", R.drawable.mdi_gesture_tap) }
            item {
                TonalCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { TriggerEditor(vm.trigger) { vm.changeTrigger(it) } }
                }
            }
            item { StepHeader("2", "Only if", R.drawable.mdi_tune_variant) }
            item {
                TonalCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (vm.condition == null) {
                            Text(
                                "No rules. The actions run every time.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ConditionsSection(vm.condition, { vm.changeCondition(it) })
                    }
                }
            }
            item { StepHeader("3", "Then do", R.drawable.mdi_play_circle_outline) }
            itemsIndexed(vm.actions) { index, action ->
                ActionBlock(
                    index = index,
                    count = vm.actions.size,
                    action = action,
                    others = others,
                    startExpanded = vm.actions.size <= 3,
                    onChange = { vm.updateAction(index, it) },
                    onMove = { delta -> vm.moveAction(index, delta) },
                    onDuplicate = { vm.duplicateAction(index) },
                    onRemove = { vm.removeAction(index) },
                    onOpenScript = onOpenScript,
                )
            }
            item {
                FilledTonalButton(
                    onClick = { addSheet = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add action", modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (orphans > 0) {
                item {
                    Text(
                        "The Workflow tab also holds $orphans unconnected node${if (orphans == 1) "" else "s"}. They don't run and they are kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (addSheet) {
        AddActionSheet(
            onPick = { kind -> addSheet = false; vm.addAction(kind.create()) },
            onDismiss = { addSheet = false },
        )
    }
}

@Composable
private fun ActionBlock(
    index: Int,
    count: Int,
    action: Action,
    others: List<Automation>,
    startExpanded: Boolean,
    onChange: (Action) -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
    onOpenScript: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(startExpanded) }
    val scheme = MaterialTheme.colorScheme
    TonalCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = if (expanded) 16.dp else 4.dp)) {
            Row(
                Modifier.clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(action.kind().icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    Text("${index + 1}. ${action.label()}", style = MaterialTheme.typography.titleSmall)
                    if (!expanded) {
                        Text(
                            action.summary().ifBlank { "Not set up yet" },
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = scheme.onSurfaceVariant,
                )
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Options for action ${index + 1}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Move up") },
                            leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                            enabled = index > 0,
                            onClick = { menu = false; onMove(-1) },
                        )
                        DropdownMenuItem(
                            text = { Text("Move down") },
                            leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                            enabled = index < count - 1,
                            onClick = { menu = false; onMove(1) },
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; onRemove() },
                        )
                    }
                }
            }
            if (expanded) {
                Column(Modifier.padding(top = 8.dp, end = 12.dp)) {
                    ActionEditor(action = action, others = others, onChange = onChange, onOpenScript = onOpenScript)
                }
            }
        }
    }
}
