package com.androx.app.core

/**
 * Special access some actions need, with the plain-English instruction shown when it is missing (spec §18.1: every
 * entry explains a permission failure and says exactly how to fix it). The ids match [com.androx.app.core.spec.Perm].
 */
object Permissions {
    const val NOTIFICATIONS = "notifications"
    const val EXACT_ALARMS = "exact_alarms"
    const val DND_ACCESS = "dnd_access"
    const val WRITE_SETTINGS = "write_settings"

    fun label(id: String): String = when (id) {
        NOTIFICATIONS -> "Notifications"
        EXACT_ALARMS -> "Exact alarms"
        DND_ACCESS -> "Do Not Disturb access"
        WRITE_SETTINGS -> "Modify system settings"
        else -> id
    }

    /** What to tell the person when [id] is missing. Always says where to go and what to tap. */
    fun fixText(id: String): String = when (id) {
        NOTIFICATIONS -> "Androx isn't allowed to show notifications. Open Android Settings → Apps → Androx → Notifications and turn them on."
        EXACT_ALARMS -> "Androx isn't allowed to set exact alarms. Open Android Settings → Apps → Special app access → Alarms & reminders and allow Androx."
        DND_ACCESS -> "Androx needs Do Not Disturb access. Open Android Settings → Apps → Special app access → Do Not Disturb and turn Androx on."
        WRITE_SETTINGS -> "Androx needs permission to modify system settings. Open Android Settings → Apps → Special app access → Modify system settings and allow Androx."
        else -> "Androx needs the permission \"${label(id)}\"."
    }
}
