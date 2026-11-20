package com.androx.app.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.androx.app.MainActivity
import com.androx.app.R
import com.androx.app.engine.ActionException
import com.androx.app.engine.DeviceBridge
import com.androx.app.model.FlashlightMode
import com.androx.app.model.RingerMode
import com.androx.app.model.VolumeStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class AndroidDeviceBridge(private val context: Context) : DeviceBridge {
    companion object {
        const val CHANNEL_ID = "automations"
    }

    private val counter = AtomicInteger(1000)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val torchOn = AtomicBoolean(false)
    private val torchWatching = AtomicBoolean(false)

    /** Runs [block] on the main thread and waits for it, so errors can still fail the run. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = arrayOfNulls<Any?>(1)
        val error = arrayOfNulls<Throwable>(1)
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result[0] = block()
            } catch (t: Throwable) {
                error[0] = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(3, TimeUnit.SECONDS)) throw ActionException("Android didn't respond in time.")
        error[0]?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result[0] as T
    }

    // ------------------------------------------------------------ state

    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    override fun batteryLevel(): Int {
        val intent = batteryIntent()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) throw ActionException("Android did not report a battery level.")
        return (level * 100f / scale).roundToInt()
    }

    override fun isCharging(): Boolean {
        val intent = batteryIntent() ?: throw ActionException("Android did not report the charging state.")
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun isWifiConnected(): Boolean {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: SecurityException) {
            false
        }
    }

    override fun isScreenOn(): Boolean = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    override fun ringerMode(): RingerMode {
        val audio = context.getSystemService(AudioManager::class.java) ?: return RingerMode.NORMAL
        return when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
            else -> RingerMode.NORMAL
        }
    }

    override fun isPowerSaver(): Boolean = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false

    override fun isAirplaneMode(): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    // ------------------------------------------------------------ actions

    @Suppress("MissingPermission") // permission state is verified with areNotificationsEnabled() below
    override fun showNotification(title: String, message: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            throw ActionException("Notifications are blocked for Androx. Allow them in Settings → Permissions.")
        }
        ensureChannel()
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
            throw ActionException("The \"Automation notifications\" channel is turned off in Android settings.")
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_androx)
            .setContentTitle(title.ifBlank { "Androx" })
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(counter.incrementAndGet(), notification)
        } catch (e: SecurityException) {
            throw ActionException("Android blocked the notification: ${e.message}")
        }
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notification_channel_description) }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    override fun vibrate(durationMs: Int) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) throw ActionException("This device can't vibrate.")
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun showToast(message: String) {
        if (message.isBlank()) throw ActionException("The toast message is empty.")
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    override fun copyToClipboard(text: String) {
        onMain {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
                ?: throw ActionException("The clipboard isn't available.")
            clipboard.setPrimaryClip(ClipData.newPlainText("Androx", text))
        }
    }

    override fun speak(text: String) {
        if (text.isBlank()) throw ActionException("There is nothing to say.")
        onMain {
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { status ->
                val tts = engine
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            mainHandler.post { tts.shutdown() }
                        }

                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onError(utteranceId: String?) {
                            mainHandler.post { tts.shutdown() }
                        }
                    })
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "androx-speak")
                } else {
                    tts?.shutdown()
                }
            }
        }
    }

    override fun setFlashlight(mode: FlashlightMode): Boolean {
        val cameras = context.getSystemService(CameraManager::class.java)
            ?: throw ActionException("The camera service isn't available.")
        try {
            val id = cameras.cameraIdList.firstOrNull {
                cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: throw ActionException("This device has no flashlight.")
            if (torchWatching.compareAndSet(false, true)) {
                cameras.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        torchOn.set(enabled)
                    }
                }, mainHandler)
            }
            val target = when (mode) {
                FlashlightMode.ON -> true
                FlashlightMode.OFF -> false
                FlashlightMode.TOGGLE -> !torchOn.get()
            }
            cameras.setTorchMode(id, target)
            torchOn.set(target)
            return target
        } catch (e: CameraAccessException) {
            throw ActionException("Android wouldn't change the flashlight: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw ActionException("Android wouldn't change the flashlight: ${e.message}")
        }
    }

    override fun setVolume(stream: VolumeStream, percent: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: throw ActionException("The audio service isn't available.")
        val type = when (stream) {
            VolumeStream.MEDIA -> AudioManager.STREAM_MUSIC
            VolumeStream.RING -> AudioManager.STREAM_RING
            VolumeStream.ALARM -> AudioManager.STREAM_ALARM
            VolumeStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        }
        val max = audio.getStreamMaxVolume(type)
        val index = (max * percent / 100f).roundToInt().coerceIn(0, max)
        try {
            audio.setStreamVolume(type, index, 0)
        } catch (e: SecurityException) {
            throw ActionException("Android blocked this volume change. Do Not Disturb may be on.")
        }
    }

    override fun playNotificationSound() {
        onMain {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, uri)
                ?: throw ActionException("No notification sound is set on this device.")
            ringtone.play()
        }
    }
}
