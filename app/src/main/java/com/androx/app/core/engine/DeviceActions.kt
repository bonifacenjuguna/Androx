package com.androx.app.core.engine

import com.androx.app.core.OpIds
import com.androx.app.core.Permissions
import com.androx.app.core.Value
import com.androx.app.core.bool
import com.androx.app.core.enum
import com.androx.app.core.int
import com.androx.app.core.str
import com.androx.app.engine.ActionException
import com.androx.app.model.RingerMode
import com.androx.app.model.VolumeStream
import com.androx.app.model.ringerWord
import com.androx.app.model.volumeStreamLabel
import kotlinx.serialization.json.JsonObject

/** Every action that changes the phone or talks to the system. Skipped (only described) in a simulation. */
val DEVICE_OP_IDS: Set<String> = setOf(
    OpIds.RINGER_MODE, OpIds.DND, OpIds.VOLUME_ADJUST, OpIds.BRIGHTNESS, OpIds.SCREEN_TIMEOUT, OpIds.AUTO_ROTATE,
    OpIds.KEEP_AWAKE, OpIds.MEDIA_KEY, OpIds.OPEN_URL, OpIds.LAUNCH_APP, OpIds.SHARE_TEXT, OpIds.SET_ALARM,
    OpIds.SET_TIMER, OpIds.NOTIFICATION, OpIds.CLEAR_NOTIFICATION,
)

private val allowedSchemes = listOf("http://", "https://", "mailto:", "tel:", "geo:")

/**
 * The device and notification actions (spec §18.2). Settings actions take a "restore" switch: the old value is read
 * first and put back when the Flow ends, even if a later step fails or the run is stopped (spec §18.1 "temporary change").
 */
internal fun deviceActions(system: SystemActions?): Map<String, ActionExecutor> {
    fun sys(): SystemActions = system ?: throw ActionException("Device actions aren't available here.")

    fun need(s: SystemActions, permission: String) {
        if (!s.hasPermission(permission)) throw ActionException(Permissions.fixText(permission))
    }

    return mapOf(
        OpIds.RINGER_MODE to ActionExecutor { p, env ->
            val s = sys()
            val mode = p.enum("mode", RingerMode.SILENT)
            if (mode == RingerMode.SILENT) need(s, Permissions.DND_ACCESS)
            val before = if (p.bool("restore")) s.ringerMode() else null
            s.setRinger(mode)
            if (before != null) env.onRunEnd("ringer back to ${ringerWord(before).lowercase()}") { s.setRinger(before) }
            env.detail("ringer is now ${ringerWord(mode).lowercase()}")
            Value.Text(mode.name.lowercase())
        },
        OpIds.DND to ActionExecutor { p, env ->
            val s = sys()
            need(s, Permissions.DND_ACCESS)
            val filter = p.str("filter", "off")
            val before = if (p.bool("restore")) s.dndFilter() else null
            s.setDnd(filter)
            if (before != null) env.onRunEnd("Do Not Disturb back to $before") { s.setDnd(before) }
            env.detail("Do Not Disturb is now $filter")
            Value.Text(filter)
        },
        OpIds.VOLUME_ADJUST to ActionExecutor { p, env ->
            val s = sys()
            val stream = p.enum("stream", VolumeStream.MEDIA)
            val action = p.str("action", "down")
            val before = if (p.bool("restore")) s.volumePercent(stream) else null
            s.adjustVolume(stream, action)
            if (before != null) env.onRunEnd("${volumeStreamLabel(stream)} volume back to $before%") { s.setVolumePercent(stream, before) }
            val now = runCatching { s.volumePercent(stream) }.getOrNull()
            env.detail("${volumeStreamLabel(stream)} volume: $action" + if (now != null) " (now $now%)" else "")
            now?.let { Value.Num(it.toDouble()) }
        },
        OpIds.BRIGHTNESS to ActionExecutor { p, env ->
            val s = sys()
            need(s, Permissions.WRITE_SETTINGS)
            val auto = p.str("mode", "manual") == "auto"
            val percent = p.int("percent", 50).coerceIn(0, 100)
            val before = if (p.bool("restore")) s.brightness() else null
            s.setBrightness(percent, auto)
            if (before != null) env.onRunEnd("brightness back to ${if (before.auto) "automatic" else "${before.percent}%"}") { s.setBrightness(before.percent, before.auto) }
            env.detail(if (auto) "brightness is automatic" else "brightness is $percent%")
            null
        },
        OpIds.SCREEN_TIMEOUT to ActionExecutor { p, env ->
            val s = sys()
            need(s, Permissions.WRITE_SETTINGS)
            val seconds = p.int("seconds", 60).coerceIn(15, 1_800)
            val before = if (p.bool("restore")) s.screenTimeoutSeconds() else null
            s.setScreenTimeoutSeconds(seconds)
            if (before != null) env.onRunEnd("screen timeout back to $before s") { s.setScreenTimeoutSeconds(before) }
            env.detail("screen turns off after $seconds s")
            null
        },
        OpIds.AUTO_ROTATE to ActionExecutor { p, env ->
            val s = sys()
            need(s, Permissions.WRITE_SETTINGS)
            val current = s.autoRotate()
            val target = when (p.str("action", "toggle")) {
                "on" -> true
                "off" -> false
                else -> !current
            }
            s.setAutoRotate(target)
            if (p.bool("restore")) env.onRunEnd("auto-rotate back to ${if (current) "on" else "off"}") { s.setAutoRotate(current) }
            env.detail("auto-rotate is ${if (target) "on" else "off"}")
            Value.Bool(target)
        },
        OpIds.KEEP_AWAKE to ActionExecutor { p, env ->
            val s = sys()
            val minutes = p.int("minutes", 2).coerceIn(1, 10)
            val lock = s.keepAwake(minutes)
            env.onRunEnd("stopped keeping the phone awake") { lock.close() }
            env.detail("keeping the phone awake until this run ends (at most $minutes min)")
            null
        },
        OpIds.MEDIA_KEY to ActionExecutor { p, env ->
            val key = p.str("key", "play_pause")
            sys().mediaKey(key)
            env.detail("sent $key")
            null
        },
        OpIds.OPEN_URL to ActionExecutor { p, env ->
            val url = env.render(p.str("url")).trim()
            if (url.isEmpty()) throw ActionException("Give a link to open.")
            if (allowedSchemes.none { url.startsWith(it, ignoreCase = true) }) {
                throw ActionException("Only web, email, phone and map links can be opened (http, https, mailto, tel, geo).")
            }
            val result = sys().openUrl(url)
            env.detail(result)
            Value.Text(result)
        },
        OpIds.LAUNCH_APP to ActionExecutor { p, env ->
            val pkg = env.render(p.str("packageName")).trim()
            if (pkg.isEmpty()) throw ActionException("Give the package name of the app to open.")
            val result = sys().launchApp(pkg)
            env.detail(result)
            Value.Text(result)
        },
        OpIds.SHARE_TEXT to ActionExecutor { p, env ->
            val result = sys().shareText(env.render(p.str("text")), env.render(p.str("subject")))
            env.detail(result)
            Value.Text(result)
        },
        OpIds.SET_ALARM to ActionExecutor { p, env ->
            val hour = p.int("hour", 7)
            val minute = p.int("minute", 0)
            if (hour !in 0..23 || minute !in 0..59) throw ActionException("That isn't a valid time.")
            val result = sys().setAlarm(hour, minute, env.render(p.str("label")), p.bool("skipUi", true))
            env.detail(result)
            Value.Text(result)
        },
        OpIds.SET_TIMER to ActionExecutor { p, env ->
            val seconds = p.int("seconds", 60)
            if (seconds !in 1..86_400) throw ActionException("A timer can be 1 second to 24 hours.")
            val result = sys().setTimer(seconds, env.render(p.str("label")))
            env.detail(result)
            Value.Text(result)
        },
        OpIds.NOTIFICATION to ActionExecutor { p, env -> postNotification(p, env, sys()) },
        OpIds.CLEAR_NOTIFICATION to ActionExecutor { p, env ->
            val id = env.render(p.str("id", "default")).trim().ifEmpty { "default" }
            sys().clearNotification(id)
            env.detail("cleared \"$id\"")
            null
        },
    )
}

private fun postNotification(p: JsonObject, env: ActionEnv, s: SystemActions): Value {
    val id = env.render(p.str("id", "default")).trim().ifEmpty { "default" }
    val buttons = (1..3).mapNotNull { n ->
        val label = env.render(p.str("b${n}Label")).trim()
        val flowId = p.str("b${n}FlowId")
        if (label.isEmpty() || flowId.isEmpty()) null else NotificationButton(label, flowId, p.str("b${n}FlowName"))
    }
    val progress = p.int("progress", -1).coerceIn(-1, 100)
    val request = NotificationRequest(
        id = id,
        title = env.render(p.str("title", "Androx")),
        text = env.render(p.str("text")),
        importance = p.str("importance", "default"),
        ongoing = p.bool("ongoing"),
        progress = progress,
        autoDismissSeconds = if (p.bool("autoDismiss")) p.int("dismissSeconds", 30).coerceIn(1, 86_400) else 0,
        buttons = buttons,
    )
    s.postNotification(request)
    env.detail("posted \"$id\"" + if (buttons.isNotEmpty()) " with ${buttons.size} button${if (buttons.size == 1) "" else "s"}" else "")
    return Value.Text(id)
}
