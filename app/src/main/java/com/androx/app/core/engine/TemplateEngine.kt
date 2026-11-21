package com.androx.app.core.engine

import com.androx.app.core.Value
import com.androx.app.core.path

/**
 * Fills `{{name}}` placeholders (spec §5.3). Unlike v0.0.1, names can reach into lists and dictionaries:
 * `{{user.items.0.name}}` finds the variable `user` and walks `items → 0 → name`. A variable whose own name
 * contains dots still wins, so nothing saved by older versions changes meaning.
 */
object TemplateEngine {
    private val pattern = Regex("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}")

    fun render(text: String, lookup: (String) -> Value?): String =
        if (!text.contains("{{")) text else pattern.replace(text) { match -> resolve(match.groupValues[1], lookup)?.render() ?: "" }

    /** The placeholder names used in [text], in order, without duplicates. Used by the editor's "unknown name" hints. */
    fun names(text: String): List<String> = pattern.findAll(text).map { it.groupValues[1] }.distinct().toList()

    fun resolve(key: String, lookup: (String) -> Value?): Value? {
        lookup(key)?.let { return it }
        var cut = key.lastIndexOf('.')
        while (cut > 0) {
            val head = key.substring(0, cut)
            val rest = key.substring(cut + 1)
            val value = lookup(head)?.path(rest)
            if (value != null) return value
            cut = key.lastIndexOf('.', cut - 1)
        }
        return null
    }
}
