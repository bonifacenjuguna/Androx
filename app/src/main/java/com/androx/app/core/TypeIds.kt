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
    const val COMPARE = "compare"
    const val FLOW_STATE = "flow_state"
    const val TRIGGERED_BY = "triggered_by"
    const val DATE_RANGE = "date_range"
    const val DAY_OF_MONTH = "day_of_month"
    const val MONTH_OF_YEAR = "month_of_year"

    /** Device-state constraints are stored as `state:<provider id>`, for example `state:battery.temp`. */
    const val STATE_PREFIX = "state:"
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
    const val FOR_EACH = "for_each"
    const val WHILE = "while"
    const val BREAK = "break"
    const val CONTINUE = "continue"
    const val STOP = "stop"
    const val WAIT_UNTIL = "wait_until"
    const val TRY = "try"
    const val CALCULATE = "calculate"
    const val TEXT = "text"
    const val JSON = "json"
    const val DATE_FORMAT = "date_format"
    const val RANDOM = "random"
    const val LIST_OP = "list_op"
    const val DICT_OP = "dict_op"
    const val SET_LOCAL = "set_local"
    const val FLOW_ENABLE = "flow_enable"
    const val RINGER_MODE = "ringer_mode"
    const val DND = "dnd"
    const val VOLUME_ADJUST = "volume_adjust"
    const val BRIGHTNESS = "brightness"
    const val SCREEN_TIMEOUT = "screen_timeout"
    const val AUTO_ROTATE = "auto_rotate"
    const val KEEP_AWAKE = "keep_awake"
    const val MEDIA_KEY = "media_key"
    const val OPEN_URL = "open_url"
    const val LAUNCH_APP = "launch_app"
    const val SHARE_TEXT = "share_text"
    const val SET_ALARM = "set_alarm"
    const val SET_TIMER = "set_timer"
    const val NOTIFICATION = "notification"
    const val CLEAR_NOTIFICATION = "clear_notification"

    /** Block names used by flow-control steps. */
    const val BLOCK_THEN = "then"
    const val BLOCK_ELSE = "else"
    const val BLOCK_BODY = "body"
    const val BLOCK_TRY = "try"
    const val BLOCK_CATCH = "catch"
}
