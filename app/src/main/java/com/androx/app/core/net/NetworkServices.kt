package com.androx.app.core.net

/** What the engine is given for internet actions: the gate every request goes through, and read-only access to secrets. */
class NetworkServices(val gate: NetworkGate, val secrets: SecretReader)

/** Open-Meteo "weather_code" in words (spec §14.6, no API key needed). */
object WeatherCodes {
    fun describe(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }
}
