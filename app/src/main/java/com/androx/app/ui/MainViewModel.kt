package com.androx.app.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androx.app.AppContainer
import com.androx.app.data.AppSettings
import com.androx.app.data.Backup
import com.androx.app.model.Automation
import com.androx.app.model.EditorKind
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus
import com.androx.app.samples.Idea
import com.androx.app.samples.Samples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** What a snackbar button does. Navigation actions are handled by the root, which owns the nav controller. */
sealed interface MessageAction {
    data class Invoke(val block: () -> Unit) : MessageAction
    data class OpenRun(val id: String) : MessageAction
    data class OpenEditor(val id: String, val kind: EditorKind) : MessageAction
}

data class UiMessage(val text: String, val actionLabel: String? = null, val action: MessageAction? = null)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    val automations = container.automations.state
    val history = container.history.state
    val settings = container.settings.settings
    val variables = container.variables.snapshot
    val loaded = container.automations.loaded

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    /** One-off messages for the snackbar. */
    val messages = _messages.receiveAsFlow()

    /** Set when something (a snackbar button) wants History to open a particular run. */
    private val _focusRunId = MutableStateFlow<String?>(null)
    val focusRunId: StateFlow<String?> = _focusRunId

    /** Failed runs that happened since History was last opened. Drives the badge on the History tab. */
    val unseenFailures: StateFlow<Int> = combine(history, settings) { runs, s ->
        runs.count { it.status == ExecutionStatus.FAILED && it.startedAt > s.historySeenAt }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private fun say(text: String, actionLabel: String? = null, action: MessageAction? = null) {
        _messages.trySend(UiMessage(text, actionLabel, action))
    }

    fun focusRun(id: String) { _focusRunId.value = id }
    fun clearFocusRun() { _focusRunId.value = null }
    fun markHistorySeen() = container.settings.update { it.copy(historySeenAt = System.currentTimeMillis()) }

    // Work runs in the app scope so it finishes even if the user leaves the screen.
    fun setEnabled(id: String, enabled: Boolean) {
        container.scope.launch { container.automations.setEnabled(id, enabled) }
    }

    fun delete(automation: Automation) {
        container.scope.launch {
            container.automations.delete(automation.id)
            say(
                "Deleted “${automation.name}”",
                "Undo",
                MessageAction.Invoke { container.scope.launch { container.automations.upsert(automation) } },
            )
        }
    }

    fun duplicate(automation: Automation) {
        container.scope.launch {
            val now = System.currentTimeMillis()
            val copy = automation.copy(
                id = UUID.randomUUID().toString(),
                name = automation.name + " copy",
                enabled = false,
                createdAt = now,
                updatedAt = now,
            )
            container.automations.upsert(copy)
            say("Duplicated. The copy is turned off.", "Edit", MessageAction.OpenEditor(copy.id, copy.editor))
        }
    }

    fun run(automation: Automation) = runById(automation.id, automation.name)

    fun runById(id: String, fallbackName: String) {
        container.scope.launch {
            val record = container.runner.runManually(id)
            say(
                describeRun(record, fallbackName),
                if (record != null) "Details" else null,
                if (record != null) MessageAction.OpenRun(record.id) else null,
            )
        }
    }

    fun addSamples() {
        container.scope.launch {
            container.automations.addAll(Samples.create(System.currentTimeMillis()))
            say("Added 3 sample automations")
        }
    }

    fun addIdea(idea: Idea) {
        container.scope.launch {
            val automation = idea.build(System.currentTimeMillis())
            container.automations.upsert(automation)
            say("Added “${automation.name}”", "Edit", MessageAction.OpenEditor(automation.id, automation.editor))
        }
    }

    fun clearHistory() {
        container.scope.launch { container.history.clear() }
    }

    fun setHistoryLimit(limit: Int) {
        container.settings.update { it.copy(historyLimit = limit) }
        container.scope.launch { container.history.trim(limit) }
    }

    fun setVariable(name: String, value: String) {
        val key = name.trim()
        if (key.isNotEmpty()) container.variables.set(key, value)
    }

    fun deleteVariable(name: String) = container.variables.remove(name)
    fun clearVariables() = container.variables.clear()

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        container.settings.update(transform)
    }

    // ---------------------------------------------------------------- backup

    fun exportTo(resolver: ContentResolver, uri: Uri) {
        container.scope.launch {
            val text = Backup.encode(automations.value, variables.value, System.currentTimeMillis())
            val ok = withContext(Dispatchers.IO) {
                runCatching { resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no stream") }.isSuccess
            }
            say(if (ok) "Backed up ${automations.value.size} automations and ${variables.value.size} variables" else "Couldn't write the backup file")
        }
    }

    fun importFrom(resolver: ContentResolver, uri: Uri) {
        container.scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            }
            if (text == null) {
                say("Couldn't read that file")
                return@launch
            }
            try {
                val backup = Backup.decode(text)
                container.automations.upsertAll(backup.automations)
                backup.variables.forEach { (k, v) -> container.variables.set(k, v) }
                say("Restored ${backup.automations.size} automations and ${backup.variables.size} variables")
            } catch (e: Exception) {
                say("That file isn't an Androx backup")
            }
        }
    }
}

fun describeRun(record: ExecutionRecord?, fallbackName: String): String = when {
    record == null -> "“$fallbackName” no longer exists"
    record.status == ExecutionStatus.SUCCESS -> "“${record.automationName}” ran successfully"
    record.status == ExecutionStatus.SKIPPED -> "“${record.automationName}” skipped. ${record.message.orEmpty()}"
    else -> "“${record.automationName}” failed. ${record.message.orEmpty()}"
}
