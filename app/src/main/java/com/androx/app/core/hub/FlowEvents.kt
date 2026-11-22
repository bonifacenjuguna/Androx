package com.androx.app.core.hub

import com.androx.app.core.Event
import com.androx.app.core.Value
import com.androx.app.core.TriggerIds
import com.androx.app.model.ExecutionRecord

/** Builds the events Androx raises about itself. */
object FlowEvents {
    fun finished(record: ExecutionRecord): Event = Event(
        typeId = TriggerIds.FLOW_FINISHED,
        values = mapOf(
            "flow.id" to Value.Text(record.automationId),
            "flow.name" to Value.Text(record.automationName),
            "flow.status" to Value.Text(record.status.name.lowercase()),
            "flow.message" to Value.Text(record.message.orEmpty()),
        ),
        at = record.startedAt + record.durationMs,
    )

    fun variableChanged(name: String, old: Value?, new: Value?): Event = Event(
        typeId = TriggerIds.VARIABLE_CHANGED,
        values = mapOf(
            "variable.name" to Value.Text(name),
            "variable.old" to Value.Text(old?.render().orEmpty()),
            "variable.new" to Value.Text(new?.render().orEmpty()),
        ),
    )

    fun notificationButton(notificationId: String, button: String): Event = Event(
        typeId = TriggerIds.NOTIFICATION_BUTTON,
        values = mapOf("notification.id" to Value.Text(notificationId), "notification.button" to Value.Text(button)),
    )

    fun timeChanged(what: String): Event = Event(TriggerIds.TIME_CHANGE, mapOf("time.change" to Value.Text(what)))

    /** A believable event for "Test fire": every value the trigger exposes, filled with a sample. */
    fun sample(typeId: String, catalog: com.androx.app.core.spec.SpecCatalog = com.androx.app.core.spec.SpecCatalog.default): Event {
        val spec = catalog.triggers[typeId]
        val values = spec?.outputs.orEmpty().associate { out ->
            out.key to when (out.type) {
                "boolean" -> Value.Bool(true)
                "number" -> Value.Num(42.0)
                else -> Value.Text("sample")
            }
        }
        return Event(typeId, values)
    }
}
