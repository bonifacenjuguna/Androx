@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androx.app.model.Action
import com.androx.app.model.Condition
import com.androx.app.model.WorkflowCompiler
import com.androx.app.model.label
import com.androx.app.ui.components.AddActionSheet
import com.androx.app.ui.components.CenteredContent
import com.androx.app.ui.components.SectionLabel
import com.androx.app.ui.components.SwitchRow
import com.androx.app.ui.components.actionIcon

/** Trigger → optional condition → actions, as a plain form. No code. */
@Composable
fun BuilderTab(vm: EditorViewModel, onOpenScript: () -> Unit) {
    val orphans = remember(vm.graph) { WorkflowCompiler.compile(vm.graph).orphanIds.size }
    var addSheet by remember { mutableStateOf(false) }
    val others = vm.otherAutomations
    val condition = vm.condition

    CenteredContent {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {
            item { SectionLabel("Trigger", Modifier.padding(top = 4.dp)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    TriggerEditor(vm.trigger) { vm.changeTrigger(it) }
                }
            }
            item { SectionLabel("Condition") }
            item {
                SwitchRow(
                    title = "Only run if",
                    summary = if (condition == null) "Off. The actions always run." else "The actions run only when this check passes.",
                    checked = condition != null,
                    onCheckedChange = { on -> vm.changeCondition(if (on) Condition.BatteryLevel() else null) },
                )
            }
            if (condition != null) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        ConditionEditor(condition) { vm.changeCondition(it) }
                    }
                }
            }
            item { SectionLabel("Actions") }
            itemsIndexed(vm.actions) { index, action ->
                ActionBlock(
                    index = index,
                    count = vm.actions.size,
                    action = action,
                    others = others,
                    onChange = { vm.updateAction(index, it) },
                    onMove = { delta -> vm.moveAction(index, delta) },
                    onRemove = { vm.removeAction(index) },
                    onOpenScript = onOpenScript,
                )
            }
            item {
                OutlinedButton(
                    onClick = { addSheet = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
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
            onPick = { index -> addSheet = false; vm.addAction(newActionOfKind(index)) },
            onDismiss = { addSheet = false },
        )
    }
}

@Composable
private fun ActionBlock(
    index: Int,
    count: Int,
    action: Action,
    others: List<com.androx.app.model.Automation>,
    onChange: (Action) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onOpenScript: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(actionIcon(action.kindIndex()), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${index + 1}. ${action.label()}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
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
                            text = { Text("Remove") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; onRemove() },
                        )
                    }
                }
            }
            Column(Modifier.padding(end = 12.dp)) {
                ActionEditor(action = action, others = others, onChange = onChange, onOpenScript = onOpenScript)
            }
        }
    }
}
