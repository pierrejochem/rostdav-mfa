package app.rustmfa.android.crypto

import android.security.keystore.KeyInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import app.rustmfa.contract.Signature

/**
 * Runs on a device or emulator. The device needs a secure lock screen (`adb shell locksettings set-pin 1234`),
 * otherwise Android refuses to create a key that needs user authentication.
 * An emulator proves none of: hardware backing, StrongBox, the biometric prompt.
 */
@RunWith(AndroidJUnit4::class)
class DeviceKeyInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = KeyStoreDeviceKey(context)
    private val message = "GET\n/x\n1\nn\nh".toByteArray()

    @BeforeTest fun clean() = key.delete()
    @AfterTest fun cleanUp() = key.delete()

    @Test
    fun requestKeySignsWithoutAuthentication() {
        key.createKeys("token-1")
        val sig = key.signRequest(message)
        assertEquals(64, sig.size)
        Signature.verifyEs256Raw(Base64.getDecoder().decode(key.requestPublicKeyB64()), message, sig)
    }

    @Test
    fun createKeysReturnsTwoSpkiKeysAndAChainWhoseChallengeIsTheTokenHash() {
        val created = key.createKeys("token-1")
        assertTrue(created.requestPublicKey != created.approvalPublicKey)
        // both are X.509 SPKI P-256 keys
        for (k in listOf(created.requestPublicKey, created.approvalPublicKey)) {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(k))) as ECPublicKey
            assertEquals(256, pub.params.order.bitLength())
        }
    }

    @Test
    fun deleteRemovesBothKeys() {
        key.createKeys("token-1")
        assertTrue(key.exists())
        key.delete()
        assertFalse(key.exists())
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(ks.containsAlias(KeyStoreDeviceKey.REQUEST_ALIAS))
        assertFalse(ks.containsAlias(KeyStoreDeviceKey.APPROVAL_ALIAS))
    }

    @Test
    fun approvalKeyIsAuthenticationBound() {
        key.createKeys("token-1")
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val approval = ks.getKey(KeyStoreDeviceKey.APPROVAL_ALIAS, null) as PrivateKey
        val info = KeyFactory.getInstance(approval.algorithm, "AndroidKeyStore").getKeySpec(approval, KeyInfo::class.java)
        assertTrue(info.isUserAuthenticationRequired)
        val request = ks.getKey(KeyStoreDeviceKey.REQUEST_ALIAS, null) as PrivateKey
        val reqInfo = KeyFactory.getInstance(request.algorithm, "AndroidKeyStore").getKeySpec(request, KeyInfo::class.java)
        assertFalse(reqInfo.isUserAuthenticationRequired)
    }

    @Test
    fun theChainChallengeIsTheTokenHash() {
        val created = key.createKeys("token-1")
        val leaf = CertificateFactory.getInstance("X.509")
            .generateCertificate(Base64.getDecoder().decode(created.attestationChain[0]).inputStream()) as X509Certificate
        val challenge = AttestationChain.challenge(leaf)
        assertNotNull(challenge)
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest("token-1".toByteArray()), challenge)
        // the leaf certifies the approval key
        assertEquals(created.approvalPublicKey, Base64.getEncoder().encodeToString(leaf.publicKey.encoded))
    }
}
