package com.androx.app.core.net

import com.androx.app.core.Event
import com.androx.app.core.Flow
import com.androx.app.core.TriggerEntry
import com.androx.app.core.int
import com.androx.app.core.str

/** What to remember between polls, per trigger. */
interface PollStateStore {
    fun get(entryId: String): String?
    fun set(entryId: String, state: String)
}

class MemoryPollStateStore : PollStateStore {
    private val map = HashMap<String, String>()
    override fun get(entryId: String) = map[entryId]
    override fun set(entryId: String, state: String) {
        map[entryId] = state
    }
}

/** [event] is null when nothing changed (or the check couldn't be made); [note] says which, for the Trigger log. */
data class PollOutcome(val event: Event?, val note: String)

/**
 * One look at a "Web address changes" trigger: fetches the address through the [NetworkGate], decides whether anything
 * changed, and remembers where it stands. A refused or failed request is reported, never thrown, so one bad poll can't
 * stop the next one.
 */
class PollRunner(
    private val gate: NetworkGate,
    private val secrets: SecretReader,
    private val state: PollStateStore,
) {
    suspend fun check(flow: Flow, entry: TriggerEntry): PollOutcome {
        val p = entry.params
        val url = p.str("url").trim()
        if (url.isEmpty()) return PollOutcome(null, "no address set")
        val used = HashSet<String>()
        val headers = ArrayList<Pair<String, String>>()
        val secretName = p.str("authSecret").trim()
        if (secretName.isNotEmpty()) {
            val token = secrets.get(secretName) ?: return PollOutcome(null, "there is no secret named \"$secretName\" in the vault")
            used += token
            headers += "Authorization" to "Bearer $token"
        }
        val response = try {
            gate.send(HttpRequestSpec("GET", url, headers, timeoutSeconds = 30), NetContext(flow.id, flow.name, flow.internet, used))
        } catch (e: NetException) {
            return PollOutcome(null, "couldn't check: ${e.message}")
        }
        if (!response.ok) return PollOutcome(null, "the address answered ${response.status}")
        val result = try {
            Poller.evaluate(p.str("mode", "text"), p.str("jsonPath").trim(), state.get(entry.id), response.body)
        } catch (e: NetException) {
            return PollOutcome(null, "couldn't read the answer: ${e.message}")
        }
        state.set(entry.id, result.state)
        val minutes = p.int("minutes", 30)
        return if (result.changed) PollOutcome(Poller.event(result, url), "changed") else PollOutcome(null, "no change (checked every $minutes min)")
    }
}
