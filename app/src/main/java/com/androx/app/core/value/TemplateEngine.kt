package com.androx.app.core.value

/**
 * Renders `{{name}}` placeholders. Names may reach inside lists and dictionaries (`{{http.body.items[0].id}}`).
 * `{{automation}}` is kept as a working alias of `{{flow}}` so text written for v0.0.1 does not break.
 */
object TemplateEngine {
    private val pattern = Regex("\\{\\{\\s*([A-Za-z0-9_.\\[\\]-]+)\\s*}}")

    val aliases: Map<String, String> = mapOf("automation" to "flow")

    /** Replaces every placeholder. Unknown names become an empty string. */
    fun render(text: String, lookup: (String) -> Value?): String =
        pattern.replace(text) { m ->
            val name = m.groupValues[1]
            val value = lookup(name) ?: aliases[name]?.let(lookup)
            value?.asText() ?: ""
        }

    /** Names used in [text], in order, without duplicates. Used by "insert" chips and the issues list. */
    fun references(text: String): List<String> =
        pattern.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** Builds a lookup over layered scopes (first scope wins), including dotted paths into dictionaries and lists. */
    fun scope(vararg layers: Map<String, Value>): (String) -> Value? = { name ->
        layers.firstNotNullOfOrNull { it[name] } ?: run {
            val dot = name.indexOf('.')
            val bracket = name.indexOf('[')
            val cut = listOf(dot, bracket).filter { it > 0 }.minOrNull()
            if (cut == null) null else {
                val head = name.substring(0, cut)
                val rest = name.substring(cut).removePrefix(".")
                layers.firstNotNullOfOrNull { it[head] }?.at(rest)
            }
        }
    }
}
