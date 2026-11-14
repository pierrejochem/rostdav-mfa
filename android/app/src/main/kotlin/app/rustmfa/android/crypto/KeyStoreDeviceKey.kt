package app.rustmfa.android.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricPrompt
import app.rustmfa.contract.Signature as ContractSignature
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** The real [DeviceKey], backed by the Android Keystore. */
class KeyStoreDeviceKey(@Suppress("unused") private val context: Context) : DeviceKey {
    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun exists(): Boolean = keyStore().let { it.containsAlias(REQUEST_ALIAS) && it.containsAlias(APPROVAL_ALIAS) }

    override fun createKeys(enrollToken: String): CreatedKeys {
        delete()
        generate(spec(REQUEST_ALIAS).build())
        val challenge = MessageDigest.getInstance("SHA-256").digest(enrollToken.toByteArray(Charsets.UTF_8))
        var strongBox = true
        try {
            generate(approvalSpec(challenge).setIsStrongBoxBacked(true).build())
        } catch (e: StrongBoxUnavailableException) {
            strongBox = false
            generate(approvalSpec(challenge).build())
        }
        val chain = keyStore().getCertificateChain(APPROVAL_ALIAS)
            ?: error("no certificate chain for the approval key")
        return CreatedKeys(
            requestPublicKey = requestPublicKeyB64(),
            approvalPublicKey = approvalPublicKeyB64(),
            attestationChain = AttestationChain.encode(chain),
            strongBox = strongBox,
        )
    }

    private fun spec(alias: String) =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)

    private fun approvalSpec(attestationChallenge: ByteArray) =
        spec(APPROVAL_ALIAS)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .setAttestationChallenge(attestationChallenge)

    private fun generate(spec: KeyGenParameterSpec) {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun publicKeyB64(alias: String): String {
        val cert = keyStore().getCertificate(alias) ?: error("no key $alias")
        return Base64.getEncoder().encodeToString(cert.publicKey.encoded)
    }

    override fun requestPublicKeyB64(): String = publicKeyB64(REQUEST_ALIAS)

    override fun approvalPublicKeyB64(): String = publicKeyB64(APPROVAL_ALIAS)

    private fun privateKey(alias: String): PrivateKey =
        keyStore().getKey(alias, null) as? PrivateKey ?: error("no key $alias")

    override fun signRequest(message: ByteArray): ByteArray {
        val sig = Signature.getInstance(SIGNATURE_ALGORITHM)
        sig.initSign(privateKey(REQUEST_ALIAS))
        sig.update(message)
        return ContractSignature.derToRaw(sig.sign())
    }

    override fun approvalSigner(): ApprovalSigner = object : ApprovalSigner {
        override fun cryptoObject(): BiometricPrompt.CryptoObject {
            val sig = Signature.getInstance(SIGNATURE_ALGORITHM)
            try {
                sig.initSign(privateKey(APPROVAL_ALIAS))
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw KeyInvalidatedException(e)
            }
            return BiometricPrompt.CryptoObject(sig)
        }

        override fun finish(co: BiometricPrompt.CryptoObject, message: ByteArray): ByteArray {
            val sig = co.signature ?: error("crypto object without a signature")
            try {
                sig.update(message)
                return ContractSignature.derToRaw(sig.sign())
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw KeyInvalidatedException(e)
            }
        }
    }

    override fun delete() {
        val ks = keyStore()
        for (alias in listOf(REQUEST_ALIAS, APPROVAL_ALIAS)) {
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }
    }

    companion object {
        const val REQUEST_ALIAS = "rustmfa.request"
        const val APPROVAL_ALIAS = "rustmfa.approval"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
