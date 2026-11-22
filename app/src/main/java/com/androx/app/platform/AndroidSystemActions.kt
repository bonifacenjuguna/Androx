package com.androx.app.platform

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.androx.app.MainActivity
import com.androx.app.R
import com.androx.app.core.Permissions
import com.androx.app.core.engine.BrightnessState
import com.androx.app.core.engine.NotificationRequest
import com.androx.app.core.engine.SystemActions
import com.androx.app.engine.ActionException
import com.androx.app.model.RingerMode
import com.androx.app.model.VolumeStream

/**
 * The Android side of the device and notification actions. Every failure becomes an [ActionException] with a sentence
 * a person can act on (spec §18.1); a missing special access names the exact Settings screen to open.
 */
class AndroidSystemActions(
    private val context: Context,
    private val foreground: ForegroundTracker,
) : SystemActions {

    companion object {
        const val ACTION_BUTTON = "com.androx.app.NOTIFICATION_BUTTON"
        const val EXTRA_FLOW_ID = "flowId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_BUTTON_LABEL = "buttonLabel"
        private const val CHANNEL_LOW = "androx_flow_low"
        private const val CHANNEL_DEFAULT = "androx_flow_default"
        private const val CHANNEL_HIGH = "androx_flow_high"
        private const val OPEN_TAG = "androx.open"
    }

    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notifications get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ── permissions ─────────────────────────────────────────────────────────────────────────

    override fun hasPermission(id: String): Boolean = when (id) {
        Permissions.DND_ACCESS -> notifications.isNotificationPolicyAccessGranted
        Permissions.WRITE_SETTINGS -> Settings.System.canWrite(context)
        Permissions.NOTIFICATIONS -> NotificationManagerCompat.from(context).areNotificationsEnabled()
        else -> true
    }

    /** The Settings screen where [id] can be granted, for the "Fix" button. Null when there is nothing to open. */
    fun fixIntent(id: String): Intent? = when (id) {
        Permissions.DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        Permissions.WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
        Permissions.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Every special access that is currently granted, for [com.androx.app.core.spec.Spec.status]. */
    fun granted(): Set<String> = listOf(Permissions.DND_ACCESS, Permissions.WRITE_SETTINGS, Permissions.NOTIFICATIONS).filter(::hasPermission).toSet()

    // ── ringer, Do Not Disturb, volume ──────────────────────────────────────────────────────

    override fun ringerMode(): RingerMode = when (audio.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
        else -> RingerMode.NORMAL
    }

    override fun setRinger(mode: RingerMode) {
        try {
            audio.ringerMode = when (mode) {
                RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
                RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
            }
        } catch (e: SecurityException) {
            throw ActionException(Permissions.fixText(Permissions.DND_ACCESS))
        }
    }

    override fun dndFilter(): String = when (notifications.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
        else -> "off"
    }

    override fun setDnd(filter: String) {
        if (!notifications.isNotificationPolicyAccessGranted) throw ActionException(Permissions.fixText(Permissions.DND_ACCESS))
        notifications.setInterruptionFilter(
            when (filter) {
                "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                "total_silence" -> NotificationManager.INTERRUPTION_FILTER_NONE
                else -> NotificationManager.INTERRUPTION_FILTER_ALL
            },
        )
    }

    private fun streamId(stream: VolumeStream): Int = when (stream) {
        VolumeStream.MEDIA -> AudioManager.STREAM_MUSIC
        VolumeStream.RING -> AudioManager.STREAM_RING
        VolumeStream.ALARM -> AudioManager.STREAM_ALARM
        VolumeStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
    }

    override fun volumePercent(stream: VolumeStream): Int {
        val id = streamId(stream)
        val max = audio.getStreamMaxVolume(id)
        return if (max <= 0) 0 else Math.round(audio.getStreamVolume(id) * 100.0 / max).toInt()
    }

    override fun setVolumePercent(stream: VolumeStream, percent: Int) {
        val id = streamId(stream)
        val max = audio.getStreamMaxVolume(id)
        try {
            audio.setStreamVolume(id, Math.round(max * percent.coerceIn(0, 100) / 100.0).toInt(), 0)
        } catch (e: SecurityException) {
            throw ActionException(Permissions.fixText(Permissions.DND_ACCESS))
        }
    }

    override fun adjustVolume(stream: VolumeStream, action: String) {
        val direction = when (action) {
            "up" -> AudioManager.ADJUST_RAISE
            "mute" -> AudioManager.ADJUST_MUTE
            "unmute" -> AudioManager.ADJUST_UNMUTE
            else -> AudioManager.ADJUST_LOWER
        }
        try {
            audio.adjustStreamVolume(streamId(stream), direction, 0)
        } catch (e: SecurityException) {
            throw ActionException(Permissions.fixText(Permissions.DND_ACCESS))
        }
    }

    // ── screen settings ─────────────────────────────────────────────────────────────────────

    private fun requireWrite() {
        if (!Settings.System.canWrite(context)) throw ActionException(Permissions.fixText(Permissions.WRITE_SETTINGS))
    }

    override fun brightness(): BrightnessState {
        val resolver = context.contentResolver
        val raw = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val auto = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        return BrightnessState(Math.round(raw * 100.0 / 255).toInt().coerceIn(0, 100), auto)
    }

    override fun setBrightness(percent: Int, auto: Boolean) {
        requireWrite()
        val resolver = context.contentResolver
        Settings.System.putInt(
            resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        if (!auto) {
            // 0 can switch some screens off completely, so the lowest fixed level is 1.
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, Math.round(percent.coerceIn(0, 100) * 255 / 100.0).toInt().coerceIn(1, 255))
        }
    }

    override fun screenTimeoutSeconds(): Int = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000) / 1_000

    override fun setScreenTimeoutSeconds(seconds: Int) {
        requireWrite()
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds.coerceIn(15, 1_800) * 1_000)
    }

    override fun autoRotate(): Boolean = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    override fun setAutoRotate(on: Boolean) {
        requireWrite()
        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
    }

    @SuppressLint("WakelockTimeout") // acquired with a timeout below; the "Keep phone awake" action caps it at 10 minutes
    override fun keepAwake(minutes: Int): AutoCloseable {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "androx:flow")
        lock.acquire(minutes.coerceIn(1, 10) * 60_000L)
        return AutoCloseable { if (lock.isHeld) lock.release() }
    }

    override fun mediaKey(key: String) {
        val code = when (key) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ── apps and links ──────────────────────────────────────────────────────────────────────

    override fun openUrl(url: String): String =
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "open $url")

    override fun launchApp(packageName: String): String {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw ActionException("No app with the package name \"$packageName\" was found on this phone.")
        return launch(intent, "open $packageName")
    }

    override fun shareText(text: String, subject: String): String {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (subject.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, subject)
        return launch(Intent.createChooser(send, null), "share this text")
    }

    override fun setAlarm(hour: Int, minute: Int, label: String, skipUi: Boolean): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
        if (label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return launch(intent, "set the alarm")
    }

    override fun setTimer(seconds: Int, label: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return launch(intent, "start the timer")
    }

    /**
     * Android blocks apps in the background from opening screens, so when no Androx screen is showing this posts a
     * notification instead: tapping a notification is always allowed.
     */
    private fun launch(intent: Intent, what: String): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (foreground.isForeground) {
            try {
                context.startActivity(intent)
                return "done: $what"
            } catch (e: ActivityNotFoundException) {
                throw ActionException("No app on this phone can $what.")
            } catch (e: SecurityException) {
                throw ActionException("Android wouldn't let Androx $what.")
            }
        }
        val tap = PendingIntent.getActivity(context, what.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notify(OPEN_TAG, "Tap to $what", "Androx is running in the background, so Android needs a tap from you.", CHANNEL_DEFAULT) { it.setContentIntent(tap) }
        return "Androx is in the background, so it posted a notification: tap it to $what"
    }

    // ── notifications ───────────────────────────────────────────────────────────────────────

    private fun ensureChannels() {
        val manager = NotificationManagerCompat.from(context)
        fun channel(id: String, name: String, importance: Int) {
            if (manager.getNotificationChannel(id) == null) manager.createNotificationChannel(NotificationChannel(id, name, importance))
        }
        channel(CHANNEL_LOW, "Flow notifications (quiet)", NotificationManager.IMPORTANCE_LOW)
        channel(CHANNEL_DEFAULT, "Flow notifications", NotificationManager.IMPORTANCE_DEFAULT)
        channel(CHANNEL_HIGH, "Flow notifications (pop up)", NotificationManager.IMPORTANCE_HIGH)
    }

    @Suppress("MissingPermission") // verified with areNotificationsEnabled() just before posting
    private fun notify(tag: String, title: String, text: String, channelId: String, customize: (NotificationCompat.Builder) -> Unit) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) throw ActionException(Permissions.fixText(Permissions.NOTIFICATIONS))
        ensureChannels()
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_androx)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        customize(builder)
        manager.notify(tag, 0, builder.build())
    }

    override fun postNotification(request: NotificationRequest) {
        val channel = when (request.importance) {
            "low" -> CHANNEL_LOW
            "high" -> CHANNEL_HIGH
            else -> CHANNEL_DEFAULT
        }
        val open = PendingIntent.getActivity(
            context, request.id.hashCode(), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notify(request.id, request.title, request.text, channel) { b ->
            b.setContentIntent(open)
            b.setOngoing(request.ongoing)
            if (request.ongoing) b.setAutoCancel(false)
            if (request.progress >= 0) b.setProgress(100, request.progress, false)
            if (request.autoDismissSeconds > 0) b.setTimeoutAfter(request.autoDismissSeconds * 1_000L)
            request.buttons.forEachIndexed { index, button ->
                val tap = Intent(context, NotificationActionReceiver::class.java)
                    .setAction(ACTION_BUTTON)
                    .putExtra(EXTRA_FLOW_ID, button.flowId)
                    .putExtra(EXTRA_NOTIFICATION_ID, request.id)
                    .putExtra(EXTRA_BUTTON_LABEL, button.label)
                val pending = PendingIntent.getBroadcast(
                    context, request.id.hashCode() * 31 + index, tap,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                b.addAction(0, button.label, pending)
            }
        }
    }

    override fun clearNotification(id: String) {
        NotificationManagerCompat.from(context).cancel(id, 0)
    }
}
