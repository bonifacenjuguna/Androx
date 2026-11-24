package com.androx.app.core.spec

import com.androx.app.core.OpIds
import com.androx.app.core.TriggerIds
import com.androx.app.core.bool
import com.androx.app.core.int
import com.androx.app.core.net.AiProviders
import com.androx.app.core.str
import com.androx.app.model.MIN_INTERVAL_MINUTES

/** The internet package's actions and trigger (spec §14). All need the global internet switch to be on. */
object InternetSpecs {
    private val secretHelp = "The name of a secret in your vault. Never type the key itself here."
    private val none = Choice("none", "None")

    private fun net(
        id: String, name: String, description: String, icon: String, synonyms: List<String>, params: List<ParamSpec>,
        outputs: List<OutputSpec> = emptyList(), summary: (kotlinx.serialization.json.JsonObject) -> String,
        validate: (kotlinx.serialization.json.JsonObject) -> List<String> = { emptyList() },
    ) = Spec(
        kind = SpecKind.ACTION, id = id, category = SpecCategory.CONNECTIVITY, name = name, description = description, iconKey = icon,
        synonyms = synonyms, params = params, reliability = Reliability.GOOD, batteryCost = BatteryCost.LOW, outputs = outputs,
        summary = summary, validate = validate,
    )

    private fun message(
        id: String, name: String, description: String, icon: String, synonyms: List<String>, params: List<ParamSpec>, summary: (kotlinx.serialization.json.JsonObject) -> String,
    ) = net(id, name, description, icon, synonyms, params, listOf(OutputSpec("result", "HTTP status", "number")), summary)

    fun actions(): List<Spec> = listOf(
        net(
            OpIds.HTTP, "HTTP request", "Calls a web address and keeps the answer: {{http.status}}, {{http.body}}, {{http.json.some.path}}.",
            "web", listOf("api", "webhook", "get", "post", "request", "rest", "curl"),
            listOf(
                ParamSpec.Pick("method", "Method", listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD").map { Choice(it, it) }, "GET"),
                ParamSpec.Text("url", "Address", required = true, help = "https://…"),
                ParamSpec.Text("headers", "Headers", multiline = true, help = "One per line: Name: value. Use {{secret.name}} for keys."),
                ParamSpec.Pick("bodyType", "Body", listOf(none, Choice("json", "JSON"), Choice("form", "Form fields"), Choice("raw", "Plain text")), "none"),
                ParamSpec.Text("body", "Body text", multiline = true, help = "For a form, one field per line: name=value."),
                ParamSpec.Pick("authType", "Sign in with", listOf(none, Choice("bearer", "Bearer token"), Choice("basic", "Username and password"), Choice("apikey", "API key header")), "none"),
                ParamSpec.Text("authSecret", "Secret", templated = false, help = secretHelp),
                ParamSpec.Text("authUser", "Username", help = "For username and password."),
                ParamSpec.Text("authHeader", "Header name", default = "X-API-Key", templated = false, help = "For an API key header."),
                ParamSpec.Num("timeoutSeconds", "Give up after", default = 20, min = 1, max = 120, unit = "s"),
                ParamSpec.Toggle("followRedirects", "Follow redirects", default = true),
                ParamSpec.Toggle("failOnError", "Fail the step on a server error (not 2xx)", default = true),
                ParamSpec.Text("jsonPath", "Pick a value from the JSON answer", templated = false, help = "For example data.items.0.name. Empty keeps the whole answer."),
            ),
            outputs = listOf(OutputSpec("http.status", "Status code", "number"), OutputSpec("http.ok", "Was it 2xx", "boolean"), OutputSpec("http.body", "Answer text"), OutputSpec("http.json", "Answer as JSON"), OutputSpec("result", "The picked value or the answer")),
            summary = { p -> "${p.str("method", "GET")} ${p.str("url")}" },
            validate = { p ->
                val problems = ArrayList<String>()
                if (p.str("authType", "none") != "none" && p.str("authSecret").isBlank()) problems += "Choose the secret to sign in with."
                if (p.str("authType") == "basic" && p.str("authUser").isBlank()) problems += "Enter the username."
                if (p.str("bodyType", "none") == "none" && p.str("method", "GET") in setOf("GET", "HEAD") && p.str("body").isNotBlank()) problems += "A GET request can't have a body."
                problems
            },
        ),
        net(
            OpIds.SITE_UP, "Check if a site is up", "Asks a site if it answers. Gives true or false.", "lan_check", listOf("ping", "online", "down", "status", "monitor"),
            listOf(ParamSpec.Text("url", "Address", required = true), ParamSpec.Num("timeoutSeconds", "Wait up to", default = 10, min = 1, max = 60, unit = "s")),
            listOf(OutputSpec("site.up", "Is it up", "boolean"), OutputSpec("site.status", "Status code", "number")),
            summary = { p -> p.str("url") },
        ),
        net(
            OpIds.PUBLIC_IP, "Get public IP address", "Finds the address the internet sees you as.", "ip", listOf("my ip", "external ip", "wan"),
            listOf(ParamSpec.Pick("service", "Ask", listOf(Choice("ipify", "ipify.org"), Choice("icanhazip", "icanhazip.com")), "ipify")),
            listOf(OutputSpec("ip.public", "The address")),
            summary = { p -> "via ${p.str("service", "ipify")}" },
        ),
        net(
            OpIds.DNS_LOOKUP, "DNS lookup", "Turns a host name into its addresses.", "dns", listOf("resolve", "domain", "nslookup"),
            listOf(ParamSpec.Text("host", "Host name", required = true)), listOf(OutputSpec("dns.first", "First address")),
            summary = { p -> p.str("host") },
        ),
        net(
            OpIds.TCP_CHECK, "Check a port", "Checks whether something answers on a port, and how fast.", "network", listOf("port", "tcp", "service", "reachable", "ping"),
            listOf(
                ParamSpec.Text("host", "Host", required = true),
                ParamSpec.Num("port", "Port", default = 80, min = 1, max = 65_535),
                ParamSpec.Num("timeoutMs", "Wait up to", default = 3_000, min = 200, max = 30_000, unit = "ms"),
            ),
            listOf(OutputSpec("tcp.ms", "Milliseconds, -1 if none", "number")),
            summary = { p -> "${p.str("host")}:${p.int("port", 80)}" },
        ),
        net(
            OpIds.WAKE_ON_LAN, "Wake on LAN", "Wakes a computer or NAS on your network.", "power_settings", listOf("wol", "magic packet", "boot pc", "wake pc"),
            listOf(
                ParamSpec.Text("mac", "MAC address", required = true, templated = false, help = "AA:BB:CC:DD:EE:FF"),
                ParamSpec.Text("broadcast", "Broadcast address", default = "255.255.255.255", templated = false),
                ParamSpec.Num("port", "Port", default = 9, min = 1, max = 65_535),
            ),
            summary = { p -> p.str("mac") },
            validate = { p -> if (Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$").matches(p.str("mac").trim()) || p.str("mac").contains("{{")) emptyList() else listOf("A MAC address looks like AA:BB:CC:DD:EE:FF.") },
        ),
        net(
            OpIds.WEATHER, "Weather", "Current weather from Open-Meteo (no key needed). Gives temperature, humidity, wind and a description.", "weather_partly_cloudy",
            listOf("forecast", "temperature", "rain", "wind"),
            listOf(
                ParamSpec.Text("latitude", "Latitude", required = true, help = "For example 48.8566"),
                ParamSpec.Text("longitude", "Longitude", required = true, help = "For example 2.3522"),
                ParamSpec.Pick("units", "Units", listOf(Choice("metric", "°C and km/h"), Choice("imperial", "°F and mph")), "metric"),
            ),
            listOf(OutputSpec("weather.temp", "Temperature", "number"), OutputSpec("weather.description", "In words"), OutputSpec("result", "All values")),
            summary = { p -> "At ${p.str("latitude")}, ${p.str("longitude")}" },
        ),
        net(
            OpIds.ASK_AI, "Ask AI", "Sends a question to OpenAI, Anthropic, Gemini or a local Ollama, with your own key from the vault.", "creation",
            listOf("chatgpt", "claude", "gemini", "llm", "gpt", "summarise", "assistant"),
            listOf(
                ParamSpec.Pick("provider", "Provider", AiProviders.providers.map { Choice(it, when (it) { "openai" -> "OpenAI"; "anthropic" -> "Anthropic"; "gemini" -> "Google Gemini"; else -> "Ollama (local)" }) }, "openai"),
                ParamSpec.Text("model", "Model", templated = false, help = "Leave empty for the provider's usual small model."),
                ParamSpec.Text("prompt", "Question", required = true, multiline = true),
                ParamSpec.Text("system", "Instructions", multiline = true, help = "Optional. How the AI should answer."),
                ParamSpec.Text("secret", "Key", templated = false, help = secretHelp),
                ParamSpec.Text("baseUrl", "Server address", templated = false, help = "For Ollama or an OpenAI-compatible server."),
                ParamSpec.Num("maxTokens", "Longest answer", default = 512, min = 16, max = 8_192, unit = "tokens"),
            ),
            listOf(OutputSpec("result", "The answer")),
            summary = { p -> "${p.str("provider", "openai")}: ${p.str("prompt").take(40)}" },
            validate = { p -> if (p.str("provider", "openai") != "ollama" && p.str("secret").isBlank()) listOf("Choose the secret that holds your key.") else emptyList() },
        ),
        message(
            OpIds.DISCORD, "Discord message", "Posts to a Discord channel through its webhook.", "chat", listOf("webhook", "notify"),
            listOf(ParamSpec.Text("webhook", "Webhook address", required = true, help = "Save it as a secret and write {{secret.name}}."), ParamSpec.Text("text", "Message", required = true, multiline = true), ParamSpec.Text("username", "Name shown")),
            summary = { p -> p.str("text") },
        ),
        message(
            OpIds.SLACK, "Slack message", "Posts to a Slack channel through its webhook.", "slack", listOf("webhook", "notify"),
            listOf(ParamSpec.Text("webhook", "Webhook address", required = true, help = "Save it as a secret and write {{secret.name}}."), ParamSpec.Text("text", "Message", required = true, multiline = true)),
            summary = { p -> p.str("text") },
        ),
        message(
            OpIds.TELEGRAM, "Telegram message", "Sends a message from your Telegram bot.", "send", listOf("bot", "notify", "chat"),
            listOf(
                ParamSpec.Text("token", "Bot token", required = true, help = "Write {{secret.name}}."),
                ParamSpec.Text("chatId", "Chat id", required = true),
                ParamSpec.Text("text", "Message", required = true, multiline = true),
                ParamSpec.Pick("parseMode", "Format", listOf(none, Choice("HTML", "HTML"), Choice("MarkdownV2", "Markdown")), "none"),
            ),
            summary = { p -> p.str("text") },
        ),
        message(
            OpIds.NTFY, "ntfy message", "Sends a push notification with ntfy.", "bell_ring", listOf("push", "notify", "topic"),
            listOf(
                ParamSpec.Text("server", "Server", default = "https://ntfy.sh", templated = false),
                ParamSpec.Text("topic", "Topic", required = true, templated = false),
                ParamSpec.Text("text", "Message", required = true, multiline = true),
                ParamSpec.Text("title", "Title"),
                ParamSpec.Pick("priority", "Priority", listOf("1", "2", "3", "4", "5").map { Choice(it, it) }, "3"),
                ParamSpec.Text("tags", "Tags", templated = false, help = "Comma separated, for example warning,skull"),
                ParamSpec.Text("token", "Access token", help = "Only for protected topics. Write {{secret.name}}."),
            ),
            summary = { p -> "${p.str("topic")}: ${p.str("text")}" },
        ),
        message(
            OpIds.GOTIFY, "Gotify message", "Sends a push notification to your Gotify server.", "bell", listOf("push", "notify", "self hosted"),
            listOf(
                ParamSpec.Text("server", "Server", required = true, templated = false),
                ParamSpec.Text("token", "App token", required = true, help = "Write {{secret.name}}."),
                ParamSpec.Text("text", "Message", required = true, multiline = true),
                ParamSpec.Text("title", "Title"),
                ParamSpec.Num("priority", "Priority", default = 5, min = 0, max = 10),
            ),
            summary = { p -> p.str("text") },
        ),
        message(
            OpIds.PUSHOVER, "Pushover message", "Sends a push notification with Pushover.", "bell_badge", listOf("push", "notify"),
            listOf(
                ParamSpec.Text("token", "Application token", required = true, help = "Write {{secret.name}}."),
                ParamSpec.Text("user", "User key", required = true, help = "Write {{secret.name}}."),
                ParamSpec.Text("text", "Message", required = true, multiline = true),
                ParamSpec.Text("title", "Title"),
                ParamSpec.Num("priority", "Priority", default = 0, min = -2, max = 2),
            ),
            summary = { p -> p.str("text") },
        ),
        message(
            OpIds.IFTTT, "IFTTT trigger", "Triggers an IFTTT applet through its webhook.", "lightning_bolt", listOf("applet", "maker", "webhook"),
            listOf(
                ParamSpec.Text("key", "Webhook key", required = true, help = "Write {{secret.name}}."),
                ParamSpec.Text("event", "Event name", required = true, templated = false),
                ParamSpec.Text("value1", "Value 1"), ParamSpec.Text("value2", "Value 2"), ParamSpec.Text("value3", "Value 3"),
            ),
            summary = { p -> p.str("event") },
        ),
        message(
            OpIds.HOME_ASSISTANT, "Home Assistant", "Calls a Home Assistant service, such as light.turn_on.", "home_assistant", listOf("lights", "smart home", "hass", "service"),
            listOf(
                ParamSpec.Text("server", "Address", required = true, templated = false, help = "For example http://homeassistant.local:8123. Add it under local devices in Settings → Internet."),
                ParamSpec.Text("token", "Access token", required = true, help = "A long-lived token. Write {{secret.name}}."),
                ParamSpec.Text("domain", "Domain", required = true, templated = false, help = "For example light"),
                ParamSpec.Text("service", "Service", required = true, templated = false, help = "For example turn_on"),
                ParamSpec.Text("entityId", "Device", help = "For example light.living_room"),
                ParamSpec.Text("data", "Extra data", multiline = true, help = "A JSON object, for example {\"brightness\": 120}."),
            ),
            summary = { p -> "${p.str("domain")}.${p.str("service")} ${p.str("entityId")}".trim() },
            ).let { it.copy(validate = { p -> if (p.str("data").isBlank() || p.str("data").contains("{{") || runCatching { com.androx.app.core.Value.parseJson(p.str("data")) }.isSuccess) emptyList() else listOf("The extra data must be valid JSON.") }) },
    )

    fun triggers(): List<Spec> = listOf(
        Spec(
            kind = SpecKind.TRIGGER, id = TriggerIds.POLL_URL, category = SpecCategory.CONNECTIVITY,
            name = "Web address changes", description = "Checks an address every few minutes and runs when the page, a JSON value or an RSS feed changes.",
            iconKey = "web_sync", synonyms = listOf("poll", "monitor", "rss", "feed", "website", "changed", "new post"),
            params = listOf(
                ParamSpec.Text("url", "Address", required = true, templated = false),
                ParamSpec.Num("minutes", "Check every", default = 30, min = MIN_INTERVAL_MINUTES, unit = "min"),
                ParamSpec.Pick("mode", "Watch", listOf(Choice("text", "The page text"), Choice("json", "A value in JSON"), Choice("rss", "New items in a feed")), "text"),
                ParamSpec.Text("jsonPath", "JSON path", templated = false, help = "For \"A value in JSON\", for example data.version."),
                ParamSpec.Text("authSecret", "Bearer token (optional)", templated = false, help = secretHelp),
            ),
            permissions = listOf(Perm.EXACT_ALARMS), reliability = Reliability.GOOD, batteryCost = BatteryCost.MEDIUM,
            outputs = listOf(OutputSpec("poll.value", "New value"), OutputSpec("poll.previous", "Old value"), OutputSpec("poll.title", "Feed item title"), OutputSpec("poll.link", "Feed item link"), OutputSpec("poll.url", "Address")),
            summary = { p -> "${p.str("url")} · every ${p.int("minutes", 30)} min" },
            validate = { p -> if (p.str("mode", "text") == "json" && p.str("jsonPath").isBlank()) listOf("Say which JSON value to watch.") else emptyList() },
        ),
    )
}
