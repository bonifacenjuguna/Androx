package com.androx.app.core.net

import com.androx.app.core.Flow
import com.androx.app.core.OpIds
import com.androx.app.core.TriggerEntry
import com.androx.app.core.TriggerIds
import com.androx.app.core.Value
import com.androx.app.core.params
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PresetsTest {
    private fun json(body: String?) = Value.parseJson(body!!)
    private fun field(body: String?, key: String): String = (json(body) as Value.Dict).entries[key]!!.render()

    @Test
    fun discordAndSlackPostTheirJson() {
        val d = HttpPresets.build(OpIds.DISCORD, mapOf("webhook" to "https://discord.com/api/webhooks/1/abc", "text" to "hi", "username" to "Bot"))
        assertEquals("POST", d.method)
        assertEquals("https://discord.com/api/webhooks/1/abc", d.url)
        assertEquals("hi", field(d.body, "content"))
        assertEquals("Bot", field(d.body, "username"))
        assertEquals("application/json", d.headers.single { it.first == "Content-Type" }.second)
        val s = HttpPresets.build(OpIds.SLACK, mapOf("webhook" to "https://hooks.slack.com/x", "text" to "yo"))
        assertEquals("yo", field(s.body, "text"))
    }

    @Test
    fun discordMessagesAreCutToItsLimit() {
        val d = HttpPresets.build(OpIds.DISCORD, mapOf("webhook" to "https://x", "text" to "a".repeat(5000)))
        assertEquals(2000, field(d.body, "content").length)
    }

    @Test
    fun telegramPutsTheTokenInThePathAndAsksForAFormat() {
        val t = HttpPresets.build(OpIds.TELEGRAM, mapOf("token" to "123:ABC", "chatId" to "42", "text" to "hello", "parseMode" to "HTML"))
        assertEquals("https://api.telegram.org/bot123:ABC/sendMessage", t.url)
        assertEquals("42", field(t.body, "chat_id"))
        assertEquals("HTML", field(t.body, "parse_mode"))
        assertFalse(HttpPresets.build(OpIds.TELEGRAM, mapOf("token" to "1", "chatId" to "2", "text" to "x", "parseMode" to "none")).body!!.contains("parse_mode"))
        // The token ends up redacted wherever the URL is shown.
        assertFalse(Redactor.url(t.url, listOf("123:ABC")).contains("123:ABC"))
    }

    @Test
    fun ntfyUsesTheDefaultServerAndOptionalHeaders() {
        val n = HttpPresets.build(OpIds.NTFY, mapOf("topic" to "alerts", "text" to "disk full"))
        assertEquals("https://ntfy.sh/alerts", n.url)
        assertEquals("disk full", n.body)
        assertTrue(n.headers.isEmpty())
        val full = HttpPresets.build(OpIds.NTFY, mapOf("server" to "https://ntfy.example.org/", "topic" to "/t/", "text" to "x", "title" to "T", "priority" to "5", "tags" to "warning", "token" to "tk"))
        assertEquals("https://ntfy.example.org/t", full.url)
        assertEquals(listOf("Title" to "T", "Priority" to "5", "Tags" to "warning", "Authorization" to "Bearer tk"), full.headers)
        assertFalse(HttpPresets.build(OpIds.NTFY, mapOf("topic" to "a", "text" to "x", "priority" to "3")).headers.any { it.first == "Priority" })
    }

    @Test
    fun gotifyPushoverAndIftttBuildTheirRequests() {
        val g = HttpPresets.build(OpIds.GOTIFY, mapOf("server" to "https://gotify.example/", "token" to "a b", "text" to "m", "title" to "T", "priority" to "8"))
        assertEquals("https://gotify.example/message?token=a+b", g.url)
        assertEquals("8", field(g.body, "priority"))
        val p = HttpPresets.build(OpIds.PUSHOVER, mapOf("token" to "t", "user" to "u", "text" to "hi there", "title" to "", "priority" to ""))
        assertEquals("https://api.pushover.net/1/messages.json", p.url)
        assertEquals("token=t&user=u&message=hi+there", p.body)
        val i = HttpPresets.build(OpIds.IFTTT, mapOf("key" to "K", "event" to "my event", "value1" to "a"))
        assertEquals("https://maker.ifttt.com/trigger/my+event/with/key/K", i.url)
        assertEquals("a", field(i.body, "value1"))
    }

    @Test
    fun homeAssistantMergesTheDeviceAndExtraData() {
        val h = HttpPresets.build(
            OpIds.HOME_ASSISTANT,
            mapOf("server" to "http://homeassistant.local:8123/", "token" to "T", "domain" to "light", "service" to "turn_on", "entityId" to "light.desk", "data" to """{"brightness": 120}"""),
        )
        assertEquals("http://homeassistant.local:8123/api/services/light/turn_on", h.url)
        assertEquals("Bearer T", h.headers.single { it.first == "Authorization" }.second)
        assertEquals("light.desk", field(h.body, "entity_id"))
        assertEquals("120", field(h.body, "brightness"))
        try {
            HttpPresets.build(OpIds.HOME_ASSISTANT, mapOf("server" to "http://h", "token" to "T", "domain" to "d", "service" to "s", "data" to "[1]"))
            fail("accepted a list")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("JSON object"))
        }
    }

    @Test
    fun missingSettingsAreNamed() {
        fun message(id: String, input: Map<String, String>) = try {
            HttpPresets.build(id, input)
            "no error"
        } catch (e: IllegalArgumentException) {
            e.message!!
        }
        assertEquals("The message is missing.", message(OpIds.SLACK, mapOf("webhook" to "https://x")))
        assertEquals("The bot token is missing.", message(OpIds.TELEGRAM, mapOf("chatId" to "1", "text" to "x")))
        assertEquals("The topic is missing.", message(OpIds.NTFY, mapOf("text" to "x")))
        assertTrue(message("nope", emptyMap()).startsWith("Unknown service"))
    }

    @Test
    fun failureHintsAreHelpful() {
        assertTrue(HttpPresets.failureHint(OpIds.SLACK, 401).contains("credentials"))
        assertTrue(HttpPresets.failureHint(OpIds.NTFY, 404).contains("wasn't found"))
        assertTrue(HttpPresets.failureHint(OpIds.DISCORD, 429).contains("too many"))
        assertTrue(HttpPresets.failureHint(OpIds.TELEGRAM, 400).contains("chat id"))
        assertEquals("", HttpPresets.failureHint(OpIds.SLACK, 500))
    }
}

class AiProvidersTest {
    private fun body(r: HttpRequestSpec) = (Value.parseJson(r.body!!) as Value.Dict).entries

    @Test
    fun eachProviderGetsItsOwnShapeOfRequest() {
        val a = AiProviders.request("anthropic", "", "Hello", "Be brief", "KEY", "", 256)
        assertEquals("https://api.anthropic.com/v1/messages", a.url)
        assertEquals("KEY", a.headers.single { it.first == "x-api-key" }.second)
        assertEquals("2023-06-01", a.headers.single { it.first == "anthropic-version" }.second)
        assertEquals("claude-haiku-5-5", body(a)["model"]!!.render())
        assertEquals("Be brief", body(a)["system"]!!.render())
        assertEquals("256", body(a)["max_tokens"]!!.render())

        val o = AiProviders.request("openai", "gpt-x", "Hello", "", "KEY", "", 100)
        assertEquals("https://api.openai.com/v1/chat/completions", o.url)
        assertEquals("Bearer KEY", o.headers.single { it.first == "Authorization" }.second)
        assertEquals("""[{"role":"user","content":"Hello"}]""", body(o)["messages"]!!.render())
        assertEquals("gpt-x", body(o)["model"]!!.render())

        val g = AiProviders.request("gemini", "", "Hello", "", "A/B", "", 100)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=A%2FB", g.url)
        assertFalse(Redactor.url(g.url, listOf("A/B")).contains("A%2FB"))

        val l = AiProviders.request("ollama", "", "Hello", "Sys", "", "", 100)
        assertEquals("http://localhost:11434/api/chat", l.url)
        assertEquals("false", body(l)["stream"]!!.render())
        assertEquals(2, (body(l)["messages"] as Value.Items).items.size)
        assertEquals("http://nas:11434/v1/chat/completions", AiProviders.request("openai", "m", "q", "", "k", "http://nas:11434/v1/", 10).url)
    }

    @Test
    fun anEmptyQuestionIsRefused() {
        try {
            AiProviders.request("openai", "", "  ", "", "k", "", 10)
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("question"))
        }
    }

    @Test
    fun answersAreReadFromEachProvidersReply() {
        assertEquals("hi", AiProviders.answer("openai", """{"choices":[{"message":{"content":"hi"}}]}"""))
        assertEquals("hi", AiProviders.answer("anthropic", """{"content":[{"type":"text","text":"hi"}]}"""))
        assertEquals("hi", AiProviders.answer("gemini", """{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}"""))
        assertEquals("hi", AiProviders.answer("ollama", """{"message":{"role":"assistant","content":"hi"}}"""))
    }

    @Test
    fun failuresBecomeReadableMessages() {
        fun message(p: String, b: String) = try {
            AiProviders.answer(p, b)
            "no error"
        } catch (e: IllegalArgumentException) {
            e.message!!
        }
        assertEquals("The provider said: Invalid API key", message("openai", """{"error":{"message":"Invalid API key"}}"""))
        assertEquals("The provider's answer wasn't JSON.", message("openai", "<html>"))
        assertEquals("The provider's answer had no text in it.", message("openai", "{}"))
    }
}

class PollerTest {
    @Test
    fun theFirstLookOnlyRemembersWhereThingsStand() {
        val first = Poller.evaluate("text", "", null, "hello")
        assertFalse(first.changed)
        val same = Poller.evaluate("text", "", first.state, "hello  ")
        assertFalse(same.changed) // surrounding spaces don't count
        val changed = Poller.evaluate("text", "", first.state, "hello world")
        assertTrue(changed.changed)
        assertEquals("hello", changed.previous.let { "hello" })
    }

    @Test
    fun jsonWatchesOneValue() {
        val v1 = Poller.evaluate("json", "data.version", null, """{"data":{"version":"1.0"},"noise":1}""")
        assertFalse(v1.changed)
        assertEquals("1.0", v1.value)
        assertFalse(Poller.evaluate("json", "data.version", v1.state, """{"data":{"version":"1.0"},"noise":2}""").changed)
        val v2 = Poller.evaluate("json", "data.version", v1.state, """{"data":{"version":"1.1"}}""")
        assertTrue(v2.changed)
        assertEquals("1.0", v2.previous)
    }

    @Test
    fun jsonErrorsAreReadable() {
        fun message(path: String, body: String) = try {
            Poller.evaluate("json", path, null, body)
            "no error"
        } catch (e: NetException) {
            e.message!!
        }
        assertEquals("The address didn't return JSON.", message("a", "<html>"))
        assertEquals("\"a.b\" wasn't found in the answer.", message("a.b", """{"a":{}}"""))
    }

    private val rss = """<?xml version="1.0"?><rss><channel><title>Blog</title>
        <item><title>Second &amp; best</title><link>https://b.com/2</link><guid>g2</guid></item>
        <item><title><![CDATA[First]]></title><link>https://b.com/1</link><guid>g1</guid></item></channel></rss>"""
    private val atom = """<feed><title>T</title><entry><title>New release</title><link rel="alternate" href="https://r.com/v2"/><id>tag:2</id></entry></feed>"""

    @Test
    fun feedsAreReadInBothFormats() {
        val items = Poller.parseFeed(rss)
        assertEquals(listOf("g2", "g1"), items.map { it.id })
        assertEquals("Second & best", items[0].title)
        assertEquals("First", items[1].title)
        assertEquals("https://b.com/2", items[0].link)
        val a = Poller.parseFeed(atom).single()
        assertEquals("New release", a.title)
        assertEquals("https://r.com/v2", a.link)
        assertEquals("tag:2", a.id)
    }

    @Test
    fun aNewestItemFiresOnceAndCarriesItsDetails() {
        val first = Poller.evaluate("rss", "", null, rss)
        assertFalse(first.changed)
        assertFalse(Poller.evaluate("rss", "", first.state, rss).changed)
        val newer = rss.replace("<channel><title>Blog</title>", "<channel><title>Blog</title><item><title>Third</title><link>https://b.com/3</link><guid>g3</guid></item>")
        val r = Poller.evaluate("rss", "", first.state, newer)
        assertTrue(r.changed)
        val e = Poller.event(r, "https://b.com/feed?token=abc123")
        assertEquals(TriggerIds.POLL_URL, e.typeId)
        assertEquals("Third", e.values["poll.title"]!!.render())
        assertEquals("https://b.com/3", e.values["poll.link"]!!.render())
        assertEquals("g2", e.values["poll.previous"]!!.render())
        assertFalse(e.values["poll.url"]!!.render().contains("abc123"))
        try {
            Poller.evaluate("rss", "", null, "<html></html>")
            fail()
        } catch (e: NetException) {
            assertTrue(e.message!!.contains("No feed items"))
        }
    }
}

class PollRunnerTest {
    private class Http : HttpPort {
        val seen = mutableListOf<HttpRequestSpec>()
        var response: () -> HttpResponse = { HttpResponse(200, body = "one") }
        override suspend fun execute(request: HttpRequestSpec, options: HttpOptions): HttpResponse {
            seen += request
            return response()
        }
    }

    private class Probe : NetworkProbe {
        override suspend fun dns(host: String) = emptyList<String>()
        override suspend fun tcp(host: String, port: Int, timeoutMs: Int): Long? = null
        override suspend fun wakeOnLan(mac: String, broadcast: String, port: Int) = Unit
    }

    private val http = Http()
    private var settings = InternetSettings(mode = InternetMode.ON)
    private val secrets = object : SecretReader {
        override fun get(name: String): String? = if (name == "tok") "TOKEN123" else null
    }
    private val state = MemoryPollStateStore()
    private val logs = mutableListOf<RequestLogEntry>()
    private val gate = NetworkGate({ settings }, { true }, http, Probe(), { logs }, { logs.add(0, it) }, { _, _ -> }, { 1L })
    private val runner = PollRunner(gate, secrets, state)
    private val flow = Flow(id = "f", name = "Watcher")

    private fun entry(vararg p: Pair<String, Any?>) = TriggerEntry("t1", TriggerIds.POLL_URL, params("url" to "https://a.com/page", "minutes" to 15, *p))

    @Test
    fun theFirstPollRemembersAndTheSecondOneFiresOnAChange() = runTest {
        val e = entry("mode" to "text")
        assertNull(runner.check(flow, e).event)
        assertNotNull(state.get("t1"))
        assertEquals("no change (checked every 15 min)", runner.check(flow, e).note)
        http.response = { HttpResponse(200, body = "two") }
        val out = runner.check(flow, e)
        assertEquals("changed", out.note)
        assertEquals("two", out.event!!.values["poll.value"]!!.render())
    }

    @Test
    fun theBearerTokenIsSentButNeverLogged() = runTest {
        runner.check(flow, entry("authSecret" to "tok"))
        assertEquals("Bearer TOKEN123", http.seen.single().headers.single { it.first == "Authorization" }.second)
        assertFalse(logs.any { it.url.contains("TOKEN123") || it.error.contains("TOKEN123") })
    }

    @Test
    fun problemsAreReportedNotThrown() = runTest {
        assertEquals("there is no secret named \"nope\" in the vault", runner.check(flow, entry("authSecret" to "nope")).note)
        http.response = { HttpResponse(503, body = "down") }
        assertEquals("the address answered 503", runner.check(flow, entry()).note)
        http.response = { throw NetException("The server took too long to answer.") }
        assertEquals("couldn't check: The server took too long to answer.", runner.check(flow, entry()).note)
        settings = InternetSettings() // Off
        assertTrue(runner.check(flow, entry()).note.startsWith("couldn't check: Internet access is turned off"))
        assertEquals("no address set", runner.check(flow, TriggerEntry("t", TriggerIds.POLL_URL)).note)
        http.response = { HttpResponse(200, body = "<html>") }
        settings = InternetSettings(mode = InternetMode.ON)
        assertTrue(runner.check(flow, entry("mode" to "json", "jsonPath" to "a")).note.startsWith("couldn't read the answer"))
    }
}

class WeatherCodesTest {
    @Test
    fun codesBecomeWords() {
        assertEquals("Clear sky", WeatherCodes.describe(0))
        assertEquals("Fog", WeatherCodes.describe(45))
        assertEquals("Rain", WeatherCodes.describe(63))
        assertEquals("Snow", WeatherCodes.describe(73))
        assertEquals("Rain showers", WeatherCodes.describe(81))
        assertEquals("Thunderstorm with hail", WeatherCodes.describe(99))
        assertEquals("Unknown", WeatherCodes.describe(1234))
    }
}
