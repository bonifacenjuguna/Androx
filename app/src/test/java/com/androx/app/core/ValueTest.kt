package com.androx.app.core

import com.androx.app.core.value.MathException
import com.androx.app.core.value.MathExpr
import com.androx.app.core.value.TemplateEngine
import com.androx.app.core.value.Value
import com.androx.app.core.value.ValueType
import com.androx.app.core.value.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ValueTest {
    @Test
    fun textThatLooksLikeANumberIsReadAsOne() {
        assertEquals(5.0, Value.Text(" 5 ").asNumber()!!, 0.0)
        assertNull(Value.Text("abc").asNumber())
        assertEquals(1.0, Value.Bool(true).asNumber()!!, 0.0)
    }

    @Test
    fun wholeNumbersPrintWithoutDecimals() {
        assertEquals("42", Value.Num(42.0).asText())
        assertEquals("2.5", Value.Num(2.5).asText())
        assertEquals("-3", Value.Num(-3.0).asText())
    }

    @Test
    fun booleanRules() {
        assertFalse(Value.Text("").asBool())
        assertFalse(Value.Text("False").asBool())
        assertFalse(Value.Text("0").asBool())
        assertTrue(Value.Text("hello").asBool())
        assertFalse(Value.ListV(emptyList()).asBool())
    }

    @Test
    fun jsonRoundTrip() {
        val v = Value.parseJson("""{"a":1,"b":[true,"x",2.5],"c":{"d":"e"}}""")!!
        assertEquals(ValueType.DICTIONARY, v.type)
        val back = Value.parseJson(v.asText())!!
        assertEquals(v, back)
    }

    @Test
    fun invalidJsonIsNull() {
        assertNull(Value.parseJson("{nope"))
    }

    @Test
    fun pathLookup() {
        val v = Value.parseJson("""{"items":[{"id":7},{"id":9}],"user":{"name":"Ada"}}""")!!
        assertEquals("Ada", v.at("user.name")!!.asText())
        assertEquals("7", v.at("items[0].id")!!.asText())
        assertEquals("9", v.at("items[-1].id")!!.asText())
        assertNull(v.at("items[5]"))
        assertNull(v.at("user.age"))
        assertNull(v.at("items[x]"))
    }

    @Test
    fun legacyStringMapStillMeansText() {
        // v0.0.1 variables.json was a map of strings; the same JSON must load as text values that still do maths.
        val v = Value.fromJson(kotlinx.serialization.json.JsonPrimitive("12"))
        assertEquals(Value.Text("12"), v)
        assertEquals(12.0, v.asNumber()!!, 0.0)
    }

    @Test
    fun mathBasics() {
        assertEquals(7.0, MathExpr.eval("1 + 2 * 3"), 0.0)
        assertEquals(9.0, MathExpr.eval("(1 + 2) * 3"), 0.0)
        assertEquals(-4.0, MathExpr.eval("-2^2"), 0.0)
        assertEquals(0.5, MathExpr.eval("2^-1"), 0.0)
        assertEquals(1.0, MathExpr.eval("7 % 3"), 0.0)
        assertEquals(5.0, MathExpr.eval("max(1, 5, 3)"), 0.0)
        assertEquals(3.14, MathExpr.eval("round(pi, 2)"), 0.0)
        assertEquals(10.0, MathExpr.eval("clamp(15, 0, 10)"), 0.0)
    }

    @Test
    fun mathVariables() {
        val vars = mapOf("battery.level" to 40.0, "x" to 2.0)
        assertEquals(42.0, MathExpr.eval("battery.level + x", vars::get), 0.0)
    }

    @Test
    fun mathErrors() {
        for (bad in listOf("", "1 +", "1 / 0", "foo + 1", "2 $ 3", "sqrt(-1)", "max()", "(1 + 2")) {
            try {
                MathExpr.eval(bad)
                fail("Expected an error for \"$bad\"")
            } catch (e: MathException) {
                assertNotNull(e.message)
            }
        }
    }

    @Test
    fun templatesRenderValuesAndPaths() {
        val scope = TemplateEngine.scope(
            mapOf("flow" to Value.Text("Hydration"), "http" to Value.parseJson("""{"status":200,"body":{"ids":[3,4]}}""")!!),
        )
        assertEquals("Hydration: 200 / 4", TemplateEngine.render("{{flow}}: {{http.status}} / {{http.body.ids[1]}}", scope))
    }

    @Test
    fun automationAliasStillWorks() {
        val scope = TemplateEngine.scope(mapOf("flow" to Value.Text("Morning")))
        assertEquals("Morning", TemplateEngine.render("{{automation}}", scope))
    }

    @Test
    fun unknownTemplateNamesAreEmptyAndDollarSignsSurvive() {
        val scope = TemplateEngine.scope(mapOf("price" to Value.Text("\$5\\x")))
        assertEquals("[]-\$5\\x", TemplateEngine.render("[{{missing}}]-{{price}}", scope))
    }

    @Test
    fun referencesAreListedOnce() {
        assertEquals(listOf("a", "b.c"), TemplateEngine.references("{{a}} {{ b.c }} {{a}}"))
    }
}
