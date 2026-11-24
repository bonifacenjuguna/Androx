package com.androx.app.data

import com.androx.app.core.net.InternetSettings
import com.androx.app.core.net.PollStateStore
import com.androx.app.core.net.RequestLogEntry
import com.androx.app.core.net.RequestLogFile
import com.androx.app.core.net.RequestLogs
import com.androx.app.core.net.SecretCipher
import com.androx.app.core.net.SecretReader
import com.androx.app.core.net.SecretVault
import com.androx.app.core.net.VaultFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** The internet rules (`internet.json`). Small, so it is read straight away: the first request must never beat the kill switch. */
class InternetSettingsRepository(dir: File) {
    private val file = JsonFile(File(dir, "internet.json"), InternetSettings.serializer()) { InternetSettings() }
    private val _state = MutableStateFlow(file.read())
    val state: StateFlow<InternetSettings> = _state.asStateFlow()

    @Synchronized
    fun update(transform: (InternetSettings) -> InternetSettings) {
        val next = transform(_state.value)
        _state.value = next
        file.write(next)
    }
}

/** The request log (`request_log.json`): every request, ping and lookup, without secrets. Newest first. */
class RequestLogRepository(dir: File, private val scope: CoroutineScope, private val retentionDays: () -> Int) {
    private val file = JsonFile(File(dir, "request_log.json"), RequestLogFile.serializer()) { RequestLogFile() }
    private val _state = MutableStateFlow(file.read())
    val state: StateFlow<RequestLogFile> = _state.asStateFlow()

    fun add(entry: RequestLogEntry) {
        // The in-memory list is updated at once (the hourly cap reads it); the disk write happens in the background.
        val next = synchronized(this) { RequestLogs.add(_state.value, entry, retentionDays()).also { _state.value = it } }
        scope.launch(Dispatchers.IO) { runCatching { synchronized(file) { file.write(next) } } }
    }

    @Synchronized
    fun clear() {
        _state.value = RequestLogFile()
        file.write(_state.value)
    }
}

/** The encrypted vault (`vault.json`). Only secret names are ever shown; values are read at the moment a request needs one. */
class VaultRepository(dir: File, cipher: SecretCipher) {
    private val file = JsonFile(File(dir, "vault.json"), VaultFile.serializer()) { VaultFile() }
    private val vault = SecretVault(cipher, file.read())
    private val _names = MutableStateFlow(vault.names())
    val names: StateFlow<List<String>> = _names.asStateFlow()

    /** What the engine is given: read-only, one secret at a time. */
    val reader: SecretReader = vault

    @Synchronized
    fun put(name: String, value: String) {
        file.write(vault.put(name, value))
        _names.value = vault.names()
    }

    @Synchronized
    fun remove(name: String) {
        file.write(vault.remove(name))
        _names.value = vault.names()
    }

    fun has(name: String): Boolean = vault.has(name)

    /** Every secret value, for the optional passphrase export only. */
    fun valuesByName(): Map<String, String> = vault.names().mapNotNull { n -> vault.get(n)?.let { n to it } }.toMap()
}

/** Where each "Web address changes" trigger left off (`poll_state.json`). */
class PollStateRepository(dir: File) : PollStateStore {
    private val file = JsonFile(File(dir, "poll_state.json"), MapSerializer(String.serializer(), String.serializer())) { emptyMap() }
    private var map: Map<String, String> = file.read()

    @Synchronized
    override fun get(entryId: String): String? = map[entryId]

    @Synchronized
    override fun set(entryId: String, state: String) {
        map = map + (entryId to state)
        file.write(map)
    }
}
