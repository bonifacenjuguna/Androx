package com.androx.app.core.engine

import com.androx.app.core.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemplateEngineTest {
    private val vars = mapOf(
        "name" to Value.Text("Ada"),
        "count" to Value.Num(3.0),
        "user" to Value.parseJson("""{"items":[{"name":"x"},{"name":"y"}],"age":36}"""),
        "a.b" to Value.Text("dotted"),
    )
    private val lookup: (String) -> Value? = { vars[it] }

    @Test
    fun plainNamesAndNumbers() {
        assertEquals("Hi Ada, 3 times", TemplateEngine.render("Hi {{name}}, {{ count }} times", lookup))
    }

    @Test
    fun textWithoutPlaceholdersIsUntouched() {
        assertEquals("100% sure {x}", TemplateEngine.render("100% sure {x}", lookup))
    }

    @Test
    fun unknownNamesBecomeEmpty() {
        assertEquals("[]", TemplateEngine.render("[{{nope}}]", lookup))
    }

    @Test
    fun dottedPathsReachIntoListsAndDictionaries() {
        assertEquals("y / 36", TemplateEngine.render("{{user.items.1.name}} / {{user.age}}", lookup))
        assertEquals("", TemplateEngine.render("{{user.items.9.name}}", lookup))
    }

    @Test
    fun aVariableWhoseNameHasDotsStillWins() {
        assertEquals("dotted", TemplateEngine.render("{{a.b}}", lookup))
    }

    @Test
    fun dollarSignsInValuesAreNotTreatedAsGroupReferences() {
        val l: (String) -> Value? = { if (it == "price") Value.Text("$5 \\1") else null }
        assertEquals("cost: $5 \\1", TemplateEngine.render("cost: {{price}}", l))
    }

    @Test
    fun namesListsEachOnceInOrder() {
        assertEquals(listOf("a", "b.c"), TemplateEngine.names("{{a}} {{ b.c }} {{a}}"))
    }

    @Test
    fun resolveReturnsNullWhenNothingMatches() {
        assertNull(TemplateEngine.resolve("user.nope", lookup))
    }

    @Test
    fun containersRenderAsJson() {
        assertEquals("""["a",1]""", TemplateEngine.render("{{l}}", { if (it == "l") Value.Items(listOf(Value.Text("a"), Value.Num(1.0))) else null }))
    }
}
