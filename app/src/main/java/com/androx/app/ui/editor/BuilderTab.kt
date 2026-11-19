@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.androx.app.R
import com.androx.app.model.Condition
import com.androx.app.model.WorkflowCompiler
import com.androx.app.model.label

/**
 * One step of the automation. A step is a real unit (it can be moved, switched on or removed), so a tonal card is the
 * right container here; the fields inside sit directly on it without further nesting.
 */
@Composable
private fun StepCard(
    icon: Painter,
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            trailing()
        }
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Trigger → optional condition → actions, with no code. */
@Composable
fun BuilderTab(vm: EditorViewModel, onOpenScript: () -> Unit) {
    val orphans = remember(vm.graph) { WorkflowCompiler.compile(vm.graph).orphanIds.size }
    var addMenu by remember { mutableStateOf(false) }
    val others = vm.otherAutomations

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StepCard(icon = painterResource(R.drawable.ic_bolt), title = "When") {
                TriggerEditor(vm.trigger) { vm.changeTrigger(it) }
            }
        }
        item {
            val condition = vm.condition
            StepCard(
                icon = rememberVectorPainter(Icons.Filled.CheckCircle),
                title = "Only if",
                trailing = {
                    Switch(
                        checked = condition != null,
                        onCheckedChange = { on -> vm.changeCondition(if (on) Condition.BatteryLevel() else null) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            ) {
                if (condition == null) {
                    Text(
                        "No condition: the actions always run.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ConditionEditor(condition) { vm.changeCondition(it) }
                }
            }
        }
        itemsIndexed(vm.actions) { index, action ->
            var menu by remember { mutableStateOf(false) }
            StepCard(
                icon = rememberVectorPainter(Icons.Filled.PlayArrow),
                title = "Then · ${action.label()}",
                trailing = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Action options")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Move up") },
                                enabled = index > 0,
                                leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                                onClick = { menu = false; vm.moveAction(index, -1) },
                            )
                            DropdownMenuItem(
                                text = { Text("Move down") },
                                enabled = index < vm.actions.lastIndex,
                                leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                                onClick = { menu = false; vm.moveAction(index, 1) },
                            )
                            DropdownMenuItem(
                                text = { Text("Remove") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { menu = false; vm.removeAction(index) },
                            )
                        }
                    }
                },
            ) {
                ActionEditor(
                    action = action,
                    others = others,
                    onChange = { vm.updateAction(index, it) },
                    onOpenScript = { onOpenScript() },
                )
            }
        }
        item {
            Box {
                FilledTonalButton(onClick = { addMenu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add action", modifier = Modifier.padding(start = 8.dp))
                }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    actionKindLabels.forEachIndexed { index, label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { addMenu = false; vm.addAction(newActionOfKind(index)) },
                        )
                    }
                }
            }
        }
        if (orphans > 0) {
            item {
                Text(
                    "The Workflow tab also holds $orphans unconnected node${if (orphans == 1) "" else "s"}. They don't run, and they are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
