package com.androx.app.core

import com.androx.app.core.spec.BatteryCost
import com.androx.app.core.spec.Choice
import com.androx.app.core.spec.Edition
import com.androx.app.core.spec.EditionReq
import com.androx.app.core.spec.ParamSpec
import com.androx.app.core.spec.Perm
import com.androx.app.core.spec.Spec
import com.androx.app.core.spec.SpecCatalog
import com.androx.app.core.spec.SpecKind
import com.androx.app.core.spec.SpecRegistry
import com.androx.app.core.spec.SpecStatus
import com.androx.app.core.spec.SpecCategory
import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.Comparison
import com.androx.app.model.Condition
import com.androx.app.model.FlashlightMode
import com.androx.app.model.RingerMode
import com.androx.app.model.Trigger
import com.androx.app.model.VariableMode
import com.androx.app.model.VolumeStream
import com.androx.app.model.summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RegistryTest {
    private val catalog = SpecCatalog.default

    @Test
    fun idsAreUniqueAndEntriesAreComplete() {
        for (reg in listOf(catalog.triggers, catalog.constraints, catalog.actions)) {
            val ids = reg.all().map { it.id }
            assertEquals(ids.size, ids.toSet().size)
            for (s in reg.all()) {
                assertTrue("${s.id} needs a name", s.name.isNotBlank())
                assertTrue("${s.id} needs a description", s.description.isNotBlank())
                assertTrue("${s.id} needs an icon", s.iconKey.isNotBlank())
            }
        }
        assertEquals(5, catalog.triggers.all().size)
        // 10 older checks, 6 logic and date checks, and one generated check per device state provider.
        assertEquals(10 + 6 + com.androx.app.core.state.StateCatalog.providers.size, catalog.constraints.all().size)
        assertEquals(15 + 16 + 15, catalog.actions.all().size)
    }

    @Test
    fun duplicateOrWrongKindIsRejected() {
        val reg = SpecRegistry(SpecKind.ACTION)
        val spec = catalog.actions.get(OpIds.TOAST)!!
        reg.register(spec)
        try {
            reg.register(spec)
            fail("duplicate accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Duplicate"))
        }
        try {
            reg.register(catalog.triggers.get(TriggerIds.MANUAL)!!)
            fail("wrong kind accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not a"))
        }
    }

    /** The registry summaries must read exactly like the v0.0.1 sentences, so migrated Flows look unchanged. */
    @Test
    fun summariesMatchTheV1Wording() {
        val triggers = listOf(Trigger.Manual, Trigger.Schedule(7, 5, listOf(1, 2, 3, 4, 5)), Trigger.Interval(90), Trigger.Once(0L), Trigger.DeviceBoot)
        for (t in triggers) {
            val e = FlowMigration.triggerEntry("x", t)
            assertEquals(t.summary(), catalog.triggers.summarize(e.typeId, e.params))
        }
        val conditions = listOf(
            Condition.BatteryLevel(Comparison.LTE, 15), Condition.Charging(false), Condition.Variable("count", Comparison.GT, "3"),
            Condition.TimeWindow(22, 30, 6, 0), Condition.DaysOfWeek(listOf(6, 7)), Condition.WifiConnected(false),
            Condition.ScreenOn(true), Condition.Ringer(RingerMode.VIBRATE), Condition.PowerSaver(true), Condition.AirplaneMode(false),
        )
        for (c in conditions) {
            val check = FlowMigration.constraintGroup(c).rules.single()
            assertEquals(c.summary(), catalog.constraints.summarize(check.typeId, check.params))
        }
        val actions = listOf(
            Action.ShowNotification("Hi", "there"), Action.ShowNotification("", ""), Action.SetVariable("a", "1", VariableMode.ADD),
            Action.SetVariable("a", "x", VariableMode.APPEND), Action.SetVariable("a", "", VariableMode.DELETE),
            Action.RunAutomation("abcdefghij", ""), Action.RunAutomation("id", "Other"), Action.WriteLog("log me"),
            Action.RunScript("\n// first\nx()"), Action.Vibrate(250), Action.ShowToast("toast"), Action.CopyToClipboard("c"),
            Action.Speak("say"), Action.Wait(4), Action.Flashlight(FlashlightMode.OFF), Action.SetVolume(VolumeStream.ALARM, 70),
            Action.PlaySound,
        )
        for (a in actions) {
            val a1 = Automation("i", "n", actions = listOf(a))
            val step = FlowMigration.migrate(a1).steps.single()
            assertEquals(a.summary(), catalog.actions.summarize(step.op.typeId, step.op.params))
        }
    }

    @Test
    fun everyMigratedTypeResolvesInTheCatalogue() {
        val a = Automation(
            id = "i", name = "n", trigger = Trigger.Interval(60),
            condition = Condition.MatchAll(listOf(Condition.Charging(), Condition.BatteryLevel())),
            actions = listOf(
                Action.ShowNotification(), Action.SetVariable(), Action.RunAutomation(), Action.WriteLog(), Action.RunScript(),
                Action.Vibrate(), Action.ShowToast(), Action.CopyToClipboard(), Action.Speak(), Action.Wait(), Action.Flashlight(),
                Action.SetVolume(), Action.PlaySound, Action.IfThen(), Action.Repeat(),
            ),
        )
        val f = FlowMigration.migrate(a)
        f.triggers.forEach { assertNotNull(it.typeId, catalog.triggers[it.typeId]) }
        f.constraints!!.allRules().forEach { assertNotNull(it.typeId, catalog.constraints[it.typeId]) }
        f.allSteps().forEach { assertNotNull(it.op.typeId, catalog.actions[it.op.typeId]) }
    }

    @Test
    fun flowControlEntriesDeclareTheirBlocks() {
        assertEquals(listOf("then", "else"), catalog.actions[OpIds.IF]!!.blocks)
        assertEquals(listOf("body"), catalog.actions[OpIds.REPEAT]!!.blocks)
        assertTrue(catalog.actions[OpIds.TOAST]!!.blocks.isEmpty())
    }

    @Test
    fun unknownTypesBecomeUnavailableNotACrash() {
        assertEquals("Unavailable (from_the_future)", catalog.triggers.summarize("from_the_future", EmptyParams))
        assertEquals("Unavailable", catalog.actions.nameOf("nope"))
    }

    @Test
    fun aSummaryThatThrowsFallsBackToTheName() {
        val reg = SpecRegistry(SpecKind.ACTION)
        reg.register(Spec(SpecKind.ACTION, "boom", SpecCategory.DEVICE, "Boom", "d", "bug_outline", summary = { error("bad") }))
        assertEquals("Boom", reg.summarize("boom", EmptyParams))
    }

    @Test
    fun searchFindsSynonymsAndRanksNameMatchesFirst() {
        val hits = catalog.actions.search("torch").map { it.id }
        assertEquals(OpIds.FLASHLIGHT, hits.first())
        assertTrue(OpIds.SET_VOLUME in catalog.actions.search("make phone silent").map { it.id })
        assertEquals(OpIds.WAIT, catalog.actions.search("wait").first().id)
        assertTrue(catalog.actions.search("zzzz").isEmpty())
        assertEquals(catalog.actions.all().size, catalog.actions.search("  ").size)
    }

    @Test
    fun searchRequiresEveryWordToMatch() {
        assertTrue(catalog.actions.search("torch banana").isEmpty())
        assertFalse(catalog.actions.search("show notification").isEmpty())
    }

    @Test
    fun categoriesComeOutInDeclaredOrder() {
        val order = catalog.actions.byCategory().keys.toList()
        assertEquals(order.sortedBy { it.ordinal }, order)
    }

    @Test
    fun validationExplainsProblemsInPlainEnglish() {
        val toast = catalog.actions[OpIds.TOAST]!!
        assertEquals(listOf("Message can't be empty."), toast.problems(params("message" to "  ")))
        assertTrue(toast.problems(params("message" to "hi")).isEmpty())

        val interval = catalog.triggers[TriggerIds.INTERVAL]!!
        assertTrue(interval.problems(params("minutes" to 1)).single().contains("at least 5"))
        assertTrue(interval.problems(params("minutes" to 30)).isEmpty())

        val volume = catalog.actions[OpIds.SET_VOLUME]!!
        assertTrue(volume.problems(params("stream" to "MEDIA", "percent" to 150)).single().contains("at most 100"))
        assertTrue(volume.problems(params("stream" to "BOGUS", "percent" to 10)).single().contains("unknown value"))

        val once = catalog.triggers[TriggerIds.ONCE]!!
        assertEquals(listOf("Choose a date and time."), once.problems(EmptyParams))

        val days = catalog.constraints[CheckIds.DAYS_OF_WEEK]!!
        assertTrue(days.problems(params("days" to listOf(1, 9))).single().contains("invalid day"))
    }

    @Test
    fun defaultsComeFromTheSchema() {
        val d = catalog.actions[OpIds.SET_VOLUME]!!.defaultParams()
        assertEquals("MEDIA", d.str("stream"))
        assertEquals(50, d.int("percent"))
        val t = catalog.triggers[TriggerIds.SCHEDULE]!!.defaultParams()
        assertEquals(8, t.int("hour"))
        assertEquals(0, t.int("minute"))
        // A fresh entry built from defaults must not start out invalid, except where the user has to choose something.
        assertTrue(catalog.actions[OpIds.SET_VOLUME]!!.problems(d).isEmpty())
        assertTrue(catalog.triggers[TriggerIds.SCHEDULE]!!.problems(t).isEmpty())
    }

    @Test
    fun statusReflectsEditionSdkPermissionsAndListener() {
        val granted = setOf(Perm.NOTIFICATIONS.id)
        val notify = catalog.actions[OpIds.NOTIFY]!!
        assertEquals(SpecStatus.READY, notify.status(Edition.FULL, 35, granted))
        assertEquals(SpecStatus.NEEDS_PERMISSION, notify.status(Edition.FULL, 35, emptySet()))

        val fullOnly = notify.copy(edition = EditionReq.FULL_ONLY)
        assertEquals(SpecStatus.NOT_IN_EDITION, fullOnly.status(Edition.PLAY_SAFE, 35, granted))
        assertEquals(SpecStatus.READY, fullOnly.status(Edition.FULL, 35, granted))

        val newer = notify.copy(minSdk = 34)
        assertEquals(SpecStatus.UNSUPPORTED, newer.status(Edition.FULL, 30, granted))

        val listener = notify.copy(needsListener = true, batteryCost = BatteryCost.MEDIUM)
        assertEquals(SpecStatus.USES_LISTENER, listener.status(Edition.FULL, 35, granted))
    }

    @Test
    fun paramSchemaValidatesPicksAndTimes() {
        val schema = listOf(
            ParamSpec.Pick("k", "Kind", listOf(Choice("a", "A"), Choice("b", "B")), "a"),
            ParamSpec.TimeOfDay("h", "m", "Time"),
        )
        assertTrue(validateParams(schema, params("k" to "B", "h" to 5, "m" to 30)).isEmpty())
        assertEquals(2, validateParams(schema, params("k" to "c", "h" to 25, "m" to 0)).size)
    }
}
