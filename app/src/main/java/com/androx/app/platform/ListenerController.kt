package com.androx.app.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.androx.app.core.FlowsFile
import com.androx.app.core.hub.ListenerNeed
import com.androx.app.core.hub.ListenerNeeds
import com.androx.app.data.SettingsRepository

/**
 * Starts and stops the Listener (spec §5.6): on only when the person has allowed it AND an enabled Flow has a trigger
 * that can't work without it; off as soon as neither is true. [needs] and [lastError] are what Settings and the Health
 * card show ("2 Flows use the Listener", "Android wouldn't start it").
 */
class ListenerController(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    @Volatile
    var needs: List<ListenerNeed> = emptyList()
        private set

    /** The event types the running service has to listen for. */
    @Volatile
    var neededTypes: Set<String> = emptySet()
        private set

    @Volatile
    var lastError: String? = null
        private set

    val allowed: Boolean get() = settings.settings.value.listenerEnabled

    fun sync(file: FlowsFile) {
        needs = ListenerNeeds.needed(file)
        neededTypes = ListenerNeeds.eventTypes(file)
        apply()
    }

    /** Call after either input changes. */
    fun apply() {
        val service = Intent(context, ListenerService::class.java)
        if (neededTypes.isNotEmpty() && allowed) {
            try {
                ContextCompat.startForegroundService(context, service)
                lastError = null
            } catch (e: Exception) {
                // Android 12+ refuses to start a foreground service from the background in some situations.
                lastError = "Android wouldn't start the Listener right now: ${e.message ?: e.javaClass.simpleName}"
            }
        } else {
            context.stopService(service)
            lastError = null
        }
    }
}
