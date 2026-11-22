package com.androx.app.platform

import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.nfc.NfcManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import com.androx.app.core.Value
import com.androx.app.core.state.DeviceStateReader

/**
 * Reads the live value of every state provider that needs the Android APIs. Each read is isolated: a missing service,
 * missing hardware or a denied permission gives null ("can't be read right now"), never a crash.
 * Values the plain device bridge already knows (battery level, Wi-Fi, screen…) come from the next reader in line.
 */
class AndroidStateReader(private val context: Context) : DeviceStateReader {

    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val connectivity get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun read(id: String): Value? = try {
        readOrThrow(id)
    } catch (e: Exception) {
        null
    }

    private fun readOrThrow(id: String): Value? = when (id) {
        "battery.temp" -> battery()?.let { Value.Num(it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).let { t -> if (t == Int.MIN_VALUE) return null else t / 10.0 }) }
        "battery.charger" -> battery()?.let { Value.Text(chargerName(it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))) }
        "network.type" -> Value.Text(networkType())
        "network.metered" -> connectivity?.let { Value.Bool(it.isActiveNetworkMetered) }
        "network.vpn" -> Value.Bool(hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        "bluetooth.on" -> (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.let { Value.Bool(it.isEnabled) }
        "nfc.on" -> (context.getSystemService(Context.NFC_SERVICE) as? NfcManager)?.defaultAdapter?.let { Value.Bool(it.isEnabled) }
        "location.on" -> locationOn()?.let { Value.Bool(it) }
        "device.locked" -> (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.let { Value.Bool(it.isDeviceLocked) }
        "device.dark" -> Value.Bool(
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
        )
        "device.autorotate" -> Value.Bool(Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1)
        "device.headphones" -> audio?.let { Value.Bool(headphonesConnected(it)) }
        "device.storage_free" -> Value.Num((StatFs(Environment.getDataDirectory().path).availableBytes / 1_048_576L).toDouble())
        "device.uptime" -> Value.Num((SystemClock.elapsedRealtime() / 60_000L).toDouble())
        "device.dnd" -> (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.let { Value.Text(dndName(it.currentInterruptionFilter)) }
        "audio.playing" -> audio?.let { Value.Bool(it.isMusicActive) }
        "audio.speaker" -> audio?.let { @Suppress("DEPRECATION") Value.Bool(it.isSpeakerphoneOn) }
        "volume.media" -> streamPercent(AudioManager.STREAM_MUSIC)
        "volume.ring" -> streamPercent(AudioManager.STREAM_RING)
        "volume.alarm" -> streamPercent(AudioManager.STREAM_ALARM)
        else -> null
    }

    private fun battery(): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun chargerName(plugged: Int): String = when {
        plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
        plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "ac"
        plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "usb"
        else -> "none"
    }

    private fun dndName(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
        else -> "off"
    }

    private fun hasTransport(transport: Int): Boolean {
        val cm = connectivity ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(transport)
    }

    private fun networkType(): String = when {
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
        else -> "none"
    }

    private fun locationOn(): Boolean? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    private fun headphonesConnected(am: AudioManager): Boolean {
        val wanted = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in wanted }
    }

    private fun streamPercent(stream: Int): Value? {
        val am = audio ?: return null
        val max = am.getStreamMaxVolume(stream)
        if (max <= 0) return null
        return Value.Num(Math.round(am.getStreamVolume(stream) * 100.0 / max).toDouble())
    }
}
