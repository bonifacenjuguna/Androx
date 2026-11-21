package com.androx.app.core.registry

/**
 * A lookup of specs by id. Adding a trigger, constraint or action means registering one more spec;
 * nothing else in the app has to change. Unknown ids (from a newer file) are reported as unavailable, not as crashes.
 */
class Registry<T : Spec>(specs: List<T> = emptyList()) {
    private val byId = LinkedHashMap<String, T>()

    init {
        specs.forEach { register(it) }
    }

    fun register(spec: T) {
        require(spec.id !in byId) { "Duplicate registry id: ${spec.id}" }
        byId[spec.id] = spec
    }

    operator fun get(id: String): T? = byId[id]

    fun contains(id: String): Boolean = id in byId

    val all: List<T> get() = byId.values.toList()

    val categories: List<String> get() = byId.values.map { it.category }.distinct()

    fun inCategory(category: String): List<T> = byId.values.filter { it.category == category }

    /**
     * Free-text search across names, descriptions, categories and synonyms ("make phone silent" finds Ringer mode).
     * An entry matches when at least one word hits; more hits and name hits rank higher.
     */
    fun search(query: String): List<T> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return all
        return byId.values.mapNotNull { spec ->
            val name = spec.name.lowercase()
            val syn = spec.synonyms.joinToString(" ").lowercase()
            val desc = spec.description.lowercase()
            val cat = spec.category.lowercase()
            var score = 0
            for (w in words) {
                score += when {
                    name.startsWith(w) -> 10
                    name.contains(w) -> 8
                    syn.contains(w) -> 6
                    cat.contains(w) -> 3
                    desc.contains(w) -> 2
                    else -> 0
                }
            }
            if (score == 0) null else spec to score
        }.sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy { it.first.name }).map { it.first }
    }
}
