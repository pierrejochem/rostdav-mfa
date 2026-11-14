package app.rustmfa.android.crypto

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import app.rustmfa.contract.Signature

class FakeDeviceKeyTest {
    private val message = "METHOD\n/path\n1\nnonce\nhash".toByteArray()

    @Test
    fun theRequestKeySignsRawAndTheContractVerifies() {
        val key = FakeDeviceKey()
        key.createKeys("token")
        val sig = key.signRequest(message)
        assertEquals(64, sig.size)
        Signature.verifyEs256Raw(Base64.getDecoder().decode(key.requestPublicKeyB64()), message, sig)
    }

    @Test
    fun theApprovalSignerSignsRawAndTheContractVerifies() {
        val key = FakeDeviceKey()
        key.createKeys("token")
        val signer = key.approvalSigner()
        val sig = signer.finish(signer.cryptoObject(), message)
        assertEquals(64, sig.size)
        Signature.verifyEs256Raw(Base64.getDecoder().decode(key.approvalPublicKeyB64()), message, sig)
    }

    @Test
    fun theTwoKeysDiffer() {
        val key = FakeDeviceKey()
        val created = key.createKeys("token")
        assertNotEquals(created.requestPublicKey, created.approvalPublicKey)
        assertEquals(created.requestPublicKey, key.requestPublicKeyB64())
        assertEquals(created.approvalPublicKey, key.approvalPublicKeyB64())
    }

    @Test
    fun existsFollowsCreateAndDelete() {
        val key = FakeDeviceKey()
        assertFalse(key.exists())
        key.createKeys("token")
        assertTrue(key.exists())
        key.delete()
        assertFalse(key.exists())
    }

    @Test
    fun aSignatureWithTheWrongKeyDoesNotVerify() {
        val key = FakeDeviceKey()
        key.createKeys("token")
        val sig = key.signRequest(message)
        val e = assertFailsWith<app.rustmfa.contract.ContractException> {
            Signature.verifyEs256Raw(Base64.getDecoder().decode(key.approvalPublicKeyB64()), message, sig)
        }
        assertEquals(app.rustmfa.contract.ContractErrorKind.VerifyFailed, e.kind)
    }

    @Test
    fun anInvalidatedFakeThrowsKeyInvalidated() {
        val key = FakeDeviceKey()
        key.createKeys("token")
        key.invalidateApprovalKey()
        assertFailsWith<KeyInvalidatedException> { key.approvalSigner().cryptoObject() }
    }

    @Test
    fun theFakeChainCarriesTheTokenHashAsItsChallenge() {
        val created = FakeDeviceKey().createKeys("token")
        assertEquals(1, created.attestationChain.size)
        assertContentEquals(
            MessageDigest.getInstance("SHA-256").digest("token".toByteArray()),
            Base64.getDecoder().decode(created.attestationChain[0]),
        )
    }
}
