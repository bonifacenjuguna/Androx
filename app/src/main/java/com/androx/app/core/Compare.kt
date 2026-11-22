package com.androx.app.core

/** The one comparison vocabulary every typed rule shares (spec §17.1 "typed comparisons"). */
enum class CompareOp(val symbol: String, val label: String) {
    EQ("=", "is"),
    NEQ("≠", "is not"),
    LT("<", "is less than"),
    LTE("≤", "is at most"),
    GT(">", "is greater than"),
    GTE("≥", "is at least"),
    BETWEEN("between", "is between"),
    CONTAINS("contains", "contains"),
    MATCHES("matches", "matches the pattern"),
}

object Compare {
    val numericOps = listOf(CompareOp.EQ, CompareOp.NEQ, CompareOp.LT, CompareOp.LTE, CompareOp.GT, CompareOp.GTE, CompareOp.BETWEEN)
    val textOps = listOf(CompareOp.EQ, CompareOp.NEQ, CompareOp.CONTAINS, CompareOp.MATCHES)
    val equalityOps = listOf(CompareOp.EQ, CompareOp.NEQ)
    val allOps = CompareOp.entries.toList()

    /**
     * Tests [actual] against [rhs] (and [rhs2] for BETWEEN). Numbers compare as numbers, true/false as switches,
     * everything else as text where equality and "contains" ignore upper/lower case. Throws [ExprException] for a bad
     * pattern so the caller can explain it.
     */
    fun test(actual: Value, op: CompareOp, rhs: String, rhs2: String = ""): Boolean {
        if (actual is Value.Bool && (op == CompareOp.EQ || op == CompareOp.NEQ)) {
            val same = actual.value == Value.Text(rhs).asBoolean()
            return if (op == CompareOp.EQ) same else !same
        }
        val left = actual.asNumberOrNull()
        val right = Value.parseNumber(rhs)
        val numeric = left != null && right != null && actual !is Value.Bool
        return when (op) {
            CompareOp.EQ -> if (numeric) left!!.compareTo(right!!) == 0 else actual.render().equals(rhs, ignoreCase = true)
            CompareOp.NEQ -> if (numeric) left!!.compareTo(right!!) != 0 else !actual.render().equals(rhs, ignoreCase = true)
            CompareOp.LT -> order(actual, numeric, left, right, rhs) < 0
            CompareOp.LTE -> order(actual, numeric, left, right, rhs) <= 0
            CompareOp.GT -> order(actual, numeric, left, right, rhs) > 0
            CompareOp.GTE -> order(actual, numeric, left, right, rhs) >= 0
            CompareOp.BETWEEN -> {
                val high = Value.parseNumber(rhs2)
                if (left == null || right == null || high == null) false else left >= minOf(right, high) && left <= maxOf(right, high)
            }
            CompareOp.CONTAINS -> when (actual) {
                is Value.Items -> actual.items.any { it.render().equals(rhs, ignoreCase = true) }
                else -> actual.render().contains(rhs, ignoreCase = true)
            }
            CompareOp.MATCHES -> StringOps.regexMatches(actual.render(), rhs)
        }
    }

    private fun order(actual: Value, numeric: Boolean, left: Double?, right: Double?, rhs: String): Int =
        if (numeric) left!!.compareTo(right!!) else actual.render().compareTo(rhs, ignoreCase = true)

    /** "> 40", "between 1 and 5", "contains x" for summaries and reasons. */
    fun describe(op: CompareOp, rhs: String, rhs2: String = "", unit: String = ""): String {
        val u = if (unit.isBlank()) "" else " $unit"
        return when (op) {
            CompareOp.BETWEEN -> "between $rhs and $rhs2$u"
            CompareOp.CONTAINS -> "contains $rhs"
            CompareOp.MATCHES -> "matches $rhs"
            else -> "${op.symbol} $rhs$u"
        }
    }
}
