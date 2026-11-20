@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.androx.app.R
import com.androx.app.engine.ScriptApiDocs
import com.androx.app.model.Action
import com.androx.app.ui.components.CenteredContent
import com.androx.app.ui.components.EmptyState
import com.androx.app.ui.components.transparentItemColors
import com.androx.app.ui.theme.CodeTextStyle

private val snippets = listOf(
    "Notify" to "androx.notify(\"Title\", \"Message\");\n",
    "Counter" to "const n = Number(androx.getVar(\"count\") || 0) + 1;\nandrox.setVar(\"count\", n);\nandrox.log(\"count is \" + n);\n",
    "Battery check" to "if (androx.battery() < 20 && !androx.isCharging()) {\n  androx.notify(\"Low battery\", androx.battery() + \"% left\");\n}\n",
    "Read a variable" to "const value = androx.getVar(\"name\");\nandrox.log(value === null ? \"not set\" : value);\n",
    "Night guard" to "const hour = new Date(androx.now()).getHours();\nif (hour >= 22 || hour < 7) {\n  androx.log(\"quiet hours\");\n}\n",
    "Wi-Fi only" to "if (!androx.isWifiConnected()) {\n  androx.toast(\"Connect to Wi-Fi first\");\n}\n",
    "Buzz and speak" to "androx.vibrate(300);\nandrox.speak(\"Battery is \" + androx.battery() + \" percent\");\n",
    "Copy a stamp" to "androx.copy(new Date(androx.now()).toISOString());\nandrox.toast(\"Copied\");\n",
)

/** A key on the symbol bar: what it shows, what it inserts and how far to move the caret back afterwards. */
private class Symbol(val label: String, val text: String = label, val back: Int = 0)

private val symbols = listOf(
    Symbol("⇥", "  "),
    Symbol("( )", "()", 1),
    Symbol("{ }", "{}", 1),
    Symbol("[ ]", "[]", 1),
    Symbol("\" \"", "\"\"", 1),
    Symbol("' '", "''", 1),
    Symbol(";"),
    Symbol("."),
    Symbol(","),
    Symbol("="),
    Symbol("+"),
    Symbol("!"),
    Symbol("&&"),
    Symbol("||"),
    Symbol("=>"),
)

/** Keeps the indentation of the previous line when Enter is pressed, and adds a level after `{`. */
private fun autoIndent(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (new.text.length != old.text.length + 1 || new.selection.length != 0) return new
    val caret = new.selection.start
    if (caret == 0 || new.text[caret - 1] != '\n') return new
    val lineStart = new.text.lastIndexOf('\n', caret - 2).let { if (it < 0) 0 else it + 1 }
    val previous = new.text.substring(lineStart, caret - 1)
    var indent = previous.takeWhile { it == ' ' }
    if (previous.trimEnd().endsWith("{")) indent += "  "
    if (indent.isEmpty()) return new
    val text = new.text.substring(0, caret) + indent + new.text.substring(caret)
    return new.copy(text = text, selection = TextRange(caret + indent.length))
}

private val TOKEN = Regex(
    """(//[^\n]*|/\*[\s\S]*?\*/)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`)|\b(const|let|var|function|return|if|else|for|while|do|break|continue|true|false|null|undefined|new|try|catch|finally|throw|typeof|of|in)\b|\b(androx|console)\b|\b(\d+(?:\.\d+)?)\b"""
)

private class JsHighlighter(
    private val comment: Color,
    private val string: Color,
    private val keyword: Color,
    private val builtin: Color,
    private val number: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            append(text.text)
            for (m in TOKEN.findAll(text.text)) {
                val color = when {
                    m.groups[1] != null -> comment
                    m.groups[2] != null -> string
                    m.groups[3] != null -> keyword
                    m.groups[4] != null -> builtin
                    else -> number
                }
                addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

@Composable
fun ScriptTab(vm: EditorViewModel) {
    val index = vm.scriptIndex
    if (index < 0) {
        Box(Modifier.fillMaxSize()) {
            EmptyState(
                icon = painterResource(R.drawable.ic_code),
                title = "No script yet",
                message = "Add a JavaScript action. It runs together with the other actions, in order.",
                modifier = Modifier.align(Alignment.Center),
            ) {
                Button(onClick = { vm.addAction(Action.RunScript()) }) { Text("Add script") }
            }
        }
        return
    }
    val source = (vm.actions[index] as Action.RunScript).source
    val scheme = MaterialTheme.colorScheme
    var showApi by remember { mutableStateOf(false) }
    var snippetMenu by remember { mutableStateOf(false) }

    // The editor keeps its own TextFieldValue (caret and IME state); it is resynced when the script changes elsewhere.
    var editorValue by remember { mutableStateOf(TextFieldValue(source, TextRange(source.length))) }
    LaunchedEffect(source) {
        if (editorValue.text != source) editorValue = TextFieldValue(source, TextRange(source.length))
    }
    fun insert(text: String, back: Int = 0) {
        val start = editorValue.selection.min
        val end = editorValue.selection.max
        val updated = editorValue.text.replaceRange(start, end, text)
        editorValue = TextFieldValue(updated, TextRange(start + text.length - back))
        vm.setScript(updated)
    }

    val highlighter = remember(scheme) {
        JsHighlighter(
            comment = scheme.outline,
            string = scheme.onSurface.copy(alpha = 0.72f),
            keyword = scheme.primary,
            builtin = scheme.tertiary,
            number = scheme.secondary,
        )
    }

    CenteredContent {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    AssistChip(
                        onClick = { snippetMenu = true },
                        label = { Text("Snippets") },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    )
                    DropdownMenu(expanded = snippetMenu, onDismissRequest = { snippetMenu = false }) {
                        snippets.forEach { (title, code) ->
                            DropdownMenuItem(
                                text = { Text(title) },
                                onClick = {
                                    snippetMenu = false
                                    val base = if (editorValue.text.isEmpty() || editorValue.text.endsWith("\n")) editorValue.text else editorValue.text + "\n"
                                    vm.setScript(base + code)
                                },
                            )
                        }
                    }
                }
                AssistChip(onClick = { showApi = true }, label = { Text("API reference") })
            }
            Text(
                if (vm.actions.size > 1) {
                    "Rhino · androx API only · stops after 5 s. ${vm.actions.size - 1} other action${if (vm.actions.size == 2) "" else "s"} run in order; edit them in the Builder."
                } else {
                    "Rhino · androx API only · stops after 5 s"
                },
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
            Surface(
                color = scheme.surfaceContainerLowest,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Box(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
                    BasicTextField(
                        value = editorValue,
                        onValueChange = { value ->
                            val next = autoIndent(editorValue, value)
                            editorValue = next
                            vm.setScript(next.text)
                        },
                        textStyle = CodeTextStyle.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        visualTransformation = highlighter,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 240.dp),
                    )
                }
            }
            val caret = editorValue.selection.start.coerceIn(0, editorValue.text.length)
            val before = editorValue.text.substring(0, caret)
            Text(
                "Ln ${before.count { it == '\n' } + 1}, Col ${caret - (before.lastIndexOf('\n') + 1) + 1} · " +
                    "${editorValue.text.count { it == '\n' } + 1} lines · ${editorValue.text.length} chars",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            // Symbols that are awkward to reach on a phone keyboard.
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                symbols.forEach { key ->
                    TextButton(onClick = { insert(key.text, key.back) }) {
                        Text(key.label, style = CodeTextStyle, color = scheme.onSurface)
                    }
                }
            }
        }
    }

    if (showApi) {
        ModalBottomSheet(onDismissRequest = { showApi = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Text(
                    "Androx scripting API",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ScriptApiDocs.entries.forEach { entry ->
                    ListItem(
                        headlineContent = { Text(entry.signature, style = CodeTextStyle, color = scheme.primary) },
                        supportingContent = { Text(entry.description) },
                        colors = transparentItemColors(),
                    )
                }
                Text(
                    "Variables are shared by every automation and are always text. A script that throws, or a notification Android blocks, is recorded as a failed run in History.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
