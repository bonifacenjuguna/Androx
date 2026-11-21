package com.androx.app.core.model

/** Stable ids stored in files. Never rename one: files from older versions refer to them. */
object TriggerIds {
    const val MANUAL = "manual"
    const val SCHEDULE = "time.schedule"
    const val INTERVAL = "time.interval"
    const val ONCE = "time.once"
    const val BOOT = "device.boot"
}

object CheckIds {
    const val BATTERY_LEVEL = "battery.level"
    const val BATTERY_CHARGING = "battery.charging"
    const val BATTERY_SAVER = "battery.saver"
    const val VARIABLE_COMPARE = "variable.compare"
    const val TIME_WINDOW = "time.window"
    const val WEEKDAY = "date.weekday"
    const val WIFI = "net.wifi"
    const val SCREEN = "device.screen"
    const val RINGER = "device.ringer"
    const val AIRPLANE = "device.airplane"
}

object OpIds {
    const val NOTIFY = "notify.show"
    const val TOAST = "notify.toast"
    const val SPEAK = "notify.speak"
    const val SOUND = "notify.sound"
    const val VARIABLE_SET = "var.set"
    const val RUN_FLOW = "flow.run"
    const val LOG = "log.write"
    const val SCRIPT = "script.run"
    const val VIBRATE = "device.vibrate"
    const val CLIPBOARD = "device.clipboard"
    const val WAIT = "flow.wait"
    const val FLASHLIGHT = "device.flashlight"
    const val VOLUME = "device.volume"
    const val IF = "flow.if"
    const val REPEAT = "flow.repeat"
}

/** Names of the child step lists of control-flow steps. */
object Blocks {
    const val THEN = "then"
    const val ELSE = "else"
    const val BODY = "body"
}
