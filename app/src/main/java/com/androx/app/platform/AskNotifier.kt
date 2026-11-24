package com.androx.app.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.androx.app.AndroxApp
import com.androx.app.R
import com.androx.app.core.net.Domains

/**
 * The "Ask" mode of the internet switch (spec §14.1). The run that wanted a new domain is refused, and this notification
 * lets the person decide once: Always allow, or Block. Nothing is ever sent before they answer.
 */
class AskNotifier(private val context: Context) {
    companion object {
        const val ACTION_ANSWER = "com.androx.app.INTERNET_ANSWER"
        const val EXTRA_HOST = "host"
        const val EXTRA_ALLOW = "allow"
        private const val CHANNEL = "androx_internet_ask"
    }

    @Suppress("MissingPermission") // checked with areNotificationsEnabled() right below
    fun ask(host: String, flowName: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Internet permission questions", NotificationManager.IMPORTANCE_HIGH))
        }
        fun answer(allow: Boolean): PendingIntent = PendingIntent.getBroadcast(
            context, (host + allow).hashCode(),
            Intent(context, InternetAnswerReceiver::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_HOST, host).putExtra(EXTRA_ALLOW, allow),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val who = flowName.ifBlank { "A Flow" }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_androx)
            .setContentTitle("Allow $host?")
            .setContentText("$who wants to contact $host. Nothing was sent.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$who wants to contact $host. Nothing was sent. If you allow it, this and later Flows can use it; run the Flow again afterwards."))
            .addAction(0, "Always allow", answer(true))
            .addAction(0, "Block", answer(false))
            .setAutoCancel(true)
            .build()
        manager.notify("ask:$host", 0, notification)
    }
}

/** Applies the answer from the notification above to the internet rules. Not exported: only Androx's own notification reaches it. */
class InternetAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = Domains.normalize(intent.getStringExtra(AskNotifier.EXTRA_HOST).orEmpty())
        if (host.isEmpty()) return
        val allow = intent.getBooleanExtra(AskNotifier.EXTRA_ALLOW, false)
        val container = (context.applicationContext as AndroxApp).container
        container.internetSettings.update { s ->
            if (allow) s.copy(allowedDomains = (s.allowedDomains + host).distinct(), blockedDomains = s.blockedDomains - host)
            else s.copy(blockedDomains = (s.blockedDomains + host).distinct(), allowedDomains = s.allowedDomains - host)
        }
        NotificationManagerCompat.from(context).cancel("ask:$host", 0)
    }
}
