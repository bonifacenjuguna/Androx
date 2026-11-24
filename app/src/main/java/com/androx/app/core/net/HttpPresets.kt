package com.androx.app.core.net

import com.androx.app.core.OpIds
import com.androx.app.core.Value
import com.androx.app.core.path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * One-tap services (spec §14.3). Each preset turns a few friendly settings into a complete [HttpRequestSpec]; the
 * engine then sends it through the same gate as any other request. [input] holds the already-filled-in text of each
 * setting (secrets included), keyed by the setting's name.
 */
object HttpPresets {
    private fun form(vararg pairs: Pair<String, String>) =
        pairs.filter { it.second.isNotEmpty() }.joinToString("&") { URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8") }

    private fun json(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String = buildJsonObject(block).toString()
    private val jsonType = listOf("Content-Type" to "application/json")

    private fun need(input: Map<String, String>, key: String, what: String): String =
        input[key]?.trim().orEmpty().ifEmpty { throw IllegalArgumentException("$what is missing.") }

    private fun base(url: String): String = url.trim().trimEnd('/')

    fun build(typeId: String, input: Map<String, String>): HttpRequestSpec = when (typeId) {
        OpIds.DISCORD -> HttpRequestSpec(
            "POST", need(input, "webhook", "The Discord webhook address"), jsonType,
            json {
                put("content", need(input, "text", "The message").take(2000))
                input["username"]?.takeIf { it.isNotBlank() }?.let { put("username", it) }
            },
        )
        OpIds.SLACK -> HttpRequestSpec("POST", need(input, "webhook", "The Slack webhook address"), jsonType, json { put("text", need(input, "text", "The message")) })
        OpIds.TELEGRAM -> HttpRequestSpec(
            "POST", "https://api.telegram.org/bot${need(input, "token", "The bot token")}/sendMessage", jsonType,
            json {
                put("chat_id", need(input, "chatId", "The chat id"))
                put("text", need(input, "text", "The message").take(4096))
                input["parseMode"]?.takeIf { it.isNotBlank() && it != "none" }?.let { put("parse_mode", it) }
            },
        )
        OpIds.NTFY -> HttpRequestSpec(
            "POST", base(input["server"].orEmpty().ifBlank { "https://ntfy.sh" }) + "/" + need(input, "topic", "The topic").trim('/'),
            buildList<Pair<String, String>> {
                input["title"]?.takeIf { it.isNotBlank() }?.let { add("Title" to it) }
                input["priority"]?.takeIf { it.isNotBlank() && it != "3" }?.let { add("Priority" to it) }
                input["tags"]?.takeIf { it.isNotBlank() }?.let { add("Tags" to it) }
                input["token"]?.takeIf { it.isNotBlank() }?.let { add("Authorization" to "Bearer $it") }
            },
            need(input, "text", "The message"),
        )
        OpIds.GOTIFY -> HttpRequestSpec(
            "POST", base(need(input, "server", "The Gotify server")) + "/message?token=" + URLEncoder.encode(need(input, "token", "The app token"), "UTF-8"), jsonType,
            json {
                put("title", input["title"].orEmpty())
                put("message", need(input, "text", "The message"))
                put("priority", input["priority"]?.toIntOrNull() ?: 5)
            },
        )
        OpIds.PUSHOVER -> HttpRequestSpec(
            "POST", "https://api.pushover.net/1/messages.json", listOf("Content-Type" to "application/x-www-form-urlencoded"),
            form(
                "token" to need(input, "token", "The application token"), "user" to need(input, "user", "The user key"),
                "message" to need(input, "text", "The message"), "title" to input["title"].orEmpty(), "priority" to input["priority"].orEmpty(),
            ),
        )
        OpIds.IFTTT -> HttpRequestSpec(
            "POST", "https://maker.ifttt.com/trigger/${URLEncoder.encode(need(input, "event", "The event name"), "UTF-8")}/with/key/${need(input, "key", "The webhook key")}", jsonType,
            json {
                put("value1", input["value1"].orEmpty())
                put("value2", input["value2"].orEmpty())
                put("value3", input["value3"].orEmpty())
            },
        )
        OpIds.HOME_ASSISTANT -> {
            val data = input["data"].orEmpty().trim()
            val extra: JsonObject = if (data.isEmpty()) JsonObject(emptyMap()) else (Value.parseJson(data).toJson() as? JsonObject
                ?: throw IllegalArgumentException("The extra data must be a JSON object, like {\"brightness\": 120}."))
            val merged = buildJsonObject {
                input["entityId"]?.takeIf { it.isNotBlank() }?.let { put("entity_id", it) }
                for ((k, v) in extra) put(k, v)
            }
            HttpRequestSpec(
                "POST", base(need(input, "server", "The Home Assistant address")) + "/api/services/" + need(input, "domain", "The domain") + "/" + need(input, "service", "The service"),
                jsonType + ("Authorization" to "Bearer ${need(input, "token", "The access token")}"), merged.toString(),
            )
        }
        else -> throw IllegalArgumentException("Unknown service \"$typeId\".")
    }

    /** What to read from the response of each service when it fails, to give a helpful message. */
    fun failureHint(typeId: String, status: Int): String = when {
        status == 401 || status == 403 -> "The service refused the credentials. Check the key or token in your vault."
        status == 404 -> "The address or topic wasn't found. Check it for typos."
        status == 429 -> "The service says you are sending too many messages. Wait a bit."
        typeId == OpIds.TELEGRAM && status == 400 -> "Telegram couldn't deliver it. Check the chat id, and that you have started the bot."
        else -> ""
    }
}

/** Ask AI (spec §14.3): the user's own key, from the vault, sent straight to the provider they chose. */
object AiProviders {
    val providers = listOf("openai", "anthropic", "gemini", "ollama")

    fun defaultModel(provider: String): String = when (provider) {
        "anthropic" -> "claude-haiku-5-5"
        "gemini" -> "gemini-2.0-flash"
        "ollama" -> "llama3.2"
        else -> "gpt-4o-mini"
    }

    fun request(provider: String, model: String, prompt: String, system: String, key: String, baseUrl: String, maxTokens: Int): HttpRequestSpec {
        require(prompt.isNotBlank()) { "Write a question or instruction first." }
        val m = model.ifBlank { defaultModel(provider) }
        return when (provider) {
            "anthropic" -> HttpRequestSpec(
                "POST", "https://api.anthropic.com/v1/messages",
                listOf("x-api-key" to key, "anthropic-version" to "2023-06-01", "content-type" to "application/json"),
                buildJsonObject {
                    put("model", m)
                    put("max_tokens", maxTokens)
                    if (system.isNotBlank()) put("system", system)
                    put("messages", JsonArray(listOf(buildJsonObject { put("role", "user"); put("content", prompt) })))
                }.toString(),
                timeoutSeconds = 60,
            )
            "gemini" -> HttpRequestSpec(
                "POST", "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=${URLEncoder.encode(key, "UTF-8")}",
                jsonType,
                buildJsonObject {
                    put("contents", JsonArray(listOf(buildJsonObject { put("parts", JsonArray(listOf(buildJsonObject { put("text", prompt) }))) })))
                    if (system.isNotBlank()) put("systemInstruction", buildJsonObject { put("parts", JsonArray(listOf(buildJsonObject { put("text", system) }))) })
                    put("generationConfig", buildJsonObject { put("maxOutputTokens", maxTokens) })
                }.toString(),
                timeoutSeconds = 60,
            )
            "ollama" -> HttpRequestSpec(
                "POST", base(baseUrl.ifBlank { "http://localhost:11434" }) + "/api/chat", jsonType,
                buildJsonObject {
                    put("model", m)
                    put("stream", false)
                    put("messages", JsonArray(messages(system, prompt)))
                }.toString(),
                timeoutSeconds = 120,
            )
            else -> HttpRequestSpec(
                "POST", base(baseUrl.ifBlank { "https://api.openai.com/v1" }) + "/chat/completions",
                jsonType + ("Authorization" to "Bearer $key"),
                buildJsonObject {
                    put("model", m)
                    put("max_tokens", maxTokens)
                    put("messages", JsonArray(messages(system, prompt)))
                }.toString(),
                timeoutSeconds = 60,
            )
        }
    }

    private fun base(url: String) = url.trim().trimEnd('/')

    private fun messages(system: String, prompt: String): List<JsonObject> = buildList {
        if (system.isNotBlank()) add(buildJsonObject { put("role", "system"); put("content", system) })
        add(buildJsonObject { put("role", "user"); put("content", prompt) })
    }

    /** The answer text from a provider's response, or a readable explanation when it isn't there. */
    fun answer(provider: String, body: String): String {
        val root = try {
            Value.parseJson(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("The provider's answer wasn't JSON.")
        }
        val where = when (provider) {
            "anthropic" -> "content.0.text"
            "gemini" -> "candidates.0.content.parts.0.text"
            "ollama" -> "message.content"
            else -> "choices.0.message.content"
        }
        root.path(where)?.let { return it.render() }
        val error = root.path("error.message") ?: root.path("error")
        throw IllegalArgumentException(error?.render()?.take(200)?.let { "The provider said: $it" } ?: "The provider's answer had no text in it.")
    }

}
