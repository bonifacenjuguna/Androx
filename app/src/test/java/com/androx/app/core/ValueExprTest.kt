package com.androx.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ValueExprTest {
    @Test
    fun numbersRenderWithoutTrailingZeros() {
        assertEquals("5", Value.Num(5.0).render())
        assertEquals("2.5", Value.Num(2.5).render())
        assertEquals("0.1", Value.Num(0.1).render())
        assertEquals("-3", Value.Num(-3.0).render())
    }

    @Test
    fun textStoredByOlderVersionsStillWorksInMath() {
        val stored = Value.fromStored("42")
        assertEquals(42.0, stored.asNumberOrNull()!!, 0.0)
        assertNull(Value.fromStored("hello").asNumberOrNull())
        assertNull(Value.fromStored("  ").asNumberOrNull())
    }

    @Test
    fun truthiness() {
        assertTrue(Value.Text("Yes").asBoolean())
        assertTrue(Value.Num(2.0).asBoolean())
        assertFalse(Value.Text("nope").asBoolean())
        assertFalse(Value.Items(emptyList()).asBoolean())
    }

    @Test
    fun jsonRoundTripKeepsTypes() {
        val v = Value.Dict(
            mapOf(
                "name" to Value.Text("Ada"),
                "age" to Value.Num(36.0),
                "ok" to Value.Bool(true),
                "tags" to Value.Items(listOf(Value.Text("a"), Value.Num(1.5))),
            ),
        )
        val back = Value.parseJson(v.toJson().toString())
        assertEquals(v, back)
    }

    @Test
    fun jsonRendersIntegersWithoutDecimals() {
        assertEquals("[1,2.5]", Value.Items(listOf(Value.Num(1.0), Value.Num(2.5))).render())
    }

    @Test
    fun badJsonGivesReadableError() {
        try {
            Value.parseJson("{oops")
            fail("expected an error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.startsWith("That isn't valid JSON"))
        }
    }

    @Test
    fun pathLookup() {
        val v = Value.parseJson("""{"user":{"items":[{"name":"x"},{"name":"y"}]}}""")
        assertEquals(Value.Text("y"), v.path("user.items.1.name"))
        assertNull(v.path("user.items.5.name"))
        assertNull(v.path("user.nope"))
        assertEquals(v, v.path(""))
    }

    @Test
    fun expressionPrecedenceAndPower() {
        assertEquals(14.0, Expr.eval("2 + 3 * 4"), 0.0)
        assertEquals(20.0, Expr.eval("(2 + 3) * 4"), 0.0)
        assertEquals(512.0, Expr.eval("2 ^ 3 ^ 2"), 0.0)
        assertEquals(-4.0, Expr.eval("-2 * 2"), 0.0)
        assertEquals(1.0, Expr.eval("10 % 3"), 0.0)
    }

    @Test
    fun expressionFunctionsAndVariables() {
        assertEquals(5.0, Expr.eval("max(1, 5, 3)"), 0.0)
        assertEquals(3.0, Expr.eval("round(2.6)"), 0.0)
        assertEquals(30.0, Expr.eval("battery / 2", resolve = { if (it == "battery") 60.0 else null }), 0.0)
        assertEquals(Math.PI, Expr.eval("pi"), 1e-12)
    }

    @Test
    fun expressionErrorsAreReadable() {
        fun message(src: String): String = try {
            Expr.eval(src)
            "no error"
        } catch (e: ExprException) {
            e.message!!
        }
        assertTrue(message("1 / 0").contains("divide by zero"))
        assertTrue(message("2 +").contains("ends too early"))
        assertTrue(message("(1 + 2").contains("closing bracket"))
        assertTrue(message("foo + 1").contains("foo"))
        assertTrue(message("sqrt(-1)").contains("negative"))
        assertTrue(message("1 2").contains("Unexpected"))
        assertTrue(message("x".repeat(600)).contains("too long"))
    }

    @Test
    fun deeplyNestedExpressionIsRejectedNotCrashed() {
        val src = "(".repeat(200) + "1" + ")".repeat(200)
        try {
            Expr.eval(src)
            fail("expected an error")
        } catch (e: ExprException) {
            assertTrue(e.message!!.contains("nested"))
        }
    }

    @Test
    fun stringOps() {
        assertEquals("ell", StringOps.substring("hello", 1, 3))
        assertEquals("hello", StringOps.substring("hello", -5, null))
        assertEquals("", StringOps.substring("hello", 9, 2))
        assertEquals("a-b-c", StringOps.replace("a b c", " ", "-"))
        assertEquals("x1y22", StringOps.regexReplace("x1y22", "z", "!"))
        assertEquals("x#y#", StringOps.regexReplace("x1y22", "[0-9]+", "#"))
        assertEquals(listOf("12", "34"), StringOps.regexFind("ab12cd34", "[0-9]+"))
        assertEquals("007", StringOps.pad("7", 3, '0'))
        assertEquals("Hello World", StringOps.changeCase("hello world", "title"))
        assertEquals(listOf("a", "b"), StringOps.split("a,b", ","))
    }

    @Test
    fun badPatternIsAReadableError() {
        try {
            StringOps.regexMatches("abc", "(")
            fail("expected an error")
        } catch (e: ExprException) {
            assertTrue(e.message!!.startsWith("That pattern isn't valid"))
        }
    }
}
