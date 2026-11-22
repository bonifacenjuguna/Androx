package com.androx.app.core

import com.androx.app.core.spec.SpecCatalog
import com.androx.app.core.spec.SpecKind
import com.androx.app.core.spec.StateSpecs
import com.androx.app.core.state.CompositeStateReader
import com.androx.app.core.state.FixedStateReader
import com.androx.app.core.state.LegacyStateReader
import com.androx.app.core.state.StateCatalog
import com.androx.app.core.state.StateType
import com.androx.app.engine.DeviceBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CompareStateTest {
    private val catalog = SpecCatalog.default

    // ── typed comparisons ───────────────────────────────────────────────────────────────────

    @Test
    fun numbersCompareAsNumbersEvenWhenStoredAsText() {
        assertTrue(Compare.test(Value.Text("10"), CompareOp.GT, "9"))
        assertTrue(Compare.test(Value.Num(5.0), CompareOp.EQ, "5.0"))
        assertTrue(Compare.test(Value.Text("2"), CompareOp.LTE, "2"))
        assertFalse(Compare.test(Value.Num(1.0), CompareOp.GTE, "2"))
    }

    @Test
    fun textComparesIgnoringCaseForEqualityAndContains() {
        assertTrue(Compare.test(Value.Text("Home"), CompareOp.EQ, "home"))
        assertTrue(Compare.test(Value.Text("Home"), CompareOp.NEQ, "work"))
        assertTrue(Compare.test(Value.Text("My Phone"), CompareOp.CONTAINS, "PHONE"))
        assertTrue(Compare.test(Value.Items(listOf(Value.Text("a"), Value.Text("B"))), CompareOp.CONTAINS, "b"))
    }

    @Test
    fun switchesCompareAsTrueOrFalse() {
        assertTrue(Compare.test(Value.Bool(true), CompareOp.EQ, "yes"))
        assertTrue(Compare.test(Value.Bool(false), CompareOp.EQ, "off"))
        assertTrue(Compare.test(Value.Bool(true), CompareOp.NEQ, "false"))
    }

    @Test
    fun betweenIsInclusiveAndAcceptsEitherOrder() {
        assertTrue(Compare.test(Value.Num(5.0), CompareOp.BETWEEN, "1", "5"))
        assertTrue(Compare.test(Value.Num(1.0), CompareOp.BETWEEN, "5", "1"))
        assertFalse(Compare.test(Value.Num(6.0), CompareOp.BETWEEN, "1", "5"))
        assertFalse(Compare.test(Value.Text("x"), CompareOp.BETWEEN, "1", "5"))
    }

    @Test
    fun patternsMatchAndBadPatternsExplainThemselves() {
        assertTrue(Compare.test(Value.Text("abc123"), CompareOp.MATCHES, "[a-z]+[0-9]+"))
        try {
            Compare.test(Value.Text("x"), CompareOp.MATCHES, "(")
            fail("expected an error")
        } catch (e: ExprException) {
            assertTrue(e.message!!.startsWith("That pattern isn't valid"))
        }
    }

    @Test
    fun describeReadsNaturally() {
        assertEquals("> 40 °C", Compare.describe(CompareOp.GT, "40", unit = "°C"))
        assertEquals("between 1 and 5", Compare.describe(CompareOp.BETWEEN, "1", "5"))
        assertEquals("contains x", Compare.describe(CompareOp.CONTAINS, "x"))
    }

    // ── state providers ─────────────────────────────────────────────────────────────────────

    @Test
    fun providerIdsAreUniqueAndEveryOneHasAConstraint() {
        val ids = StateCatalog.providers.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (p in StateCatalog.providers) {
            val spec = catalog.constraints[StateSpecs.typeId(p)]
            assertNotNull(p.id, spec)
            assertEquals(SpecKind.CONSTRAINT, spec!!.kind)
            assertEquals(p.name, spec.name)
            assertTrue("${p.id} needs choices", p.type != StateType.CHOICE || p.choices.isNotEmpty())
            assertEquals(p.id, spec.outputs.single().key)
        }
    }

    @Test
    fun everyGeneratedConstraintHasValidDefaultsAndAReadableSummary() {
        for (p in StateCatalog.providers) {
            val spec = catalog.constraints[StateSpecs.typeId(p)]!!
            val defaults = spec.defaultParams()
            assertTrue("${p.id}: ${spec.problems(defaults)}", spec.problems(defaults).isEmpty())
            assertTrue(spec.summary(defaults).startsWith(p.name))
        }
    }

    @Test
    fun summariesFollowTheProviderType() {
        fun summary(id: String, p: kotlinx.serialization.json.JsonObject) = catalog.constraints.summarize(CheckIds.STATE_PREFIX + id, p)
        assertEquals("Battery temperature > 40 °C", summary("battery.temp", params("op" to CompareOp.GT, "value" to "40")))
        assertEquals("Battery temperature between 10 and 20 °C", summary("battery.temp", params("op" to CompareOp.BETWEEN, "value" to "10", "value2" to "20")))
        assertEquals("Headphones: connected", summary("device.headphones", params("is" to true)))
        assertEquals("Headphones: not connected", summary("device.headphones", params("is" to false)))
        assertEquals("Do Not Disturb = Priority only", summary("device.dnd", params("op" to CompareOp.EQ, "value" to "priority")))
        assertEquals("Network type ≠ Wi-Fi", summary("network.type", params("op" to CompareOp.NEQ, "value" to "wifi")))
    }

    @Test
    fun numberRulesValidateTheirValues() {
        val spec = catalog.constraints["state:battery.temp"]!!
        assertEquals(listOf("Value must be a number."), spec.problems(params("op" to CompareOp.GT, "value" to "hot")))
        assertEquals(
            listOf("The first value must not be higher than the second."),
            spec.problems(params("op" to CompareOp.BETWEEN, "value" to "30", "value2" to "20")),
        )
        assertTrue(spec.problems(params("op" to CompareOp.GT, "value" to "37.5")).isEmpty())
    }

    @Test
    fun displayUsesWordsAndUnits() {
        assertEquals("41 °C", StateCatalog["battery.temp"]!!.display(Value.Num(41.0)))
        assertEquals("connected", StateCatalog["device.headphones"]!!.display(Value.Bool(true)))
        assertEquals("Priority only", StateCatalog["device.dnd"]!!.display(Value.Text("priority")))
        assertEquals("30 min", StateCatalog["device.uptime"]!!.display(Value.Num(30.0)))
    }

    @Test
    fun olderChecksArePointedAtTheirReplacements() {
        val legacy = catalog.constraints.all().filter { it.supersededBy != null }
        assertEquals(7, legacy.size)
        for (s in legacy) assertNotNull(s.id, catalog.constraints[s.supersededBy!!])
        assertFalse(catalog.constraints.pickable().any { it.id == CheckIds.BATTERY_LEVEL })
        assertNotNull(catalog.constraints[CheckIds.BATTERY_LEVEL]) // still resolves for saved Flows
    }

    // ── readers ─────────────────────────────────────────────────────────────────────────────

    private class Bridge : DeviceBridge {
        override fun batteryLevel() = 64
        override fun isCharging() = true
        override fun showNotification(title: String, message: String) {}
        override fun isWifiConnected() = true
    }

    @Test
    fun theLegacyReaderAnswersWhatTheBridgeKnows() {
        val r = LegacyStateReader(Bridge())
        assertEquals(Value.Num(64.0), r.read("battery.level"))
        assertEquals(Value.Bool(true), r.read("battery.charging"))
        assertEquals(Value.Bool(true), r.read("network.wifi"))
        assertEquals(Value.Text("normal"), r.read("device.ringer"))
        assertNull(r.read("battery.temp"))
    }

    @Test
    fun theCompositeReaderUsesTheFirstReaderThatKnows() {
        val r = CompositeStateReader(FixedStateReader(mapOf("battery.level" to Value.Num(1.0))), LegacyStateReader(Bridge()))
        assertEquals(Value.Num(1.0), r.read("battery.level"))
        assertEquals(Value.Bool(true), r.read("battery.charging"))
        assertNull(r.read("nfc.on"))
    }
}
