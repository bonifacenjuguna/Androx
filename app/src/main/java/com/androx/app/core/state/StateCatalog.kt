package com.androx.app.core.state

import com.androx.app.core.Value
import com.androx.app.core.spec.Choice
import com.androx.app.core.spec.SpecCategory
import com.androx.app.engine.DeviceBridge

/** Every built-in state provider. Adding one here gives it a constraint, a `{{value}}` and a preview automatically. */
object StateCatalog {
    private fun yesNo(
        id: String, name: String, description: String, category: SpecCategory, icon: String,
        yes: String = "on", no: String = "off", synonyms: List<String> = emptyList(), minSdk: Int = 26,
    ) = StateProvider(id, name, description, category, StateType.BOOLEAN, icon, yes = yes, no = no, synonyms = synonyms, minSdk = minSdk)

    private fun number(
        id: String, name: String, description: String, category: SpecCategory, icon: String, unit: String,
        synonyms: List<String> = emptyList(),
    ) = StateProvider(id, name, description, category, StateType.NUMBER, icon, unit = unit, synonyms = synonyms)

    private fun choice(
        id: String, name: String, description: String, category: SpecCategory, icon: String, choices: List<Choice>,
        synonyms: List<String> = emptyList(),
    ) = StateProvider(id, name, description, category, StateType.CHOICE, icon, choices = choices, synonyms = synonyms)

    val providers: List<StateProvider> = listOf(
        // Battery and power
        number("battery.level", "Battery level", "The battery percentage.", SpecCategory.BATTERY, "battery", "%", listOf("charge", "percent", "low battery")),
        yesNo("battery.charging", "Charging", "Whether the phone is plugged in and charging.", SpecCategory.BATTERY, "battery_charging", "charging", "not charging", listOf("plugged in", "charger")),
        choice(
            "battery.charger", "Charger type", "What the phone is charging from.", SpecCategory.BATTERY, "power_plug",
            listOf(Choice("none", "Not charging"), Choice("ac", "Wall charger"), Choice("usb", "USB"), Choice("wireless", "Wireless")),
            listOf("wireless charging", "usb"),
        ),
        number("battery.temp", "Battery temperature", "How warm the battery is.", SpecCategory.BATTERY, "thermometer", "°C", listOf("hot", "overheating", "warm")),
        yesNo("battery.saver", "Battery Saver", "Whether Battery Saver is on.", SpecCategory.BATTERY, "battery_alert_variant_outline", synonyms = listOf("power saving", "low power")),
        // Connectivity
        yesNo("network.wifi", "Wi-Fi", "Whether the phone is connected to Wi-Fi.", SpecCategory.CONNECTIVITY, "wifi", "connected", "not connected", listOf("wireless", "network")),
        choice(
            "network.type", "Network type", "What the phone's internet connection is using.", SpecCategory.CONNECTIVITY, "signal",
            listOf(Choice("wifi", "Wi-Fi"), Choice("mobile", "Mobile data"), Choice("ethernet", "Ethernet"), Choice("vpn", "VPN"), Choice("none", "No connection")),
            listOf("internet", "cellular", "data"),
        ),
        yesNo("network.metered", "Metered connection", "Whether the connection is metered (limited data).", SpecCategory.CONNECTIVITY, "cash", "metered", "not metered", listOf("data saver", "limited")),
        yesNo("network.internet", "Internet reachable", "Whether the connection really reaches the internet.", SpecCategory.CONNECTIVITY, "web", "reachable", "not reachable", listOf("online", "offline", "connected to internet")),
        StateProvider("network.ip", "IP address", "The phone's address on the current network. Handy as an \"am I at home?\" check.", SpecCategory.CONNECTIVITY, StateType.TEXT, "ip", synonyms = listOf("at home", "local address", "lan", "wifi address")),
        yesNo("network.vpn", "VPN", "Whether a VPN is connected.", SpecCategory.CONNECTIVITY, "vpn", "connected", "not connected"),
        yesNo("bluetooth.on", "Bluetooth", "Whether Bluetooth is turned on.", SpecCategory.CONNECTIVITY, "bluetooth", synonyms = listOf("bt")),
        yesNo("nfc.on", "NFC", "Whether NFC is turned on.", SpecCategory.CONNECTIVITY, "nfc"),
        yesNo("location.on", "Location", "Whether location services are turned on.", SpecCategory.CONNECTIVITY, "map_marker", synonyms = listOf("gps")),
        // Device
        yesNo("device.airplane", "Airplane mode", "Whether airplane mode is on.", SpecCategory.DEVICE, "airplane", synonyms = listOf("flight")),
        yesNo("device.screen", "Screen", "Whether the screen is on.", SpecCategory.DEVICE, "cellphone", synonyms = listOf("display", "awake")),
        yesNo("device.locked", "Device locked", "Whether the phone is locked.", SpecCategory.DEVICE, "lock", "locked", "unlocked", listOf("unlock", "keyguard")),
        yesNo("device.dark", "Dark theme", "Whether the phone is using the dark theme.", SpecCategory.DEVICE, "theme_light_dark", synonyms = listOf("night mode")),
        yesNo("device.autorotate", "Auto-rotate", "Whether the screen rotates automatically.", SpecCategory.DEVICE, "screen_rotation", synonyms = listOf("rotation", "orientation")),
        yesNo("device.headphones", "Headphones", "Whether headphones or a headset are connected.", SpecCategory.DEVICE, "headphones", "connected", "not connected", listOf("headset", "earbuds", "earphones")),
        number("device.storage_free", "Free storage", "How much storage is free.", SpecCategory.DEVICE, "harddisk", "MB", listOf("space", "memory")),
        number("device.uptime", "Time since boot", "How long the phone has been on since it last started.", SpecCategory.DATE_TIME, "timer_outline", "min", listOf("uptime", "restart")),
        // Sound
        choice(
            "device.ringer", "Ringer mode", "Whether the phone rings, vibrates or is silent.", SpecCategory.SOUND, "volume_medium",
            listOf(Choice("normal", "Normal"), Choice("vibrate", "Vibrate"), Choice("silent", "Silent")),
            listOf("silent", "vibrate", "mute"),
        ),
        choice(
            "device.dnd", "Do Not Disturb", "The Do Not Disturb setting.", SpecCategory.SOUND, "minus_circle",
            listOf(Choice("off", "Off"), Choice("priority", "Priority only"), Choice("alarms", "Alarms only"), Choice("total_silence", "Total silence")),
            listOf("dnd", "quiet", "focus"),
        ),
        yesNo("audio.playing", "Media playing", "Whether music or other media is playing.", SpecCategory.SOUND, "music", "playing", "not playing", listOf("music", "audio", "sound")),
        yesNo("audio.speaker", "Speakerphone", "Whether the speakerphone is on.", SpecCategory.SOUND, "phone_in_talk"),
        number("volume.media", "Media volume", "The media volume level.", SpecCategory.SOUND, "volume_high", "%", listOf("loudness")),
        number("volume.ring", "Ring volume", "The ringtone volume level.", SpecCategory.SOUND, "volume_medium", "%"),
        number("volume.alarm", "Alarm volume", "The alarm volume level.", SpecCategory.SOUND, "alarm", "%"),
    )

    private val byId = providers.associateBy { it.id }

    operator fun get(id: String): StateProvider? = byId[id]

    /** Providers a `{{name}}` can refer to. */
    fun ids(): Set<String> = byId.keys
}

/**
 * Reads what a plain [DeviceBridge] already knows, so the engine works on the JVM and in tests with no Android reader.
 * The Android app puts a richer reader in front of this one.
 */
class LegacyStateReader(private val device: DeviceBridge) : DeviceStateReader {
    override fun read(id: String): Value? = when (id) {
        "battery.level" -> Value.Num(device.batteryLevel().toDouble())
        "battery.charging" -> Value.Bool(device.isCharging())
        "battery.saver" -> Value.Bool(device.isPowerSaver())
        "network.wifi" -> Value.Bool(device.isWifiConnected())
        "device.screen" -> Value.Bool(device.isScreenOn())
        "device.airplane" -> Value.Bool(device.isAirplaneMode())
        "device.ringer" -> Value.Text(device.ringerMode().name.lowercase())
        else -> null
    }
}
