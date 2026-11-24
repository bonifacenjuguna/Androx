package com.androx.app.core.net

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Reads one secret by name. The engine only ever gets this, never the whole vault. */
interface SecretReader {
    fun get(name: String): String?
}

/** Turns bytes into unreadable bytes and back. Android supplies one backed by the Keystore (the key never leaves it). */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

/** AES-256-GCM with a fresh random IV per message. [key] is 32 bytes. Used by the passphrase export and by tests. */
class AesGcmCipher(private val key: ByteArray) : SecretCipher {
    private val random = SecureRandom()

    init {
        require(key.size == 32) { "AES-256 needs a 32 byte key." }
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size > 12) { "That data is too short to be a secret." }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return c.doFinal(sealed.copyOfRange(12, sealed.size))
    }
}

@Serializable
data class VaultFile(
    val version: Int = 1,
    /** name → base64 of the encrypted value. Names are not secret; values never appear here in plain text. */
    val entries: Map<String, String> = emptyMap(),
)

/**
 * API keys, tokens and passwords (spec §14.8). Values are encrypted with [cipher] before they are stored, so the file on
 * disk holds nothing readable. Screens only ever list [names]; a value is read at the moment a request needs it.
 * The vault is not part of backups: [PassphraseExport] is the only way a copy leaves the phone, and only if asked.
 */
class SecretVault(private val cipher: SecretCipher, initial: VaultFile = VaultFile()) : SecretReader {
    private var file = initial

    @Synchronized
    fun snapshot(): VaultFile = file

    @Synchronized
    fun names(): List<String> = file.entries.keys.sorted()

    @Synchronized
    fun has(name: String): Boolean = name in file.entries

    @Synchronized
    override fun get(name: String): String? {
        val sealed = file.entries[name] ?: return null
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(sealed)), Charsets.UTF_8)
        } catch (e: Exception) {
            null // damaged or from another device: treated as missing rather than crashing a run
        }
    }

    @Synchronized
    fun put(name: String, value: String): VaultFile {
        require(isValidName(name)) { "A secret name can use letters, digits, _ . - and must not be empty." }
        file = file.copy(entries = file.entries + (name to Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)))))
        return file
    }

    @Synchronized
    fun remove(name: String): VaultFile {
        file = file.copy(entries = file.entries - name)
        return file
    }

    /** Every secret value, for redacting logs. */
    @Synchronized
    fun values(): List<String> = file.entries.keys.mapNotNull { get(it) }

    companion object {
        fun isValidName(name: String) = name.isNotEmpty() && name.length <= 64 && name.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }
    }
}

/**
 * The optional encrypted copy of the vault (spec §14.8): a key is derived from a passphrase with PBKDF2 and the names
 * and values are sealed with AES-GCM. A wrong passphrase fails cleanly and never returns partial data.
 */
object PassphraseExport {
    private const val ITERATIONS = 210_000
    private const val MAGIC = "androx-vault-1"

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase, salt, iterations, 256)).encoded

    /** [secrets] is name → plain value. Returns text safe to save anywhere. */
    fun seal(secrets: Map<String, String>, passphrase: CharArray, iterations: Int = ITERATIONS): String {
        require(passphrase.size >= 8) { "Use a passphrase of at least 8 characters." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val body = secrets.entries.joinToString("\n") { Base64.getEncoder().encodeToString(it.key.toByteArray()) + ":" + Base64.getEncoder().encodeToString(it.value.toByteArray()) }
        val sealed = AesGcmCipher(key(passphrase, salt, iterations)).encrypt((MAGIC + "\n" + body).toByteArray())
        return listOf(MAGIC, iterations.toString(), Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(sealed)).joinToString(".")
    }

    /** Throws [IllegalArgumentException] with a readable message for a wrong passphrase or a file that isn't an export. */
    fun open(text: String, passphrase: CharArray): Map<String, String> {
        val parts = text.trim().split('.')
        require(parts.size == 4 && parts[0] == MAGIC) { "That isn't an Androx secrets export." }
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1_000..2_000_000 } ?: throw IllegalArgumentException("That isn't an Androx secrets export.")
        val plain = try {
            AesGcmCipher(key(passphrase, Base64.getDecoder().decode(parts[2]), iterations)).decrypt(Base64.getDecoder().decode(parts[3]))
        } catch (e: Exception) {
            throw IllegalArgumentException("Wrong passphrase, or the file is damaged.")
        }
        val lines = String(plain).lines()
        require(lines.firstOrNull() == MAGIC) { "Wrong passphrase, or the file is damaged." }
        return lines.drop(1).filter { it.isNotBlank() }.associate { line ->
            val (n, v) = line.split(':', limit = 2)
            String(Base64.getDecoder().decode(n)) to String(Base64.getDecoder().decode(v))
        }
    }
}
