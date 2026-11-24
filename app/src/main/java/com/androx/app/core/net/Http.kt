package com.androx.app.core.net

/** A request, fully filled in. Built by the HTTP actions, presets and cURL import, and sent through the [NetworkGate]. */
data class HttpRequestSpec(
    val method: String = "GET",
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: String? = null,
    val timeoutSeconds: Int = 20,
    val followRedirects: Boolean = true,
)

data class HttpResponse(
    val status: Int,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: String = "",
    /** True when the body was longer than the size cap and was cut off. */
    val truncated: Boolean = false,
    val millis: Long = 0L,
) {
    val ok: Boolean get() = status in 200..299
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

/** Per-request limits and exceptions the gate passes to the network layer. */
data class HttpOptions(
    val maxBytes: Int,
    val allowHttp: Boolean,
    val trustSelfSigned: Boolean,
)

/** The only thing that touches the network. The Android build uses OkHttp; tests use a fake. */
interface HttpPort {
    suspend fun execute(request: HttpRequestSpec, options: HttpOptions): HttpResponse
}

/** Anything that goes wrong with a request, in a sentence a person can act on. */
open class NetException(message: String) : Exception(message)

/** The rules refused the request (Off, blocked, Ask…). A failure to reach the server is a plain [NetException]. */
class NetDeniedException(message: String) : NetException(message)

/** DNS, TCP and Wake-on-LAN, also behind the gate. */
interface NetworkProbe {
    suspend fun dns(host: String): List<String>

    /** Milliseconds to connect, or null when it couldn't. */
    suspend fun tcp(host: String, port: Int, timeoutMs: Int): Long?

    /** Sends a Wake-on-LAN "magic packet" to the local network. */
    suspend fun wakeOnLan(mac: String, broadcast: String, port: Int)
}
