package com.androx.app.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.androx.app.AndroxApp
import com.androx.app.MainActivity
import com.androx.app.R
import com.androx.app.core.spec.SpecCatalog
import kotlinx.coroutines.launch

/**
 * The opt-in Listener: one foreground service with a quiet, low-importance notification that keeps Androx running so
 * live device events (screen, power, battery, network, headphones…) can be delivered. [ListenerController] starts it
 * only while it is both allowed and needed, so it stops by itself when the last Flow that needs it is turned off.
 */
class ListenerService : Service() {
    companion object {
        private const val CHANNEL = "androx_listener"
        private const val NOTIFICATION_ID = 7001
    }

    private var sources: AndroidEventSources? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as AndroxApp).container
        showNotification(container.listener.neededTypes)
        val active = sources ?: AndroidEventSources(applicationContext, container.bus).also { sources = it }
        container.scope.launch {
            // After a restart by Android the Flows may not be loaded yet, so wait before deciding what to listen for.
            container.flows.awaitLoaded()
            val types = container.listener.neededTypes
            if (types.isEmpty() || !container.listener.allowed) {
                stopSelf()
            } else {
                showNotification(types)
                active.update(types)
            }
        }
        return START_STICKY
    }

    private fun showNotification(types: Set<String>) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Listener", NotificationManager.IMPORTANCE_LOW))
        }
        val catalog = SpecCatalog.default
        val names = types.map { catalog.triggers.nameOf(it) }.sorted()
        val text = if (names.isEmpty()) "Starting…" else "Listening for: " + names.joinToString(", ")
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_androx)
            .setContentTitle("Androx Listener is on")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    override fun onDestroy() {
        sources?.stop()
        sources = null
        super.onDestroy()
    }
}
