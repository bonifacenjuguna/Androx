package com.androx.app.core.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DomainsAndPolicyTest {
    private val on = InternetSettings(mode = InternetMode.ON)
    private fun decide(s: InternetSettings, url: String, flow: FlowInternet? = null, wifi: Boolean = true, sent: Int = 0) =
        InternetPolicy.decide(s, NetRequestInfo(url, flow, wifi), sent)

    private fun denied(d: NetDecision): String = (d as NetDecision.Deny).reason

    @Test
    fun hostsAreReadFromAddresses() {
        assertEquals("example.com", Domains.hostOf("https://Example.com/path?x=1"))
        assertEquals("example.com", Domains.hostOf("https://user:pw@example.com:8443/a"))
        assertEquals("::1", Domains.hostOf("http://[::1]:8080/"))
        assertNull(Domains.hostOf("not a url"))
        assertEquals("http", Domains.schemeOf("HTTP://a"))
    }

    @Test
    fun domainRulesIncludeSubdomainsButNotLookalikes() {
        assertTrue(Domains.matches("api.example.com", "example.com"))
        assertTrue(Domains.matches("example.com", "example.com"))
        assertFalse(Domains.matches("badexample.com", "example.com"))
        assertFalse(Domains.matches("example.com", "*.example.com"))
        assertTrue(Domains.matches("a.example.com", "*.example.com"))
        assertTrue(Domains.matches("EXAMPLE.com.", "example.com"))
        assertFalse(Domains.matches("example.com", ""))
    }

    @Test
    fun localAddressesAreRecognised() {
        for (h in listOf("localhost", "192.168.1.20", "10.0.0.5", "172.16.4.4", "172.31.255.1", "127.0.0.1", "169.254.1.1", "nas", "printer.local", "x.home.arpa", "::1", "fd12::1")) {
            assertTrue(h, Domains.isLocal(h))
        }
        for (h in listOf("example.com", "8.8.8.8", "172.32.0.1", "192.169.1.1", "1.2.3.4")) assertFalse(h, Domains.isLocal(h))
    }

    @Test
    fun offAndTheKillSwitchRefuseEverything() {
        assertTrue(denied(decide(InternetSettings(), "https://a.com")).contains("turned off"))
        assertTrue(denied(decide(on.copy(killSwitch = true), "https://a.com")).contains("Block internet"))
        assertTrue(denied(decide(on.copy(killSwitch = true), "https://a.com", FlowInternet(FlowInternetMode.ALLOW))).contains("Block internet"))
        assertTrue(decide(on, "https://a.com") is NetDecision.Allow)
    }

    @Test
    fun badAddressesAndSchemesAreRefused() {
        assertTrue(denied(decide(on, "nonsense")).contains("valid web address"))
        assertTrue(denied(decide(on, "ftp://a.com/x")).contains("http and https"))
        assertTrue(denied(decide(on, "file:///sdcard/x")).contains("valid web address"))
    }

    @Test
    fun plainHttpOnlyForListedLocalDevices() {
        assertTrue(denied(decide(on, "http://example.com")).contains("plain http"))
        assertTrue(denied(decide(on, "http://192.168.1.5")).contains("plain http")) // local, but not listed
        val lan = on.copy(lanHosts = listOf("192.168.1.5"))
        val d = decide(lan, "http://192.168.1.5:8123/api") as NetDecision.Allow
        assertTrue(d.allowHttp && d.trustSelfSigned)
        val other = decide(lan, "https://example.com") as NetDecision.Allow
        assertFalse(other.allowHttp || other.trustSelfSigned)
        assertTrue(decide(on.copy(httpsOnly = false), "http://example.com") is NetDecision.Allow)
    }

    @Test
    fun wifiOnlyBlocksMobileData() {
        val s = on.copy(wifiOnly = true)
        assertTrue(denied(decide(s, "https://a.com", wifi = false)).contains("Wi-Fi only"))
        assertTrue(decide(s, "https://a.com", wifi = true) is NetDecision.Allow)
    }

    @Test
    fun blocklistWinsOverEverythingElse() {
        val s = on.copy(blockedDomains = listOf("evil.com"), allowedDomains = listOf("evil.com"), mode = InternetMode.ASK)
        assertTrue(denied(decide(s, "https://api.evil.com/x", FlowInternet(FlowInternetMode.ALLOW))).contains("blocked list"))
    }

    @Test
    fun theHourlyCapStopsRunawayLoops() {
        val s = on.copy(hourlyCap = 5)
        assertTrue(decide(s, "https://a.com", sent = 4) is NetDecision.Allow)
        assertTrue(denied(decide(s, "https://a.com", sent = 5)).contains("5 requests per hour"))
        assertTrue(decide(s.copy(hourlyCap = 0), "https://a.com", sent = 9_999) is NetDecision.Allow)
    }

    @Test
    fun askModeAsksOncePerNewDomain() {
        val s = on.copy(mode = InternetMode.ASK, allowedDomains = listOf("known.com"))
        assertEquals(NetDecision.Ask("new.com"), decide(s, "https://new.com/x"))
        assertTrue(decide(s, "https://api.known.com/x") is NetDecision.Allow)
        assertTrue(decide(s, "https://new.com", FlowInternet(FlowInternetMode.ALLOW)) is NetDecision.Allow)
        assertTrue(decide(s.copy(lanHosts = listOf("nas")), "https://nas/x") is NetDecision.Allow)
    }

    @Test
    fun aFlowCanBeBlockedOrConfinedToItsOwnDomains() {
        assertTrue(denied(decide(on, "https://a.com", FlowInternet(FlowInternetMode.BLOCK))).contains("isn't allowed"))
        val confined = FlowInternet(FlowInternetMode.INHERIT, listOf("api.example.com"))
        assertTrue(decide(on, "https://api.example.com/x", confined) is NetDecision.Allow)
        assertTrue(denied(decide(on, "https://other.com/x", confined)).contains("may only contact api.example.com"))
        // Allow never overrides Off.
        assertTrue(denied(decide(InternetSettings(), "https://a.com", FlowInternet(FlowInternetMode.ALLOW))).contains("turned off"))
    }
}

class RedactorTest {
    @Test
    fun urlsLoseTheirPasswordsAndTokens() {
        assertEquals("https://user:${Redactor.MASK}@host.com/p?q=1&token=${Redactor.MASK}", Redactor.url("https://user:hunter2@host.com/p?q=1&token=abc123"))
        assertEquals("https://x.com/?key=${Redactor.MASK}&a=b#frag", Redactor.url("https://x.com/?key=AIzaSy&a=b#frag"))
        assertEquals("https://x.com/?name=bob", Redactor.url("https://x.com/?name=bob"))
    }

    @Test
    fun knownSecretsAreHiddenWhereverTheyAppear() {
        assertEquals("https://api.telegram.org/bot${Redactor.MASK}/sendMessage", Redactor.url("https://api.telegram.org/bot123:ABCDEF/sendMessage", listOf("123:ABCDEF")))
        assertEquals("a ${Redactor.MASK} b ${Redactor.MASK}", Redactor.mask("a longer-secret b secret", listOf("secret", "longer-secret")))
        assertEquals("keep it", Redactor.mask("keep it", listOf("ab"))) // too short to be a safe match
    }

    @Test
    fun headersAndTextAreRedactedByShape() {
        assertEquals(Redactor.MASK, Redactor.header("Authorization", "Bearer abc"))
        assertEquals(Redactor.MASK, Redactor.header("x-api-key", "abc"))
        assertEquals("application/json", Redactor.header("Content-Type", "application/json"))
        assertEquals("failed: Bearer ${Redactor.MASK} and password=${Redactor.MASK}", Redactor.text("failed: Bearer abc123xyz and password=hunter2"))
        assertTrue(Redactor.isSensitiveName("X-Auth-Token"))
        assertFalse(Redactor.isSensitiveName("monkey"))
    }
}

class CurlTest {
    @Test
    fun commandsAreSplitLikeAShell() {
        assertEquals(listOf("curl", "-H", "A: b c", "--data", "{\"x\":1}", "http://u"), Curl.tokenize("curl -H 'A: b c' --data \"{\\\"x\\\":1}\" http://u"))
        assertEquals(listOf("curl", "-X", "POST", "http://u"), Curl.tokenize("curl \\\n -X POST \\\n http://u"))
    }

    @Test
    fun aTypicalCommandBecomesARequest() {
        val r = Curl.parse("""curl -X POST https://api.example.com/v1/items -H 'Authorization: Bearer tok' -H "Content-Type: application/json" -d '{"name":"a"}' -L""")
        assertEquals("POST", r.method)
        assertEquals("https://api.example.com/v1/items", r.url)
        assertEquals(listOf("Authorization" to "Bearer tok", "Content-Type" to "application/json"), r.headers)
        assertEquals("""{"name":"a"}""", r.body)
        assertTrue(r.followRedirects)
    }

    @Test
    fun methodsAndOptionsAreInferred() {
        assertEquals("GET", Curl.parse("curl https://a.com").method)
        assertEquals("POST", Curl.parse("curl https://a.com -d x=1").method)
        assertEquals("HEAD", Curl.parse("curl -I https://a.com").method)
        val json = Curl.parse("""curl --json '{"a":1}' https://a.com""")
        assertEquals("application/json", json.headers.first { it.first == "Content-Type" }.second)
        val basic = Curl.parse("curl -u bob:pw https://a.com")
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("bob:pw".toByteArray()), basic.headers.single().second)
        assertFalse(Curl.parse("curl https://a.com").followRedirects)
        assertEquals(listOf("Content-Type" to "application/x-www-form-urlencoded"), Curl.parse("curl -d a=1 https://a.com").headers)
    }

    @Test
    fun badCommandsExplainThemselves() {
        fun message(c: String) = try {
            Curl.parse(c)
            "no error"
        } catch (e: IllegalArgumentException) {
            e.message!!
        }
        assertTrue(message("wget https://a.com").contains("start with \"curl\""))
        assertTrue(message("curl -X").contains("needs a value"))
        assertTrue(message("curl --weird https://a.com").contains("can't import the option"))
        assertTrue(message("curl -s").contains("no address"))
        assertTrue(message("curl -H nocolon https://a.com").contains("name and a colon"))
    }

    @Test
    fun copyAsCurlHidesSecretsAndRoundTripsWhenAsked() {
        val spec = HttpRequestSpec("POST", "https://api.x.com/v1?key=SECRET1&q=1", listOf("Authorization" to "Bearer TOKEN9", "Content-Type" to "application/json"), """{"t":"TOKEN9"}""", followRedirects = true)
        val safe = Curl.render(spec, redact = true, secrets = listOf("TOKEN9", "SECRET1"))
        assertFalse(safe.contains("TOKEN9") || safe.contains("SECRET1"))
        assertTrue(safe.contains(Redactor.MASK))
        val full = Curl.render(spec, redact = false)
        val back = Curl.parse(full)
        assertEquals(spec.method, back.method)
        assertEquals(spec.url, back.url)
        assertEquals(spec.headers, back.headers)
        assertEquals(spec.body, back.body)
        assertTrue(back.followRedirects)
    }

    @Test
    fun quotesInValuesSurviveTheRoundTrip() {
        val spec = HttpRequestSpec("POST", "https://a.com", listOf("X-Note" to "it's fine"), "say 'hi'")
        val back = Curl.parse(Curl.render(spec, redact = false))
        assertEquals("it's fine", back.headers.single().second)
        assertEquals("say 'hi'", back.body)
    }
}

class VaultTest {
    private val cipher = AesGcmCipher(ByteArray(32) { it.toByte() })

    @Test
    fun valuesAreStoredEncryptedAndReadBack() {
        val v = SecretVault(cipher)
        val file = v.put("telegram", "123:SECRET-TOKEN")
        assertEquals("123:SECRET-TOKEN", v.get("telegram"))
        assertEquals(listOf("telegram"), v.names())
        assertFalse(file.entries.values.single().contains("SECRET"))
        assertFalse(java.util.Base64.getDecoder().decode(file.entries.values.single()).toString(Charsets.UTF_8).contains("SECRET"))
        assertEquals(listOf("123:SECRET-TOKEN"), v.values())
        v.remove("telegram")
        assertNull(v.get("telegram"))
        assertFalse(v.has("telegram"))
    }

    @Test
    fun theSameValueEncryptsDifferentlyEachTime() {
        val a = AesGcmCipher(ByteArray(32) { 7 }).encrypt("x".toByteArray())
        val b = AesGcmCipher(ByteArray(32) { 7 }).encrypt("x".toByteArray())
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun aVaultFromAnotherDeviceOrDamagedEntryReadsAsMissing() {
        val file = SecretVault(cipher).put("k", "v")
        val other = SecretVault(AesGcmCipher(ByteArray(32) { 9 }), file)
        assertNull(other.get("k"))
        assertNull(SecretVault(cipher, VaultFile(entries = mapOf("k" to "not-base64!!"))).get("k"))
    }

    @Test
    fun namesAreValidated() {
        val v = SecretVault(cipher)
        for (bad in listOf("", "has space", "a/b", "x".repeat(65))) {
            try {
                v.put(bad, "x")
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("secret name"))
            }
        }
        v.put("api-key_1.prod", "x")
    }

    @Test
    fun theKeyMustBeThirtyTwoBytes() {
        try {
            AesGcmCipher(ByteArray(16))
            fail("accepted a short key")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("32"))
        }
    }

    @Test
    fun passphraseExportRoundTripsAndFailsCleanly() {
        val secrets = mapOf("a" to "one:two", "b" to "line\nbreak ünïcode")
        val sealed = PassphraseExport.seal(secrets, "correct horse".toCharArray(), iterations = 1_000)
        assertFalse(sealed.contains("one:two"))
        assertEquals(secrets, PassphraseExport.open(sealed, "correct horse".toCharArray()))
        for (bad in listOf("wrong passphrase", "correct horsf")) {
            try {
                PassphraseExport.open(sealed, bad.toCharArray())
                fail("opened with $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.startsWith("Wrong passphrase"))
            }
        }
        try {
            PassphraseExport.open("garbage", "correct horse".toCharArray())
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("isn't an Androx secrets export"))
        }
        try {
            PassphraseExport.seal(secrets, "short".toCharArray(), iterations = 1_000)
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("8 characters"))
        }
    }
}

class RequestLogTest {
    private fun e(at: Long, status: Int = 200, millis: Long = 5, error: String = "") = RequestLogEntry(at, "GET", "https://a.com", status, millis, 1, "f", "Flow", error)

    @Test
    fun newestFirstCappedAndExpired() {
        var f = RequestLogFile()
        val day = 24L * 60 * 60 * 1000
        f = RequestLogs.add(f, e(0), 14)
        f = RequestLogs.add(f, e(10 * day), 14)
        f = RequestLogs.add(f, e(20 * day), 14)
        assertEquals(listOf(20 * day, 10 * day), f.entries.map { it.at }) // the first is older than 14 days
        var g = RequestLogFile()
        for (i in 1..520) g = RequestLogs.add(g, e(i.toLong()), 0)
        assertEquals(RequestLogs.CAP, g.entries.size)
        assertEquals(520L, g.entries.first().at)
    }

    @Test
    fun onlyRequestsThatLeftThePhoneCountTowardTheCap() {
        val now = 10_000_000L
        val entries = listOf(
            e(now - 10), // sent
            e(now - 20, status = 0, millis = 0, error = "blocked"), // refused: never left the phone
            e(now - 30, status = 0, millis = 40, error = "timeout"), // left the phone, no answer
            e(now - 3_700_000L), // too old
        )
        assertEquals(2, RequestLogs.sentLastHour(entries, now))
    }
}

class GateTest {
    private class FakeHttp : HttpPort {
        val requests = mutableListOf<HttpRequestSpec>()
        val options = mutableListOf<HttpOptions>()
        var next: () -> HttpResponse = { HttpResponse(200, body = "ok") }
        override suspend fun execute(request: HttpRequestSpec, options: HttpOptions): HttpResponse {
            requests += request
            this.options += options
            return next()
        }
    }

    private class FakeProbe : NetworkProbe {
        val calls = mutableListOf<String>()
        var tcpResult: Long? = 12
        override suspend fun dns(host: String): List<String> = listOf("1.2.3.4").also { calls += "dns:$host" }
        override suspend fun tcp(host: String, port: Int, timeoutMs: Int): Long? = tcpResult.also { calls += "tcp:$host:$port" }
        override suspend fun wakeOnLan(mac: String, broadcast: String, port: Int) {
            calls += "wol:$mac"
        }
    }

    private var settings = InternetSettings(mode = InternetMode.ON)
    private var wifi = true
    private var now = 5_000_000L
    private val logs = mutableListOf<RequestLogEntry>()
    private val asked = mutableListOf<String>()
    private val http = FakeHttp()
    private val probe = FakeProbe()
    private val gate = NetworkGate({ settings }, { wifi }, http, probe, { logs }, { logs.add(0, it) }, { h, f -> asked += "$h|$f" }, { now })
    private val cx = NetContext("f1", "My Flow")

    @Test
    fun anAllowedRequestIsSentAndLoggedWithoutSecrets() = runTest {
        val r = gate.send(HttpRequestSpec("GET", "https://api.x.com/v1?token=SECRET9&q=1"), cx.copy(secrets = listOf("SECRET9")))
        assertEquals(200, r.status)
        val line = logs.single()
        assertEquals("https://api.x.com/v1?token=${Redactor.MASK}&q=1", line.url)
        assertEquals(200, line.status)
        assertEquals("My Flow", line.flowName)
        assertEquals("", line.error)
    }

    @Test
    fun aRefusedRequestNeverReachesTheNetworkAndIsLogged() = runTest {
        settings = InternetSettings() // Off
        try {
            gate.send(HttpRequestSpec("GET", "https://a.com"), cx)
            fail("sent while off")
        } catch (e: NetDeniedException) {
            assertTrue(e.message!!.contains("turned off"))
        }
        assertTrue(http.requests.isEmpty())
        assertEquals(0, logs.single().status)
        assertTrue(logs.single().error.contains("turned off"))
    }

    @Test
    fun askModeNotifiesAndRefusesThisRun() = runTest {
        settings = settings.copy(mode = InternetMode.ASK)
        try {
            gate.send(HttpRequestSpec("GET", "https://new.example.org/x"), cx)
            fail("sent without asking")
        } catch (e: NetDeniedException) {
            assertTrue(e.message!!.contains("needs your OK before contacting new.example.org"))
        }
        assertEquals(listOf("new.example.org|My Flow"), asked)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun theKillSwitchTakesEffectOnTheVeryNextRequest() = runTest {
        gate.send(HttpRequestSpec("GET", "https://a.com"), cx)
        settings = settings.copy(killSwitch = true)
        try {
            gate.send(HttpRequestSpec("GET", "https://a.com"), cx)
            fail()
        } catch (e: NetDeniedException) {
            assertTrue(e.message!!.contains("Block internet"))
        }
        assertEquals(1, http.requests.size)
    }

    @Test
    fun theHourlyCapCountsWhatWasActuallySent() = runTest {
        settings = settings.copy(hourlyCap = 3)
        repeat(3) { gate.send(HttpRequestSpec("GET", "https://a.com"), cx); now += 1_000 }
        try {
            gate.send(HttpRequestSpec("GET", "https://a.com"), cx)
            fail("cap ignored")
        } catch (e: NetDeniedException) {
            assertTrue(e.message!!.contains("3 requests per hour"))
        }
        now += 3_700_000L // an hour later the window is clear again
        assertEquals(200, gate.send(HttpRequestSpec("GET", "https://a.com"), cx).status)
    }

    @Test
    fun networkFailuresAreLoggedAndTheirMessagesRedacted() = runTest {
        http.next = { throw NetException("failed for https://a.com/?token=SECRET9") }
        try {
            gate.send(HttpRequestSpec("GET", "https://a.com/?token=SECRET9"), cx.copy(secrets = listOf("SECRET9")))
            fail()
        } catch (e: NetException) {
            assertFalse(e.message!!.contains("SECRET9"))
            assertFalse(e is NetDeniedException)
        }
        assertFalse(logs.single().error.contains("SECRET9"))
        assertFalse(logs.single().url.contains("SECRET9"))
    }

    @Test
    fun localDevicesGetTheirExceptionsAndTheSizeCapComesFromSettings() = runTest {
        settings = settings.copy(lanHosts = listOf("192.168.1.5"), maxResponseKb = 64)
        gate.send(HttpRequestSpec("GET", "http://192.168.1.5:8123/api"), cx)
        gate.send(HttpRequestSpec("GET", "https://example.com"), cx)
        assertEquals(HttpOptions(64 * 1024, allowHttp = true, trustSelfSigned = true), http.options[0])
        assertEquals(HttpOptions(64 * 1024, allowHttp = false, trustSelfSigned = false), http.options[1])
    }

    @Test
    fun dnsTcpAndWakeOnLanAreGatedAndLogged() = runTest {
        assertEquals(listOf("1.2.3.4"), gate.dns("example.com", cx))
        assertEquals(12L, gate.tcp("example.com", 443, 1000, cx))
        probe.tcpResult = null
        assertNull(gate.tcp("example.com", 81, 1000, cx))
        gate.wakeOnLan("AA:BB:CC:DD:EE:FF", "255.255.255.255", 9, cx)
        assertEquals(listOf("dns:example.com", "tcp:example.com:443", "tcp:example.com:81", "wol:AA:BB:CC:DD:EE:FF"), probe.calls)
        assertEquals(4, logs.size)

        settings = settings.copy(killSwitch = true)
        for (blocked in listOf<suspend () -> Unit>({ gate.dns("example.com", cx) }, { gate.tcp("example.com", 80, 100, cx) }, { gate.wakeOnLan("AA:BB:CC:DD:EE:FF", "255.255.255.255", 9, cx) })) {
            try {
                blocked()
                fail("a probe got through the kill switch")
            } catch (e: NetDeniedException) {
                assertTrue(e.message!!.contains("Block internet"))
            }
        }
        assertEquals(4, probe.calls.size)
    }
}
