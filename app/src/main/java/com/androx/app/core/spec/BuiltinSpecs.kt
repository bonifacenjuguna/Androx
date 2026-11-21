package com.androx.app.core.spec

import com.androx.app.core.CheckIds
import com.androx.app.core.OpIds
import com.androx.app.core.TriggerIds
import com.androx.app.core.enum
import com.androx.app.core.int
import com.androx.app.core.intList
import com.androx.app.core.long
import com.androx.app.core.bool
import com.androx.app.core.str
import com.androx.app.model.Comparison
import com.androx.app.model.FlashlightMode
import com.androx.app.model.MIN_INTERVAL_MINUTES
import com.androx.app.model.RingerMode
import com.androx.app.model.VariableMode
import com.androx.app.model.VolumeStream
import com.androx.app.model.describeDays
import com.androx.app.model.describeInterval
import com.androx.app.model.formatClock
import com.androx.app.model.formatDateTime
import com.androx.app.model.ringerWord
import com.androx.app.model.volumeStreamLabel

/**
 * The v0.0.1 feature set, expressed as registry entries. The ids and parameter names match what
 * [com.androx.app.core.FlowMigration] writes, so migrated Flows resolve here with no further mapping.
 */
object BuiltinSpecs {

    fun build(): SpecCatalog {
        val triggers = SpecRegistry(SpecKind.TRIGGER)
        val constraints = SpecRegistry(SpecKind.CONSTRAINT)
        val actions = SpecRegistry(SpecKind.ACTION)
        triggerSpecs().forEach(triggers::register)
        constraintSpecs().forEach(constraints::register)
        actionSpecs().forEach(actions::register)
        return SpecCatalog(triggers, constraints, actions)
    }

    private val comparisonChoices = Comparison.entries.map { Choice(it.name, it.symbol) }

    private fun cmp(p: kotlinx.serialization.json.JsonObject, default: Comparison = Comparison.EQ) = p.enum("op", default)

    // ── triggers ────────────────────────────────────────────────────────────────────────────

    private fun triggerSpecs(): List<Spec> = listOf(
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.MANUAL, category = SpecCategory.DATE_TIME,
            name = "Manual", description = "Starts only when you run the Flow yourself.",
            iconKey = "gesture_tap", synonyms = listOf("tap", "button", "run now"),
            reliability = Reliability.EXACT,
            summary = { "Manual" },
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.SCHEDULE, category = SpecCategory.DATE_TIME,
            name = "Schedule", description = "Runs at a time of day, on the days you choose.",
            iconKey = "calendar_clock", synonyms = listOf("daily", "every day", "alarm", "weekly", "time"),
            params = listOf(
                ParamSpec.TimeOfDay("hour", "minute", "Time"),
                ParamSpec.Days("days", "Days", help = "Leave empty for every day."),
            ),
            permissions = listOf(Perm.EXACT_ALARMS), reliability = Reliability.EXACT,
            summary = { p -> "${formatClock(p.int("hour", 8), p.int("minute", 0))} · ${describeDays(p.intList("days"))}" },
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.INTERVAL, category = SpecCategory.DATE_TIME,
            name = "Interval", description = "Repeats every few minutes or hours.",
            iconKey = "repeat", synonyms = listOf("every", "repeat", "periodic", "reminder"),
            params = listOf(
                ParamSpec.Num("minutes", "Every", default = 60, min = MIN_INTERVAL_MINUTES, unit = "min"),
            ),
            permissions = listOf(Perm.EXACT_ALARMS), reliability = Reliability.GOOD,
            summary = { p -> describeInterval(p.int("minutes", 60)) },
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.ONCE, category = SpecCategory.DATE_TIME,
            name = "Once", description = "Runs one time at a date and time, then turns itself off.",
            iconKey = "alarm_check", synonyms = listOf("one time", "later", "remind me"),
            params = listOf(ParamSpec.DateTime("atMillis", "When")),
            permissions = listOf(Perm.EXACT_ALARMS), reliability = Reliability.EXACT,
            summary = { p ->
                val at = p.long("atMillis", 0L)
                if (at > 0) "Once · ${formatDateTime(at)}" else "Once"
            },
            validate = { p -> if (p.long("atMillis", 0L) <= 0L) listOf("Choose a date and time.") else emptyList() },
        ),
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.BOOT, category = SpecCategory.DEVICE,
            name = "Device starts", description = "Runs when the phone finishes starting up.",
            iconKey = "power", synonyms = listOf("boot", "startup", "restart", "power on"),
            reliability = Reliability.GOOD,
            summary = { "When the device starts" },
        ),
    )

    // ── constraints ─────────────────────────────────────────────────────────────────────────

    private fun constraintSpecs(): List<Spec> = listOf(
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.BATTERY_LEVEL, category = SpecCategory.BATTERY,
            name = "Battery level", description = "Compares the battery percentage.",
            iconKey = "battery", synonyms = listOf("charge", "percent", "low battery"),
            params = listOf(
                ParamSpec.Pick("op", "Is", comparisonChoices, Comparison.LT.name),
                ParamSpec.Num("percent", "Percent", default = 20, min = 0, max = 100, unit = "%"),
            ),
            outputs = listOf(OutputSpec("battery", "Battery percent", "number")),
            summary = { p -> "Battery ${cmp(p, Comparison.LT).symbol} ${p.int("percent", 20)}%" },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.CHARGING, category = SpecCategory.BATTERY,
            name = "Charging", description = "Whether the phone is plugged in and charging.",
            iconKey = "battery_charging", synonyms = listOf("plugged in", "charger", "power"),
            params = listOf(ParamSpec.Toggle("charging", "Charging", default = true)),
            outputs = listOf(OutputSpec("charging", "Is charging", "boolean")),
            summary = { p -> if (p.bool("charging", true)) "Device is charging" else "Device is not charging" },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.BATTERY_SAVER, category = SpecCategory.BATTERY,
            name = "Battery Saver", description = "Whether Battery Saver is on.",
            iconKey = "battery_alert_variant_outline", synonyms = listOf("power saving", "low power"),
            params = listOf(ParamSpec.Toggle("on", "Battery Saver is on", default = true)),
            summary = { p -> if (p.bool("on", true)) "Battery Saver is on" else "Battery Saver is off" },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.TIME_WINDOW, category = SpecCategory.DATE_TIME,
            name = "Time of day", description = "True between two times. A window like 22:00 to 07:00 wraps past midnight.",
            iconKey = "clock_outline", synonyms = listOf("between", "night", "hours", "quiet hours"),
            params = listOf(
                ParamSpec.TimeOfDay("fromHour", "fromMinute", "From", defaultHour = 22),
                ParamSpec.TimeOfDay("toHour", "toMinute", "To", defaultHour = 7),
            ),
            summary = { p ->
                "Between ${formatClock(p.int("fromHour", 22), p.int("fromMinute", 0))} and " +
                    formatClock(p.int("toHour", 7), p.int("toMinute", 0))
            },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.DAYS_OF_WEEK, category = SpecCategory.DATE_TIME,
            name = "Day of week", description = "True on the days you choose.",
            iconKey = "calendar_today", synonyms = listOf("weekday", "weekend", "monday"),
            params = listOf(ParamSpec.Days("days", "Days")),
            summary = { p -> describeDays(p.intList("days")) },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.WIFI_CONNECTED, category = SpecCategory.CONNECTIVITY,
            name = "Wi-Fi", description = "Whether the phone is connected to Wi-Fi.",
            iconKey = "wifi", synonyms = listOf("network", "wireless"),
            params = listOf(ParamSpec.Toggle("connected", "Connected", default = true)),
            outputs = listOf(OutputSpec("wifi", "Wi-Fi connected", "boolean")),
            summary = { p -> if (p.bool("connected", true)) "Wi-Fi is connected" else "Wi-Fi is not connected" },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.AIRPLANE_MODE, category = SpecCategory.CONNECTIVITY,
            name = "Airplane mode", description = "Whether airplane mode is on.",
            iconKey = "airplane", synonyms = listOf("flight"),
            params = listOf(ParamSpec.Toggle("on", "Airplane mode is on", default = true)),
            summary = { p -> if (p.bool("on", true)) "Airplane mode is on" else "Airplane mode is off" },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.SCREEN_ON, category = SpecCategory.DEVICE,
            name = "Screen", description = "Whether the screen is on.",
            iconKey = "cellphone", synonyms = listOf("display", "awake"),
            params = listOf(ParamSpec.Toggle("on", "Screen is on", default = true)),
            outputs = listOf(OutputSpec("screen", "Screen on", "boolean")),
            summary = { p -> if (p.bool("on", true)) "Screen is on" else "Screen is off" },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.RINGER_MODE, category = SpecCategory.SOUND,
            name = "Ringer mode", description = "Whether the phone rings, vibrates or is silent.",
            iconKey = "volume_medium", synonyms = listOf("silent", "vibrate", "mute"),
            params = listOf(
                ParamSpec.Pick("mode", "Ringer is", RingerMode.entries.map { Choice(it.name, ringerWord(it)) }, RingerMode.SILENT.name),
            ),
            outputs = listOf(OutputSpec("ringer", "Ringer mode")),
            summary = { p -> "Ringer is ${ringerWord(p.enum("mode", RingerMode.SILENT)).lowercase()}" },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.VARIABLE, category = SpecCategory.LOGIC,
            name = "Variable", description = "Compares a variable with a value.",
            iconKey = "variable", synonyms = listOf("compare", "value", "counter"),
            params = listOf(
                ParamSpec.VariableName("name", "Variable"),
                ParamSpec.Pick("op", "Is", comparisonChoices, Comparison.EQ.name),
                ParamSpec.Text("value", "Value"),
            ),
            summary = { p -> "${p.str("name")} ${cmp(p).symbol} ${p.str("value")}" },
        ),
    )

    // ── actions ─────────────────────────────────────────────────────────────────────────────

    private val variableModes = listOf(
        Choice(VariableMode.SET.name, "Set to"),
        Choice(VariableMode.ADD.name, "Add"),
        Choice(VariableMode.SUBTRACT.name, "Subtract"),
        Choice(VariableMode.MULTIPLY.name, "Multiply by"),
        Choice(VariableMode.APPEND.name, "Append"),
        Choice(VariableMode.DELETE.name, "Remove"),
    )

    private fun actionSpecs(): List<Spec> = listOf(
        Spec(
            kind = SpecKind.ACTION, id = OpIds.NOTIFY, category = SpecCategory.NOTIFY,
            name = "Show notification", description = "Posts a notification.",
            iconKey = "bell_outline", synonyms = listOf("alert", "message", "remind"),
            params = listOf(
                ParamSpec.Text("title", "Title", default = "Androx"),
                ParamSpec.Text("message", "Message", multiline = true),
            ),
            permissions = listOf(Perm.NOTIFICATIONS),
            summary = { p -> p.str("title", "Androx").ifBlank { "Androx" } + if (p.str("message").isNotBlank()) " – ${p.str("message")}" else "" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.TOAST, category = SpecCategory.NOTIFY,
            name = "Show toast", description = "Shows a short message on screen.",
            iconKey = "message_text_outline", synonyms = listOf("popup", "message"),
            params = listOf(ParamSpec.Text("message", "Message", required = true)),
            summary = { p -> p.str("message") },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.VIBRATE, category = SpecCategory.NOTIFY,
            name = "Vibrate", description = "Vibrates the phone.",
            iconKey = "vibrate", synonyms = listOf("buzz", "haptic"),
            params = listOf(ParamSpec.Num("durationMs", "Length", default = 400, min = 50, max = 5000, unit = "ms")),
            summary = { p -> "${p.int("durationMs", 400)} ms" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.SPEAK, category = SpecCategory.NOTIFY,
            name = "Speak", description = "Reads text aloud.",
            iconKey = "volume_high", synonyms = listOf("say", "text to speech", "tts"),
            params = listOf(ParamSpec.Text("text", "Text", required = true, multiline = true)),
            summary = { p -> p.str("text") },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.PLAY_SOUND, category = SpecCategory.SOUND,
            name = "Play sound", description = "Plays the default notification sound.",
            iconKey = "bell_ring_outline", synonyms = listOf("ding", "chime"),
            summary = { "Default notification sound" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.SET_VOLUME, category = SpecCategory.SOUND,
            name = "Set volume", description = "Sets the volume of a sound stream.",
            iconKey = "volume_medium", synonyms = listOf("loudness", "quieter", "louder", "make phone silent"),
            params = listOf(
                ParamSpec.Pick("stream", "Stream", VolumeStream.entries.map { Choice(it.name, volumeStreamLabel(it)) }, VolumeStream.MEDIA.name),
                ParamSpec.Num("percent", "Level", default = 50, min = 0, max = 100, unit = "%"),
            ),
            summary = { p -> "${volumeStreamLabel(p.enum("stream", VolumeStream.MEDIA))} to ${p.int("percent", 50)}%" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.FLASHLIGHT, category = SpecCategory.DEVICE,
            name = "Flashlight", description = "Turns the flashlight on, off or toggles it.",
            iconKey = "flashlight", synonyms = listOf("torch", "light"),
            params = listOf(
                ParamSpec.Pick(
                    "mode", "Flashlight",
                    listOf(Choice(FlashlightMode.ON.name, "Turn on"), Choice(FlashlightMode.OFF.name, "Turn off"), Choice(FlashlightMode.TOGGLE.name, "Toggle")),
                    FlashlightMode.TOGGLE.name,
                ),
            ),
            summary = { p ->
                when (p.enum("mode", FlashlightMode.TOGGLE)) {
                    FlashlightMode.ON -> "Turn on"
                    FlashlightMode.OFF -> "Turn off"
                    FlashlightMode.TOGGLE -> "Toggle"
                }
            },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.CLIPBOARD, category = SpecCategory.DEVICE,
            name = "Copy to clipboard", description = "Puts text on the clipboard.",
            iconKey = "content_copy", synonyms = listOf("copy", "paste"),
            params = listOf(ParamSpec.Text("text", "Text", required = true, multiline = true)),
            summary = { p -> p.str("text") },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.SET_VARIABLE, category = SpecCategory.VARIABLES,
            name = "Set variable", description = "Sets or changes a variable shared by all Flows.",
            iconKey = "variable", synonyms = listOf("counter", "store", "remember", "add", "increase"),
            params = listOf(
                ParamSpec.VariableName("name", "Variable"),
                ParamSpec.Pick("mode", "Action", variableModes, VariableMode.SET.name),
                ParamSpec.Text("value", "Value"),
            ),
            summary = { p ->
                val name = p.str("name")
                val value = p.str("value")
                when (p.enum("mode", VariableMode.SET)) {
                    VariableMode.SET -> "$name = $value"
                    VariableMode.ADD -> "$name += $value"
                    VariableMode.SUBTRACT -> "$name -= $value"
                    VariableMode.MULTIPLY -> "$name *= $value"
                    VariableMode.APPEND -> "$name + \"$value\""
                    VariableMode.DELETE -> "Remove $name"
                }
            },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.WAIT, category = SpecCategory.FLOW_CONTROL,
            name = "Wait", description = "Pauses the Flow for a few seconds (up to 10 at a time, 30 per run).",
            iconKey = "timer_sand", synonyms = listOf("delay", "pause", "sleep"),
            params = listOf(ParamSpec.Num("seconds", "Seconds", default = 2, min = 1, max = 10, unit = "s")),
            summary = { p -> "${p.int("seconds", 2)} s" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.IF, category = SpecCategory.FLOW_CONTROL,
            name = "If / else", description = "Runs one list of steps when constraints pass and another when they don't.",
            iconKey = "source_branch", synonyms = listOf("condition", "branch", "otherwise"),
            blocks = listOf(OpIds.BLOCK_THEN, OpIds.BLOCK_ELSE),
            summary = { "If / else" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.REPEAT, category = SpecCategory.FLOW_CONTROL,
            name = "Repeat", description = "Runs steps several times. {{index}} holds the round number.",
            iconKey = "repeat", synonyms = listOf("loop", "again", "times"),
            params = listOf(ParamSpec.Num("times", "Times", default = 3, min = 1, max = 20)),
            blocks = listOf(OpIds.BLOCK_BODY),
            summary = { p -> "${p.int("times", 3)} times" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.RUN_FLOW, category = SpecCategory.FLOW_CONTROL,
            name = "Run Flow", description = "Starts another Flow from this one.",
            iconKey = "play_circle_outline", synonyms = listOf("call", "start", "another flow", "run automation"),
            params = listOf(ParamSpec.FlowRef("flowId", "Flow")),
            summary = { p -> p.str("flowName").ifBlank { p.str("flowId").take(8) } },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.RUN_SCRIPT, category = SpecCategory.SCRIPTING,
            name = "Run JavaScript", description = "Runs a script that can use the androx API.",
            iconKey = "code_braces", synonyms = listOf("js", "code", "script"),
            params = listOf(ParamSpec.Code("source", "Script")),
            summary = { p -> p.str("source").lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.WRITE_LOG, category = SpecCategory.LOGGING,
            name = "Write to log", description = "Adds a line to the User log.",
            iconKey = "clipboard_text_outline", synonyms = listOf("log", "note", "record"),
            params = listOf(ParamSpec.Text("message", "Message", required = true)),
            summary = { p -> p.str("message") },
        ),
    )
}
