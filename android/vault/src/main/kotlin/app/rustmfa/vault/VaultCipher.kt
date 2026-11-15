package app.rustmfa.vault

import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM with a 128-bit tag; the ciphertext carries the tag at its end. */
object VaultCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = init(Cipher.ENCRYPT_MODE, key, nonce, aad)
        return cipher.doFinal(plaintext)
    }

    /** Throws [AEADBadTagException] on any authentication or decryption failure. */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = init(Cipher.DECRYPT_MODE, key, nonce, aad)
        try {
            return cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw AEADBadTagException("decryption failed")
        }
    }

    private fun init(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        require(key.size == 32) { "key must be 32 bytes" }
        require(nonce.size == 12) { "nonce must be 12 bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher
    }
}
