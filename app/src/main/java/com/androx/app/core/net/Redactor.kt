package com.androx.app.core.net

/**
 * Keeps secrets out of every log, request line and error message (spec §14.9, §5.4). Redaction works two ways: by the
 * secret values this run actually used (exact), and by shape (Authorization headers, Bearer tokens, `key=`/`token=`
 * parameters, passwords in an address), so a secret that arrives from a variable is hidden too.
 */
object Redactor {
    const val MASK = "••••••"

    private val sensitiveNames = listOf(
        "authorization", "proxy-authorization", "x-api-key", "api-key", "apikey", "api_key", "x-auth-token", "x-token",
        "token", "access_token", "secret", "password", "passwd", "key", "signature", "sig",
    )

    fun isSensitiveName(name: String): Boolean = name.trim().lowercase().let { n -> n in sensitiveNames || sensitiveNames.any { n.endsWith("-$it") || n.endsWith("_$it") } }

    /** Replaces every occurrence of each known secret (longest first so a longer one isn't half-hidden by a shorter one). */
    fun mask(text: String, secrets: Collection<String>): String {
        var out = text
        for (s in secrets.filter { it.length >= 3 }.sortedByDescending { it.length }) out = out.replace(s, MASK)
        return out
    }

    /** A URL with passwords, tokens and keys in it hidden: `https://user:••••@host/path?token=••••&q=1`. */
    fun url(url: String, secrets: Collection<String> = emptyList()): String {
        var out = mask(url, secrets)
        // user:password@host
        out = out.replace(Regex("(://[^/@:\\s]+):([^@/\\s]+)@")) { "${it.groupValues[1]}:$MASK@" }
        // sensitive query parameters
        val q = out.indexOf('?')
        if (q >= 0) {
            val base = out.substring(0, q)
            val hash = out.indexOf('#', q).let { if (it < 0) out.length else it }
            val query = out.substring(q + 1, hash).split('&').joinToString("&") { pair ->
                val name = pair.substringBefore('=')
                if (pair.contains('=') && isSensitiveName(name)) "$name=$MASK" else pair
            }
            out = base + "?" + query + out.substring(hash)
        }
        return out
    }

    fun header(name: String, value: String, secrets: Collection<String> = emptyList()): String =
        if (isSensitiveName(name)) MASK else mask(value, secrets)

    /** Free text (an error message, a response excerpt): known secrets, Bearer/Basic tokens and `name=value` pairs. */
    fun text(text: String, secrets: Collection<String> = emptyList()): String {
        var out = mask(text, secrets)
        out = out.replace(Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{6,}")) { "${it.groupValues[1]} $MASK" }
        out = out.replace(Regex("(?i)\\b(password|passwd|token|secret|api[_-]?key|access_token)(\\s*[=:]\\s*)([^\\s&\",;]+)")) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        return out
    }
}
