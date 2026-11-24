package com.androx.app.core.net

/** Who is asking, for the policy, the request log and the redaction of this run's secrets. */
data class NetContext(
    val flowId: String = "",
    val flowName: String = "",
    val flowInternet: FlowInternet? = null,
    /** Secret values this run used, so they can be hidden wherever they might show up. */
    val secrets: Collection<String> = emptyList(),
)

/**
 * Every request, every ping and every lookup goes through here (spec §14.9). It applies the [InternetPolicy], counts
 * the hourly cap, sends the request through the [HttpPort], and writes one log line without secrets. Nothing else in
 * Androx may talk to the network, which is what makes "Block internet" and the privacy promises true.
 */
class NetworkGate(
    private val settings: () -> InternetSettings,
    private val onWifi: () -> Boolean,
    private val http: HttpPort,
    private val probe: NetworkProbe,
    private val recent: () -> List<RequestLogEntry>,
    private val log: (RequestLogEntry) -> Unit,
    private val onAsk: (host: String, flowName: String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private fun decide(url: String, cx: NetContext): NetDecision.Allow {
        val s = settings()
        val info = NetRequestInfo(url, cx.flowInternet, onWifi())
        return when (val d = InternetPolicy.decide(s, info, RequestLogs.sentLastHour(recent(), clock()))) {
            is NetDecision.Allow -> d
            is NetDecision.Deny -> throw NetDeniedException(d.reason)
            is NetDecision.Ask -> {
                onAsk(d.host, cx.flowName)
                throw NetDeniedException("Androx needs your OK before contacting ${d.host}. Open the notification to allow or block it, then run this again.")
            }
        }
    }

    private fun record(method: String, url: String, cx: NetContext, status: Int, millis: Long, bytes: Int, error: String) {
        log(
            RequestLogEntry(
                at = clock(), method = method, url = Redactor.url(url, cx.secrets), status = status, millis = millis, bytes = bytes,
                flowId = cx.flowId, flowName = cx.flowName, error = Redactor.text(error, cx.secrets),
            ),
        )
    }

    /** Sends [request] if the rules allow it. Throws [NetException] (already free of secrets) when it can't. */
    suspend fun send(request: HttpRequestSpec, cx: NetContext): HttpResponse {
        val allow = try {
            decide(request.url, cx)
        } catch (e: NetDeniedException) {
            record(request.method, request.url, cx, 0, 0, 0, e.message.orEmpty())
            throw e
        }
        val started = clock()
        val options = HttpOptions(settings().maxResponseKb.coerceIn(1, 20_480) * 1024, allow.allowHttp, allow.trustSelfSigned)
        try {
            val response = http.execute(request, options)
            record(request.method, request.url, cx, response.status, clock() - started, response.body.length, "")
            return response
        } catch (e: NetException) {
            record(request.method, request.url, cx, 0, (clock() - started).coerceAtLeast(1), 0, e.message.orEmpty())
            throw NetException(Redactor.text(e.message.orEmpty(), cx.secrets))
        }
    }

    private fun guardHost(host: String, cx: NetContext, what: String): String {
        val url = "https://$host/"
        try {
            decide(url, cx.copy(flowInternet = cx.flowInternet))
        } catch (e: NetDeniedException) {
            record(what, url, cx, 0, 0, 0, e.message.orEmpty())
            throw e
        }
        return url
    }

    suspend fun dns(host: String, cx: NetContext): List<String> {
        val url = guardHost(host, cx, "DNS")
        val started = clock()
        return try {
            probe.dns(host).also { record("DNS", url, cx, 200, (clock() - started).coerceAtLeast(1), it.size, "") }
        } catch (e: NetException) {
            record("DNS", url, cx, 0, (clock() - started).coerceAtLeast(1), 0, e.message.orEmpty())
            throw e
        }
    }

    /** Milliseconds to connect, or null when the port didn't answer. */
    suspend fun tcp(host: String, port: Int, timeoutMs: Int, cx: NetContext): Long? {
        val url = guardHost(host, cx, "TCP")
        val started = clock()
        val ms = probe.tcp(host, port, timeoutMs)
        record("TCP", "$url:$port", cx, if (ms != null) 200 else 0, (clock() - started).coerceAtLeast(1), 0, if (ms == null) "no answer" else "")
        return ms
    }

    /** Wake-on-LAN only ever talks to the local network, so it needs the global switch on but no domain rules. */
    suspend fun wakeOnLan(mac: String, broadcast: String, port: Int, cx: NetContext) {
        val s = settings()
        if (s.killSwitch) throw NetDeniedException("Internet is blocked (the \"Block internet\" switch is on).")
        if (s.mode == InternetMode.OFF) throw NetDeniedException("Internet access is turned off. Turn it on in Settings → Internet.")
        if (cx.flowInternet?.mode == FlowInternetMode.BLOCK) throw NetDeniedException("This Flow isn't allowed to use the internet.")
        probe.wakeOnLan(mac, broadcast, port)
        record("WOL", "udp://$broadcast:$port", cx, 200, 1, 0, "")
    }
}
