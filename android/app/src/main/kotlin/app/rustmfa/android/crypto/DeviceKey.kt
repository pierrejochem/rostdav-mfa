package app.rustmfa.android.crypto

import androidx.biometric.BiometricPrompt

/**
 * The device's two EC P-256 keys.
 * `request_key` signs every API request and needs no user. `approval_key` signs challenge answers
 * and can only be used after a biometric check.
 * All signatures are raw 64-byte R||S; DER never leaves this layer.
 */
interface DeviceKey {
    fun exists(): Boolean

    /** Creates both keys, replacing existing ones. The attestation challenge is SHA-256 of the token's UTF-8 bytes. */
    fun createKeys(enrollToken: String): CreatedKeys

    /** base64 (standard) X.509 SubjectPublicKeyInfo DER. */
    fun requestPublicKeyB64(): String

    fun approvalPublicKeyB64(): String

    fun signRequest(message: ByteArray): ByteArray

    /** Throws [KeyInvalidatedException] when the key was invalidated. */
    fun approvalSigner(): ApprovalSigner

    fun delete()
}

data class CreatedKeys(
    val requestPublicKey: String,
    val approvalPublicKey: String,
    /** Certificates of the `approval_key`, leaf first, each base64 (standard) DER. */
    val attestationChain: List<String>,
    val strongBox: Boolean,
)

interface ApprovalSigner {
    /** Pass this to `BiometricPrompt.authenticate`. */
    fun cryptoObject(): BiometricPrompt.CryptoObject

    /** Call with the crypto object the prompt returned. Returns the raw R||S signature. */
    fun finish(co: BiometricPrompt.CryptoObject, message: ByteArray): ByteArray
}

/** The approval key is gone for good, for example because a new fingerprint was enrolled. */
class KeyInvalidatedException(cause: Throwable? = null) : Exception("approval key invalidated", cause)
