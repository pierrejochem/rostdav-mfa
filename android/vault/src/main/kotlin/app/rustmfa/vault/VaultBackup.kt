package app.rustmfa.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.CharBuffer
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.AEADBadTagException

/**
 * Passphrase-encrypted backup file:
 * `{"app":"rostdav-mfa","v":1,"kdf":{"alg":"argon2id","m":..,"t":..,"p":..,"salt":".."},"enc":{"alg":"aes-256-gcm","nonce":".."},"data":".."}`.
 * The associated data is the header object (`app`, `v`, `kdf`, `enc` in that order, no whitespace) as UTF-8,
 * always rebuilt by [headerText] so export and import use the same bytes.
 */
object VaultBackup {
    const val MIN_PASSPHRASE = 8

    private const val APP = "rostdav-mfa"
    private const val VERSION = 1L
    private const val KDF_ALG = "argon2id"
    private const val ENC_ALG = "aes-256-gcm"
    private const val DEFAULT_MEMORY_KIB = 65_536
    private const val DEFAULT_ITERATIONS = 3
    private const val PARALLELISM = 1
    private const val MAX_MEMORY_KIB = 131_072L
    private const val MAX_ITERATIONS = 10L
    private const val MAX_PARALLELISM = 4L
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BYTES = 32

    private val b64Encoder = Base64.getEncoder()
    private val b64Decoder = Base64.getDecoder()

    fun export(accounts: List<OtpAccount>, passphrase: CharArray, random: SecureRandom = SecureRandom()): ByteArray =
        export(accounts, passphrase, random, DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS)

    internal fun export(
        accounts: List<OtpAccount>,
        passphrase: CharArray,
        random: SecureRandom,
        memoryKiB: Int,
        iterations: Int,
    ): ByteArray {
        val password = passphraseBytes(passphrase, requireMinimum = true)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = headerText(memoryKiB, iterations, PARALLELISM, salt, nonce)
        val key = try {
            VaultKdf.argon2id(password, salt, memoryKiB, iterations, PARALLELISM, KEY_BYTES)
        } finally {
            password.fill(0)
        }
        val plaintext = VaultAccountsJson.encode(accounts)
        try {
            val ciphertext = VaultCipher.encrypt(key, nonce, header.toByteArray(Charsets.UTF_8), plaintext)
            val file = header.dropLast(1) + ",\"data\":\"" + b64Encoder.encodeToString(ciphertext) + "\"}"
            return file.toByteArray(Charsets.UTF_8)
        } finally {
            key.fill(0)
            plaintext.fill(0)
        }
    }

    fun import(bytes: ByteArray, passphrase: CharArray): List<OtpAccount> {
        val root = parseObject(bytes)
        if (stringMember(root, "app") != APP) throw BackupFormatException("not a rostdav-mfa backup")
        if (numberMember(root, "v") != VERSION) throw BackupFormatException("unsupported backup version")
        val kdf = objectMember(root, "kdf")
        val enc = objectMember(root, "enc")
        if (stringMember(kdf, "alg") != KDF_ALG) throw BackupFormatException("unsupported key derivation")
        if (stringMember(enc, "alg") != ENC_ALG) throw BackupFormatException("unsupported encryption")

        // Limits are checked before anything is derived or allocated.
        val p = numberMember(kdf, "p")
        if (p !in 1..MAX_PARALLELISM) throw BackupFormatException("parallelism out of range")
        val m = numberMember(kdf, "m")
        if (m !in (8 * p)..MAX_MEMORY_KIB) throw BackupFormatException("memory out of range")
        val t = numberMember(kdf, "t")
        if (t !in 1..MAX_ITERATIONS) throw BackupFormatException("iterations out of range")
        val salt = base64Member(kdf, "salt")
        if (salt.size != SALT_BYTES) throw BackupFormatException("salt must be 16 bytes")
        val nonce = base64Member(enc, "nonce")
        if (nonce.size != NONCE_BYTES) throw BackupFormatException("nonce must be 12 bytes")
        val data = base64Member(root, "data")

        val header = headerText(m.toInt(), t.toInt(), p.toInt(), salt, nonce).toByteArray(Charsets.UTF_8)
        val password = passphraseBytes(passphrase, requireMinimum = false)
        val key = try {
            VaultKdf.argon2id(password, salt, m.toInt(), t.toInt(), p.toInt(), KEY_BYTES)
        } finally {
            password.fill(0)
        }
        val plaintext = try {
            VaultCipher.decrypt(key, nonce, header, data)
        } catch (e: AEADBadTagException) {
            throw BackupDecryptException()
        } finally {
            key.fill(0)
        }
        try {
            return VaultAccountsJson.decode(plaintext)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("backup contents are invalid")
        } finally {
            plaintext.fill(0)
        }
    }

    /** The authenticated header: `app`, `v`, `kdf`, `enc` in fixed order, no whitespace. */
    internal fun headerText(memoryKiB: Int, iterations: Int, parallelism: Int, salt: ByteArray, nonce: ByteArray): String =
        "{\"app\":\"$APP\",\"v\":$VERSION," +
            "\"kdf\":{\"alg\":\"$KDF_ALG\",\"m\":$memoryKiB,\"t\":$iterations,\"p\":$parallelism," +
            "\"salt\":\"${b64Encoder.encodeToString(salt)}\"}," +
            "\"enc\":{\"alg\":\"$ENC_ALG\",\"nonce\":\"${b64Encoder.encodeToString(nonce)}\"}}"

    /** NFKC-normalised UTF-8 bytes of the passphrase. The caller zeroes the result. */
    private fun passphraseBytes(passphrase: CharArray, requireMinimum: Boolean): ByteArray {
        // Normalizer returns an immutable String; it cannot be zeroed and is left to the garbage collector.
        val normalized = Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFKC)
        if (requireMinimum && normalized.codePointCount(0, normalized.length) < MIN_PASSPHRASE) {
            throw BackupFormatException("passphrase must be at least $MIN_PASSPHRASE characters")
        }
        val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(normalized))
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        if (buffer.hasArray()) buffer.array().fill(0)
        return out
    }

    private fun parseObject(bytes: ByteArray): JsonObject {
        val element = try {
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw BackupFormatException("not a rostdav-mfa backup")
        }
        return element as? JsonObject ?: throw BackupFormatException("not a rostdav-mfa backup")
    }

    private fun member(obj: JsonObject, name: String): JsonElement =
        obj[name] ?: throw BackupFormatException("missing member $name")

    private fun objectMember(obj: JsonObject, name: String): JsonObject =
        member(obj, name) as? JsonObject ?: throw BackupFormatException("member $name must be an object")

    private fun stringMember(obj: JsonObject, name: String): String {
        val value = member(obj, name) as? JsonPrimitive
        if (value == null || !value.isString) throw BackupFormatException("member $name must be a string")
        return value.content
    }

    /** A JSON integer that fits a Long; anything else is a format error. */
    private fun numberMember(obj: JsonObject, name: String): Long {
        val value = member(obj, name) as? JsonPrimitive
        if (value == null || value.isString) throw BackupFormatException("member $name must be a number")
        return value.longOrNull ?: throw BackupFormatException("member $name must be an integer")
    }

    private fun base64Member(obj: JsonObject, name: String): ByteArray =
        try {
            b64Decoder.decode(stringMember(obj, name))
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("member $name is not valid base64")
        }
}
