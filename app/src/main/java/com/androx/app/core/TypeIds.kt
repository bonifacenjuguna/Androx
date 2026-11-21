package com.androx.app.core

/**
 * Stable ids for every built-in trigger, constraint and action. These are what is stored on disk, so they never
 * change once shipped. The ids of the v0.0.1 features keep their v0.0.1 meaning; new entries just add a constant.
 */
object TriggerIds {
    const val MANUAL = "manual"
    const val SCHEDULE = "schedule"
    const val INTERVAL = "interval"
    const val ONCE = "once"
    const val BOOT = "boot"
}

object CheckIds {
    const val BATTERY_LEVEL = "battery_level"
    const val CHARGING = "charging"
    const val VARIABLE = "variable"
    const val TIME_WINDOW = "time_window"
    const val DAYS_OF_WEEK = "days_of_week"
    const val WIFI_CONNECTED = "wifi_connected"
    const val SCREEN_ON = "screen_on"
    const val RINGER_MODE = "ringer_mode"
    const val BATTERY_SAVER = "battery_saver"
    const val AIRPLANE_MODE = "airplane_mode"
}

object OpIds {
    const val NOTIFY = "notify"
    const val SET_VARIABLE = "set_variable"
    const val RUN_FLOW = "run_flow"
    const val WRITE_LOG = "write_log"
    const val RUN_SCRIPT = "run_script"
    const val VIBRATE = "vibrate"
    const val TOAST = "toast"
    const val CLIPBOARD = "clipboard"
    const val SPEAK = "speak"
    const val WAIT = "wait"
    const val FLASHLIGHT = "flashlight"
    const val SET_VOLUME = "set_volume"
    const val PLAY_SOUND = "play_sound"
    const val IF = "if"
    const val REPEAT = "repeat"

    /** Block names used by flow-control steps. */
    const val BLOCK_THEN = "then"
    const val BLOCK_ELSE = "else"
    const val BLOCK_BODY = "body"
}
