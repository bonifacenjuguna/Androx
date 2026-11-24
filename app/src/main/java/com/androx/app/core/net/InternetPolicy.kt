package com.androx.app.core.net

import kotlinx.serialization.Serializable

/** The global switch (spec §14.1). Off until the person turns it on. */
@Serializable
enum class InternetMode { OFF, ASK, ON }

/** Per-Flow override: follow the global mode, always allow, or never allow. */
@Serializable
enum class FlowInternetMode { INHERIT, ALLOW, BLOCK }

@Serializable
data class FlowInternet(
    val mode: FlowInternetMode = FlowInternetMode.INHERIT,
    /** When not empty, this Flow may only contact these domains (and their subdomains). */
    val domains: List<String> = emptyList(),
)

@Serializable
data class InternetSettings(
    val mode: InternetMode = InternetMode.OFF,
    /** The one-tap "Block internet" switch: stops every request at once, whatever else is set. */
    val killSwitch: Boolean = false,
    val wifiOnly: Boolean = false,
    /** A hard limit so a loop can't spam a server. */
    val hourlyCap: Int = 120,
    val httpsOnly: Boolean = true,
    /** Domains allowed without asking ("Always"). Subdomains are included. */
    val allowedDomains: List<String> = emptyList(),
    val blockedDomains: List<String> = emptyList(),
    /** Local devices (Home Assistant, a NAS…) that may use plain http and a self-signed certificate. Nothing else may. */
    val lanHosts: List<String> = emptyList(),
    val logRetentionDays: Int = 14,
    /** Longest response body kept. Anything beyond is cut off. */
    val maxResponseKb: Int = 1_024,
)

sealed interface NetDecision {
    /** [allowHttp] and [trustSelfSigned] are true only for a host listed under "local devices". */
    data class Allow(val allowHttp: Boolean = false, val trustSelfSigned: Boolean = false) : NetDecision
    data class Deny(val reason: String) : NetDecision

    /** The global mode is Ask and this domain has never been answered. The caller denies this run and tells the person. */
    data class Ask(val host: String) : NetDecision
}

object Domains {
    fun normalize(host: String): String = host.trim().lowercase().trimEnd('.')

    /** "example.com" matches itself and every subdomain; "*.example.com" matches subdomains only. */
    fun matches(host: String, pattern: String): Boolean {
        val h = normalize(host)
        val p = normalize(pattern)
        if (p.isEmpty()) return false
        if (p.startsWith("*.")) return h.endsWith(p.substring(1)) && h.length > p.length - 1
        return h == p || h.endsWith(".$p")
    }

    fun matchesAny(host: String, patterns: List<String>): Boolean = patterns.any { matches(host, it) }

    fun hostOf(url: String): String? {
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return null
        var authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        authority = authority.substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']').removePrefix("[") else authority.substringBefore(':')
        return normalize(host).ifEmpty { null }
    }

    fun schemeOf(url: String): String = url.substringBefore("://", "").lowercase()

    /** A literal private or loopback address, or a plain local name ("nas", "printer.local"). */
    fun isLocal(host: String): Boolean {
        val h = normalize(host)
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        if (!h.contains('.') && !h.contains(':')) return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
        }
        return h == "::1" || h.startsWith("fc") && h.contains(':') || h.startsWith("fd") && h.contains(':') || h.startsWith("fe80")
    }
}

/** What the gate knows about one request when it asks for permission. */
data class NetRequestInfo(
    val url: String,
    val flowInternet: FlowInternet? = null,
    val onWifi: Boolean = true,
)

/**
 * The access rules, in order (spec §14.1). Pure, so every rule is unit tested:
 * kill switch → global Off → this Flow's Block → blocklist → Flow's domain list → Wi-Fi only → HTTPS only → hourly cap →
 * Ask. A Flow set to "Allow" skips the Ask step only; it never overrides Off, the kill switch or the guards.
 */
object InternetPolicy {
    fun decide(settings: InternetSettings, info: NetRequestInfo, requestsLastHour: Int): NetDecision {
        if (settings.killSwitch) return NetDecision.Deny("Internet is blocked (the \"Block internet\" switch is on).")
        if (settings.mode == InternetMode.OFF) return NetDecision.Deny("Internet access is turned off. Turn it on in Settings → Internet.")
        val host = Domains.hostOf(info.url) ?: return NetDecision.Deny("That isn't a valid web address.")
        val scheme = Domains.schemeOf(info.url)
        if (scheme != "http" && scheme != "https") return NetDecision.Deny("Only http and https addresses can be used.")
        val flow = info.flowInternet ?: FlowInternet()
        if (flow.mode == FlowInternetMode.BLOCK) return NetDecision.Deny("This Flow isn't allowed to use the internet.")
        if (Domains.matchesAny(host, settings.blockedDomains)) return NetDecision.Deny("$host is on your blocked list.")
        if (flow.domains.isNotEmpty() && !Domains.matchesAny(host, flow.domains)) {
            return NetDecision.Deny("This Flow may only contact ${flow.domains.joinToString(", ")}, not $host.")
        }
        if (settings.wifiOnly && !info.onWifi) return NetDecision.Deny("Wi-Fi only is on and the phone isn't on Wi-Fi.")
        val lan = Domains.matchesAny(host, settings.lanHosts)
        if (scheme == "http" && settings.httpsOnly && !lan) {
            return NetDecision.Deny("$host uses plain http. Only https is allowed, unless you add it under local devices.")
        }
        if (settings.hourlyCap > 0 && requestsLastHour >= settings.hourlyCap) {
            return NetDecision.Deny("The limit of ${settings.hourlyCap} requests per hour has been reached.")
        }
        if (settings.mode == InternetMode.ASK && flow.mode != FlowInternetMode.ALLOW && !Domains.matchesAny(host, settings.allowedDomains) && !lan) {
            return NetDecision.Ask(host)
        }
        return NetDecision.Allow(allowHttp = lan, trustSelfSigned = lan)
    }
}
