package app.rustmfa.vault

import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VaultCipherTest {
    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(12) { (it + 100).toByte() }
    private val aad = "header".toByteArray()
    private val plaintext = "secret plaintext".toByteArray()

    private fun ByteArray.flipped(index: Int): ByteArray =
        copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }

    @Test
    fun roundTrip() {
        val ct = VaultCipher.encrypt(key, nonce, aad, plaintext)
        assertEquals(plaintext.size + 16, ct.size)
        assertContentEquals(plaintext, VaultCipher.decrypt(key, nonce, aad, ct))
    }

    @Test
    fun flippedCiphertextBitFails() {
        val ct = VaultCipher.encrypt(key, nonce, aad, plaintext)
        assertFailsWith<AEADBadTagException> { VaultCipher.decrypt(key, nonce, aad, ct.flipped(0)) }
        assertFailsWith<AEADBadTagException> { VaultCipher.decrypt(key, nonce, aad, ct.flipped(ct.size - 1)) }
    }

    @Test
    fun flippedNonceBitFails() {
        val ct = VaultCipher.encrypt(key, nonce, aad, plaintext)
        assertFailsWith<AEADBadTagException> { VaultCipher.decrypt(key, nonce.flipped(5), aad, ct) }
    }

    @Test
    fun flippedAadBitFails() {
        val ct = VaultCipher.encrypt(key, nonce, aad, plaintext)
        assertFailsWith<AEADBadTagException> { VaultCipher.decrypt(key, nonce, aad.flipped(2), ct) }
    }

    @Test
    fun wrongKeyFails() {
        val ct = VaultCipher.encrypt(key, nonce, aad, plaintext)
        assertFailsWith<AEADBadTagException> { VaultCipher.decrypt(key.flipped(31), nonce, aad, ct) }
    }

    @Test
    fun truncatedCiphertextFails() {
        assertFailsWith<AEADBadTagException> { VaultCipher.decrypt(key, nonce, aad, ByteArray(5)) }
    }

    @Test
    fun rejectsWrongKeyAndNonceLengths() {
        assertFailsWith<IllegalArgumentException> { VaultCipher.encrypt(ByteArray(16), nonce, aad, plaintext) }
        assertFailsWith<IllegalArgumentException> { VaultCipher.encrypt(key, ByteArray(16), aad, plaintext) }
    }
}
