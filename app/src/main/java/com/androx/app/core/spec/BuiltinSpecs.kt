package com.androx.app.core.spec

import com.androx.app.core.CheckIds
import com.androx.app.core.Compare
import com.androx.app.core.CompareOp
import com.androx.app.core.state.StateCatalog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
        (triggerSpecs() + EventTriggerSpecs.specs() + InternetSpecs.triggers()).forEach(triggers::register)
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

    /** Legacy entries stay (older Flows use them) but pickers offer the state-based entry instead. */
    private val superseded = mapOf(
        CheckIds.BATTERY_LEVEL to "state:battery.level",
        CheckIds.CHARGING to "state:battery.charging",
        CheckIds.BATTERY_SAVER to "state:battery.saver",
        CheckIds.WIFI_CONNECTED to "state:network.wifi",
        CheckIds.AIRPLANE_MODE to "state:device.airplane",
        CheckIds.SCREEN_ON to "state:device.screen",
        CheckIds.RINGER_MODE to "state:device.ringer",
    )

    private fun constraintSpecs(): List<Spec> =
        legacyConstraintSpecs().map { s -> superseded[s.id]?.let { s.copy(supersededBy = it) } ?: s } +
            logicConstraintSpecs() +
            StateCatalog.providers.map(StateSpecs::constraintSpec)

    private fun legacyConstraintSpecs(): List<Spec> = listOf(
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

    private fun actionSpecs(): List<Spec> = baseActionSpecs() + logicActionSpecs() + deviceActionSpecs() + InternetSpecs.actions()

    private fun baseActionSpecs(): List<Spec> = listOf(
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
                ParamSpec.Toggle("restore", "Put it back when the Flow ends"),
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
            params = listOf(ParamSpec.Logic("condition", "Condition")),
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
            params = listOf(
                ParamSpec.FlowRef("flowId", "Flow"),
                ParamSpec.Text("inputs", "Inputs", multiline = true, help = "One per line: name=value. The Flow reads them as {{name}}."),
            ),
            outputs = listOf(OutputSpec("result", "Value the Flow returned with Stop")),
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

    // ── logic and Flow constraints ──────────────────────────────────────────────────────────

    private fun dateOnly(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private val flowStateChoices = listOf(
        Choice("running", "is running"),
        Choice("enabled", "is turned on"),
        Choice("ran_recently", "ran in the last few minutes"),
        Choice("runs_today", "ran a number of times today"),
    )

    private fun logicConstraintSpecs(): List<Spec> = listOf(
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.COMPARE, category = SpecCategory.LOGIC,
            name = "Compare values", description = "Compares two values. Either side can use {{names}}.",
            iconKey = "scale_balance", synonyms = listOf("equals", "greater", "less", "between", "contains", "regex", "matches"),
            params = listOf(
                ParamSpec.Text("left", "Value", required = true),
                ParamSpec.Pick("op", "Is", Compare.allOps.map { Choice(it.name, it.label) }, CompareOp.EQ.name),
                ParamSpec.Text("right", "Compared with"),
                ParamSpec.Text("right2", "And", help = "Only used for \"between\"."),
            ),
            summary = { p ->
                "${p.str("left")} ${Compare.describe(p.enum("op", CompareOp.EQ), p.str("right"), p.str("right2"))}"
            },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.FLOW_STATE, category = SpecCategory.LOGIC,
            name = "Flow state", description = "Checks whether a Flow is running, turned on, or has run recently.",
            iconKey = "state_machine", synonyms = listOf("another flow", "ran today", "running", "enabled"),
            params = listOf(
                ParamSpec.FlowRef("flowId", "Flow"),
                ParamSpec.Pick("what", "Check", flowStateChoices, "running"),
                ParamSpec.Num("minutes", "Within", default = 60, min = 1, max = 10_080, unit = "min", help = "For \"ran in the last few minutes\"."),
                ParamSpec.Pick("op", "Times today", Compare.numericOps.filter { it != CompareOp.BETWEEN }.map { Choice(it.name, it.label) }, CompareOp.GTE.name),
                ParamSpec.Num("count", "Count", default = 1, min = 0, max = 10_000),
            ),
            summary = { p ->
                val name = p.str("flowName").ifBlank { p.str("flowId").take(8) }
                when (p.str("what", "running")) {
                    "enabled" -> "$name is turned on"
                    "ran_recently" -> "$name ran in the last ${p.int("minutes", 60)} min"
                    "runs_today" -> "$name ran ${Compare.describe(p.enum("op", CompareOp.GTE), p.int("count", 1).toString())} times today"
                    else -> "$name is running"
                }
            },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.TRIGGERED_BY, category = SpecCategory.LOGIC,
            name = "Started by", description = "Checks what started this run: you, a timer, boot or an event.",
            iconKey = "play_circle_outline", synonyms = listOf("trigger", "source", "manual", "timer"),
            params = listOf(
                ParamSpec.Pick(
                    "by", "Started by",
                    listOf(Choice("manual", "You (manually)"), Choice("timer", "A timer"), Choice("boot", "Device start"), Choice("event", "An event")),
                    "manual",
                ),
                ParamSpec.Text("eventType", "Event type", help = "Only for \"An event\". Leave empty for any.", templated = false),
            ),
            summary = { p ->
                when (p.str("by", "manual")) {
                    "timer" -> "Started by a timer"
                    "boot" -> "Started by device start"
                    "event" -> "Started by " + p.str("eventType").ifBlank { "any event" }
                    else -> "Started manually"
                }
            },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.DATE_RANGE, category = SpecCategory.DATE_TIME,
            name = "Date range", description = "True between two dates, both included.",
            iconKey = "calendar_range", synonyms = listOf("from", "until", "holiday", "season"),
            params = listOf(ParamSpec.DateTime("fromMillis", "From"), ParamSpec.DateTime("toMillis", "To")),
            summary = { p ->
                val from = p.long("fromMillis", 0L)
                val to = p.long("toMillis", 0L)
                when {
                    from > 0 && to > 0 -> "From ${dateOnly(from)} to ${dateOnly(to)}"
                    from > 0 -> "From ${dateOnly(from)}"
                    to > 0 -> "Until ${dateOnly(to)}"
                    else -> "Any date"
                }
            },
            validate = { p ->
                val from = p.long("fromMillis", 0L)
                val to = p.long("toMillis", 0L)
                if (from > 0 && to > 0 && dateOnly(from) > dateOnly(to)) listOf("The start date must not be after the end date.") else emptyList()
            },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.DAY_OF_MONTH, category = SpecCategory.DATE_TIME,
            name = "Day of month", description = "True on the days of the month you choose.",
            iconKey = "calendar_month", synonyms = listOf("1st", "15th", "end of month", "payday"),
            params = listOf(
                ParamSpec.IntSet("days", "Days", 1, 31),
                ParamSpec.Toggle("last", "Also the last day of the month"),
            ),
            summary = { p ->
                val last: List<String> = if (p.bool("last")) listOf("last") else emptyList()
                val days: List<String> = p.intList("days").sorted().map { it.toString() } + last
                "Day of month: " + days.joinToString(", ").ifBlank { "none" }
            },
            validate = { p -> if (p.intList("days").isEmpty() && !p.bool("last")) listOf("Choose at least one day.") else emptyList() },
        ),
        Spec(
            kind = SpecKind.CONSTRAINT, id = CheckIds.MONTH_OF_YEAR, category = SpecCategory.DATE_TIME,
            name = "Month", description = "True in the months you choose.",
            iconKey = "calendar_blank", synonyms = listOf("january", "winter", "summer", "season"),
            params = listOf(ParamSpec.IntSet("months", "Months", 1, 12, monthNames)),
            summary = { p -> "Months: " + p.intList("months").sorted().mapNotNull { monthNames.getOrNull(it - 1) }.joinToString(", ").ifBlank { "none" } },
            validate = { p -> if (p.intList("months").isEmpty()) listOf("Choose at least one month.") else emptyList() },
        ),
    )

    // ── logic, loops and data actions ───────────────────────────────────────────────────────

    private val textOps = listOf(
        Choice("substring", "Part of the text"), Choice("replace", "Replace text"), Choice("regex_replace", "Replace with a pattern"),
        Choice("regex_find", "Find with a pattern"), Choice("matches", "Matches a pattern"), Choice("split", "Split into a list"),
        Choice("join", "Join a list"), Choice("upper", "UPPER CASE"), Choice("lower", "lower case"), Choice("title", "Title Case"),
        Choice("trim", "Trim spaces"), Choice("pad", "Pad to a width"), Choice("length", "Length"), Choice("contains", "Contains"),
    )

    private val listOps = listOf(
        Choice("create", "Create an empty list"), Choice("add", "Add an item"), Choice("remove_at", "Remove item number"),
        Choice("remove_value", "Remove an item by value"), Choice("get", "Get item number"), Choice("length", "Count items"),
        Choice("contains", "Contains"), Choice("sort", "Sort"), Choice("reverse", "Reverse"), Choice("join", "Join into text"),
        Choice("clear", "Remove everything"),
    )

    private val dictOps = listOf(
        Choice("set", "Set a key"), Choice("get", "Get a key"), Choice("remove", "Remove a key"), Choice("has", "Has a key"),
        Choice("keys", "List the keys"), Choice("length", "Count keys"), Choice("clear", "Remove everything"),
    )

    private fun textLabel(id: String) = textOps.firstOrNull { it.value == id }?.label ?: id

    private fun logicActionSpecs(): List<Spec> = listOf(
        Spec(
            kind = SpecKind.ACTION, id = OpIds.FOR_EACH, category = SpecCategory.FLOW_CONTROL,
            name = "For each", description = "Runs steps once for every item of a list or dictionary. Gives {{item}}, {{index}} and {{key}}.",
            iconKey = "format_list_bulleted", synonyms = listOf("loop", "list", "every", "iterate"),
            params = listOf(
                ParamSpec.Text("source", "List", required = true, multiline = true, help = "A list or dictionary such as {{names}}, or one item per line."),
                ParamSpec.Text("itemName", "Call each item", default = "item", templated = false),
            ),
            blocks = listOf(OpIds.BLOCK_BODY),
            outputs = listOf(OutputSpec("item", "Current item"), OutputSpec("index", "Round number", "number"), OutputSpec("key", "Key (dictionaries)")),
            summary = { p -> "For each ${p.str("itemName", "item")} in ${p.str("source")}" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.WHILE, category = SpecCategory.FLOW_CONTROL,
            name = "While", description = "Repeats steps while a condition holds. Always stops after a maximum number of rounds.",
            iconKey = "sync", synonyms = listOf("loop", "until", "repeat while"),
            params = listOf(
                ParamSpec.Logic("condition", "While"),
                ParamSpec.Num("max", "At most", default = 100, min = 1, max = 1000, unit = "rounds"),
            ),
            blocks = listOf(OpIds.BLOCK_BODY),
            outputs = listOf(OutputSpec("index", "Round number", "number")),
            summary = { p -> "While the condition holds (up to ${p.int("max", 100)} rounds)" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.BREAK, category = SpecCategory.FLOW_CONTROL,
            name = "Leave loop", description = "Stops the loop it is in and carries on after it.",
            iconKey = "exit_to_app", synonyms = listOf("break", "exit loop"),
            summary = { "Leave the loop" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.CONTINUE, category = SpecCategory.FLOW_CONTROL,
            name = "Next round", description = "Skips the rest of this round of the loop.",
            iconKey = "skip_next", synonyms = listOf("continue", "skip"),
            summary = { "Go to the next round" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.STOP, category = SpecCategory.FLOW_CONTROL,
            name = "Stop Flow", description = "Ends the Flow here. It can finish normally, fail with a message, or count as skipped.",
            iconKey = "stop_circle_outline", synonyms = listOf("end", "exit", "return", "abort", "finish"),
            params = listOf(
                ParamSpec.Pick(
                    "outcome", "Result",
                    listOf(Choice("success", "Finish normally"), Choice("failed", "Fail"), Choice("skipped", "Count as skipped")),
                    "success",
                ),
                ParamSpec.Text("message", "Message"),
                ParamSpec.Text("result", "Value to return", help = "What a Flow that called this one gets back."),
            ),
            summary = { p ->
                val how = when (p.str("outcome", "success")) {
                    "failed" -> "fail"
                    "skipped" -> "skip"
                    else -> "finish"
                }
                "Stop and $how" + if (p.str("message").isNotBlank()) ": ${p.str("message")}" else ""
            },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.WAIT_UNTIL, category = SpecCategory.FLOW_CONTROL,
            name = "Wait until", description = "Waits for a condition to become true, for up to 10 seconds. Counts toward the run's wait budget.",
            iconKey = "timer_sand_complete", synonyms = listOf("wait for", "until", "poll"),
            params = listOf(
                ParamSpec.Logic("condition", "Wait until"),
                ParamSpec.Num("timeoutSeconds", "Give up after", default = 10, min = 1, max = 10, unit = "s"),
                ParamSpec.Num("pollSeconds", "Check every", default = 1, min = 1, max = 5, unit = "s"),
                ParamSpec.Pick("onTimeout", "If it never happens", listOf(Choice("fail", "Fail the step"), Choice("continue", "Carry on")), "fail"),
            ),
            outputs = listOf(OutputSpec("met", "Condition became true", "boolean")),
            summary = { p -> "Wait up to ${p.int("timeoutSeconds", 10)} s for the condition" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.TRY, category = SpecCategory.FLOW_CONTROL,
            name = "Try / on error", description = "Runs steps and, if one fails, runs a second list instead of failing the Flow. Gives {{error.message}}.",
            iconKey = "shield_alert_outline", synonyms = listOf("catch", "error handling", "fallback", "rescue"),
            blocks = listOf(OpIds.BLOCK_TRY, OpIds.BLOCK_CATCH),
            outputs = listOf(OutputSpec("error.message", "What went wrong")),
            summary = { "Try, and handle errors" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.CALCULATE, category = SpecCategory.VARIABLES,
            name = "Calculate", description = "Does maths: + - * / % ^, brackets, min, max, round, floor, ceil, abs, sqrt, pow. Variables work by name.",
            iconKey = "calculator", synonyms = listOf("math", "add", "multiply", "formula", "percent"),
            params = listOf(ParamSpec.Text("expression", "Calculation", required = true, help = "For example (battery + 5) * 2")),
            outputs = listOf(OutputSpec("result", "The answer", "number")),
            summary = { p -> p.str("expression") },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.TEXT, category = SpecCategory.VARIABLES,
            name = "Text tools", description = "Cuts, replaces, splits, joins and changes text, with patterns if you need them.",
            iconKey = "format_text", synonyms = listOf("string", "regex", "replace", "split", "substring", "uppercase"),
            params = listOf(
                ParamSpec.Pick("operation", "Do", textOps, "trim"),
                ParamSpec.Text("input", "Text", multiline = true),
                ParamSpec.Text("a", "First setting", help = "Part: start position (0 is the first character). Replace: text to find. Pattern: the pattern. Split/Join: the separator. Pad: the width."),
                ParamSpec.Text("b", "Second setting", help = "Part: length. Replace: the new text. Pad: the fill character."),
            ),
            outputs = listOf(OutputSpec("result", "The result")),
            summary = { p -> "${textLabel(p.str("operation", "trim"))}: ${p.str("input")}" },
            validate = { p ->
                val op = p.str("operation", "trim")
                when {
                    op in setOf("replace", "regex_replace", "regex_find", "matches", "contains") && p.str("a").isEmpty() -> listOf("Say what to look for.")
                    op in setOf("substring", "pad") && p.str("a").trim().toIntOrNull() == null -> listOf("The first setting must be a whole number.")
                    else -> emptyList()
                }
            },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.JSON, category = SpecCategory.VARIABLES,
            name = "Read JSON", description = "Parses JSON text and optionally picks a value out of it, for example user.items.0.name.",
            iconKey = "code_json", synonyms = listOf("parse", "api response", "dictionary", "object"),
            params = listOf(
                ParamSpec.Text("json", "JSON", required = true, multiline = true),
                ParamSpec.Text("path", "Path", help = "Dots walk into objects and lists. Leave empty for everything.", templated = false),
            ),
            outputs = listOf(OutputSpec("result", "The value")),
            summary = { p -> if (p.str("path").isBlank()) "Parse JSON" else "Read ${p.str("path")} from JSON" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.DATE_FORMAT, category = SpecCategory.DATE_TIME,
            name = "Format date and time", description = "Writes the current time, or a saved timestamp, in the layout you choose.",
            iconKey = "calendar_clock", synonyms = listOf("date", "time", "clock", "timestamp", "now"),
            params = listOf(
                ParamSpec.Pick("from", "Use", listOf(Choice("now", "The current time"), Choice("timestamp", "A timestamp (milliseconds)")), "now"),
                ParamSpec.Text("timestamp", "Timestamp", help = "Only for \"A timestamp\"."),
                ParamSpec.Text("pattern", "Layout", default = "yyyy-MM-dd HH:mm", help = "For example EEEE d MMMM or HH:mm:ss.", templated = false),
            ),
            outputs = listOf(OutputSpec("result", "The formatted date")),
            summary = { p -> "${if (p.str("from", "now") == "now") "Now" else "Timestamp"} as ${p.str("pattern", "yyyy-MM-dd HH:mm")}" },
            validate = { p ->
                try {
                    DateTimeFormatter.ofPattern(p.str("pattern", "yyyy-MM-dd HH:mm"))
                    emptyList()
                } catch (e: IllegalArgumentException) {
                    listOf("That layout isn't valid.")
                }
            },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.RANDOM, category = SpecCategory.VARIABLES,
            name = "Random", description = "A random whole number between two values, or a random item from a list.",
            iconKey = "dice_multiple", synonyms = listOf("dice", "pick", "choose", "chance"),
            params = listOf(
                ParamSpec.Pick("mode", "Pick", listOf(Choice("number", "A number"), Choice("item", "An item from a list")), "number"),
                ParamSpec.Num("min", "From", default = 1),
                ParamSpec.Num("max", "To", default = 100),
                ParamSpec.Text("list", "List", multiline = true, help = "For \"An item\": a list such as {{names}}, or one item per line."),
            ),
            outputs = listOf(OutputSpec("result", "The random value")),
            summary = { p -> if (p.str("mode", "number") == "item") "Random item from ${p.str("list")}" else "Random ${p.int("min", 1)} to ${p.int("max", 100)}" },
            validate = { p -> if (p.str("mode", "number") == "number" && p.int("min", 1) > p.int("max", 100)) listOf("\"From\" must not be higher than \"To\".") else emptyList() },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.LIST_OP, category = SpecCategory.VARIABLES,
            name = "List", description = "Keeps a list in a variable: add, remove, look up, count, sort, join.",
            iconKey = "format_list_numbered", synonyms = listOf("array", "collection", "queue", "stack"),
            params = listOf(
                ParamSpec.VariableName("variable", "List variable"),
                ParamSpec.Pick("operation", "Do", listOps, "add"),
                ParamSpec.Text("value", "Value", help = "Item, or separator for \"Join into text\"."),
                ParamSpec.Num("index", "Item number", default = 1, min = 1, help = "Counting from 1."),
            ),
            outputs = listOf(OutputSpec("result", "The answer (item, count, text…)")),
            summary = { p -> "${listOps.firstOrNull { it.value == p.str("operation", "add") }?.label ?: "List"} · ${p.str("variable")}" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.DICT_OP, category = SpecCategory.VARIABLES,
            name = "Dictionary", description = "Keeps named values in a variable: set, get, remove, list the keys.",
            iconKey = "book_open_variant", synonyms = listOf("map", "object", "key value", "record"),
            params = listOf(
                ParamSpec.VariableName("variable", "Dictionary variable"),
                ParamSpec.Pick("operation", "Do", dictOps, "set"),
                ParamSpec.Text("key", "Key", templated = true),
                ParamSpec.Text("value", "Value"),
            ),
            outputs = listOf(OutputSpec("result", "The answer (value, keys, count…)")),
            summary = { p -> "${dictOps.firstOrNull { it.value == p.str("operation", "set") }?.label ?: "Dictionary"} · ${p.str("variable")}" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.SET_LOCAL, category = SpecCategory.VARIABLES,
            name = "Set local value", description = "A value that lives only during this run. It can be text, a number, true/false or JSON.",
            iconKey = "variable_box", synonyms = listOf("temporary", "local variable", "typed value"),
            params = listOf(
                ParamSpec.VariableName("name", "Name"),
                ParamSpec.Pick(
                    "valueType", "Type",
                    listOf(Choice("text", "Text"), Choice("number", "Number"), Choice("boolean", "True / false"), Choice("json", "List or dictionary (JSON)")),
                    "text",
                ),
                ParamSpec.Text("value", "Value", multiline = true),
            ),
            summary = { p -> "${p.str("name")} = ${p.str("value")}" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.FLOW_ENABLE, category = SpecCategory.FLOW_CONTROL,
            name = "Turn a Flow on or off", description = "Switches another Flow on, off, or flips it.",
            iconKey = "toggle_switch", synonyms = listOf("enable", "disable", "activate", "pause a flow"),
            params = listOf(
                ParamSpec.FlowRef("flowId", "Flow"),
                ParamSpec.Pick("action", "Do", listOf(Choice("enable", "Turn on"), Choice("disable", "Turn off"), Choice("toggle", "Switch")), "enable"),
            ),
            summary = { p ->
                val name = p.str("flowName").ifBlank { p.str("flowId").take(8) }
                when (p.str("action", "enable")) {
                    "disable" -> "Turn off $name"
                    "toggle" -> "Switch $name"
                    else -> "Turn on $name"
                }
            },
        ),
    )

    // ── device, apps and notification actions ───────────────────────────────────────────────

    private val restoreToggle = ParamSpec.Toggle("restore", "Put it back when the Flow ends", help = "Restored even if a later step fails.")

    private fun deviceActionSpecs(): List<Spec> = listOf(
        Spec(
            kind = SpecKind.ACTION, id = OpIds.RINGER_MODE, category = SpecCategory.SOUND,
            name = "Set ringer mode", description = "Switches the phone between ringing, vibrating and silent. Silent needs Do Not Disturb access.",
            iconKey = "volume_off", synonyms = listOf("silent", "mute", "vibrate", "ring", "make phone silent"),
            params = listOf(
                ParamSpec.Pick("mode", "Ringer", RingerMode.entries.map { Choice(it.name, ringerWord(it)) }, RingerMode.SILENT.name),
                restoreToggle,
            ),
            permissions = listOf(Perm.DND_ACCESS), reliability = Reliability.GOOD,
            summary = { p -> "Ringer: ${ringerWord(p.enum("mode", RingerMode.SILENT)).lowercase()}" + if (p.bool("restore")) " (temporarily)" else "" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.DND, category = SpecCategory.SOUND,
            name = "Do Not Disturb", description = "Turns Do Not Disturb on or off, or changes how much it lets through.",
            iconKey = "minus_circle", synonyms = listOf("dnd", "quiet", "focus", "sleep", "silence"),
            params = listOf(
                ParamSpec.Pick(
                    "filter", "Set to",
                    listOf(Choice("off", "Off"), Choice("priority", "Priority only"), Choice("alarms", "Alarms only"), Choice("total_silence", "Total silence")),
                    "priority",
                ),
                restoreToggle,
            ),
            permissions = listOf(Perm.DND_ACCESS),
            summary = { p ->
                val label = when (p.str("filter", "priority")) {
                    "off" -> "off"
                    "alarms" -> "alarms only"
                    "total_silence" -> "total silence"
                    else -> "priority only"
                }
                "Do Not Disturb: $label" + if (p.bool("restore")) " (temporarily)" else ""
            },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.VOLUME_ADJUST, category = SpecCategory.SOUND,
            name = "Change volume", description = "Turns a volume up or down by one step, or mutes and unmutes it.",
            iconKey = "volume_plus", synonyms = listOf("louder", "quieter", "mute", "unmute", "volume up", "volume down"),
            params = listOf(
                ParamSpec.Pick("stream", "Stream", VolumeStream.entries.map { Choice(it.name, volumeStreamLabel(it)) }, VolumeStream.MEDIA.name),
                ParamSpec.Pick("action", "Do", listOf(Choice("up", "Turn up"), Choice("down", "Turn down"), Choice("mute", "Mute"), Choice("unmute", "Unmute")), "down"),
                restoreToggle,
            ),
            outputs = listOf(OutputSpec("result", "Volume afterwards", "number")),
            summary = { p -> "${volumeStreamLabel(p.enum("stream", VolumeStream.MEDIA))}: ${p.str("action", "down")}" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.BRIGHTNESS, category = SpecCategory.DEVICE,
            name = "Set brightness", description = "Sets the screen brightness, or switches to automatic brightness.",
            iconKey = "brightness_6", synonyms = listOf("dim", "brighter", "screen", "light"),
            params = listOf(
                ParamSpec.Pick("mode", "Mode", listOf(Choice("manual", "Fixed level"), Choice("auto", "Automatic")), "manual"),
                ParamSpec.Num("percent", "Level", default = 50, min = 0, max = 100, unit = "%"),
                restoreToggle,
            ),
            permissions = listOf(Perm.WRITE_SETTINGS),
            summary = { p -> if (p.str("mode", "manual") == "auto") "Automatic brightness" else "Brightness ${p.int("percent", 50)}%" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.SCREEN_TIMEOUT, category = SpecCategory.DEVICE,
            name = "Set screen timeout", description = "Changes how long the screen stays on when you aren't touching it.",
            iconKey = "timer_outline", synonyms = listOf("sleep", "screen off", "display timeout"),
            params = listOf(
                ParamSpec.Num("seconds", "Turn off after", default = 60, min = 15, max = 1_800, unit = "s"),
                restoreToggle,
            ),
            permissions = listOf(Perm.WRITE_SETTINGS),
            summary = { p -> "Screen off after ${p.int("seconds", 60)} s" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.AUTO_ROTATE, category = SpecCategory.DEVICE,
            name = "Auto-rotate", description = "Turns automatic screen rotation on or off, or flips it.",
            iconKey = "screen_rotation", synonyms = listOf("rotation", "orientation", "landscape"),
            params = listOf(
                ParamSpec.Pick("action", "Do", listOf(Choice("on", "Turn on"), Choice("off", "Turn off"), Choice("toggle", "Switch")), "toggle"),
                restoreToggle,
            ),
            permissions = listOf(Perm.WRITE_SETTINGS),
            outputs = listOf(OutputSpec("result", "Auto-rotate is on", "boolean")),
            summary = { p -> "Auto-rotate: ${p.str("action", "toggle")}" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.KEEP_AWAKE, category = SpecCategory.DEVICE,
            name = "Keep phone awake", description = "Stops the phone sleeping until this run ends, for at most 10 minutes.",
            iconKey = "sleep_off", synonyms = listOf("wake lock", "stay on", "caffeine"),
            params = listOf(ParamSpec.Num("minutes", "At most", default = 2, min = 1, max = 10, unit = "min")),
            batteryCost = BatteryCost.MEDIUM,
            summary = { p -> "Awake for up to ${p.int("minutes", 2)} min" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.MEDIA_KEY, category = SpecCategory.SOUND,
            name = "Media control", description = "Presses a media key: play, pause, next, previous or stop.",
            iconKey = "play_pause", synonyms = listOf("music", "play", "pause", "skip", "next song"),
            params = listOf(
                ParamSpec.Pick(
                    "key", "Press",
                    listOf(Choice("play_pause", "Play / pause"), Choice("play", "Play"), Choice("pause", "Pause"), Choice("next", "Next"), Choice("previous", "Previous"), Choice("stop", "Stop")),
                    "play_pause",
                ),
            ),
            reliability = Reliability.BEST_EFFORT,
            summary = { p -> "Media: ${p.str("key", "play_pause").replace('_', ' ')}" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.OPEN_URL, category = SpecCategory.DEVICE,
            name = "Open link", description = "Opens a web, email, phone or map link. When Androx is in the background it posts a notification to tap instead.",
            iconKey = "open_in_new", synonyms = listOf("website", "browser", "url", "web", "call", "email"),
            params = listOf(ParamSpec.Text("url", "Link", required = true, help = "http://, https://, mailto:, tel: or geo:")),
            reliability = Reliability.BEST_EFFORT,
            outputs = listOf(OutputSpec("result", "What happened")),
            summary = { p -> p.str("url") },
            validate = { p ->
                val url = p.str("url").trim()
                if (url.isEmpty() || url.contains("{{") || listOf("http://", "https://", "mailto:", "tel:", "geo:").any { url.startsWith(it, ignoreCase = true) }) {
                    emptyList()
                } else {
                    listOf("The link must start with http://, https://, mailto:, tel: or geo:.")
                }
            },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.LAUNCH_APP, category = SpecCategory.DEVICE,
            name = "Open app", description = "Opens an installed app. When Androx is in the background it posts a notification to tap instead.",
            iconKey = "application", synonyms = listOf("launch", "start app", "run app"),
            params = listOf(ParamSpec.Text("packageName", "App", required = true, templated = false, help = "The app's package name, for example com.android.chrome.")),
            reliability = Reliability.BEST_EFFORT,
            outputs = listOf(OutputSpec("result", "What happened")),
            summary = { p -> p.str("packageName") },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.SHARE_TEXT, category = SpecCategory.DEVICE,
            name = "Share text", description = "Opens the share sheet with some text. When Androx is in the background it posts a notification to tap instead.",
            iconKey = "share_variant", synonyms = listOf("send", "message"),
            params = listOf(
                ParamSpec.Text("text", "Text", required = true, multiline = true),
                ParamSpec.Text("subject", "Subject"),
            ),
            reliability = Reliability.BEST_EFFORT,
            outputs = listOf(OutputSpec("result", "What happened")),
            summary = { p -> p.str("text") },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.SET_ALARM, category = SpecCategory.DATE_TIME,
            name = "Set alarm", description = "Creates an alarm in the Clock app.",
            iconKey = "alarm_plus", synonyms = listOf("wake me", "clock", "reminder"),
            params = listOf(
                ParamSpec.TimeOfDay("hour", "minute", "Time", defaultHour = 7),
                ParamSpec.Text("label", "Label"),
                ParamSpec.Toggle("skipUi", "Don't open the Clock app", default = true),
            ),
            reliability = Reliability.BEST_EFFORT,
            outputs = listOf(OutputSpec("result", "What happened")),
            summary = { p -> "Alarm at ${formatClock(p.int("hour", 7), p.int("minute", 0))}" + if (p.str("label").isNotBlank()) " – ${p.str("label")}" else "" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.SET_TIMER, category = SpecCategory.DATE_TIME,
            name = "Set timer", description = "Starts a countdown timer in the Clock app.",
            iconKey = "timer_sand", synonyms = listOf("countdown", "egg timer", "stopwatch"),
            params = listOf(
                ParamSpec.Num("seconds", "Length", default = 300, min = 1, max = 86_400, unit = "s"),
                ParamSpec.Text("label", "Label"),
            ),
            reliability = Reliability.BEST_EFFORT,
            outputs = listOf(OutputSpec("result", "What happened")),
            summary = { p -> "Timer for ${p.int("seconds", 300)} s" + if (p.str("label").isNotBlank()) " – ${p.str("label")}" else "" },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.NOTIFICATION, category = SpecCategory.NOTIFY,
            name = "Notification", description = "A full notification: importance, progress bar, auto-dismiss and up to three buttons that run Flows.",
            iconKey = "bell_plus_outline", synonyms = listOf("alert", "button", "progress", "ongoing", "reminder"),
            params = listOf(
                ParamSpec.Text("id", "Name", default = "default", templated = false, help = "Use the same name later to update or clear it."),
                ParamSpec.Text("title", "Title", default = "Androx"),
                ParamSpec.Text("text", "Message", multiline = true),
                ParamSpec.Pick("importance", "Importance", listOf(Choice("low", "Quiet"), Choice("default", "Normal"), Choice("high", "Pops up")), "default"),
                ParamSpec.Toggle("ongoing", "Keep it until I clear it"),
                ParamSpec.Num("progress", "Progress bar", default = -1, min = -1, max = 100, unit = "%", help = "-1 shows none."),
                ParamSpec.Toggle("autoDismiss", "Remove it automatically"),
                ParamSpec.Num("dismissSeconds", "After", default = 30, min = 1, max = 86_400, unit = "s"),
                ParamSpec.Text("b1Label", "Button 1", templated = true),
                ParamSpec.FlowRef("b1FlowId", "Button 1 runs", nameKey = "b1FlowName", required = false),
                ParamSpec.Text("b2Label", "Button 2", templated = true),
                ParamSpec.FlowRef("b2FlowId", "Button 2 runs", nameKey = "b2FlowName", required = false),
                ParamSpec.Text("b3Label", "Button 3", templated = true),
                ParamSpec.FlowRef("b3FlowId", "Button 3 runs", nameKey = "b3FlowName", required = false),
            ),
            permissions = listOf(Perm.NOTIFICATIONS),
            outputs = listOf(OutputSpec("result", "Notification name")),
            summary = { p ->
                val buttons = (1..3).count { p.str("b${it}Label").isNotBlank() && p.str("b${it}FlowId").isNotBlank() }
                p.str("title", "Androx").ifBlank { "Androx" } + (if (p.str("text").isNotBlank()) " – ${p.str("text")}" else "") +
                    if (buttons > 0) " · $buttons button${if (buttons == 1) "" else "s"}" else ""
            },
            validate = { p ->
                (1..3).mapNotNull { n ->
                    val label = p.str("b${n}Label").isNotBlank()
                    val flow = p.str("b${n}FlowId").isNotBlank()
                    if (label != flow) "Button $n needs both a name and a Flow to run." else null
                }
            },
        ),
        Spec(
            kind = SpecKind.ACTION, id = OpIds.CLEAR_NOTIFICATION, category = SpecCategory.NOTIFY,
            name = "Clear notification", description = "Removes a notification that a Notification step posted.",
            iconKey = "bell_off_outline", synonyms = listOf("dismiss", "remove notification"),
            params = listOf(ParamSpec.Text("id", "Name", default = "default", templated = false)),
            summary = { p -> "Clear \"${p.str("id", "default")}\"" },
        ),
    )
}
