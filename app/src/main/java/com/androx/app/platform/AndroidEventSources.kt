package com.androx.app.platform

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.androx.app.core.Event
import com.androx.app.core.TriggerIds
import com.androx.app.core.Value
import com.androx.app.core.hub.EventBus

/**
 * Turns Android's live notifications into events on the [EventBus]. It registers receivers only for the event types
 * some enabled Flow actually uses, so the Listener costs nothing for triggers nobody asked for. Called from the
 * [ListenerService]; nothing here runs while the Listener is off.
 */
class AndroidEventSources(private val context: Context, private val bus: EventBus) {
    private var receiver: BroadcastReceiver? = null
    private var audioCallback: AudioDeviceCallback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastBattery = -1
    private var lastHeadphones: Boolean? = null
    private var lastNetwork: String? = null

    @Synchronized
    fun update(types: Set<String>) {
        stop()
        if (types.isEmpty()) return
        registerBroadcasts(types)
        if (TriggerIds.HEADPHONES in types) registerHeadphones()
        if (TriggerIds.NETWORK in types) registerNetwork()
    }

    @Synchronized
    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        audioCallback?.let { runCatching { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(it) } }
        audioCallback = null
        networkCallback?.let { runCatching { (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } }
        networkCallback = null
        lastBattery = -1
        lastHeadphones = null
        lastNetwork = null
    }

    // ── broadcasts ──────────────────────────────────────────────────────────────────────────

    private fun registerBroadcasts(types: Set<String>) {
        val filter = IntentFilter()
        if (TriggerIds.SCREEN in types) {
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (TriggerIds.POWER in types) {
            filter.addAction(Intent.ACTION_POWER_CONNECTED)
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (TriggerIds.BATTERY_LEVEL in types) filter.addAction(Intent.ACTION_BATTERY_CHANGED)
        if (TriggerIds.BATTERY_SAVER in types) filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (TriggerIds.AIRPLANE in types) filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        if (TriggerIds.RINGER in types) filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        if (TriggerIds.DND in types) filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        if (TriggerIds.UNLOCKED in types) filter.addAction(Intent.ACTION_USER_PRESENT)
        if (filter.countActions() == 0) return

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handle(intent)
            }
        }
        receiver = r
        // System broadcasts reach a receiver that isn't exported; the flag only has to be stated on Android 13+.
        val sticky = ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // The battery broadcast is sticky: remember where the level is now so the first change is a real crossing.
        if (TriggerIds.BATTERY_LEVEL in types && sticky?.action == Intent.ACTION_BATTERY_CHANGED) lastBattery = percent(sticky)
    }

    private fun percent(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> bus.emit(Event(TriggerIds.SCREEN, mapOf("screen.on" to Value.Bool(true))))
            Intent.ACTION_SCREEN_OFF -> bus.emit(Event(TriggerIds.SCREEN, mapOf("screen.on" to Value.Bool(false))))
            Intent.ACTION_POWER_CONNECTED -> bus.emit(Event(TriggerIds.POWER, mapOf("power.connected" to Value.Bool(true))))
            Intent.ACTION_POWER_DISCONNECTED -> bus.emit(Event(TriggerIds.POWER, mapOf("power.connected" to Value.Bool(false))))
            Intent.ACTION_BATTERY_CHANGED -> {
                val now = percent(intent)
                val before = lastBattery
                if (now >= 0 && now != before) {
                    lastBattery = now
                    if (before >= 0) {
                        bus.emit(Event(TriggerIds.BATTERY_LEVEL, mapOf("battery.level" to Value.Num(now.toDouble()), "battery.previous" to Value.Num(before.toDouble()))))
                    }
                }
            }
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                val on = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
                bus.emit(Event(TriggerIds.BATTERY_SAVER, mapOf("battery.saver" to Value.Bool(on))))
            }
            Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                bus.emit(Event(TriggerIds.AIRPLANE, mapOf("device.airplane" to Value.Bool(intent.getBooleanExtra("state", false)))))
            AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                val mode = when ((context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "normal"
                }
                bus.emit(Event(TriggerIds.RINGER, mapOf("ringer.mode" to Value.Text(mode))))
            }
            NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                val filter = when ((context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter) {
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                    NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
                    else -> "off"
                }
                bus.emit(Event(TriggerIds.DND, mapOf("dnd.filter" to Value.Text(filter))))
            }
            Intent.ACTION_USER_PRESENT -> bus.emit(Event(TriggerIds.UNLOCKED))
        }
    }

    // ── headphones ──────────────────────────────────────────────────────────────────────────

    private val headphoneTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )

    private fun headphonesConnected(audio: AudioManager): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headphoneTypes }

    private fun registerHeadphones() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        lastHeadphones = headphonesConnected(audio)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()

            private fun refresh() {
                val now = headphonesConnected(audio)
                if (now != lastHeadphones) {
                    lastHeadphones = now
                    bus.emit(Event(TriggerIds.HEADPHONES, mapOf("headphones.connected" to Value.Bool(now))))
                }
            }
        }
        audioCallback = callback
        audio.registerAudioDeviceCallback(callback, null)
    }

    // ── network ─────────────────────────────────────────────────────────────────────────────

    private fun typeOf(caps: NetworkCapabilities?): String = when {
        caps == null -> "other"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
        else -> "other"
    }

    private fun emitNetwork(change: String, type: String) {
        bus.emit(
            Event(
                TriggerIds.NETWORK,
                mapOf("network.change" to Value.Text(change), "network.type" to Value.Text(type), "network.connected" to Value.Bool(change != "lost")),
            ),
        )
    }

    private fun registerNetwork() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        lastNetwork = cm.getNetworkCapabilities(cm.activeNetwork)?.let { typeOf(it) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val type = typeOf(capabilities)
                val before = lastNetwork
                if (type == before) return
                lastNetwork = type
                emitNetwork(if (before == null) "connected" else "changed", type)
            }

            override fun onLost(network: Network) {
                val before = lastNetwork ?: return
                lastNetwork = null
                emitNetwork("lost", before)
            }
        }
        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }
}
