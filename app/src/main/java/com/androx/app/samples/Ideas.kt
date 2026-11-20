package com.androx.app.samples

import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.Comparison
import com.androx.app.model.Condition
import com.androx.app.model.EditorKind
import com.androx.app.model.FlashlightMode
import com.androx.app.model.Trigger
import com.androx.app.model.VariableMode
import com.androx.app.model.VolumeStream
import java.util.UUID

/** A ready-made automation shown in the "Ideas" gallery. */
class Idea(
    val id: String,
    val title: String,
    val description: String,
    /** Key from the automation icon picker. */
    val icon: String,
    val build: (now: Long) -> Automation,
)

private fun make(
    now: Long,
    name: String,
    icon: String,
    trigger: Trigger,
    condition: Condition? = null,
    vararg actions: Action,
) = Automation(
    id = UUID.randomUUID().toString(),
    name = name,
    enabled = true,
    trigger = trigger,
    condition = condition,
    actions = actions.toList(),
    editor = EditorKind.BUILDER,
    icon = icon,
    createdAt = now,
    updatedAt = now,
)

object Ideas {
    val all: List<Idea> = listOf(
        Idea("morning", "Good morning", "A weekday nudge at 07:30 with the day and your battery.", "sun") { now ->
            make(
                now, "Good morning", "sun", Trigger.Schedule(7, 30, listOf(1, 2, 3, 4, 5)), null,
                Action.ShowNotification("Good morning", "It's {{weekday}}. Battery is {{battery}}%."),
                Action.Vibrate(250),
            )
        },
        Idea("low-battery", "Low battery alert", "Every 15 minutes: warn me under 20% when I'm not charging.", "battery") { now ->
            make(
                now, "Low battery alert", "battery", Trigger.Interval(15, now),
                Condition.MatchAll(listOf(Condition.BatteryLevel(Comparison.LT, 20), Condition.Charging(false))),
                Action.ShowNotification("Battery is {{battery}}%", "Plug in soon."),
                Action.Vibrate(500),
            )
        },
        Idea("hydration", "Hydration reminder", "Every 90 minutes between 09:00 and 21:00, with a running count.", "coffee") { now ->
            make(
                now, "Hydration reminder", "coffee", Trigger.Interval(90, now),
                Condition.TimeWindow(9, 0, 21, 0),
                Action.SetVariable("water", "1", VariableMode.ADD),
                Action.ShowNotification("Time for water", "That's glass number {{water}} today."),
                Action.Vibrate(300),
            )
        },
        Idea("daily-reset", "Daily reset", "Clears the hydration counter at midnight.", "cog") { now ->
            make(
                now, "Daily reset", "cog", Trigger.Schedule(0, 0), null,
                Action.SetVariable("water", "0", VariableMode.SET),
                Action.WriteLog("Counters reset on {{date}}"),
            )
        },
        Idea("wind-down", "Bedtime wind-down", "At 22:30, lower the media volume and say goodnight.", "moon") { now ->
            make(
                now, "Bedtime wind-down", "moon", Trigger.Schedule(22, 30), null,
                Action.SetVolume(VolumeStream.MEDIA, 15),
                Action.ShowNotification("Wind down", "Media volume lowered. Sleep well."),
            )
        },
        Idea("plug-in", "Plug in tonight", "At 23:00, remind me if the phone isn't charging.", "bolt") { now ->
            make(
                now, "Plug in tonight", "bolt", Trigger.Schedule(23, 0),
                Condition.Charging(false),
                Action.ShowNotification("Plug in your phone", "Battery is {{battery}}%."),
            )
        },
        Idea("restart", "Restart greeting", "Logs and notifies every time the phone starts.", "rocket") { now ->
            make(
                now, "Restart greeting", "rocket", Trigger.DeviceBoot, null,
                Action.WriteLog("Started at {{time}}"),
                Action.ShowNotification("Phone restarted", "Back up and running at {{time}}."),
            )
        },
        Idea("flash", "Flash three times", "Blinks the flashlight. Shows off Repeat and Wait.", "flash") { now ->
            make(
                now, "Flash three times", "flash", Trigger.Manual, null,
                Action.Repeat(
                    3,
                    listOf(
                        Action.Flashlight(FlashlightMode.ON),
                        Action.Wait(1),
                        Action.Flashlight(FlashlightMode.OFF),
                        Action.Wait(1),
                    ),
                ),
            )
        },
        Idea("stamp", "Copy a timestamp", "Puts the current date and time on the clipboard.", "star") { now ->
            make(
                now, "Copy a timestamp", "star", Trigger.Manual, null,
                Action.CopyToClipboard("{{datetime}}"),
                Action.ShowToast("Copied {{datetime}}"),
            )
        },
        Idea("quiet-hours", "Quiet hours check", "Shows different messages by time of day. Shows off If / else.", "sleep") { now ->
            make(
                now, "Quiet hours check", "sleep", Trigger.Manual, null,
                Action.IfThen(
                    condition = Condition.TimeWindow(22, 0, 7, 0),
                    then = listOf(Action.ShowToast("Quiet hours. Keep it down.")),
                    otherwise = listOf(Action.ShowToast("It's daytime. Make some noise.")),
                ),
            )
        },
    )
}
