package com.androx.app.core.net

import com.androx.app.core.Event
import com.androx.app.core.TriggerIds
import com.androx.app.core.Value
import com.androx.app.core.path
import java.security.MessageDigest

data class FeedItem(val id: String, val title: String, val link: String)

/** The result of looking at a polled address once. [state] is what to remember for next time. */
data class PollResult(val changed: Boolean, val state: String, val value: String, val previous: String?, val item: FeedItem? = null)

/**
 * "Poll a URL and run when it changes" (spec §14.4). Three ways to decide what "changed" means: the text of the page,
 * one value inside JSON, or a new item in an RSS / Atom feed. The first look only remembers the starting point, so a
 * Flow never fires just because it was switched on.
 */
object Poller {
    fun evaluate(mode: String, jsonPath: String, previousState: String?, body: String): PollResult = when (mode) {
        "json" -> {
            val root = try {
                Value.parseJson(body)
            } catch (e: IllegalArgumentException) {
                throw NetException("The address didn't return JSON.")
            }
            val picked = if (jsonPath.isBlank()) root else root.path(jsonPath) ?: throw NetException("\"$jsonPath\" wasn't found in the answer.")
            val now = picked.render()
            PollResult(previousState != null && now != previousState, now, now, previousState)
        }
        "rss" -> {
            val newest = parseFeed(body).firstOrNull() ?: throw NetException("No feed items were found at that address.")
            PollResult(previousState != null && newest.id != previousState, newest.id, newest.title, previousState, newest)
        }
        else -> {
            val hash = sha256(body.trim())
            PollResult(previousState != null && hash != previousState, hash, body.trim().take(200), previousState)
        }
    }

    /** The event a Flow sees: `{{poll.value}}`, `{{poll.previous}}`, and for feeds `{{poll.title}}` and `{{poll.link}}`. */
    fun event(result: PollResult, url: String): Event = Event(
        TriggerIds.POLL_URL,
        buildMap<String, Value> {
            put("poll.value", Value.Text(result.value))
            put("poll.previous", Value.Text(result.previous.orEmpty()))
            put("poll.url", Value.Text(Redactor.url(url)))
            result.item?.let {
                put("poll.title", Value.Text(it.title))
                put("poll.link", Value.Text(it.link))
            }
        },
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    // A deliberately small reader: enough for the RSS and Atom feeds of blogs, news sites and release pages.
    private val itemPattern = Regex("<(item|entry)[\\s>].*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private fun tag(block: String, name: String): String {
        val m = Regex("<$name[^>]*>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(block) ?: return ""
        return unescape(m.groupValues[1].trim().removePrefix("<![CDATA[").removeSuffix("]]>").trim())
    }

    private fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun parseFeed(xml: String): List<FeedItem> = itemPattern.findAll(xml).map { m ->
        val block = m.value
        val title = tag(block, "title")
        // RSS puts the address in <link>…</link>; Atom puts it in <link href="…"/>.
        val link = tag(block, "link").ifEmpty { Regex("<link[^>]*href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1).orEmpty() }
        val id = tag(block, "guid").ifEmpty { tag(block, "id") }.ifEmpty { link.ifEmpty { title } }
        FeedItem(id, title, link)
    }.toList()
}
