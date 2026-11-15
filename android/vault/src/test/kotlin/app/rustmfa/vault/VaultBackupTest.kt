package app.rustmfa.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultBackupTest {
    private val totp = OtpAccount(
        id = "a1", type = OtpType.TOTP, issuer = "Example", label = "alice@example.com",
        secret = "12345678901234567890".toByteArray(), algorithm = OtpAlgorithm.SHA256, digits = 8, period = 60,
    )
    private val hotp = OtpAccount(
        id = "b2", type = OtpType.HOTP, issuer = "", label = "bob",
        secret = byteArrayOf(1, 2, 3, 4, 5), counter = 42,
    )
    private val pass = "correct horse".toCharArray()

    private fun smallExport(accounts: List<OtpAccount>, passphrase: CharArray = pass): ByteArray =
        VaultBackup.export(accounts, passphrase, SecureRandom(), memoryKiB = 64, iterations = 1)

    private fun parse(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun JsonObject.with(key: String, value: kotlinx.serialization.json.JsonElement) =
        JsonObject(this + (key to value))

    private fun JsonObject.toBytes(): ByteArray = toString().toByteArray()

    private fun flipBase64(b64: String, index: Int): String {
        val raw = Base64.getDecoder().decode(b64)
        raw[index] = (raw[index].toInt() xor 0x01).toByte()
        return Base64.getEncoder().encodeToString(raw)
    }

    private fun hostile(
        app: String = "rostdav-mfa", v: Int = 1, kdfAlg: String = "argon2id",
        m: Long = 65536, t: Long = 3, p: Long = 1, encAlg: String = "aes-256-gcm",
    ): ByteArray {
        val salt = Base64.getEncoder().encodeToString(ByteArray(16))
        val nonce = Base64.getEncoder().encodeToString(ByteArray(12))
        val data = Base64.getEncoder().encodeToString(ByteArray(32))
        return ("{\"app\":\"$app\",\"v\":$v,\"kdf\":{\"alg\":\"$kdfAlg\",\"m\":$m,\"t\":$t,\"p\":$p,\"salt\":\"$salt\"}," +
            "\"enc\":{\"alg\":\"$encAlg\",\"nonce\":\"$nonce\"},\"data\":\"$data\"}").toByteArray()
    }

    @Test
    fun roundTripsTotpAndHotp() {
        val bytes = smallExport(listOf(totp, hotp))
        val restored = VaultBackup.import(bytes, pass)
        assertEquals(listOf(totp, hotp), restored)
        assertEquals(42, restored[1].counter)
    }

    @Test
    fun emptyListRoundTrips() {
        assertEquals(emptyList(), VaultBackup.import(smallExport(emptyList()), pass))
    }

    @Test
    fun exportHasExactlyTheExpectedMembers() {
        val obj = parse(smallExport(listOf(totp)))
        assertEquals(setOf("app", "v", "kdf", "enc", "data"), obj.keys)
        assertEquals("rostdav-mfa", obj["app"]!!.jsonPrimitive.content)
        assertEquals(setOf("alg", "m", "t", "p", "salt"), obj["kdf"]!!.jsonObject.keys)
        assertEquals(setOf("alg", "nonce"), obj["enc"]!!.jsonObject.keys)
    }

    @Test
    fun exportDoesNotContainTheSecret() {
        val text = smallExport(listOf(totp)).decodeToString()
        assertFalse(text.contains(Base32.encode(totp.secret)))
        assertFalse(text.contains("alice"))
    }

    @Test
    fun productionExportUsesDefaultParameters() {
        val kdf = parse(VaultBackup.export(listOf(totp), pass))["kdf"]!!.jsonObject
        assertEquals(65536, kdf["m"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, kdf["t"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, kdf["p"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun exportRejectsShortPassphrase() {
        assertFailsWith<BackupFormatException> { smallExport(listOf(totp), "1234567".toCharArray()) }
        assertFailsWith<BackupFormatException> { VaultBackup.export(listOf(totp), "1234567".toCharArray()) }
    }

    @Test
    fun wrongPassphraseFails() {
        val bytes = smallExport(listOf(totp))
        assertFailsWith<BackupDecryptException> { VaultBackup.import(bytes, "wrong passphrase".toCharArray()) }
    }

    @Test
    fun flippedDataBitFails() {
        val obj = parse(smallExport(listOf(totp)))
        val bad = obj.with("data", JsonPrimitive(flipBase64(obj["data"]!!.jsonPrimitive.content, 3)))
        assertFailsWith<BackupDecryptException> { VaultBackup.import(bad.toBytes(), pass) }
    }

    @Test
    fun flippedNonceBitFails() {
        val obj = parse(smallExport(listOf(totp)))
        val enc = obj["enc"]!!.jsonObject
        val badEnc = enc.with("nonce", JsonPrimitive(flipBase64(enc["nonce"]!!.jsonPrimitive.content, 0)))
        assertFailsWith<BackupDecryptException> { VaultBackup.import(obj.with("enc", badEnc).toBytes(), pass) }
    }

    @Test
    fun changedMemoryParameterFailsAuthentication() {
        val obj = parse(smallExport(listOf(totp)))
        val kdf = obj["kdf"]!!.jsonObject
        val badKdf = kdf.with("m", JsonPrimitive(128))
        assertFailsWith<BackupDecryptException> { VaultBackup.import(obj.with("kdf", badKdf).toBytes(), pass) }
    }

    @Test
    fun headerIsBoundAsAssociatedData() {
        // Same key, different header bytes: only the AAD differs, so this proves authentication covers the header.
        val obj = parse(smallExport(listOf(totp)))
        val salt = Base64.getDecoder().decode(obj["kdf"]!!.jsonObject["salt"]!!.jsonPrimitive.content)
        val nonce = Base64.getDecoder().decode(obj["enc"]!!.jsonObject["nonce"]!!.jsonPrimitive.content)
        val data = Base64.getDecoder().decode(obj["data"]!!.jsonPrimitive.content)
        val key = VaultKdf.argon2id("correct horse".toByteArray(), salt, 64, 1, 1)
        val header = VaultBackup.headerText(64, 1, 1, salt, nonce).toByteArray()
        VaultCipher.decrypt(key, nonce, header, data)
        val other = VaultBackup.headerText(64, 1, 2, salt, nonce).toByteArray()
        assertFailsWith<javax.crypto.AEADBadTagException> { VaultCipher.decrypt(key, nonce, other, data) }
    }

    @Test
    fun unknownHeaderValuesAreFormatErrors() {
        assertFailsWith<BackupFormatException> { VaultBackup.import(hostile(v = 2), pass) }
        assertFailsWith<BackupFormatException> { VaultBackup.import(hostile(app = "x"), pass) }
        assertFailsWith<BackupFormatException> { VaultBackup.import(hostile(kdfAlg = "argon2i"), pass) }
        assertFailsWith<BackupFormatException> { VaultBackup.import(hostile(encAlg = "aes-128-gcm"), pass) }
    }

    @Test
    fun hostileParametersAreRejectedQuickly() {
        // warm up class loading so the timing measures only the import itself
        assertFailsWith<BackupFormatException> { VaultBackup.import(hostile(t = 11), pass) }
        val cases = listOf(hostile(m = 4_000_000_000), hostile(t = 11), hostile(p = 5), hostile(m = 131_073))
        for (bytes in cases) {
            val start = System.nanoTime()
            assertFailsWith<BackupFormatException> { VaultBackup.import(bytes, pass) }
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue(ms < 50, "took $ms ms")
        }
    }

    @Test
    fun badSaltOrNonceLengthIsFormatError() {
        val obj = parse(hostile())
        val kdf = obj["kdf"]!!.jsonObject.with("salt", JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(15))))
        assertFailsWith<BackupFormatException> { VaultBackup.import(obj.with("kdf", kdf).toBytes(), pass) }
        val enc = obj["enc"]!!.jsonObject.with("nonce", JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(16))))
        assertFailsWith<BackupFormatException> { VaultBackup.import(obj.with("enc", enc).toBytes(), pass) }
    }

    @Test
    fun unicodePassphraseIsNormalised() {
        val composed = "café-pass".toCharArray()
        val decomposed = "café-pass".toCharArray()
        val bytes = smallExport(listOf(totp), composed)
        assertEquals(listOf(totp), VaultBackup.import(bytes, decomposed))
    }

    @Test
    fun nonJsonIsFormatError() {
        assertFailsWith<BackupFormatException> { VaultBackup.import("not json at all".toByteArray(), pass) }
        assertFailsWith<BackupFormatException> { VaultBackup.import(byteArrayOf(0xff.toByte(), 0x00), pass) }
        assertFailsWith<BackupFormatException> { VaultBackup.import("{}".toByteArray(), pass) }
    }

    @Test
    fun accountsJsonRoundTripsAndRejectsInvalid() {
        assertEquals(listOf(totp, hotp), VaultAccountsJson.decode(VaultAccountsJson.encode(listOf(totp, hotp))))
        val good = VaultAccountsJson.encode(listOf(totp)).decodeToString()
        assertFailsWith<IllegalArgumentException> { VaultAccountsJson.decode("nope".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { VaultAccountsJson.decode(good.replace("\"v\":1", "\"v\":2").toByteArray()) }
        assertFailsWith<IllegalArgumentException> { VaultAccountsJson.decode(good.replace("\"digits\":8", "\"digits\":7").toByteArray()) }
        assertFailsWith<IllegalArgumentException> { VaultAccountsJson.decode(good.replace("\"type\":\"TOTP\"", "\"type\":\"X\"").toByteArray()) }
        assertFailsWith<IllegalArgumentException> { VaultAccountsJson.decode("{\"v\":1}".toByteArray()) }
    }
}
