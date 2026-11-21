package com.androx.app.data

import com.androx.app.core.FlowMigration
import com.androx.app.core.FlowsFile
import com.androx.app.model.Automation
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Reads `flows.json` (format v2). On the first launch after updating from v0.0.1, when only `automations.json`
 * exists, the saved automations are migrated in memory and written as `flows.json`.
 *
 * Guarantees (spec §1 rule 6, §5.2):
 * - `automations.json` is never modified or deleted, so a downgrade or a failed migration loses nothing;
 * - once `flows.json` exists it always wins, so the migration never runs twice and never overwrites newer edits;
 * - migrating is deterministic, so even if it did run twice the result would be identical.
 */
class FlowStorage(private val dir: File) {
    private val flowsFile = JsonFile(File(dir, "flows.json"), FlowsFile.serializer()) { FlowsFile() }
    private val legacyFile = JsonFile(File(dir, "automations.json"), ListSerializer(Automation.serializer())) { emptyList() }

    /** True when the last read had to fall back to the last-good copy. */
    val hadRecovery: Boolean get() = flowsFile.hadRecovery

    fun loadOrMigrate(): FlowsFile {
        val target = File(dir, "flows.json")
        val backup = File(dir, "flows.json.bak")
        if (target.exists() || backup.exists()) return flowsFile.read()
        val legacy = File(dir, "automations.json")
        if (!legacy.exists()) return FlowsFile()
        val migrated = FlowsFile(flows = FlowMigration.migrateAll(legacyFile.read()))
        flowsFile.write(migrated)
        return migrated
    }

    fun save(file: FlowsFile) = flowsFile.write(file)

    /** "Recover from backup": replaces a damaged `flows.json` with the last-good copy. */
    fun recover(): Boolean = flowsFile.restoreFromBackup()
}
