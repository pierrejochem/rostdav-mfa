package app.rustmfa.android.crypto

import androidx.biometric.BiometricPrompt
import app.rustmfa.contract.Signature as ContractSignature
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** A [DeviceKey] with software keys, for the JVM tests of later tasks. No biometric prompt, no hardware. */
class FakeDeviceKey : DeviceKey {
    private var request: KeyPair? = null
    private var approval: KeyPair? = null
    private var invalidated = false

    private fun newPair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun b64(p: KeyPair) = Base64.getEncoder().encodeToString(p.public.encoded)

    override fun exists() = request != null && approval != null

    override fun createKeys(enrollToken: String): CreatedKeys {
        request = newPair()
        approval = newPair()
        invalidated = false
        // not a real attestation chain: one entry that carries the challenge, so tests can see which token was used
        val challenge = MessageDigest.getInstance("SHA-256").digest(enrollToken.toByteArray(Charsets.UTF_8))
        return CreatedKeys(requestPublicKeyB64(), approvalPublicKeyB64(), listOf(Base64.getEncoder().encodeToString(challenge)), false)
    }

    override fun requestPublicKeyB64() = b64(checkNotNull(request))

    override fun approvalPublicKeyB64() = b64(checkNotNull(approval))

    private fun sign(key: KeyPair, message: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(key.private)
        sig.update(message)
        return ContractSignature.derToRaw(sig.sign())
    }

    override fun signRequest(message: ByteArray) = sign(checkNotNull(request), message)

    /** Makes the next [approvalSigner] call fail like a key that a new fingerprint invalidated. */
    fun invalidateApprovalKey() {
        invalidated = true
    }

    override fun approvalSigner(): ApprovalSigner {
        if (invalidated) throw KeyInvalidatedException()
        val key = checkNotNull(approval)
        return object : ApprovalSigner {
            override fun cryptoObject(): BiometricPrompt.CryptoObject {
                if (invalidated) throw KeyInvalidatedException()
                return BiometricPrompt.CryptoObject(Signature.getInstance("SHA256withECDSA").apply { initSign(key.private) })
            }

            override fun finish(co: BiometricPrompt.CryptoObject, message: ByteArray): ByteArray {
                val sig = checkNotNull(co.signature)
                sig.update(message)
                return ContractSignature.derToRaw(sig.sign())
            }
        }
    }

    override fun delete() {
        request = null
        approval = null
    }
}
