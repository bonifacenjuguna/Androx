package com.androx.app.core

import com.androx.app.data.AutomationRepository
import com.androx.app.data.FlowRepository
import com.androx.app.data.JsonFile
import com.androx.app.model.Action
import com.androx.app.model.Automation
import com.androx.app.model.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FlowRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val day = 24L * 60 * 60 * 1000
    private fun dir(): File = Files.createTempDirectory("androx-flows").toFile().also { it.deleteOnExit() }
    private fun auto(id: String, name: String = id, enabled: Boolean = true) =
        Automation(id, name, enabled, Trigger.Schedule(7, 0), actions = listOf(Action.ShowToast("hi")))

    private fun writeLegacy(dir: File, list: List<Automation>) =
        File(dir, "automations.json").writeText(JsonFile.json.encodeToString(ListSerializer(Automation.serializer()), list))

    @Test
    fun firstLaunchMigratesOldAutomations() = runBlocking {
        val d = dir()
        val old = listOf(auto("a"), auto("b", enabled = false))
        writeLegacy(d, old)
        val repo = FlowRepository(d, scope)
        val view = AutomationRepository(repo)
        repo.awaitLoaded()
        assertEquals(old, view.state.value)
        assertTrue(File(d, "flows.json").exists())
        assertTrue(File(d, "automations.json").exists())
        assertEquals("a", view.get("a")!!.id)
    }

    @Test
    fun changesArePersistedAndReloaded() = runBlocking {
        val d = dir()
        val repo = FlowRepository(d, scope)
        val view = AutomationRepository(repo)
        view.upsert(auto("a", "First"))
        view.upsertAll(listOf(auto("a", "Renamed"), auto("b")))
        view.setEnabled("b", false)
        assertEquals(listOf("Renamed", "b"), view.state.value.map { it.name })

        val again = FlowRepository(d, scope)
        val view2 = AutomationRepository(again)
        again.awaitLoaded()
        assertEquals(view.state.value, view2.state.value)
        assertFalse(view2.get("b")!!.enabled)
    }

    @Test
    fun deletingMovesToTrashAndRestoreBringsItBack() = runBlocking {
        val d = dir()
        val repo = FlowRepository(d, scope, clock = { 1_000L })
        val view = AutomationRepository(repo)
        view.upsert(auto("a"))
        view.delete("a")
        assertNull(view.get("a"))
        assertTrue(view.state.value.isEmpty())
        val stored = repo.get("a")
        assertNotNull(stored)
        assertEquals(1_000L, stored!!.deletedAt)
        assertFalse(stored.enabled)

        repo.restore("a")
        assertNotNull(view.get("a"))
        assertFalse(view.get("a")!!.enabled) // restored switched off
    }

    @Test
    fun undoAfterDeleteWorksThroughUpsert() = runBlocking {
        val repo = FlowRepository(dir(), scope)
        val view = AutomationRepository(repo)
        val a = auto("a")
        view.upsert(a)
        view.delete("a")
        view.upsert(a) // what the screens do for "Undo"
        assertEquals(a, view.get("a"))
        assertNull(repo.get("a")!!.deletedAt)
    }

    @Test
    fun oldTrashIsEmptiedOnLoad() = runBlocking {
        val d = dir()
        val gone = Trash.moveToTrash(FlowMigration.migrate(auto("old")), 0L)
        val recent = Trash.moveToTrash(FlowMigration.migrate(auto("recent")), 29 * day)
        val live = FlowMigration.migrate(auto("live"))
        File(d, "flows.json").writeText(JsonFile.json.encodeToString(FlowsFile.serializer(), FlowsFile(flows = listOf(gone, recent, live))))
        val repo = FlowRepository(d, scope, clock = { 31 * day })
        repo.awaitLoaded()
        assertEquals(listOf("recent", "live"), repo.state.value.flows.map { it.id })
    }

    @Test
    fun listenersHearAboutLoadAndEveryChange() = runBlocking {
        val repo = FlowRepository(dir(), scope)
        val seen = mutableListOf<Int>()
        repo.observe { seen += it.flows.size }
        repo.awaitLoaded()
        AutomationRepository(repo).upsert(auto("a"))
        assertEquals(listOf(0, 1), seen)

        val late = mutableListOf<Int>()
        repo.observe { late += it.flows.size }
        assertEquals(listOf(1), late) // called right away because data is already loaded
    }

    @Test
    fun theViewIsNeverBehindWhenLoadingCompletes() = runBlocking {
        val d = dir()
        writeLegacy(d, listOf(auto("a")))
        val repo = FlowRepository(d, scope)
        val view = AutomationRepository(repo)
        view.awaitLoaded()
        assertEquals(1, view.state.value.size) // alarms are armed from this value right after loading
    }

    @Test
    fun aDamagedFlowsFileRecoversFromTheLastGoodCopy() = runBlocking {
        val d = dir()
        val repo = FlowRepository(d, scope)
        val view = AutomationRepository(repo)
        view.upsert(auto("a"))
        view.upsert(auto("b"))
        File(d, "flows.json").writeText("{{ broken")
        val again = FlowRepository(d, scope)
        val view2 = AutomationRepository(again)
        again.awaitLoaded()
        assertTrue(again.recoveredFromBackup)
        assertEquals(listOf("a"), view2.state.value.map { it.id }) // the copy from before the last save
    }
}
