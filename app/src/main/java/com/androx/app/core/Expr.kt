package com.androx.app.core

class ExprException(message: String) : Exception(message)

/**
 * Math expressions for the "calculate" action and `{{=…}}`-style fields (spec §5.3).
 * Supports + - * / % ^, parentheses, unary minus, numbers, variables (through [resolve]) and a few functions.
 * Hand-written recursive descent: no eval, nothing outside arithmetic can run.
 */
object Expr {
    private const val MAX_LENGTH = 500
    private const val MAX_DEPTH = 64

    fun eval(source: String, resolve: (String) -> Double? = { null }): Double {
        if (source.length > MAX_LENGTH) throw ExprException("That expression is too long.")
        val p = Parser(source, resolve)
        val result = p.parseExpression(0)
        p.skipSpaces()
        if (!p.atEnd()) throw ExprException("Unexpected \"${p.peek()}\" at position ${p.pos + 1}.")
        if (result.isNaN()) throw ExprException("The result isn't a number.")
        if (result.isInfinite()) throw ExprException("The result is too large (or divides by zero).")
        return result
    }

    private class Parser(val s: String, val resolve: (String) -> Double?) {
        var pos = 0

        fun atEnd() = pos >= s.length
        fun peek(): Char = s[pos]
        fun skipSpaces() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun accept(c: Char): Boolean {
            skipSpaces()
            if (pos < s.length && s[pos] == c) {
                pos++
                return true
            }
            return false
        }

        // expression := term (('+' | '-') term)*
        fun parseExpression(depth: Int): Double {
            if (depth > MAX_DEPTH) throw ExprException("That expression is nested too deeply.")
            var left = parseTerm(depth)
            while (true) {
                left = when {
                    accept('+') -> left + parseTerm(depth)
                    accept('-') -> left - parseTerm(depth)
                    else -> return left
                }
            }
        }

        // term := power (('*' | '/' | '%') power)*
        private fun parseTerm(depth: Int): Double {
            var left = parsePower(depth)
            while (true) {
                left = when {
                    accept('*') -> left * parsePower(depth)
                    accept('/') -> {
                        val right = parsePower(depth)
                        if (right == 0.0) throw ExprException("Can't divide by zero.")
                        left / right
                    }
                    accept('%') -> {
                        val right = parsePower(depth)
                        if (right == 0.0) throw ExprException("Can't divide by zero.")
                        left % right
                    }
                    else -> return left
                }
            }
        }

        // power := unary ('^' power)?   (right associative)
        private fun parsePower(depth: Int): Double {
            val base = parseUnary(depth)
            return if (accept('^')) Math.pow(base, parsePower(depth)) else base
        }

        private fun parseUnary(depth: Int): Double = when {
            accept('-') -> -parseUnary(depth)
            accept('+') -> parseUnary(depth)
            else -> parseAtom(depth)
        }

        private fun parseAtom(depth: Int): Double {
            skipSpaces()
            if (atEnd()) throw ExprException("The expression ends too early.")
            val c = peek()
            if (c == '(') {
                pos++
                val v = parseExpression(depth + 1)
                if (!accept(')')) throw ExprException("Missing closing bracket.")
                return v
            }
            if (c.isDigit() || c == '.') return parseNumber()
            if (c.isLetter() || c == '_') return parseNameOrCall(depth)
            throw ExprException("Unexpected \"$c\" at position ${pos + 1}.")
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: throw ExprException("\"${s.substring(start, pos)}\" isn't a number.")
        }

        private fun parseNameOrCall(depth: Int): Double {
            val start = pos
            while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_' || s[pos] == '.')) pos++
            val name = s.substring(start, pos)
            skipSpaces()
            if (pos < s.length && s[pos] == '(') {
                pos++
                val args = mutableListOf<Double>()
                if (!accept(')')) {
                    do {
                        args += parseExpression(depth + 1)
                    } while (accept(','))
                    if (!accept(')')) throw ExprException("Missing closing bracket after $name(.")
                }
                return call(name.lowercase(), args)
            }
            return when (name.lowercase()) {
                "pi" -> Math.PI
                "e" -> Math.E
                else -> resolve(name) ?: throw ExprException("\"$name\" isn't a number or a known variable.")
            }
        }

        private fun call(name: String, a: List<Double>): Double {
            fun need(n: Int) {
                if (a.size != n) throw ExprException("$name needs $n value${if (n == 1) "" else "s"}.")
            }
            return when (name) {
                "abs" -> { need(1); Math.abs(a[0]) }
                "round" -> { need(1); Math.round(a[0]).toDouble() }
                "floor" -> { need(1); Math.floor(a[0]) }
                "ceil" -> { need(1); Math.ceil(a[0]) }
                "sqrt" -> { need(1); if (a[0] < 0) throw ExprException("sqrt of a negative number."); Math.sqrt(a[0]) }
                "pow" -> { need(2); Math.pow(a[0], a[1]) }
                "min" -> { if (a.isEmpty()) throw ExprException("min needs at least one value."); a.min() }
                "max" -> { if (a.isEmpty()) throw ExprException("max needs at least one value."); a.max() }
                else -> throw ExprException("Unknown function \"$name\".")
            }
        }
    }
}

/** Safe string helpers for the text actions (spec §5.3). All of them cap their input so a bad value can't hang a run. */
object StringOps {
    private const val MAX_TEXT = 200_000
    private const val MAX_PATTERN = 500

    fun substring(text: String, start: Int, length: Int?): String {
        val from = start.coerceIn(0, text.length)
        val to = if (length == null) text.length else (from + length.coerceAtLeast(0)).coerceAtMost(text.length)
        return text.substring(from, to)
    }

    fun replace(text: String, find: String, with: String, ignoreCase: Boolean = false): String =
        if (find.isEmpty()) text else text.replace(find, with, ignoreCase)

    fun regexReplace(text: String, pattern: String, with: String): String = compile(text, pattern).replace(text, with)

    fun regexFind(text: String, pattern: String): List<String> =
        compile(text, pattern).findAll(text).map { it.value }.take(1000).toList()

    fun regexMatches(text: String, pattern: String): Boolean = compile(text, pattern).containsMatchIn(text)

    private fun compile(text: String, pattern: String): Regex {
        if (text.length > MAX_TEXT) throw ExprException("That text is too long for a pattern match.")
        if (pattern.length > MAX_PATTERN) throw ExprException("That pattern is too long.")
        return try {
            Regex(pattern)
        } catch (e: Exception) {
            throw ExprException("That pattern isn't valid: ${e.message?.lineSequence()?.firstOrNull()?.take(80)}")
        }
    }

    fun split(text: String, separator: String): List<String> =
        if (separator.isEmpty()) text.map { it.toString() } else text.split(separator)

    fun join(items: List<String>, separator: String): String = items.joinToString(separator)

    fun changeCase(text: String, mode: String): String = when (mode.lowercase()) {
        "upper" -> text.uppercase()
        "lower" -> text.lowercase()
        "title" -> text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        else -> text
    }

    fun trim(text: String): String = text.trim()

    /** Pads to [width] on the left (numbers, "007") or right. */
    fun pad(text: String, width: Int, fill: Char = ' ', left: Boolean = true): String =
        if (left) text.padStart(width.coerceIn(0, 1000), fill) else text.padEnd(width.coerceIn(0, 1000), fill)
}
