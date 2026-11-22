package com.androx.app.core.engine

import com.androx.app.engine.ActionException
import com.androx.app.model.RingerMode
import com.androx.app.model.VolumeStream

/** A button on a Flow notification. Tapping it runs [flowId]. */
data class NotificationButton(val label: String, val flowId: String, val flowName: String = "")

/** Everything the notification builder action can set (spec §18.2). */
data class NotificationRequest(
    /** Names the notification so a later step can update or clear it. */
    val id: String,
    val title: String,
    val text: String,
    /** low, default or high. */
    val importance: String = "default",
    val ongoing: Boolean = false,
    /** 0..100 for a progress bar, -1 for none. */
    val progress: Int = -1,
    /** 0 keeps it until dismissed. */
    val autoDismissSeconds: Int = 0,
    val buttons: List<NotificationButton> = emptyList(),
)

data class BrightnessState(val percent: Int, val auto: Boolean)

private fun <T> unavailable(what: String): T = throw ActionException("$what isn't available here.")

/**
 * What the device and notification actions need from Android. The engine only ever talks to this interface, so every
 * action is unit tested on the JVM with a fake. Each method must throw [ActionException] with a plain-English reason
 * when it can't do its job (missing permission, nothing installed that can handle a link…).
 */
interface SystemActions {
    /** Whether a special access (see [com.androx.app.core.Permissions]) is currently granted. */
    fun hasPermission(id: String): Boolean = true

    fun ringerMode(): RingerMode = unavailable("Ringer mode")
    fun setRinger(mode: RingerMode): Unit = unavailable("Ringer mode")

    /** off, priority, alarms or total_silence. */
    fun dndFilter(): String = unavailable("Do Not Disturb")
    fun setDnd(filter: String): Unit = unavailable("Do Not Disturb")

    fun volumePercent(stream: VolumeStream): Int = unavailable("Volume")
    fun setVolumePercent(stream: VolumeStream, percent: Int): Unit = unavailable("Volume")

    /** [action] is up, down, mute or unmute. */
    fun adjustVolume(stream: VolumeStream, action: String): Unit = unavailable("Volume")

    fun brightness(): BrightnessState = unavailable("Brightness")
    fun setBrightness(percent: Int, auto: Boolean): Unit = unavailable("Brightness")
    fun screenTimeoutSeconds(): Int = unavailable("Screen timeout")
    fun setScreenTimeoutSeconds(seconds: Int): Unit = unavailable("Screen timeout")
    fun autoRotate(): Boolean = unavailable("Auto-rotate")
    fun setAutoRotate(on: Boolean): Unit = unavailable("Auto-rotate")

    /** Keeps the phone awake. Closing the result releases it. [minutes] is a hard upper limit. */
    fun keepAwake(minutes: Int): AutoCloseable = unavailable("Keeping the phone awake")

    /** play_pause, play, pause, next, previous or stop. */
    fun mediaKey(key: String): Unit = unavailable("Media keys")

    /** The four below return a short sentence about what happened ("opened", or "posted a notification to tap"). */
    fun openUrl(url: String): String = unavailable("Opening links")
    fun launchApp(packageName: String): String = unavailable("Opening apps")
    fun shareText(text: String, subject: String): String = unavailable("Sharing")
    fun setAlarm(hour: Int, minute: Int, label: String, skipUi: Boolean): String = unavailable("Setting alarms")
    fun setTimer(seconds: Int, label: String): String = unavailable("Setting timers")

    fun postNotification(request: NotificationRequest): Unit = unavailable("Notifications")
    fun clearNotification(id: String): Unit = unavailable("Notifications")
}
