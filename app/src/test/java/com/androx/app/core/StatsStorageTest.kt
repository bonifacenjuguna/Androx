package com.androx.app.core

import com.androx.app.data.FlowStorage
import com.androx.app.data.JsonFile
import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.ExecutionStatus
import com.androx.app.model.Trigger
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

class StatsStorageTest {
    private val utc = ZoneId.of("UTC")
    private fun at(day: Int, hour: Int = 12) = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, utc).toInstant().toEpochMilli()
    private fun tempDir(): File = Files.createTempDirectory("androx-test").toFile().also { it.deleteOnExit() }

    // ── stats ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun recordCountsPerFlowPerDay() {
        var s = StatsFile()
        s = Stats.record(s, "a", at(20), ExecutionStatus.SUCCESS, 100, utc)
        s = Stats.record(s, "a", at(20, 15), ExecutionStatus.FAILED, 50, utc)
        s = Stats.record(s, "b", at(20), ExecutionStatus.SKIPPED, 0, utc)
        s = Stats.record(s, "a", at(21), ExecutionStatus.SUCCESS, 10, utc)
        assertEquals(DayStat(success = 1, failure = 1, durationMs = 150), s.days["2026-11-20"]!!["a"])
        assertEquals(1, s.days["2026-11-20"]!!["b"]!!.skipped)
        assertEquals(2, Stats.total(s, "a").success)
        assertEquals(4, Stats.total(s).runs)
    }

    @Test
    fun lastDaysIsOldestFirstAndPadsEmptyDays() {
        var s = StatsFile()
        s = Stats.record(s, "a", at(21), ExecutionStatus.SUCCESS, 1, utc)
        s = Stats.record(s, "a", at(19), ExecutionStatus.SUCCESS, 1, utc)
        val week = Stats.lastDays(s, at(21), 3, null, utc)
        assertEquals(listOf(1, 0, 1), week.map { it.success })
    }

    @Test
    fun successRateIgnoresSkippedRuns() {
        assertNull(Stats.successRate(DayStat(skipped = 5)))
        assertEquals(0.75, Stats.successRate(DayStat(success = 3, failure = 1, skipped = 9))!!, 1e-9)
    }

    @Test
    fun streakCountsBackAndSurvivesAQuietToday() {
        var s = StatsFile()
        for (d in 17..19) s = Stats.record(s, "a", at(d), ExecutionStatus.SUCCESS, 1, utc)
        assertEquals(3, Stats.streakDays(s, at(20), utc)) // today is quiet, yesterday counts
        s = Stats.record(s, "a", at(20), ExecutionStatus.SUCCESS, 1, utc)
        assertEquals(4, Stats.streakDays(s, at(20), utc))
        s = Stats.record(s, "a", at(18, 20), ExecutionStatus.FAILED, 1, utc)
        assertEquals(2, Stats.streakDays(s, at(20), utc)) // a failure on the 18th breaks it: only 19 and 20 count
    }

    @Test
    fun streakIsZeroWithNoRuns() {
        assertEquals(0, Stats.streakDays(StatsFile(), at(20), utc))
    }

    @Test
    fun busiestFlowAndTimeSavedEstimate() {
        var s = StatsFile()
        repeat(3) { s = Stats.record(s, "a", at(20), ExecutionStatus.SUCCESS, 1, utc) }
        repeat(5) { s = Stats.record(s, "b", at(19), ExecutionStatus.SUCCESS, 1, utc) }
        assertEquals("b" to 5, Stats.busiest(s, at(21), 7, utc))
        assertEquals("a" to 3, Stats.busiest(s, at(20), 1, utc))
        assertNull(Stats.busiest(StatsFile(), at(20), 7, utc))
        assertEquals(3L * Stats.ESTIMATED_SECONDS_SAVED_PER_RUN, Stats.estimatedSecondsSaved(DayStat(success = 3)))
    }

    @Test
    fun forgetDropsOnlyTheNamedFlows() {
        var s = StatsFile()
        s = Stats.record(s, "a", at(20), ExecutionStatus.SUCCESS, 1, utc)
        s = Stats.record(s, "b", at(20), ExecutionStatus.SUCCESS, 1, utc)
        s = Stats.record(s, "a", at(19), ExecutionStatus.SUCCESS, 1, utc)
        val f = Stats.forget(s, setOf("a"))
        assertEquals(setOf("2026-11-20"), f.days.keys)
        assertEquals(setOf("b"), f.days["2026-11-20"]!!.keys)
    }

    // ── trash ───────────────────────────────────────────────────────────────────────────────

    private fun flow(id: String) = Flow(id = id, name = id, enabled = true)

    @Test
    fun trashDisablesAndRestoreComesBackOff() {
        val t = Trash.moveToTrash(flow("a"), 1000L)
        assertTrue(t.inTrash)
        assertFalse(t.enabled)
        val r = Trash.restore(t, 2000L)
        assertFalse(r.inTrash)
        assertFalse(r.enabled)
        assertEquals(t, Trash.moveToTrash(t, 5000L)) // already trashed: unchanged
    }

    @Test
    fun trashExpiresAfterThirtyDays() {
        val day = 24L * 60 * 60 * 1000
        val old = Trash.moveToTrash(flow("old"), 0L)
        val fresh = Trash.moveToTrash(flow("new"), 29 * day)
        val live = flow("live")
        val now = 31 * day
        assertEquals(listOf("old"), Trash.expired(listOf(old, fresh, live), now).map { it.id })
        assertEquals(0, Trash.daysLeft(old, now))
        assertEquals(28, Trash.daysLeft(fresh, now))
        assertEquals(listOf("live"), Trash.active(listOf(old, fresh, live)).map { it.id })
        assertEquals(2, Trash.trashed(listOf(old, fresh, live)).size)
    }

    @Test
    fun trashSizeFormats() {
        assertEquals(0L, Trash.sizeBytes(listOf(flow("a"))))
        assertTrue(Trash.sizeBytes(listOf(Trash.moveToTrash(flow("a"), 1L))) > 0)
        assertEquals("500 B", Trash.formatSize(500))
        assertEquals("33 KB", Trash.formatSize(33 * 1024L))
        assertEquals("2 MB", Trash.formatSize(2L * 1024 * 1024))
    }

    // ── atomic storage ──────────────────────────────────────────────────────────────────────

    private fun stringFile(dir: File) = JsonFile(File(dir, "t.json"), ListSerializer(String.serializer())) { emptyList() }

    @Test
    fun secondWriteKeepsTheFirstAsLastGood() {
        val dir = tempDir()
        val f = stringFile(dir)
        f.write(listOf("one"))
        assertFalse(f.hasBackup())
        f.write(listOf("two"))
        assertTrue(f.hasBackup())
        assertEquals(listOf("two"), f.read())
        assertEquals(listOf("one"), f.readBackup())
    }

    @Test
    fun damagedFileFallsBackToLastGoodAndIsKept() {
        val dir = tempDir()
        val f = stringFile(dir)
        f.write(listOf("one"))
        f.write(listOf("two"))
        File(dir, "t.json").writeText("{{ garbage")
        assertEquals(listOf("one"), f.read())
        assertTrue(f.hadRecovery)
        assertTrue(File(dir, "t.json.corrupt").exists())
    }

    @Test
    fun aDamagedFileIsNeverCopiedOverTheGoodBackup() {
        val dir = tempDir()
        val f = stringFile(dir)
        f.write(listOf("one"))
        f.write(listOf("two"))
        File(dir, "t.json").writeText("{{ garbage")
        f.write(listOf("three"))
        assertEquals(listOf("one"), f.readBackup()) // garbage was not promoted to last-good
        assertEquals(listOf("three"), f.read())
    }

    @Test
    fun restoreFromBackupBringsTheOldFileBack() {
        val dir = tempDir()
        val f = stringFile(dir)
        f.write(listOf("one"))
        f.write(listOf("two"))
        assertTrue(f.restoreFromBackup())
        assertEquals(listOf("one"), f.read())
        assertFalse(stringFile(tempDir()).restoreFromBackup())
    }

    @Test
    fun missingMainFileUsesBackupAfterACrashBetweenRenames() {
        val dir = tempDir()
        val f = stringFile(dir)
        f.write(listOf("one"))
        f.write(listOf("two"))
        File(dir, "t.json").delete()
        assertEquals(listOf("one"), f.read())
    }

    @Test
    fun freshInstallReadsFallback() {
        val f = stringFile(tempDir())
        assertTrue(f.read().isEmpty())
        assertFalse(f.hadRecovery)
    }

    // ── flow storage / migration on first launch ────────────────────────────────────────────

    private fun writeLegacy(dir: File, list: List<Automation>) =
        File(dir, "automations.json").writeText(JsonFile.json.encodeToString(ListSerializer(Automation.serializer()), list))

    @Test
    fun firstLaunchAfterUpdateMigratesAndKeepsTheOldFile() {
        val dir = tempDir()
        val a = Automation("a1", "Morning", trigger = Trigger.Schedule(7, 0), actions = listOf(Action.ShowToast("hi")))
        writeLegacy(dir, listOf(a))
        val before = File(dir, "automations.json").readText()

        val file = FlowStorage(dir).loadOrMigrate()
        assertEquals(1, file.flows.size)
        assertEquals("a1", file.flows[0].id)
        assertTrue(File(dir, "flows.json").exists())
        assertEquals(before, File(dir, "automations.json").readText())
    }

    @Test
    fun migrationNeverRunsAgainOrOverwritesNewerEdits() {
        val dir = tempDir()
        writeLegacy(dir, listOf(Automation("a1", "Old name")))
        val storage = FlowStorage(dir)
        val first = storage.loadOrMigrate()
        storage.save(first.copy(flows = first.flows.map { it.copy(name = "Renamed in 0.0.2") }))
        writeLegacy(dir, listOf(Automation("a1", "Changed by an old build")))
        val again = FlowStorage(dir).loadOrMigrate()
        assertEquals("Renamed in 0.0.2", again.flows.single().name)
    }

    @Test
    fun freshInstallHasNothingToMigrate() {
        val dir = tempDir()
        val file = FlowStorage(dir).loadOrMigrate()
        assertTrue(file.flows.isEmpty())
        assertFalse(File(dir, "flows.json").exists())
    }

    @Test
    fun damagedFlowsFileRecoversFromLastGood() {
        val dir = tempDir()
        val storage = FlowStorage(dir)
        storage.save(FlowsFile(flows = listOf(flow("a"))))
        storage.save(FlowsFile(flows = listOf(flow("a"), flow("b"))))
        File(dir, "flows.json").writeText("nope")
        val loaded = FlowStorage(dir).loadOrMigrate()
        assertEquals(listOf("a"), loaded.flows.map { it.id })
        assertNotNull(File(dir, "flows.json.corrupt").takeIf { it.exists() })
    }
}
