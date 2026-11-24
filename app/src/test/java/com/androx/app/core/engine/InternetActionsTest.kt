package com.androx.app.core.engine

import com.androx.app.core.Flow
import com.androx.app.core.Op
import com.androx.app.core.OpIds
import com.androx.app.core.RunOptions
import com.androx.app.core.Step
import com.androx.app.core.StepOptions
import com.androx.app.core.net.FlowInternet
import com.androx.app.core.net.FlowInternetMode
import com.androx.app.core.net.HttpOptions
import com.androx.app.core.net.HttpPort
import com.androx.app.core.net.HttpRequestSpec
import com.androx.app.core.net.HttpResponse
import com.androx.app.core.net.InternetMode
import com.androx.app.core.net.InternetSettings
import com.androx.app.core.net.NetException
import com.androx.app.core.net.NetworkGate
import com.androx.app.core.net.NetworkProbe
import com.androx.app.core.net.NetworkServices
import com.androx.app.core.net.RequestLogEntry
import com.androx.app.core.net.SecretReader
import com.androx.app.core.params
import com.androx.app.engine.ScriptHost
import com.androx.app.engine.ScriptResult
import com.androx.app.engine.ScriptRunner
import com.androx.app.model.ExecutionRecord
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.RunSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetActionsTest {
    private class Http : HttpPort {
        val requests = mutableListOf<HttpRequestSpec>()
        var handler: (HttpRequestSpec) -> HttpResponse = { HttpResponse(200, body = "ok") }
        override suspend fun execute(request: HttpRequestSpec, options: HttpOptions): HttpResponse {
            requests += request
            return handler(request)
        }
    }

    private class Probe : NetworkProbe {
        val calls = mutableListOf<String>()
        var tcpMs: Long? = 25
        override suspend fun dns(host: String): List<String> = listOf("93.184.216.34", "2606:2800::1").also { calls += "dns:$host" }
        override suspend fun tcp(host: String, port: Int, timeoutMs: Int): Long? = tcpMs.also { calls += "tcp:$host:$port" }
        override suspend fun wakeOnLan(mac: String, broadcast: String, port: Int) {
            calls += "wol:$mac:$broadcast:$port"
        }
    }

    private val http = Http()
    private val probe = Probe()
    private val device = EngineFakeDevice()
    private val secrets = mutableMapOf("tok" to "S3CR3T-VALUE", "hook" to "https://discord.test/api/webhooks/77/HOOKTOKEN")
    private var settings = InternetSettings(mode = InternetMode.ON)
    private val log = mutableListOf<RequestLogEntry>()
    private val asked = mutableListOf<String>()
    private val reader = object : SecretReader {
        override fun get(name: String): String? = secrets[name]
    }
    private val noScripts = object : ScriptRunner {
        override fun run(source: String, host: ScriptHost): ScriptResult = ScriptResult.Success
    }
    private val gate = NetworkGate({ settings }, { true }, http, probe, { log }, { log.add(0, it) }, { h, f -> asked += "$h|$f" }, { 1_000_000L })
    private val engine = FlowEngine(
        device = device, variables = MapValueStore(), findFlow = { null }, scripts = noScripts,
        net = NetworkServices(gate, reader),
    )

    private fun step(typeId: String, p: JsonObject = params(), options: StepOptions = StepOptions()) = Step("s-$typeId", Op(typeId, p), options)
    private fun toast(text: String) = Step("t", Op(OpIds.TOAST, params("message" to text)))
    private fun flow(vararg steps: Step, internet: FlowInternet? = null) =
        Flow(id = "f", name = "Net flow", steps = steps.toList(), runOptions = RunOptions(timeoutSeconds = 0), internet = internet)

    private suspend fun run(vararg steps: Step): ExecutionRecord = engine.execute(flow(*steps), RunSource.MANUAL)
    private fun lastToast() = device.calls.last().removePrefix("toast:")
    private fun allText(r: ExecutionRecord) = (r.logs + listOfNotNull(r.message)).joinToString("\n")

    // ── HTTP request ────────────────────────────────────────────────────────────────────────

    @Test
    fun aGetKeepsTheAnswerForLaterSteps() = runTest {
        http.handler = { HttpResponse(200, listOf("Content-Type" to "application/json"), """{"items":[{"name":"x"}],"n":3}""") }
        val rec = run(step(OpIds.HTTP, params("url" to "https://api.test/v1")), toast("{{http.status}} {{http.json.n}} {{http.ok}} {{http.json.items.0.name}} {{http.header.content-type}}"))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals("200 3 true x application/json", lastToast())
        assertEquals("GET", http.requests.single().method)
    }

    @Test
    fun aJsonPathPicksOneValue() = runTest {
        http.handler = { HttpResponse(200, body = """{"items":[{"name":"x"}]}""") }
        run(step(OpIds.HTTP, params("url" to "https://api.test/v1", "jsonPath" to "items.0.name"), StepOptions(saveResultTo = "r")), toast("{{r}}"))
        assertEquals("x", lastToast())
        val missing = run(step(OpIds.HTTP, params("url" to "https://api.test/v1", "jsonPath" to "nope.here")))
        assertEquals("\"nope.here\" wasn't found in the answer.", missing.message)
        http.handler = { HttpResponse(200, body = "plain text") }
        assertTrue(run(step(OpIds.HTTP, params("url" to "https://api.test/v1", "jsonPath" to "a"))).message!!.contains("isn't JSON"))
    }

    @Test
    fun serverErrorsFailTheStepUnlessToldNotTo() = runTest {
        http.handler = { HttpResponse(404, body = "no such page") }
        val failed = run(step(OpIds.HTTP, params("url" to "https://api.test/x")))
        assertEquals(ExecutionStatus.FAILED, failed.status)
        assertEquals("The server answered 404. It said: no such page", failed.message)
        val ok = run(step(OpIds.HTTP, params("url" to "https://api.test/x", "failOnError" to false)), toast("{{http.status}} {{http.ok}}"))
        assertEquals(ExecutionStatus.SUCCESS, ok.status)
        assertEquals("404 false", lastToast())
    }

    @Test
    fun secretsAreSentButNeverShownInLogs() = runTest {
        val rec = run(
            step(OpIds.HTTP, params("url" to "https://api.test/x?k={{secret.tok}}&q=1", "headers" to "X-Extra: {{secret.tok}}\nbad line\nAccept: */*", "authType" to "bearer", "authSecret" to "tok")),
        )
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        val sent = http.requests.single()
        assertEquals("https://api.test/x?k=S3CR3T-VALUE&q=1", sent.url)
        assertEquals(listOf("X-Extra" to "S3CR3T-VALUE", "Accept" to "*/*", "Authorization" to "Bearer S3CR3T-VALUE"), sent.headers)
        assertFalse(allText(rec).contains("S3CR3T-VALUE"))
        assertFalse(log.any { it.url.contains("S3CR3T-VALUE") || it.error.contains("S3CR3T-VALUE") })
        assertTrue(allText(rec).contains("k="))
    }

    @Test
    fun secretsDoNotLeakIntoOrdinaryTemplates() = runTest {
        run(toast("[{{secret.tok}}]"))
        assertEquals("[]", lastToast())
    }

    @Test
    fun aMissingSecretIsExplained() = runTest {
        val rec = run(step(OpIds.HTTP, params("url" to "https://api.test/x", "authType" to "bearer", "authSecret" to "nope")))
        assertTrue(rec.message!!.startsWith("There is no secret named \"nope\" in the vault"))
        assertTrue(http.requests.isEmpty())
        val blank = run(step(OpIds.HTTP, params("url" to "https://api.test/x", "authType" to "bearer")))
        assertEquals("Choose which secret to use.", blank.message)
    }

    @Test
    fun basicAndApiKeyAuth() = runTest {
        run(step(OpIds.HTTP, params("url" to "https://api.test/x", "authType" to "basic", "authUser" to "bob", "authSecret" to "tok")))
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("bob:S3CR3T-VALUE".toByteArray()), http.requests.last().headers.single().second)
        run(step(OpIds.HTTP, params("url" to "https://api.test/x", "authType" to "apikey", "authSecret" to "tok")))
        assertEquals("X-API-Key" to "S3CR3T-VALUE", http.requests.last().headers.single())
        run(step(OpIds.HTTP, params("url" to "https://api.test/x", "authType" to "apikey", "authSecret" to "tok", "authHeader" to "X-Token")))
        assertEquals("X-Token", http.requests.last().headers.single().first)
    }

    @Test
    fun bodiesAreCheckedAndEncoded() = runTest {
        val bad = run(step(OpIds.HTTP, params("method" to "POST", "url" to "https://api.test/x", "bodyType" to "json", "body" to "{oops")))
        assertTrue(bad.message!!.contains("isn't valid JSON"))
        assertTrue(http.requests.isEmpty())

        run(step(OpIds.HTTP, params("method" to "POST", "url" to "https://api.test/x", "bodyType" to "json", "body" to """{"a":1}""")))
        assertEquals("""{"a":1}""", http.requests.last().body)
        assertEquals("application/json", http.requests.last().headers.single { it.first == "Content-Type" }.second)

        run(step(OpIds.HTTP, params("method" to "POST", "url" to "https://api.test/x", "bodyType" to "form", "body" to "a=1\nb=x y\nignored")))
        assertEquals("a=1&b=x+y", http.requests.last().body)
        assertEquals("application/x-www-form-urlencoded", http.requests.last().headers.single { it.first == "Content-Type" }.second)

        run(step(OpIds.HTTP, params("method" to "PUT", "url" to "https://api.test/x", "bodyType" to "raw", "body" to "hello {{flow}}", "headers" to "Content-Type: text/plain")))
        assertEquals("hello Net flow", http.requests.last().body)
        assertEquals("PUT", http.requests.last().method)
    }

    @Test
    fun anEmptyAddressIsExplained() = runTest {
        assertEquals("Give the address to contact.", run(step(OpIds.HTTP, params("url" to " "))).message)
    }

    // ── the access rules apply to every action ──────────────────────────────────────────────

    @Test
    fun whenInternetIsOffNothingIsSent() = runTest {
        settings = InternetSettings()
        val rec = run(step(OpIds.HTTP, params("url" to "https://api.test/x")))
        assertEquals(ExecutionStatus.FAILED, rec.status)
        assertTrue(rec.message!!.contains("turned off"))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun askModeTellsThePersonAndRefusesThisRun() = runTest {
        settings = settings.copy(mode = InternetMode.ASK)
        val rec = run(step(OpIds.HTTP, params("url" to "https://new.test/x")))
        assertTrue(rec.message!!.contains("needs your OK before contacting new.test"))
        assertEquals(listOf("new.test|Net flow"), asked)
    }

    @Test
    fun aFlowCanBeBlockedFromTheInternet() = runTest {
        val rec = engine.execute(flow(step(OpIds.HTTP, params("url" to "https://api.test/x")), internet = FlowInternet(FlowInternetMode.BLOCK)), RunSource.MANUAL)
        assertTrue(rec.message!!.contains("isn't allowed to use the internet"))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun aSimulationNeverTouchesTheNetwork() = runTest {
        val rec = engine.simulate(flow(step(OpIds.HTTP, params("url" to "https://api.test/x")), step(OpIds.DNS_LOOKUP, params("host" to "example.com"))))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertTrue(http.requests.isEmpty() && probe.calls.isEmpty())
        assertTrue(rec.logs.any { it.contains("would: HTTP request") })
    }

    // ── utility actions ─────────────────────────────────────────────────────────────────────

    @Test
    fun checkIfASiteIsUp() = runTest {
        run(step(OpIds.SITE_UP, params("url" to "https://site.test"), StepOptions(saveResultTo = "up")), toast("{{up}} {{site.status}}"))
        assertEquals("true 200", lastToast())
        assertEquals("HEAD", http.requests.single().method)

        http.requests.clear()
        http.handler = { r -> if (r.method == "HEAD") HttpResponse(405) else HttpResponse(200) }
        run(step(OpIds.SITE_UP, params("url" to "https://site.test")), toast("{{site.up}}"))
        assertEquals(listOf("HEAD", "GET"), http.requests.map { it.method })
        assertEquals("true", lastToast())

        http.handler = { HttpResponse(503) }
        run(step(OpIds.SITE_UP, params("url" to "https://site.test")), toast("{{site.up}}"))
        assertEquals("false", lastToast())
    }

    @Test
    fun aSiteThatDoesNotAnswerIsDownNotAnError() = runTest {
        http.handler = { throw NetException("The server took too long to answer.") }
        val rec = run(step(OpIds.SITE_UP, params("url" to "https://site.test")), toast("{{site.up}}"))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals("false", lastToast())
    }

    @Test
    fun butARefusedCheckIsAnErrorSoItIsNotMistakenForDown() = runTest {
        settings = InternetSettings()
        val rec = run(step(OpIds.SITE_UP, params("url" to "https://site.test")))
        assertEquals(ExecutionStatus.FAILED, rec.status)
    }

    @Test
    fun publicIpIsCheckedBeforeItIsTrusted() = runTest {
        http.handler = { HttpResponse(200, body = "203.0.113.7\n") }
        run(step(OpIds.PUBLIC_IP, params("service" to "icanhazip")), toast("{{ip.public}}"))
        assertEquals("203.0.113.7", lastToast())
        assertEquals("https://icanhazip.com", http.requests.single().url)
        http.handler = { HttpResponse(200, body = "<html>captive portal</html>") }
        assertEquals("The service didn't answer with an address.", run(step(OpIds.PUBLIC_IP)).message)
    }

    @Test
    fun dnsTcpAndWakeOnLan() = runTest {
        run(step(OpIds.DNS_LOOKUP, params("host" to "example.com"), StepOptions(saveResultTo = "addr")), toast("{{addr}} {{dns.first}}"))
        assertEquals("""["93.184.216.34","2606:2800::1"] 93.184.216.34""", lastToast())

        run(step(OpIds.TCP_CHECK, params("host" to "nas.test", "port" to 22), StepOptions(saveResultTo = "open")), toast("{{open}} {{tcp.ms}}"))
        assertEquals("true 25", lastToast())
        probe.tcpMs = null
        run(step(OpIds.TCP_CHECK, params("host" to "nas.test", "port" to 22)), toast("{{tcp.ms}}"))
        assertEquals("-1", lastToast())

        assertEquals(ExecutionStatus.SUCCESS, run(step(OpIds.WAKE_ON_LAN, params("mac" to "aa-bb-cc-dd-ee-ff"))).status)
        assertEquals("wol:aa-bb-cc-dd-ee-ff:255.255.255.255:9", probe.calls.last())
        assertEquals("A MAC address looks like AA:BB:CC:DD:EE:FF.", run(step(OpIds.WAKE_ON_LAN, params("mac" to "nonsense"))).message)
        assertEquals("A port is a number from 1 to 65535.", run(step(OpIds.TCP_CHECK, params("host" to "x", "port" to 70_000))).message)
        assertEquals("Give a host name to look up.", run(step(OpIds.DNS_LOOKUP, params("host" to ""))).message)
    }

    @Test
    fun weatherReadsOpenMeteo() = runTest {
        http.handler = { HttpResponse(200, body = """{"current":{"temperature_2m":18.5,"relative_humidity_2m":60,"weather_code":3,"wind_speed_10m":11.2}}""") }
        run(step(OpIds.WEATHER, params("latitude" to "48.85", "longitude" to "2.35", "units" to "imperial"), StepOptions(saveResultTo = "w")), toast("{{w.description}} {{w.temperature}} {{weather.temp}}"))
        assertEquals("Overcast 18.5 18.5", lastToast())
        val url = http.requests.single().url
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=48.85&longitude=2.35"))
        assertTrue(url.contains("temperature_unit=fahrenheit"))
        assertEquals("Latitude must be a number from -90 to 90.", run(step(OpIds.WEATHER, params("latitude" to "99", "longitude" to "0"))).message)
        assertEquals("Longitude must be a number from -180 to 180.", run(step(OpIds.WEATHER, params("latitude" to "10", "longitude" to "abc"))).message)
    }

    // ── Ask AI ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun askAiSendsTheQuestionWithTheVaultKey() = runTest {
        secrets["ai"] = "sk-test-KEY"
        http.handler = { HttpResponse(200, body = """{"choices":[{"message":{"content":"42"}}]}""") }
        val rec = run(step(OpIds.ASK_AI, params("provider" to "openai", "prompt" to "Answer for {{flow}}", "secret" to "ai"), StepOptions(saveResultTo = "a")), toast("{{a}}"))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals("42", lastToast())
        val sent = http.requests.single()
        assertEquals("Bearer sk-test-KEY", sent.headers.single { it.first == "Authorization" }.second)
        assertTrue(sent.body!!.contains("Answer for Net flow"))
        assertFalse(allText(rec).contains("sk-test-KEY"))
    }

    @Test
    fun askAiErrorsAreReadable() = runTest {
        secrets["ai"] = "sk-test-KEY"
        http.handler = { HttpResponse(401, body = """{"error":{"message":"Invalid API key"}}""") }
        val rec = run(step(OpIds.ASK_AI, params("provider" to "openai", "prompt" to "hi", "secret" to "ai")))
        assertEquals("The provider answered 401. The provider said: Invalid API key", rec.message)
        assertTrue(run(step(OpIds.ASK_AI, params("provider" to "anthropic", "prompt" to "hi"))).message!!.contains("Choose which secret"))
        assertTrue(run(step(OpIds.ASK_AI, params("provider" to "openai", "prompt" to "", "secret" to "ai"))).message!!.contains("question"))
    }

    @Test
    fun aLocalOllamaNeedsNoKeyButMustBeListedAsALocalDevice() = runTest {
        http.handler = { HttpResponse(200, body = """{"message":{"content":"hello"}}""") }
        val refused = run(step(OpIds.ASK_AI, params("provider" to "ollama", "prompt" to "hi", "baseUrl" to "http://nas:11434")))
        assertTrue(refused.message!!.contains("plain http"))
        settings = settings.copy(lanHosts = listOf("nas"))
        val ok = run(step(OpIds.ASK_AI, params("provider" to "ollama", "prompt" to "hi", "baseUrl" to "http://nas:11434"), StepOptions(saveResultTo = "a")), toast("{{a}}"))
        assertEquals(ExecutionStatus.SUCCESS, ok.status)
        assertEquals("hello", lastToast())
    }

    // ── message presets ─────────────────────────────────────────────────────────────────────

    @Test
    fun aWebhookAddressFromTheVaultIsUsedAndHidden() = runTest {
        http.handler = { HttpResponse(204) }
        val rec = run(step(OpIds.DISCORD, params("webhook" to "{{secret.hook}}", "text" to "Backup done on {{flow}}")))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals("https://discord.test/api/webhooks/77/HOOKTOKEN", http.requests.single().url)
        assertTrue(http.requests.single().body!!.contains("Backup done on Net flow"))
        assertFalse(allText(rec).contains("HOOKTOKEN"))
        assertFalse(log.any { it.url.contains("HOOKTOKEN") })
    }

    @Test
    fun presetFailuresComeWithAHint() = runTest {
        http.handler = { HttpResponse(401) }
        val rec = run(step(OpIds.SLACK, params("webhook" to "https://hooks.slack.test/x", "text" to "hi")))
        assertEquals("The server answered 401. The service refused the credentials. Check the key or token in your vault.", rec.message)
        assertEquals("The message is missing.", run(step(OpIds.SLACK, params("webhook" to "https://hooks.slack.test/x"))).message)
    }

    @Test
    fun telegramHidesTheBotTokenInThePath() = runTest {
        secrets["bot"] = "123456:ABC-DEF"
        val rec = run(step(OpIds.TELEGRAM, params("token" to "{{secret.bot}}", "chatId" to "42", "text" to "hi")))
        assertEquals(ExecutionStatus.SUCCESS, rec.status)
        assertEquals("https://api.telegram.org/bot123456:ABC-DEF/sendMessage", http.requests.single().url)
        assertFalse(allText(rec).contains("123456:ABC-DEF"))
        assertFalse(log.any { it.url.contains("123456:ABC-DEF") })
    }

    @Test
    fun everyInternetActionIsRegisteredAndDescribesItself() {
        val catalog = com.androx.app.core.spec.SpecCatalog.default
        assertEquals(16, INTERNET_OP_IDS.size)
        for (id in INTERNET_OP_IDS) {
            val spec = catalog.actions[id]!!
            spec.summary(spec.defaultParams()) // never throws on an empty step
            assertTrue(id, spec.description.isNotBlank())
        }
        val http = catalog.actions[OpIds.HTTP]!!
        assertEquals(listOf("Choose the secret to sign in with."), http.problems(params("url" to "https://x", "authType" to "bearer")))
        assertEquals(listOf("A GET request can't have a body."), http.problems(params("url" to "https://x", "body" to "x")))
        assertTrue(catalog.actions[OpIds.ASK_AI]!!.problems(params("prompt" to "hi", "provider" to "openai")).single().contains("secret"))
        assertTrue(catalog.actions[OpIds.ASK_AI]!!.problems(params("prompt" to "hi", "provider" to "ollama")).isEmpty())
        assertTrue(catalog.actions[OpIds.WAKE_ON_LAN]!!.problems(params("mac" to "zz")).single().contains("MAC address"))
        val poll = catalog.triggers[com.androx.app.core.TriggerIds.POLL_URL]!!
        assertEquals(listOf("Say which JSON value to watch."), poll.problems(params("url" to "https://x", "mode" to "json")))
        assertTrue(poll.problems(params("url" to "https://x", "minutes" to 1)).single().contains("at least 5"))
    }
}
