package com.androx.app.core.registry

import com.androx.app.core.model.CheckIds
import com.androx.app.core.model.OpIds
import com.androx.app.core.model.P
import com.androx.app.core.model.TriggerIds
import com.androx.app.core.value.Value
import com.androx.app.core.value.ValueType
import com.androx.app.model.Comparison
import com.androx.app.model.FlashlightMode
import com.androx.app.model.RingerMode
import com.androx.app.model.VariableMode
import com.androx.app.model.VolumeStream
import com.androx.app.model.describeDays
import com.androx.app.model.describeInterval
import com.androx.app.model.formatClock
import com.androx.app.model.formatDateTime
import com.androx.app.model.ringerWord
import com.androx.app.model.volumeStreamLabel

private val DAYS = listOf(
    Choice("1", "Monday"), Choice("2", "Tuesday"), Choice("3", "Wednesday"), Choice("4", "Thursday"),
    Choice("5", "Friday"), Choice("6", "Saturday"), Choice("7", "Sunday"),
)

private fun cmpChoices() = Comparison.entries.map { Choice(it.name, it.symbol) }

private fun onOff(p: P, key: String = "on") = if (p.bool(key, true)) "on" else "off"

/** The specs for everything v0.0.1 could do. Later layers add many more, one declaration each. */
object BuiltinSpecs {
    val triggers: List<TriggerSpec> = listOf(
        TriggerSpec(
            id = TriggerIds.MANUAL, category = "Manual", name = "Manual", icon = "play",
            description = "Starts only when you run it: from the app, a widget, a tile or a shortcut.",
            synonyms = listOf("tap", "button", "run now", "on demand"),
        ) { "Only when you run it" },
        TriggerSpec(
            id = TriggerIds.SCHEDULE, category = "Date and time", name = "Schedule", icon = "calendar-clock", timed = true,
            description = "Runs at a time of day, every day or on chosen days.",
            synonyms = listOf("daily", "weekly", "alarm", "every morning", "time of day", "at 8"),
            fields = listOf(
                FieldSpec("hour", "Hour", FieldType.TIME, Value.Num(8.0)),
                FieldSpec("minute", "Minute", FieldType.TIME, Value.Num(0.0)),
                FieldSpec("days", "Days", FieldType.DAYS, null, choices = DAYS),
            ),
        ) { p ->
            val days = p.ints("days")
            "At ${formatClock(p.int("hour", 8), p.int("minute", 0))} · ${describeDays(days)}"
        },
        TriggerSpec(
            id = TriggerIds.INTERVAL, category = "Date and time", name = "Interval", icon = "timelapse", timed = true,
            description = "Repeats every few minutes or hours. Android may delay runs to save battery.",
            synonyms = listOf("repeat", "every 30 minutes", "regular", "periodic", "hourly"),
            fields = listOf(FieldSpec("minutes", "Every (minutes)", FieldType.DURATION_MINUTES, Value.Num(60.0), required = true, min = 5.0)),
        ) { p -> "Every ${describeInterval(p.int("minutes", 60))}" },
        TriggerSpec(
            id = TriggerIds.ONCE, category = "Date and time", name = "Once", icon = "calendar-today", timed = true,
            description = "Runs one time at a date and time, then turns itself off.",
            synonyms = listOf("one time", "single", "reminder", "date"),
            fields = listOf(FieldSpec("atMillis", "Date and time", FieldType.TIME, null, required = true)),
        ) { p -> "Once, ${formatDateTime(p.long("atMillis"))}" },
        TriggerSpec(
            id = TriggerIds.BOOT, category = "Device", name = "Device starts", icon = "power",
            description = "Runs when the phone finishes starting up.",
            synonyms = listOf("boot", "startup", "restart", "power on"),
        ) { "When the device starts" },
    )

    val constraints: List<ConstraintSpec> = listOf(
        ConstraintSpec(
            id = CheckIds.BATTERY_LEVEL, category = "Battery and power", name = "Battery level", icon = "battery",
            description = "Compares the battery percentage.", synonyms = listOf("charge", "percent", "low battery"),
            fields = listOf(
                FieldSpec("op", "Comparison", FieldType.COMPARISON, Value.Text("LT"), choices = cmpChoices()),
                FieldSpec("percent", "Percent", FieldType.NUMBER, Value.Num(20.0), required = true, min = 0.0, max = 100.0),
            ),
        ) { p ->
            val op = p.enum("op", Comparison.entries.toTypedArray(), Comparison.LT)
            "Battery ${op.symbol} ${p.int("percent", 20)}%"
        },
        ConstraintSpec(
            id = CheckIds.BATTERY_CHARGING, category = "Battery and power", name = "Charging", icon = "battery-charging",
            description = "Whether the phone is plugged in.", synonyms = listOf("plugged", "charger", "power connected"),
            fields = listOf(FieldSpec("charging", "Charging", FieldType.BOOLEAN, Value.Bool(true))),
        ) { p -> if (p.bool("charging", true)) "Charging" else "Not charging" },
        ConstraintSpec(
            id = CheckIds.BATTERY_SAVER, category = "Battery and power", name = "Battery Saver", icon = "battery-alert",
            description = "Whether Battery Saver is on.", synonyms = listOf("power saver", "low power mode"),
            fields = listOf(FieldSpec("on", "On", FieldType.BOOLEAN, Value.Bool(true))),
        ) { p -> "Battery Saver is ${onOff(p)}" },
        ConstraintSpec(
            id = CheckIds.VARIABLE_COMPARE, category = "Logic and Flow", name = "Variable", icon = "variable",
            description = "Compares a variable with a value. Numbers are compared as numbers.", synonyms = listOf("compare", "value", "equals"),
            fields = listOf(
                FieldSpec("name", "Variable", FieldType.VARIABLE_NAME, null, required = true),
                FieldSpec("op", "Comparison", FieldType.COMPARISON, Value.Text("EQ"), choices = cmpChoices()),
                FieldSpec("value", "Value", FieldType.TEXT, Value.Text(""), templated = true),
            ),
        ) { p ->
            val op = p.enum("op", Comparison.entries.toTypedArray(), Comparison.EQ)
            "${p.str("name", "variable")} ${op.symbol} ${p.str("value")}"
        },
        ConstraintSpec(
            id = CheckIds.TIME_WINDOW, category = "Date and time", name = "Time of day", icon = "clock-outline",
            description = "True between two times. A window such as 22:00 to 07:00 wraps past midnight.",
            synonyms = listOf("between", "night", "working hours", "window"),
            fields = listOf(
                FieldSpec("fromHour", "From hour", FieldType.TIME, Value.Num(22.0)),
                FieldSpec("fromMinute", "From minute", FieldType.TIME, Value.Num(0.0)),
                FieldSpec("toHour", "To hour", FieldType.TIME, Value.Num(7.0)),
                FieldSpec("toMinute", "To minute", FieldType.TIME, Value.Num(0.0)),
            ),
        ) { p ->
            "Between ${formatClock(p.int("fromHour", 22), p.int("fromMinute", 0))} and ${formatClock(p.int("toHour", 7), p.int("toMinute", 0))}"
        },
        ConstraintSpec(
            id = CheckIds.WEEKDAY, category = "Date and time", name = "Day of week", icon = "calendar-today",
            description = "True on the chosen days.", synonyms = listOf("weekday", "weekend", "monday"),
            fields = listOf(FieldSpec("days", "Days", FieldType.DAYS, null, choices = DAYS)),
        ) { p -> "On ${describeDays(p.ints("days"))}" },
        ConstraintSpec(
            id = CheckIds.WIFI, category = "Connectivity", name = "Wi-Fi", icon = "wifi",
            description = "Whether the phone is on Wi-Fi.", synonyms = listOf("wireless", "network"),
            requires = listOf(Requirement.RuntimePermission("android.permission.ACCESS_NETWORK_STATE", "Network state")),
            fields = listOf(FieldSpec("connected", "Connected", FieldType.BOOLEAN, Value.Bool(true))),
        ) { p -> if (p.bool("connected", true)) "Wi-Fi connected" else "Wi-Fi not connected" },
        ConstraintSpec(
            id = CheckIds.SCREEN, category = "Device state", name = "Screen", icon = "cellphone",
            description = "Whether the screen is on.", synonyms = listOf("display", "awake"),
            fields = listOf(FieldSpec("on", "On", FieldType.BOOLEAN, Value.Bool(true))),
        ) { p -> "Screen is ${onOff(p)}" },
        ConstraintSpec(
            id = CheckIds.RINGER, category = "Device state", name = "Ringer mode", icon = "volume-off",
            description = "Normal, vibrate or silent.", synonyms = listOf("silent", "mute", "vibrate", "sound mode"),
            fields = listOf(
                FieldSpec(
                    "mode", "Mode", FieldType.CHOICE, Value.Text("SILENT"),
                    choices = RingerMode.entries.map { Choice(it.name, ringerWord(it)) },
                ),
            ),
        ) { p -> "Ringer is ${ringerWord(p.enum("mode", RingerMode.entries.toTypedArray(), RingerMode.SILENT)).lowercase()}" },
        ConstraintSpec(
            id = CheckIds.AIRPLANE, category = "Device state", name = "Airplane mode", icon = "airplane",
            description = "Whether Airplane mode is on.", synonyms = listOf("flight mode", "offline"),
            fields = listOf(FieldSpec("on", "On", FieldType.BOOLEAN, Value.Bool(true))),
        ) { p -> "Airplane mode is ${onOff(p)}" },
    )

    private val textOut = OutputSpec("result", "Result", ValueType.TEXT)

    val actions: List<ActionSpec> = listOf(
        ActionSpec(
            id = OpIds.NOTIFY, category = "Notify", name = "Show notification", icon = "bell-outline",
            description = "Posts a notification.", synonyms = listOf("alert", "message", "remind"),
            requires = listOf(Requirement.RuntimePermission("android.permission.POST_NOTIFICATIONS", "Notifications")),
            fields = listOf(
                FieldSpec("title", "Title", FieldType.TEXT, Value.Text("Androx"), templated = true),
                FieldSpec("message", "Message", FieldType.MULTILINE, Value.Text(""), templated = true),
            ),
        ) { p -> "Show notification \"${p.str("title", "Androx")}\"" },
        ActionSpec(
            id = OpIds.TOAST, category = "Notify", name = "Show toast", icon = "message-text-outline",
            description = "Shows a short message at the bottom of the screen.", synonyms = listOf("popup", "snackbar"),
            fields = listOf(FieldSpec("message", "Message", FieldType.TEXT, Value.Text(""), required = true, templated = true)),
        ) { p -> "Show toast \"${p.str("message")}\"" },
        ActionSpec(
            id = OpIds.SPEAK, category = "Notify", name = "Speak", icon = "volume-high",
            description = "Reads text aloud.", synonyms = listOf("say", "text to speech", "tts", "voice"),
            fields = listOf(FieldSpec("text", "Text", FieldType.TEXT, Value.Text(""), required = true, templated = true)),
        ) { p -> "Say \"${p.str("text")}\"" },
        ActionSpec(
            id = OpIds.SOUND, category = "Notify", name = "Play sound", icon = "music-note",
            description = "Plays the default notification sound.", synonyms = listOf("ding", "beep", "chime"),
        ) { "Play the notification sound" },
        ActionSpec(
            id = OpIds.VIBRATE, category = "Device", name = "Vibrate", icon = "vibrate",
            description = "Buzzes the phone.", synonyms = listOf("buzz", "haptic"),
            fields = listOf(FieldSpec("durationMs", "Milliseconds", FieldType.NUMBER, Value.Num(400.0), min = 50.0, max = 5000.0)),
        ) { p -> "Vibrate for ${p.int("durationMs", 400)} ms" },
        ActionSpec(
            id = OpIds.CLIPBOARD, category = "Device", name = "Copy to clipboard", icon = "content-copy",
            description = "Copies text so you can paste it.", synonyms = listOf("copy", "paste"),
            fields = listOf(FieldSpec("text", "Text", FieldType.MULTILINE, Value.Text(""), required = true, templated = true)),
        ) { "Copy text to the clipboard" },
        ActionSpec(
            id = OpIds.FLASHLIGHT, category = "Device", name = "Flashlight", icon = "flashlight",
            description = "Turns the torch on, off or toggles it.", synonyms = listOf("torch", "light"),
            requires = listOf(Requirement.Hardware("android.hardware.camera.flash", "Camera flash")),
            fields = listOf(
                FieldSpec(
                    "mode", "Mode", FieldType.CHOICE, Value.Text("TOGGLE"),
                    choices = FlashlightMode.entries.map { Choice(it.name, it.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                ),
            ),
        ) { p -> "Flashlight ${p.enum("mode", FlashlightMode.entries.toTypedArray(), FlashlightMode.TOGGLE).name.lowercase()}" },
        ActionSpec(
            id = OpIds.VOLUME, category = "Device", name = "Set volume", icon = "volume-medium",
            description = "Sets a volume stream to a percentage.", synonyms = listOf("loudness", "media volume", "ringer volume", "mute"),
            fields = listOf(
                FieldSpec(
                    "stream", "Stream", FieldType.CHOICE, Value.Text("MEDIA"),
                    choices = VolumeStream.entries.map { Choice(it.name, volumeStreamLabel(it)) },
                ),
                FieldSpec("percent", "Percent", FieldType.NUMBER, Value.Num(50.0), min = 0.0, max = 100.0),
            ),
        ) { p ->
            "Set ${volumeStreamLabel(p.enum("stream", VolumeStream.entries.toTypedArray(), VolumeStream.MEDIA)).lowercase()} volume to ${p.int("percent", 50)}%"
        },
        ActionSpec(
            id = OpIds.VARIABLE_SET, category = "Variables and data", name = "Set variable", icon = "variable",
            description = "Sets or changes a variable: set, add, subtract, multiply, append or remove.",
            synonyms = listOf("counter", "store", "increment", "save value"),
            fields = listOf(
                FieldSpec("name", "Variable", FieldType.VARIABLE_NAME, null, required = true),
                FieldSpec(
                    "mode", "Operation", FieldType.CHOICE, Value.Text("SET"),
                    choices = VariableMode.entries.map { Choice(it.name, it.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                ),
                FieldSpec("value", "Value", FieldType.TEXT, Value.Text(""), templated = true),
            ),
        ) { p ->
            val name = p.str("name", "variable")
            when (p.enum("mode", VariableMode.entries.toTypedArray(), VariableMode.SET)) {
                VariableMode.SET -> "Set $name to ${p.str("value")}"
                VariableMode.ADD -> "Add ${p.str("value")} to $name"
                VariableMode.SUBTRACT -> "Subtract ${p.str("value")} from $name"
                VariableMode.MULTIPLY -> "Multiply $name by ${p.str("value")}"
                VariableMode.APPEND -> "Append ${p.str("value")} to $name"
                VariableMode.DELETE -> "Remove $name"
            }
        },
        ActionSpec(
            id = OpIds.LOG, category = "Logging", name = "Write to log", icon = "message-text-outline",
            description = "Adds a line to the User log.", synonyms = listOf("debug", "record", "print"),
            fields = listOf(FieldSpec("message", "Message", FieldType.TEXT, Value.Text(""), required = true, templated = true)),
        ) { p -> "Write \"${p.str("message")}\" to the log" },
        ActionSpec(
            id = OpIds.SCRIPT, category = "Variables and data", name = "Run JavaScript", icon = "code-braces",
            description = "Runs a small script that can only use the androx API.", synonyms = listOf("code", "js", "script"),
            fields = listOf(FieldSpec("source", "Script", FieldType.MULTILINE, null, required = true)),
        ) { "Run a script" },
        ActionSpec(
            id = OpIds.RUN_FLOW, category = "Flow control", name = "Run Flow", icon = "play-circle-outline",
            description = "Runs another Flow as a step.", synonyms = listOf("call", "subflow", "nested"),
            fields = listOf(FieldSpec("flowId", "Flow", FieldType.FLOW, null, required = true)),
        ) { p -> "Run Flow \"${p.str("flowName").ifBlank { p.str("flowId") }}\"" },
        ActionSpec(
            id = OpIds.WAIT, category = "Flow control", name = "Wait", icon = "timer-sand",
            description = "Pauses the Flow for a few seconds.", synonyms = listOf("delay", "sleep", "pause"),
            fields = listOf(FieldSpec("seconds", "Seconds", FieldType.NUMBER, Value.Num(2.0), min = 1.0, max = 10.0)),
        ) { p -> "Wait ${p.int("seconds", 2)} s" },
        ActionSpec(
            id = OpIds.IF, category = "Flow control", name = "If / else", icon = "source-branch",
            description = "Runs one list of steps if a constraint passes, another if not.", synonyms = listOf("condition", "branch", "otherwise"),
            blocks = listOf("then", "else"), conditionIsTest = true,
        ) { "If … then … else …" },
        ActionSpec(
            id = OpIds.REPEAT, category = "Flow control", name = "Repeat", icon = "repeat",
            description = "Runs steps several times. {{index}} holds the round number.", synonyms = listOf("loop", "again", "times"),
            blocks = listOf("body"),
            fields = listOf(FieldSpec("times", "Times", FieldType.NUMBER, Value.Num(3.0), min = 1.0, max = 20.0)),
        ) { p -> "Repeat ${p.int("times", 3)} times" },
    )

    val triggerRegistry: Registry<TriggerSpec> get() = Registry(triggers)
    val constraintRegistry: Registry<ConstraintSpec> get() = Registry(constraints)
    val actionRegistry: Registry<ActionSpec> get() = Registry(actions)
}
