package com.androx.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Accent palettes used when Material You colours are off (or unavailable, before Android 12). */
enum class Accent(val label: String, val hue: Float) {
    ANDROX("Androx", 0f),
    GREEN("Android green", 146f),
    BLUE("Ocean", 215f),
    VIOLET("Violet", 265f),
    ORANGE("Sunset", 28f),
    ROSE("Rose", 342f),
}

enum class AutomationSort(val label: String) {
    EDITED("Recently edited"),
    NAME("Name"),
    LAST_RUN("Last run"),
    TRIGGER("Trigger type"),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You colours from the wallpaper (Android 12+). */
    val dynamicColor: Boolean = true,
    /** Palette used when [dynamicColor] is off or unavailable. */
    val accent: Accent = Accent.ANDROX,
    /** True-black dark theme. */
    val pureBlack: Boolean = false,
    val haptics: Boolean = true,
    val sort: AutomationSort = AutomationSort.EDITED,
    val historyLimit: Int = 100,
    /** When the History tab was last opened; failures newer than this show as a badge. */
    val historySeenAt: Long = 0L,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("androx_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, default.name) ?: default.name) }.getOrDefault(default)

    private fun read(): AppSettings = AppSettings(
        themeMode = enumPref("theme", ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean("dynamic", true),
        accent = enumPref("accent", Accent.ANDROX),
        pureBlack = prefs.getBoolean("pure_black", false),
        haptics = prefs.getBoolean("haptics", true),
        sort = enumPref("sort", AutomationSort.EDITED),
        historyLimit = prefs.getInt("history_limit", 100),
        historySeenAt = prefs.getLong("history_seen_at", 0L),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putString("theme", next.themeMode.name)
            .putBoolean("dynamic", next.dynamicColor)
            .putString("accent", next.accent.name)
            .putBoolean("pure_black", next.pureBlack)
            .putBoolean("haptics", next.haptics)
            .putString("sort", next.sort.name)
            .putInt("history_limit", next.historyLimit)
            .putLong("history_seen_at", next.historySeenAt)
            .apply()
    }
}
