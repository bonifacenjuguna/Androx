package com.androx.app.core.state

import com.androx.app.core.Value
import com.androx.app.core.spec.Choice
import com.androx.app.core.spec.PermissionNeed
import com.androx.app.core.spec.SpecCategory

enum class StateType { BOOLEAN, NUMBER, CHOICE, TEXT }

/**
 * One piece of device state, defined once (spec §5.1 "StateProvider"). It feeds a constraint, a `{{value}}` you can
 * put in any text, trigger previews and the Home "Device status" card. How the value is read lives in a
 * [DeviceStateReader]; this is only the description.
 */
data class StateProvider(
    /** Stable id and the `{{name}}` it is available under, for example `battery.temp`. */
    val id: String,
    val name: String,
    val description: String,
    val category: SpecCategory,
    val type: StateType,
    val iconKey: String,
    val unit: String = "",
    val choices: List<Choice> = emptyList(),
    /** Words for true and false, used in summaries ("connected" / "not connected"). */
    val yes: String = "on",
    val no: String = "off",
    val minSdk: Int = 26,
    val permissions: List<PermissionNeed> = emptyList(),
    val synonyms: List<String> = emptyList(),
) {
    /** The text a person would read for [value], for logs and the live "Right now" line. */
    fun display(value: Value): String = when (type) {
        StateType.BOOLEAN -> if (value.asBoolean()) yes else no
        StateType.NUMBER -> value.render() + if (unit.isBlank()) "" else " $unit"
        StateType.CHOICE -> choices.firstOrNull { it.value.equals(value.render(), ignoreCase = true) }?.label ?: value.render()
        StateType.TEXT -> value.render()
    }
}

/** Reads the live value of a provider. Null means "can't be read right now" (no permission, no hardware, unsupported). */
interface DeviceStateReader {
    fun read(id: String): Value?
}

/** Tries each reader in turn; the first one that knows the value wins. */
class CompositeStateReader(private val readers: List<DeviceStateReader>) : DeviceStateReader {
    constructor(vararg readers: DeviceStateReader) : this(readers.toList())

    override fun read(id: String): Value? {
        for (r in readers) r.read(id)?.let { return it }
        return null
    }
}

/** A reader with fixed values, for tests and the simulator's "fake device state". */
class FixedStateReader(private val values: Map<String, Value>) : DeviceStateReader {
    override fun read(id: String): Value? = values[id]
}
