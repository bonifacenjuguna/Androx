package com.androx.app.core.net

/** Import from cURL and Copy as cURL (spec §14.2, §14.9). */
object Curl {
    /** Splits a command into words the way a shell does: quotes, escapes and `\` line continuations. */
    fun tokenize(command: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote = ' '
        var inWord = false
        var i = 0
        val s = command.replace("\\\r\n", " ").replace("\\\n", " ")
        while (i < s.length) {
            val c = s[i]
            when {
                quote != ' ' -> when {
                    c == quote -> quote = ' '
                    c == '\\' && quote == '"' && i + 1 < s.length && s[i + 1] in "\"\\\$`" -> {
                        cur.append(s[i + 1])
                        i++
                    }
                    else -> cur.append(c)
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    inWord = true
                }
                c == '\\' && i + 1 < s.length -> {
                    cur.append(s[i + 1])
                    inWord = true
                    i++
                }
                c.isWhitespace() -> if (inWord) {
                    out += cur.toString()
                    cur.clear()
                    inWord = false
                }
                else -> {
                    cur.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) out += cur.toString()
        return out
    }

    /** Throws [IllegalArgumentException] with a readable message when [command] isn't a usable cURL command. */
    fun parse(command: String): HttpRequestSpec {
        val words = tokenize(command.trim())
        require(words.isNotEmpty() && words[0] == "curl") { "That doesn't look like a cURL command. It should start with \"curl\"." }
        var method: String? = null
        var url: String? = null
        val headers = ArrayList<Pair<String, String>>()
        var body: String? = null
        var follow = false
        var user: String? = null
        var i = 1
        fun value(flag: String): String {
            if (i + 1 >= words.size) throw IllegalArgumentException("$flag needs a value.")
            i++
            return words[i]
        }
        while (i < words.size) {
            val w = words[i]
            when {
                w == "-X" || w == "--request" -> method = value(w).uppercase()
                w == "-H" || w == "--header" -> {
                    val h = value(w)
                    val name = h.substringBefore(':').trim()
                    require(h.contains(':') && name.isNotEmpty()) { "A header needs a name and a colon: \"$h\"." }
                    headers += name to h.substringAfter(':').trim()
                }
                w == "-d" || w == "--data" || w == "--data-raw" || w == "--data-binary" || w == "--data-ascii" -> {
                    val d = value(w)
                    body = if (body == null) d else body + "&" + d
                }
                w == "--json" -> {
                    body = value(w)
                    if (headers.none { it.first.equals("Content-Type", true) }) headers += "Content-Type" to "application/json"
                    if (headers.none { it.first.equals("Accept", true) }) headers += "Accept" to "application/json"
                }
                w == "-u" || w == "--user" -> user = value(w)
                w == "-L" || w == "--location" -> follow = true
                w == "-I" || w == "--head" -> method = "HEAD"
                w == "--url" -> url = value(w)
                w == "-s" || w == "--silent" || w == "-S" || w == "-k" || w == "--insecure" || w == "-v" || w == "--compressed" || w == "-i" || w == "-f" || w == "--fail" -> Unit
                w.startsWith("-") -> throw IllegalArgumentException("Androx can't import the option \"$w\" yet.")
                else -> url = w
            }
            i++
        }
        require(!url.isNullOrBlank()) { "The command has no address." }
        if (user != null) {
            val token = java.util.Base64.getEncoder().encodeToString(user.toByteArray())
            headers += "Authorization" to "Basic $token"
        }
        if (body != null && headers.none { it.first.equals("Content-Type", true) }) headers += "Content-Type" to "application/x-www-form-urlencoded"
        return HttpRequestSpec(
            method = method ?: if (body != null) "POST" else "GET",
            url = url,
            headers = headers,
            body = body,
            followRedirects = follow,
        )
    }

    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** A command that repeats the request. With [redact] (the default) tokens and keys are hidden, so it is safe to share. */
    fun render(request: HttpRequestSpec, redact: Boolean = true, secrets: Collection<String> = emptyList()): String {
        val parts = ArrayList<String>()
        parts += "curl"
        if (request.method != "GET") parts += "-X ${request.method}"
        parts += quote(if (redact) Redactor.url(request.url, secrets) else request.url)
        for ((n, v) in request.headers) parts += "-H " + quote("$n: " + if (redact) Redactor.header(n, v, secrets) else v)
        request.body?.let { parts += "--data-raw " + quote(if (redact) Redactor.text(it, secrets) else it) }
        if (request.followRedirects) parts += "-L"
        return parts.joinToString(" ")
    }
}
