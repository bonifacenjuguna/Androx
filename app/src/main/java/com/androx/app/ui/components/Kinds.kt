@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.androx.app.R
import com.androx.app.model.EditorKind

@Composable
fun kindIcon(kind: EditorKind): Painter = when (kind) {
    EditorKind.BUILDER -> rememberVectorPainter(Icons.Filled.Build)
    EditorKind.SCRIPT -> painterResource(R.drawable.ic_code)
    EditorKind.WORKFLOW -> painterResource(R.drawable.ic_workflow)
}

fun kindLabel(kind: EditorKind): String = when (kind) {
    EditorKind.BUILDER -> "Builder"
    EditorKind.SCRIPT -> "JavaScript"
    EditorKind.WORKFLOW -> "Workflow"
}

/** Icon for an action type. The index matches ElementEditors.newActionOfKind. */
@Composable
fun actionIcon(index: Int): Painter = when (index) {
    0 -> rememberVectorPainter(Icons.Filled.Notifications)
    1 -> rememberVectorPainter(Icons.Filled.Edit)
    2 -> rememberVectorPainter(Icons.Filled.PlayArrow)
    3 -> rememberVectorPainter(Icons.AutoMirrored.Filled.List)
    else -> painterResource(R.drawable.ic_code)
}

private val actionInfo = listOf(
    "Show notification" to "Post a notification on this device",
    "Set variable" to "Store or add to a value other automations can read",
    "Run automation" to "Start another saved automation",
    "Write to log" to "Add a line to this run's details in History",
    "Run JavaScript" to "Use the androx scripting API",
)

/** Bottom sheet that lets the user pick one of the three ways to create an automation. */
@Composable
fun NewAutomationSheet(onPick: (EditorKind) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                "Create automation",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            PreferenceRow(
                title = "Builder",
                summary = "Choose a trigger, an optional condition and actions",
                icon = kindIcon(EditorKind.BUILDER),
                onClick = { onPick(EditorKind.BUILDER) },
            )
            PreferenceRow(
                title = "JavaScript",
                summary = "Write a script with the androx API",
                icon = kindIcon(EditorKind.SCRIPT),
                onClick = { onPick(EditorKind.SCRIPT) },
            )
            PreferenceRow(
                title = "Visual workflow",
                summary = "Place nodes on a canvas and connect them",
                icon = kindIcon(EditorKind.WORKFLOW),
                onClick = { onPick(EditorKind.WORKFLOW) },
            )
        }
    }
}

/** Bottom sheet for choosing the type of a new action. [onPick] receives an index for newActionOfKind. */
@Composable
fun AddActionSheet(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                "Add action",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            actionInfo.forEachIndexed { index, (title, description) ->
                PreferenceRow(
                    title = title,
                    summary = description,
                    icon = actionIcon(index),
                    onClick = { onPick(index) },
                )
            }
        }
    }
}
