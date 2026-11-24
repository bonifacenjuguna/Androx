package com.androx.app.core.engine

import com.androx.app.core.OpIds
import com.androx.app.core.Value
import com.androx.app.core.bool
import com.androx.app.core.int
import com.androx.app.core.net.AiProviders
import com.androx.app.core.net.HttpPresets
import com.androx.app.core.net.HttpRequestSpec
import com.androx.app.core.net.HttpResponse
import com.androx.app.core.net.NetContext
import com.androx.app.core.net.NetDeniedException
import com.androx.app.core.net.NetException
import com.androx.app.core.net.NetworkServices
import com.androx.app.core.net.Redactor
import com.androx.app.core.net.WeatherCodes
import com.androx.app.core.path
import com.androx.app.core.str
import com.androx.app.engine.ActionException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.util.Base64

/** The actions that touch the network. They are described, not run, in a simulation. */
val INTERNET_OP_IDS: Set<String> = setOf(
    OpIds.HTTP, OpIds.SITE_UP, OpIds.PUBLIC_IP, OpIds.DNS_LOOKUP, OpIds.TCP_CHECK, OpIds.WAKE_ON_LAN, OpIds.WEATHER, OpIds.ASK_AI,
    OpIds.DISCORD, OpIds.SLACK, OpIds.TELEGRAM, OpIds.NTFY, OpIds.GOTIFY, OpIds.PUSHOVER, OpIds.IFTTT, OpIds.HOME_ASSISTANT,
)

private val presetIds = listOf(OpIds.DISCORD, OpIds.SLACK, OpIds.TELEGRAM, OpIds.NTFY, OpIds.GOTIFY, OpIds.PUSHOVER, OpIds.IFTTT, OpIds.HOME_ASSISTANT)
private val macPattern = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

/**
 * The internet actions (spec §14.6). Every request goes through the [com.androx.app.core.net.NetworkGate]; secrets are
 * read from the vault only here, only for the length of one request, and every line written to the run log is redacted.
 */
internal fun internetActions(net: NetworkServices?): Map<String, ActionExecutor> {
    fun svc(): NetworkServices = net ?: throw ActionException("Internet actions aren't available here.")

    fun secret(name: String, used: MutableSet<String>): String {
        val n = name.trim()
        if (n.isEmpty()) throw ActionException("Choose which secret to use.")
        val v = svc().secrets.get(n) ?: throw ActionException("There is no secret named \"$n\" in the vault. Add it in Settings → Security.")
        used += v
        return v
    }

    suspend fun send(env: ActionEnv, request: HttpRequestSpec, used: Set<String>): HttpResponse = try {
        svc().gate.send(request, NetContext(env.flowId, env.flowName, env.flowInternet, used))
    } catch (e: NetException) {
        throw ActionException(e.message ?: "The request failed.")
    }

    fun cx(env: ActionEnv, used: Set<String> = emptySet()) = NetContext(env.flowId, env.flowName, env.flowInternet, used)

    fun parsed(body: String): Value? {
        val t = body.trim()
        if (!(t.startsWith("{") || t.startsWith("["))) return null
        return try {
            Value.parseJson(t)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun failIfNotOk(typeId: String, r: HttpResponse) {
        if (r.ok) return
        val hint = HttpPresets.failureHint(typeId, r.status)
        throw ActionException("The server answered ${r.status}." + if (hint.isNotEmpty()) " $hint" else "")
    }

    val table = LinkedHashMap<String, ActionExecutor>()

    table[OpIds.HTTP] = ActionExecutor { p, env ->
        val used = LinkedHashSet<String>()
        fun r(t: String) = env.renderWithSecrets(t, used)
        val method = p.str("method", "GET").uppercase()
        val url = r(p.str("url")).trim()
        if (url.isEmpty()) throw ActionException("Give the address to contact.")
        val headers = ArrayList<Pair<String, String>>()
        for (line in r(p.str("headers")).lines()) {
            if (!line.contains(':')) continue
            headers += line.substringBefore(':').trim() to line.substringAfter(':').trim()
        }
        fun hasHeader(n: String) = headers.any { it.first.equals(n, ignoreCase = true) }
        val bodyText = r(p.str("body"))
        val body: String? = when (p.str("bodyType", "none")) {
            "json" -> {
                try {
                    Value.parseJson(bodyText)
                } catch (e: IllegalArgumentException) {
                    throw ActionException(e.message ?: "The body isn't valid JSON.")
                }
                if (!hasHeader("Content-Type")) headers += "Content-Type" to "application/json"
                bodyText
            }
            "form" -> {
                if (!hasHeader("Content-Type")) headers += "Content-Type" to "application/x-www-form-urlencoded"
                bodyText.lines().filter { it.contains('=') }.joinToString("&") {
                    URLEncoder.encode(it.substringBefore('=').trim(), "UTF-8") + "=" + URLEncoder.encode(it.substringAfter('=').trim(), "UTF-8")
                }
            }
            "raw" -> bodyText
            else -> null
        }
        when (p.str("authType", "none")) {
            "bearer" -> headers += "Authorization" to "Bearer " + secret(p.str("authSecret"), used)
            "basic" -> headers += "Authorization" to "Basic " + Base64.getEncoder().encodeToString("${r(p.str("authUser"))}:${secret(p.str("authSecret"), used)}".toByteArray())
            "apikey" -> headers += p.str("authHeader", "X-API-Key").ifBlank { "X-API-Key" } to secret(p.str("authSecret"), used)
        }
        val request = HttpRequestSpec(method, url, headers, body, p.int("timeoutSeconds", 20).coerceIn(1, 120), p.bool("followRedirects", true))
        val response = send(env, request, used)
        env.detail("$method ${Redactor.url(url, used)} → ${response.status}" + if (response.truncated) " (answer cut short)" else "")
        env.setLocal("http.status", Value.Num(response.status.toDouble()))
        env.setLocal("http.ok", Value.Bool(response.ok))
        env.setLocal("http.body", Value.Text(response.body))
        for ((n, v) in response.headers.take(30)) env.setLocal("http.header.${n.lowercase()}", Value.Text(v))
        val json = parsed(response.body)
        if (json != null) env.setLocal("http.json", json)
        if (p.bool("failOnError", true) && !response.ok) {
            val excerpt = Redactor.text(response.body.take(160), used).replace('\n', ' ')
            throw ActionException("The server answered ${response.status}." + if (excerpt.isNotBlank()) " It said: $excerpt" else "")
        }
        val jsonPath = p.str("jsonPath").trim()
        when {
            jsonPath.isNotEmpty() -> (json ?: throw ActionException("The answer isn't JSON, so \"$jsonPath\" can't be read from it.")).path(jsonPath)
                ?: throw ActionException("\"$jsonPath\" wasn't found in the answer.")
            json != null -> json
            else -> Value.Text(response.body)
        }
    }

    table[OpIds.SITE_UP] = ActionExecutor { p, env ->
        val url = env.render(p.str("url")).trim()
        if (url.isEmpty()) throw ActionException("Give the address to check.")
        val timeout = p.int("timeoutSeconds", 10).coerceIn(1, 60)
        var up = false
        var status = 0
        try {
            var r = svc().gate.send(HttpRequestSpec("HEAD", url, timeoutSeconds = timeout), cx(env))
            if (r.status == 405 || r.status == 501) r = svc().gate.send(HttpRequestSpec("GET", url, timeoutSeconds = timeout), cx(env))
            status = r.status
            up = r.status < 400
        } catch (e: NetDeniedException) {
            throw ActionException(e.message ?: "The request was refused.")
        } catch (e: NetException) {
            env.detail("no answer: ${e.message}")
        }
        env.setLocal("site.up", Value.Bool(up))
        env.setLocal("site.status", Value.Num(status.toDouble()))
        env.detail(if (up) "$url is up ($status)" else "$url is down")
        Value.Bool(up)
    }

    table[OpIds.PUBLIC_IP] = ActionExecutor { p, env ->
        val service = when (p.str("service", "ipify")) {
            "icanhazip" -> "https://icanhazip.com"
            else -> "https://api.ipify.org"
        }
        val r = send(env, HttpRequestSpec("GET", service, timeoutSeconds = 10), emptySet())
        failIfNotOk(OpIds.PUBLIC_IP, r)
        val ip = r.body.trim()
        if (!Regex("^[0-9a-fA-F:.]{3,45}$").matches(ip)) throw ActionException("The service didn't answer with an address.")
        env.setLocal("ip.public", Value.Text(ip))
        env.detail("public address $ip")
        Value.Text(ip)
    }

    table[OpIds.DNS_LOOKUP] = ActionExecutor { p, env ->
        val host = env.render(p.str("host")).trim()
        if (host.isEmpty()) throw ActionException("Give a host name to look up.")
        val found = try {
            svc().gate.dns(host, cx(env))
        } catch (e: NetException) {
            throw ActionException(e.message ?: "The lookup failed.")
        }
        env.setLocal("dns.first", Value.Text(found.firstOrNull().orEmpty()))
        env.detail("$host → ${found.joinToString(", ").ifEmpty { "nothing" }}")
        Value.Items(found.map { Value.Text(it) })
    }

    table[OpIds.TCP_CHECK] = ActionExecutor { p, env ->
        val host = env.render(p.str("host")).trim()
        val port = p.int("port", 80)
        if (host.isEmpty()) throw ActionException("Give the host to check.")
        if (port !in 1..65_535) throw ActionException("A port is a number from 1 to 65535.")
        val ms = try {
            svc().gate.tcp(host, port, p.int("timeoutMs", 3_000).coerceIn(200, 30_000), cx(env))
        } catch (e: NetException) {
            throw ActionException(e.message ?: "The check failed.")
        }
        env.setLocal("tcp.ms", Value.Num((ms ?: -1L).toDouble()))
        env.detail(if (ms != null) "$host:$port answered in $ms ms" else "$host:$port didn't answer")
        Value.Bool(ms != null)
    }

    table[OpIds.WAKE_ON_LAN] = ActionExecutor { p, env ->
        val mac = env.render(p.str("mac")).trim()
        if (!macPattern.matches(mac)) throw ActionException("A MAC address looks like AA:BB:CC:DD:EE:FF.")
        try {
            svc().gate.wakeOnLan(mac, p.str("broadcast", "255.255.255.255").ifBlank { "255.255.255.255" }, p.int("port", 9).coerceIn(1, 65_535), cx(env))
        } catch (e: NetException) {
            throw ActionException(e.message ?: "The packet couldn't be sent.")
        }
        env.detail("sent a wake-up packet to $mac")
        null
    }

    table[OpIds.WEATHER] = ActionExecutor { p, env ->
        val lat = env.render(p.str("latitude")).trim().toDoubleOrNull()
        val lon = env.render(p.str("longitude")).trim().toDoubleOrNull()
        if (lat == null || lat !in -90.0..90.0) throw ActionException("Latitude must be a number from -90 to 90.")
        if (lon == null || lon !in -180.0..180.0) throw ActionException("Longitude must be a number from -180 to 180.")
        val unit = if (p.str("units", "metric") == "imperial") "&temperature_unit=fahrenheit&wind_speed_unit=mph" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m$unit"
        val r = send(env, HttpRequestSpec("GET", url, timeoutSeconds = 15), emptySet())
        failIfNotOk(OpIds.WEATHER, r)
        val current = parsed(r.body)?.path("current") as? Value.Dict ?: throw ActionException("The weather service sent an answer Androx couldn't read.")
        val code = current.entries["weather_code"]?.asNumberOrNull()?.toInt() ?: -1
        val result = Value.Dict(
            mapOf(
                "temperature" to (current.entries["temperature_2m"] ?: Value.Text("")),
                "humidity" to (current.entries["relative_humidity_2m"] ?: Value.Text("")),
                "wind_speed" to (current.entries["wind_speed_10m"] ?: Value.Text("")),
                "weather_code" to Value.Num(code.toDouble()),
                "description" to Value.Text(WeatherCodes.describe(code)),
            ),
        )
        env.setLocal("weather.temp", result.entries.getValue("temperature"))
        env.setLocal("weather.description", result.entries.getValue("description"))
        env.detail("${result.entries.getValue("description").render()}, ${result.entries.getValue("temperature").render()}°")
        result
    }

    table[OpIds.ASK_AI] = ActionExecutor { p, env ->
        val used = LinkedHashSet<String>()
        val provider = p.str("provider", "openai")
        val key = if (provider == "ollama" && p.str("secret").isBlank()) "" else secret(p.str("secret"), used)
        val request = try {
            AiProviders.request(
                provider, p.str("model"), env.render(p.str("prompt")), env.render(p.str("system")), key,
                p.str("baseUrl"), p.int("maxTokens", 512).coerceIn(16, 8_192),
            )
        } catch (e: IllegalArgumentException) {
            throw ActionException(e.message ?: "That question can't be sent.")
        }
        val r = send(env, request, used)
        val answer = try {
            AiProviders.answer(provider, r.body)
        } catch (e: IllegalArgumentException) {
            throw ActionException((if (!r.ok) "The provider answered ${r.status}. " else "") + (e.message ?: ""))
        }
        env.detail("$provider answered (${answer.length} characters)")
        Value.Text(answer)
    }

    for (id in presetIds) {
        table[id] = ActionExecutor { p, env ->
            val used = LinkedHashSet<String>()
            val input = LinkedHashMap<String, String>()
            for ((k, v) in p) if (v is JsonPrimitive && v !is kotlinx.serialization.json.JsonNull) input[k] = env.renderWithSecrets(v.content, used)
            val request = try {
                HttpPresets.build(id, input)
            } catch (e: IllegalArgumentException) {
                throw ActionException(e.message ?: "That message can't be sent.")
            }
            val response = send(env, request, used)
            env.detail("${request.method} ${Redactor.url(request.url, used)} → ${response.status}")
            failIfNotOk(id, response)
            Value.Num(response.status.toDouble())
        }
    }
    return table
}
