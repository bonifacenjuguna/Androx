package com.androx.app.core.engine

import com.androx.app.core.Value
import com.androx.app.engine.VariableStore

/** How a run treats the device: do things for real, or only describe what would happen (spec §9 simulator). */
enum class RunMode { LIVE, SIMULATE }

/**
 * Typed variable storage used by the engine. Today's on-disk variables are text, so [asValues] wraps them: numbers
 * saved as text still work in math, and typed values are written back as their text form.
 */
interface ValueStore {
    fun get(name: String): Value?
    fun set(name: String, value: Value)
    fun remove(name: String)
}

class StringBackedValueStore(private val inner: VariableStore) : ValueStore {
    override fun get(name: String): Value? = inner.get(name)?.let { Value.fromStored(it) }
    override fun set(name: String, value: Value) = inner.set(name, value.render())
    override fun remove(name: String) = inner.remove(name)
}

fun VariableStore.asValues(): ValueStore = StringBackedValueStore(this)

/** Plain in-memory store. Used by tests and as the base of simulations. */
class MapValueStore(initial: Map<String, Value> = emptyMap()) : ValueStore {
    private val map = LinkedHashMap(initial)
    override fun get(name: String): Value? = map[name]
    override fun set(name: String, value: Value) {
        map[name] = value
    }

    override fun remove(name: String) {
        map.remove(name)
    }

    fun snapshot(): Map<String, Value> = map.toMap()
}

/**
 * Copy-on-write view used by simulations: reads fall through to [base], writes stay here, so a simulated run can
 * change variables and see its own changes without touching the real ones.
 */
class ShadowValueStore(private val base: ValueStore) : ValueStore {
    private val changed = HashMap<String, Value>()
    private val removed = HashSet<String>()

    override fun get(name: String): Value? = when {
        name in removed -> null
        else -> changed[name] ?: base.get(name)
    }

    override fun set(name: String, value: Value) {
        removed.remove(name)
        changed[name] = value
    }

    override fun remove(name: String) {
        changed.remove(name)
        removed += name
    }
}
