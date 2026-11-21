package com.androx.app.core.value

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

class MathException(message: String) : Exception(message)

/**
 * A small, safe maths evaluator for the "Calculate" action and `{{= ... }}`-style uses.
 * Supports + - * / % ^, parentheses, unary minus, constants `pi` and `e`, variables, and the functions
 * min, max, abs, round, floor, ceil, sqrt, pow, ln, log10, clamp.
 */
object MathExpr {
    fun eval(source: String, variable: (String) -> Double? = { null }): Double {
        val result = Parser(source, variable).parse()
        if (!result.isFinite()) throw MathException("The result is not a finite number.")
        return result
    }

    private class Parser(val s: String, val variable: (String) -> Double?) {
        var i = 0

        fun parse(): Double {
            ws()
            if (i >= s.length) throw MathException("The expression is empty.")
            val v = expr()
            ws()
            if (i < s.length) throw MathException("Unexpected '${s[i]}' at position ${i + 1}.")
            return v
        }

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun peek(): Char? {
            ws()
            return s.getOrNull(i)
        }

        fun expr(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return v
                }
            }
        }

        fun term(): Double {
            var v = unary()
            while (true) {
                when (peek()) {
                    '*' -> { i++; v *= unary() }
                    '/' -> {
                        i++
                        val d = unary()
                        if (d == 0.0) throw MathException("Division by zero.")
                        v /= d
                    }
                    '%' -> {
                        i++
                        val d = unary()
                        if (d == 0.0) throw MathException("Division by zero.")
                        v %= d
                    }
                    else -> return v
                }
            }
        }

        fun unary(): Double = when (peek()) {
            '-' -> { i++; -unary() }
            '+' -> { i++; unary() }
            else -> power()
        }

        fun power(): Double {
            val base = atom()
            return if (peek() == '^') {
                i++
                base.pow(unary())
            } else base
        }

        fun atom(): Double {
            val c = peek() ?: throw MathException("The expression ends too early.")
            if (c == '(') {
                i++
                val v = expr()
                if (peek() != ')') throw MathException("Missing ')'.")
                i++
                return v
            }
            if (c.isDigit() || c == '.') return number()
            if (c.isLetter() || c == '_') return identifier()
            throw MathException("Unexpected '$c' at position ${i + 1}.")
        }

        fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val save = i
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i < s.length && s[i].isDigit()) {
                    while (i < s.length && s[i].isDigit()) i++
                } else i = save
            }
            return s.substring(start, i).toDoubleOrNull()
                ?: throw MathException("\"${s.substring(start, i)}\" is not a number.")
        }

        fun identifier(): Double {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
            val name = s.substring(start, i)
            if (peek() == '(') {
                i++
                val args = mutableListOf<Double>()
                if (peek() == ')') {
                    i++
                } else {
                    while (true) {
                        args += expr()
                        when (peek()) {
                            ',' -> i++
                            ')' -> { i++; break }
                            else -> throw MathException("Missing ')' after the arguments of $name.")
                        }
                    }
                }
                return call(name.lowercase(), args)
            }
            return when (name.lowercase()) {
                "pi" -> Math.PI
                "e" -> Math.E
                else -> variable(name) ?: throw MathException("\"$name\" is not a number.")
            }
        }

        fun call(name: String, a: List<Double>): Double {
            fun need(n: Int) {
                if (a.size != n) throw MathException("$name needs $n value${if (n == 1) "" else "s"}.")
            }
            return when (name) {
                "min" -> { if (a.isEmpty()) throw MathException("min needs at least one value."); a.reduce { x, y -> min(x, y) } }
                "max" -> { if (a.isEmpty()) throw MathException("max needs at least one value."); a.reduce { x, y -> max(x, y) } }
                "abs" -> { need(1); abs(a[0]) }
                "round" -> {
                    if (a.size == 1) a[0].roundToLong().toDouble()
                    else { need(2); val f = 10.0.pow(a[1]); (a[0] * f).roundToLong() / f }
                }
                "floor" -> { need(1); floor(a[0]) }
                "ceil" -> { need(1); ceil(a[0]) }
                "sqrt" -> { need(1); if (a[0] < 0) throw MathException("sqrt of a negative number."); sqrt(a[0]) }
                "pow" -> { need(2); a[0].pow(a[1]) }
                "ln" -> { need(1); if (a[0] <= 0) throw MathException("ln needs a positive number."); ln(a[0]) }
                "log10" -> { need(1); if (a[0] <= 0) throw MathException("log10 needs a positive number."); log10(a[0]) }
                "clamp" -> { need(3); a[0].coerceIn(min(a[1], a[2]), max(a[1], a[2])) }
                else -> throw MathException("Unknown function \"$name\".")
            }
        }
    }
}
