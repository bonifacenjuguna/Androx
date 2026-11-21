package com.androx.app.core.spec

import kotlinx.serialization.json.JsonObject

/** All entries of one kind, looked up by their stable id. */
class SpecRegistry(val kind: SpecKind) {
    private val byId = LinkedHashMap<String, Spec>()

    fun register(spec: Spec): SpecRegistry {
        require(spec.kind == kind) { "${spec.id} is a ${spec.kind}, not a $kind" }
        require(spec.id !in byId) { "Duplicate ${kind.name.lowercase()} id: ${spec.id}" }
        byId[spec.id] = spec
        return this
    }

    operator fun get(id: String): Spec? = byId[id]

    fun all(): List<Spec> = byId.values.toList()

    fun byCategory(): Map<SpecCategory, List<Spec>> =
        all().groupBy { it.category }.toSortedMap(compareBy<SpecCategory> { it.ordinal })

    /**
     * Search for the picker and for global search (spec §6.3, §13.1). Matches the name, the description, the category
     * and synonyms ("make phone silent"), every word of the query must hit. Best matches first.
     */
    fun search(query: String): List<Spec> {
        val words = query.lowercase().split(' ', ',').filter { it.isNotBlank() }
        if (words.isEmpty()) return all()
        return all()
            .mapNotNull { spec ->
                var score = 0
                for (w in words) {
                    val s = scoreWord(spec, w)
                    if (s == 0) return@mapNotNull null
                    score += s
                }
                spec to score
            }
            .sortedWith(compareByDescending<Pair<Spec, Int>> { it.second }.thenBy { it.first.name })
            .map { it.first }
    }

    private fun scoreWord(spec: Spec, w: String): Int = when {
        spec.name.lowercase().startsWith(w) -> 10
        spec.name.lowercase().contains(w) -> 8
        spec.synonyms.any { it.lowercase().contains(w) } -> 6
        spec.category.label.lowercase().contains(w) -> 3
        spec.description.lowercase().contains(w) -> 2
        else -> 0
    }

    /**
     * One-line summary for any stored entry. Unknown ids (from a newer file) become "Unavailable" instead of crashing
     * (spec §5.2), and a summary that throws is caught so a bad value can never break a screen.
     */
    fun summarize(typeId: String, params: JsonObject): String {
        val spec = byId[typeId] ?: return "Unavailable ($typeId)"
        return try {
            spec.summary(params)
        } catch (e: Exception) {
            spec.name
        }
    }

    fun nameOf(typeId: String): String = byId[typeId]?.name ?: "Unavailable"
}

/** The three registries every part of the app reads from. */
class SpecCatalog(
    val triggers: SpecRegistry,
    val constraints: SpecRegistry,
    val actions: SpecRegistry,
) {
    /** Every entry across all kinds, for the Catalogue and global search. */
    fun everything(): List<Spec> = triggers.all() + constraints.all() + actions.all()

    companion object {
        /** The built-in catalogue. Later zips add their entries in [BuiltinSpecs]. */
        val default: SpecCatalog by lazy { BuiltinSpecs.build() }
    }
}
