package com.androx.app.core.spec

import com.androx.app.core.Event
import com.androx.app.core.TriggerIds
import com.androx.app.core.int
import com.androx.app.core.str
import com.androx.app.core.state.DeviceStateReader
import kotlinx.serialization.json.JsonObject

/**
 * The event triggers (spec §15.2 "ready, no permission"). Each says which settings it has, which event values it
 * exposes, how an event is matched against the settings, and whether "only after holding for N minutes" makes sense.
 */
object EventTriggerSpecs {

    private fun any(p: JsonObject, key: String, actual: String): Boolean = p.str(key, "any").let { it == "any" || it.equals(actual, ignoreCase = true) }

    /** A trigger about something that is either on or off (screen, power, Battery Saver, airplane mode, headphones). */
    private fun onOff(
        id: String, name: String, description: String, icon: String, synonyms: List<String>,
        eventKey: String, stateId: String, on: String, off: String, defaultState: String,
    ): Spec {
        fun held(p: JsonObject, r: DeviceStateReader): Boolean {
            val want = p.str("state", defaultState)
            if (want == "any") return true
            val actual = r.read(stateId) ?: return false
            return actual.asBoolean() == (want == "on")
        }
        return Spec(
            kind = SpecKind.TRIGGER, id = id, category = SpecCategory.DEVICE, name = name, description = description,
            iconKey = icon, synonyms = synonyms,
            params = listOf(ParamSpec.Pick("state", "When", listOf(Choice("on", on), Choice("off", off), Choice("any", "Either way")), defaultState)),
            needsListener = true, reliability = Reliability.NEEDS_LISTENER, batteryCost = BatteryCost.LOW,
            outputs = listOf(OutputSpec(eventKey, name, "boolean")),
            summary = { p ->
                when (p.str("state", defaultState)) {
                    "on" -> on
                    "off" -> off
                    else -> "$name changes"
                }
            },
            matches = { p, e ->
                val want = p.str("state", defaultState)
                val actual = e.values[eventKey]?.asBoolean()
                want == "any" || (actual != null && (want == "on") == actual)
            },
            holdCheck = ::held,
        )
    }

    private fun pickAny(key: String, label: String, options: List<Choice>) =
        ParamSpec.Pick(key, label, listOf(Choice("any", "Any")) + options, "any")

    fun specs(): List<Spec> = listOf(
        onOff(
            TriggerIds.SCREEN, "Screen", "Runs when the screen turns on or off.", "cellphone", listOf("display", "wake", "sleep", "lock"),
            "screen.on", "device.screen", "Screen turns on", "Screen turns off", "off",
        ),
        onOff(
            TriggerIds.POWER, "Power", "Runs when a charger is connected or disconnected.", "power_plug", listOf("charger", "plug", "charging", "unplug"),
            "power.connected", "battery.charging", "Power connected", "Power disconnected", "on",
        ),
        onOff(
            TriggerIds.BATTERY_SAVER, "Battery Saver", "Runs when Battery Saver turns on or off.", "battery_alert_variant_outline", listOf("power saving", "low power"),
            "battery.saver", "battery.saver", "Battery Saver turns on", "Battery Saver turns off", "on",
        ),
        onOff(
            TriggerIds.AIRPLANE, "Airplane mode", "Runs when airplane mode turns on or off.", "airplane", listOf("flight"),
            "device.airplane", "device.airplane", "Airplane mode turns on", "Airplane mode turns off", "on",
        ),
        onOff(
            TriggerIds.HEADPHONES, "Headphones", "Runs when headphones or a headset are connected or removed.", "headphones", listOf("headset", "earbuds", "bluetooth audio", "unplug"),
            "headphones.connected", "device.headphones", "Headphones connected", "Headphones removed", "on",
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.BATTERY_LEVEL, category = SpecCategory.BATTERY,
            name = "Battery level", description = "Runs when the battery drops below, or rises above, a percentage.",
            iconKey = "battery_alert", synonyms = listOf("low battery", "full", "percent", "charged"),
            params = listOf(
                ParamSpec.Pick("direction", "When it goes", listOf(Choice("below", "Below"), Choice("above", "Above")), "below"),
                ParamSpec.Num("percent", "Percent", default = 20, min = 1, max = 99, unit = "%"),
            ),
            needsListener = true, reliability = Reliability.NEEDS_LISTENER, batteryCost = BatteryCost.LOW,
            outputs = listOf(OutputSpec("battery.level", "Battery percent", "number"), OutputSpec("battery.previous", "Percent before", "number")),
            summary = { p -> "Battery ${if (p.str("direction", "below") == "below") "drops below" else "rises above"} ${p.int("percent", 20)}%" },
            matches = { p, e ->
                val level = e.values["battery.level"]?.asNumberOrNull()
                val before = e.values["battery.previous"]?.asNumberOrNull()
                val at = p.int("percent", 20)
                when {
                    level == null || before == null -> false
                    p.str("direction", "below") == "below" -> before > at && level <= at
                    else -> before < at && level >= at
                }
            },
            holdCheck = { p, r ->
                val level = r.read("battery.level")?.asNumberOrNull()
                val at = p.int("percent", 20)
                level != null && if (p.str("direction", "below") == "below") level <= at else level >= at
            },
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.NETWORK, category = SpecCategory.CONNECTIVITY,
            name = "Network", description = "Runs when the internet connection is gained, lost or changes type.",
            iconKey = "signal", synonyms = listOf("wifi", "mobile data", "internet", "offline", "online", "vpn"),
            params = listOf(
                pickAny("change", "When it", listOf(Choice("connected", "Connects"), Choice("lost", "Is lost"), Choice("changed", "Changes type"))),
                pickAny("type", "Type", listOf(Choice("wifi", "Wi-Fi"), Choice("mobile", "Mobile data"), Choice("ethernet", "Ethernet"), Choice("vpn", "VPN"))),
            ),
            needsListener = true, reliability = Reliability.NEEDS_LISTENER, batteryCost = BatteryCost.LOW,
            outputs = listOf(OutputSpec("network.change", "What happened"), OutputSpec("network.type", "Network type"), OutputSpec("network.connected", "Is connected", "boolean")),
            summary = { p -> "Network ${p.str("change", "any").replace("any", "changes")}" + if (p.str("type", "any") != "any") " · ${p.str("type")}" else "" },
            matches = { p, e -> any(p, "change", e.values["network.change"]?.render().orEmpty()) && any(p, "type", e.values["network.type"]?.render().orEmpty()) },
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.RINGER, category = SpecCategory.SOUND,
            name = "Ringer mode", description = "Runs when the phone switches between ringing, vibrating and silent.",
            iconKey = "volume_medium", synonyms = listOf("silent", "mute", "vibrate"),
            params = listOf(pickAny("mode", "Changes to", listOf(Choice("normal", "Normal"), Choice("vibrate", "Vibrate"), Choice("silent", "Silent")))),
            needsListener = true, reliability = Reliability.NEEDS_LISTENER, batteryCost = BatteryCost.LOW,
            outputs = listOf(OutputSpec("ringer.mode", "New ringer mode")),
            summary = { p -> if (p.str("mode", "any") == "any") "Ringer mode changes" else "Ringer becomes ${p.str("mode")}" },
            matches = { p, e -> any(p, "mode", e.values["ringer.mode"]?.render().orEmpty()) },
            holdCheck = { p, r -> p.str("mode", "any") == "any" || r.read("device.ringer")?.render().equals(p.str("mode"), ignoreCase = true) },
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.DND, category = SpecCategory.SOUND,
            name = "Do Not Disturb", description = "Runs when Do Not Disturb changes.",
            iconKey = "minus_circle", synonyms = listOf("dnd", "focus", "quiet"),
            params = listOf(
                pickAny(
                    "filter", "Changes to",
                    listOf(Choice("off", "Off"), Choice("priority", "Priority only"), Choice("alarms", "Alarms only"), Choice("total_silence", "Total silence")),
                ),
            ),
            needsListener = true, reliability = Reliability.NEEDS_LISTENER, batteryCost = BatteryCost.LOW,
            outputs = listOf(OutputSpec("dnd.filter", "New setting")),
            summary = { p -> if (p.str("filter", "any") == "any") "Do Not Disturb changes" else "Do Not Disturb becomes ${p.str("filter")}" },
            matches = { p, e -> any(p, "filter", e.values["dnd.filter"]?.render().orEmpty()) },
            holdCheck = { p, r -> p.str("filter", "any") == "any" || r.read("device.dnd")?.render().equals(p.str("filter"), ignoreCase = true) },
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.UNLOCKED, category = SpecCategory.DEVICE,
            name = "Phone unlocked", description = "Runs each time you unlock the phone.",
            iconKey = "lock_open_variant", synonyms = listOf("unlock", "wake", "pick up", "keyguard"),
            needsListener = true, reliability = Reliability.NEEDS_LISTENER, batteryCost = BatteryCost.LOW,
            summary = { "When the phone is unlocked" },
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.TIME_CHANGE, category = SpecCategory.DATE_TIME,
            name = "Time or time zone changed", description = "Runs when the clock or the time zone is changed, for example while travelling.",
            iconKey = "earth", synonyms = listOf("timezone", "travel", "clock", "daylight saving"),
            params = listOf(pickAny("what", "Changes", listOf(Choice("time", "The time"), Choice("timezone", "The time zone")))),
            reliability = Reliability.GOOD,
            outputs = listOf(OutputSpec("time.change", "time or timezone")),
            summary = { p -> when (p.str("what", "any")) { "time" -> "When the time is changed"; "timezone" -> "When the time zone changes"; else -> "When the time or time zone changes" } },
            matches = { p, e -> any(p, "what", e.values["time.change"]?.render().orEmpty()) },
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.NOTIFICATION_BUTTON, category = SpecCategory.DEVICE,
            name = "Notification button", description = "Runs when you tap a button on a notification that a Flow posted.",
            iconKey = "gesture_tap_button", synonyms = listOf("tap", "action", "notification action"),
            params = listOf(
                ParamSpec.Text("notificationId", "Notification name", templated = false, help = "Leave empty for any notification."),
                ParamSpec.Text("button", "Button label", templated = false, help = "Leave empty for any button."),
            ),
            outputs = listOf(OutputSpec("notification.id", "Notification name"), OutputSpec("notification.button", "Button label")),
            summary = { p ->
                val parts = listOf(p.str("notificationId"), p.str("button")).filter { it.isNotBlank() }
                if (parts.isEmpty()) "Any notification button" else "Button: " + parts.joinToString(" · ")
            },
            matches = { p, e ->
                (p.str("notificationId").isBlank() || p.str("notificationId").equals(e.values["notification.id"]?.render(), ignoreCase = true)) &&
                    (p.str("button").isBlank() || p.str("button").equals(e.values["notification.button"]?.render(), ignoreCase = true))
            },
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.FLOW_FINISHED, category = SpecCategory.LOGIC,
            name = "Flow finished", description = "Runs when another Flow finishes, or fails.",
            iconKey = "flag_checkered", synonyms = listOf("after", "chain", "failed", "error", "completed"),
            params = listOf(
                ParamSpec.FlowRef("flowId", "Flow", required = false, help = "Leave empty for any Flow."),
                ParamSpec.Pick("when", "When it", listOf(Choice("finished", "Finishes (any result)"), Choice("success", "Succeeds"), Choice("failed", "Fails")), "finished"),
            ),
            outputs = listOf(OutputSpec("flow.id", "Flow id"), OutputSpec("flow.name", "Flow name"), OutputSpec("flow.status", "success, failed or skipped"), OutputSpec("flow.message", "Message")),
            summary = { p ->
                val name = p.str("flowName").ifBlank { if (p.str("flowId").isBlank()) "Any Flow" else p.str("flowId").take(8) }
                "$name " + when (p.str("when", "finished")) { "success" -> "succeeds"; "failed" -> "fails"; else -> "finishes" }
            },
            matches = { p, e ->
                val status = e.values["flow.status"]?.render().orEmpty()
                (p.str("flowId").isBlank() || p.str("flowId") == e.values["flow.id"]?.render()) &&
                    when (p.str("when", "finished")) {
                        "success" -> status == "success"
                        "failed" -> status == "failed"
                        else -> status != "skipped"
                    }
            },
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.VARIABLE_CHANGED, category = SpecCategory.VARIABLES,
            name = "Variable changed", description = "Runs when a variable is set to a different value by a Flow.",
            iconKey = "variable", synonyms = listOf("watch", "counter", "changes"),
            params = listOf(ParamSpec.Text("name", "Variable", templated = false, help = "Leave empty for any variable.")),
            outputs = listOf(OutputSpec("variable.name", "Variable"), OutputSpec("variable.old", "Old value"), OutputSpec("variable.new", "New value")),
            summary = { p -> if (p.str("name").isBlank()) "Any variable changes" else "${p.str("name")} changes" },
            matches = { p, e -> p.str("name").isBlank() || p.str("name").equals(e.values["variable.name"]?.render(), ignoreCase = true) },
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.CALLED, category = SpecCategory.LOGIC,
            name = "Called by another Flow", description = "This Flow only runs when another Flow uses \"Run Flow\" on it.",
            iconKey = "phone_incoming", synonyms = listOf("subroutine", "function", "helper"),
            summary = { "Only when called by another Flow" },
            matches = { _, _ -> false },
        ),
    )
}
