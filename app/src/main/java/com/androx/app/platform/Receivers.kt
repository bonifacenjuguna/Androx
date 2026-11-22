package com.androx.app.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.androx.app.AndroxApp
import com.androx.app.core.Event
import com.androx.app.core.Value
import kotlinx.coroutines.launch

/** Fires when a scheduled automation is due. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: return
        val container = (context.applicationContext as AndroxApp).container
        val pending = goAsync()
        container.scope.launch {
            try {
                container.runner.runScheduled(id)
            } finally {
                // Arm the next occurrence whether or not the run worked.
                container.automations.awaitLoaded()
                container.scheduler.reschedule(container.automations.state.value)
                pending.finish()
            }
        }
    }
}

/** Alarms are cleared by reboots, app updates and clock changes, so schedules are re-armed here. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as AndroxApp).container
        val pending = goAsync()
        val isBoot = intent.action == Intent.ACTION_BOOT_COMPLETED
        container.scope.launch {
            try {
                container.automations.awaitLoaded()
                container.scheduler.reschedule(container.automations.state.value)
                if (isBoot) container.runner.runOnBoot()
            } finally {
                pending.finish()
            }
        }
    }
}

/** Runs the Flow behind a button on a notification that a "Notification" step posted. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val flowId = intent.getStringExtra(AndroidSystemActions.EXTRA_FLOW_ID) ?: return
        val notification = intent.getStringExtra(AndroidSystemActions.EXTRA_NOTIFICATION_ID).orEmpty()
        val button = intent.getStringExtra(AndroidSystemActions.EXTRA_BUTTON_LABEL).orEmpty()
        val container = (context.applicationContext as AndroxApp).container
        val pending = goAsync()
        container.scope.launch {
            try {
                val event = Event(
                    typeId = "notification.button",
                    values = mapOf(
                        "notification.id" to Value.Text(notification),
                        "notification.button" to Value.Text(button),
                    ),
                    at = System.currentTimeMillis(),
                )
                container.runner.runForEvent(flowId, event)
            } finally {
                pending.finish()
            }
        }
    }
}
