package com.androx.app.core.hub

import com.androx.app.core.Event
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Where every event enters Androx: system broadcasts, the Listener, notification buttons, finished Flows, changed
 * variables. The [TriggerHub] is the only reader. Emitting never blocks; if events pile up faster than they can be
 * handled the oldest are dropped, so a noisy source can't stall the app.
 */
class EventBus(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val flow = MutableSharedFlow<Event>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Event> = flow.asSharedFlow()

    /** Stamps the event with the time if it has none. Always succeeds; an event nobody is collecting yet is simply dropped. */
    fun emit(event: Event): Boolean = flow.tryEmit(if (event.at == 0L) event.copy(at = clock()) else event)
}
