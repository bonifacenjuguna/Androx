package com.androx.app.core

import com.androx.app.core.migrate.FlowMigration
import com.androx.app.core.model.Check
import com.androx.app.core.model.CheckIds
import com.androx.app.core.model.OpIds
import com.androx.app.core.model.P
import com.androx.app.core.model.TriggerIds
import com.androx.app.core.model.params
import com.androx.app.core.registry.BuiltinSpecs
import com.androx.app.core.registry.Registry
import com.androx.app.core.registry.problemsOf
import com.androx.app.core.registry.summaryOf
import com.androx.app.model.Action
import com.androx.app.model.Condition
import com.androx.app.model.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryTest {
    private val triggers = BuiltinSpecs.triggerRegistry
    private val constraints = BuiltinSpecs.constraintRegistry
    private val actions = BuiltinSpecs.actionRegistry

    @Test
    fun everyLegacyTriggerConstraintAndActionHasASpec() {
        val legacyTriggers = listOf(Trigger.Manual, Trigger.Schedule(), Trigger.Interval(), Trigger.Once(), Trigger.DeviceBoot)
        for (t in legacyTriggers) assertNotNull(t.toString(), triggers[FlowMigration.event(t).typeId])

        val legacyConditions = listOf(
            Condition.BatteryLevel(), Condition.Charging(), Condition.Variable(), Condition.TimeWindow(), Condition.DaysOfWeek(),
            Condition.WifiConnected(), Condition.ScreenOn(), Condition.Ringer(), Condition.PowerSaver(), Condition.AirplaneMode(),
        )
        for (c in legacyConditions) {
            val rule = FlowMigration.constraint(c) as com.androx.app.core.model.ConstraintNode.Rule
            assertNotNull(c.toString(), constraints[rule.check.typeId])
        }

        val legacyActions = listOf(
            Action.ShowNotification(), Action.SetVariable(), Action.RunAutomation(), Action.WriteLog(), Action.RunScript(), Action.Vibrate(),
            Action.ShowToast(), Action.CopyToClipboard(), Action.Speak(), Action.Wait(), Action.Flashlight(), Action.SetVolume(),
            Action.PlaySound, Action.IfThen(), Action.Repeat(),
        )
        for (a in legacyActions) assertNotNull(a.toString(), actions[FlowMigration.step(a).op.typeId])
    }

    @Test
    fun idsAreUniqueAcrossRegistries() {
        // Registry construction throws on duplicates; constructing is the test.
        assertTrue(triggers.all.isNotEmpty() && constraints.all.isNotEmpty() && actions.all.isNotEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateRegistrationFails() {
        val first = BuiltinSpecs.triggers[0]
        Registry(listOf(first, first))
    }

    @Test
    fun unknownIdsAreNullNotCrashes() {
        assertNull(triggers["geofence.enter"])
    }

    @Test
    fun summariesAreGeneratedFromParams() {
        val interval = triggers[TriggerIds.INTERVAL]!!
        assertEquals("Every 1 h 30 min", interval.summaryOf(params("minutes" to 90)))
        val battery = constraints[CheckIds.BATTERY_LEVEL]!!
        assertEquals("Battery < 20%", battery.summaryOf(params("op" to "LT", "percent" to 20)))
        val toast = actions[OpIds.TOAST]!!
        assertEquals("Show toast \"Hi\"", toast.summaryOf(params("message" to "Hi")))
    }

    @Test
    fun validationUsesFieldRules() {
        val interval = triggers[TriggerIds.INTERVAL]!!
        assertTrue(interval.problemsOf(params("minutes" to 2)).isNotEmpty())
        assertTrue(interval.problemsOf(params("minutes" to 30)).isEmpty())
        val toast = actions[OpIds.TOAST]!!
        assertFalse(toast.problemsOf(params("message" to "")).isEmpty())
    }

    @Test
    fun searchFindsBySynonymAndRanksNameFirst() {
        val silent = constraints.search("make phone silent").map { it.id }
        assertTrue(CheckIds.RINGER in silent)
        assertEquals(CheckIds.RINGER, constraints.search("ringer").first().id)
        assertTrue(actions.search("torch").any { it.id == OpIds.FLASHLIGHT })
        assertEquals(actions.all.size, actions.search("").size)
        assertTrue(actions.search("zzzzqq").isEmpty())
    }

    @Test
    fun checkCarrierKeepsNegation() {
        val c = Check(CheckIds.SCREEN, params("on" to true), negate = true)
        assertTrue(c.negate)
        assertTrue(P(c.params).bool("on"))
    }

    @Test
    fun paramsReaderIsForgiving() {
        val p = P(params("n" to "12", "b" to "true", "d" to 2.5, "days" to listOf(1, 2)))
        assertEquals(12, p.int("n"))
        assertTrue(p.bool("b"))
        assertEquals(2, p.int("d"))
        assertEquals(listOf(1, 2), p.ints("days"))
        assertEquals(7, p.int("missing", 7))
        assertEquals("x", p.str("missing", "x"))
    }
}
