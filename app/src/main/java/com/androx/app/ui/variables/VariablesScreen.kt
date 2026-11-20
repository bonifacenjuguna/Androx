@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.variables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androx.app.R
import com.androx.app.ui.MainViewModel
import com.androx.app.ui.components.CenteredContent
import com.androx.app.ui.components.ConfirmDialog
import com.androx.app.ui.components.EmptyState
import com.androx.app.ui.components.IconTile
import com.androx.app.ui.components.transparentItemColors
import com.androx.app.ui.theme.CodeTextStyle

/** The values shared by every automation. Create, edit and remove them here. */
@Composable
fun VariablesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val variables by vm.variables.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) } // name to value; blank name = new
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val entries = variables.entries.sortedBy { it.key.lowercase() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = { Text("Variables") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (entries.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Clear all") }, onClick = { menu = false; confirmClear = true })
                            }
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = "" to "" },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New variable") },
            )
        },
    ) { padding ->
        CenteredContent(Modifier.padding(top = padding.calculateTopPadding())) {
            if (entries.isEmpty()) {
                EmptyState(
                    icon = painterResource(R.drawable.mdi_variable),
                    title = "No variables yet",
                    message = "Variables remember a value between runs. “Set variable” actions and androx.setVar() create them, or add one yourself.",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    item {
                        Text(
                            "Use {{name}} in any text field to insert a value.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    items(entries, key = { it.key }) { entry ->
                        ListItem(
                            leadingContent = { IconTile(painterResource(R.drawable.mdi_variable)) },
                            headlineContent = { Text(entry.key) },
                            supportingContent = {
                                Text(entry.value.ifEmpty { "(empty)" }, style = CodeTextStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                IconButton(onClick = { vm.deleteVariable(entry.key) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Delete ${entry.key}")
                                }
                            },
                            colors = transparentItemColors(),
                            modifier = Modifier.clickable { editing = entry.key to entry.value },
                        )
                    }
                }
            }
        }
    }

    editing?.let { (originalName, originalValue) ->
        var name by remember { mutableStateOf(originalName) }
        var value by remember { mutableStateOf(originalValue) }
        val isNew = originalName.isBlank()
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (isNew) "New variable" else "Edit variable") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.trim() },
                        label = { Text("Name") },
                        singleLine = true,
                        enabled = isNew,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Value") },
                        minLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.setVariable(name, value); editing = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Clear all variables?",
            message = "All ${entries.size} stored values will be removed.",
            confirmLabel = "Clear all",
            onConfirm = { confirmClear = false; vm.clearVariables() },
            onDismiss = { confirmClear = false },
        )
    }
}
