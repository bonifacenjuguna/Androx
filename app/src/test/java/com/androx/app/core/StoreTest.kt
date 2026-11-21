package com.androx.app.core

import com.androx.app.core.model.Flow
import com.androx.app.core.model.FlowsFile
import com.androx.app.core.store.DailyStats
import com.androx.app.core.store.DailyStatsFile
import com.androx.app.core.store.DurableJson
import com.androx.app.core.store.LoadSource
import com.androx.app.core.store.SafeFile
import com.androx.app.core.store.TrashPolicy
import com.androx.app.model.ExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class StoreTest {
    private fun tempDir(): File = Files.createTempDirectory("androx-test").toFile().also { it.deleteOnExit() }

    @Test
    fun writeThenRead() {
        val f = SafeFile(File(tempDir(), "a.txt"))
        f.write("hello")
        val r = f.read()
        assertEquals("hello", r.text)
        assertEquals(LoadSource.MAIN, r.source)
        assertFalse(r.recovered)
    }

    @Test
    fun missingFileIsNotARecovery() {
        val r = SafeFile(File(tempDir(), "none.txt")).read()
        assertNull(r.text)
        assertEquals(LoadSource.NONE, r.source)
        assertFalse(r.recovered)
    }

    @Test
    fun damagedMainFileFallsBackToLastGoodCopy() {
        val dir = tempDir()
        val f = SafeFile(File(dir, "a.json")) { it.startsWith("{") && it.endsWith("}") }
        f.write("{\"v\":1}")
        f.write("{\"v\":2}")
        File(dir, "a.json").writeText("{\"v\":3") // simulates a torn write
        val r = f.read()
        assertEquals(LoadSource.BACKUP, r.source)
        assertTrue(r.recovered)
        assertEquals("{\"v\":1}", r.text)
        assertTrue(f.hasDamage())
    }

    @Test
    fun aDamagedFileNeverOverwritesTheGoodBackup() {
        val dir = tempDir()
        val f = SafeFile(File(dir, "a.json")) { it.startsWith("{") && it.endsWith("}") }
        f.write("{\"v\":1}")
        f.write("{\"v\":2}")
        File(dir, "a.json").writeText("garbage")
        f.write("{\"v\":4}")
        assertEquals("{\"v\":1}", File(dir, "a.json.bak").readText())
    }

    @Test
    fun onlyABackupLeftStillLoads() {
        val dir = tempDir()
        val f = SafeFile(File(dir, "a.json"))
        f.write("one")
        f.write("two")
        File(dir, "a.json").delete()
        val r = f.read()
        assertEquals(LoadSource.BACKUP, r.source)
        assertNotNull(r.text)
    }

    @Test
    fun durableJsonSurvivesCorruption() {
        val dir = tempDir()
        val store = DurableJson(File(dir, "flows.json"), FlowsFile.serializer(), { FlowsFile() })
        store.write(FlowsFile(flows = listOf(Flow("1", "One"))))
        store.write(FlowsFile(flows = listOf(Flow("1", "One"), Flow("2", "Two"))))
        File(dir, "flows.json").writeText("{ not json")
        val loaded = store.read()
        assertTrue(loaded.recovered)
        assertEquals(listOf("1"), loaded.value.flows.map { it.id })
    }

    @Test
    fun dailyStatsAccumulateAndAreNeverTrimmed() {
        val d = LocalDate.of(2026, 11, 21)
        var s = DailyStatsFile()
        s = DailyStats.record(s, d, "a", ExecutionStatus.SUCCESS, 100)
        s = DailyStats.record(s, d, "a", ExecutionStatus.FAILED, 50)
        s = DailyStats.record(s, d, "b", ExecutionStatus.SKIPPED, 0)
        val all = DailyStats.forDay(s, d)
        assertEquals(1, all.success)
        assertEquals(1, all.failed)
        assertEquals(1, all.skipped)
        assertEquals(150L, all.durationMs)
        assertEquals(2, DailyStats.forDay(s, d, "a").runs)
        assertEquals(0.5, DailyStats.successRate(all)!!, 0.0)
        assertNull(DailyStats.successRate(DailyStats.forDay(s, d.minusDays(1))))
    }

    @Test
    fun streaksCountGoodDaysEndingTodayOrYesterday() {
        val today = LocalDate.of(2026, 11, 21)
        var s = DailyStatsFile()
        for (back in 1L..3L) s = DailyStats.record(s, today.minusDays(back), "a", ExecutionStatus.SUCCESS, 1)
        assertEquals(3, DailyStats.streakDays(s, today)) // nothing yet today: counts from yesterday
        s = DailyStats.record(s, today, "a", ExecutionStatus.SUCCESS, 1)
        assertEquals(4, DailyStats.streakDays(s, today))
        s = DailyStats.record(s, today, "a", ExecutionStatus.FAILED, 1)
        assertEquals(0, DailyStats.streakDays(s, today))
    }

    @Test
    fun seriesBusiestAndTimeSaved() {
        val today = LocalDate.of(2026, 11, 21)
        var s = DailyStatsFile()
        repeat(3) { s = DailyStats.record(s, today, "busy", ExecutionStatus.SUCCESS, 1) }
        s = DailyStats.record(s, today.minusDays(2), "quiet", ExecutionStatus.SUCCESS, 1)
        val series = DailyStats.series(s, today, 7)
        assertEquals(7, series.size)
        assertEquals(3, series.last().runs)
        assertEquals(1, series[4].runs)
        assertEquals("busy" to 3, DailyStats.busiestFlow(s, today.minusDays(6), today))
        assertEquals(90L, DailyStats.estimatedSecondsSaved(DailyStats.forDay(s, today)))
    }

    @Test
    fun trashRetention() {
        val day = 24L * 60 * 60 * 1000
        val f = Flow("1", "One", enabled = true)
        val t = TrashPolicy.trash(f, 1_000L)
        assertFalse(t.enabled)
        assertEquals(30, TrashPolicy.daysLeft(t, 1_000L))
        assertFalse(TrashPolicy.isExpired(t, 1_000L + 29 * day))
        assertTrue(TrashPolicy.isExpired(t, 1_000L + 30 * day))
        val (keep, gone) = TrashPolicy.purge(listOf(t), 1_000L + 31 * day)
        assertTrue(keep.isEmpty())
        assertEquals(1, gone.size)
        val restored = TrashPolicy.restore(t, 5L)
        assertNull(restored.deletedAt)
        assertFalse(restored.enabled)
        assertEquals("33 MB", TrashPolicy.formatSize(33L * 1024 * 1024 + 5))
    }
}
