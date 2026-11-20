@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.androx.app.R
import com.androx.app.model.summary
import com.androx.app.model.label
import com.androx.app.samples.Idea
import com.androx.app.samples.Ideas
import com.androx.app.ui.MainViewModel
import com.androx.app.ui.components.CenteredContent
import com.androx.app.ui.components.IconTile
import com.androx.app.ui.components.Pill
import com.androx.app.ui.components.TonalCard
import com.androx.app.ui.components.automationIconChoices

/** A gallery of ready-made automations. Adding one saves it and offers to open it for tweaking. */
@Composable
fun TemplatesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = { Text("Ideas") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        CenteredContent(Modifier.padding(top = padding.calculateTopPadding())) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Tap Add to save one to your list. You can change anything afterwards.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(Ideas.all, key = { it.id }) { idea -> IdeaCard(idea, onAdd = { vm.addIdea(idea) }) }
            }
        }
    }
}

@Composable
private fun IdeaCard(idea: Idea, onAdd: () -> Unit) {
    // Build once to show what it contains; the real copy (with a fresh id) is made when Add is tapped.
    val preview = androidx.compose.runtime.remember(idea.id) { idea.build(0L) }
    TonalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconTile(painterResource(automationIconChoices.firstOrNull { it.first == idea.icon }?.second ?: R.drawable.ic_bolt))
                Column(Modifier.weight(1f)) {
                    Text(idea.title, style = MaterialTheme.typography.titleMedium)
                    Text(idea.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(preview.trigger.summary())
                Pill("${preview.actions.size} action${if (preview.actions.size == 1) "" else "s"}")
            }
            Text(
                preview.actions.joinToString(" → ") { it.label() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onAdd) { Text("Add") }
        }
    }
}
