package app.rustmfa.android.net

import app.rustmfa.android.crypto.FakeDeviceKey
import app.rustmfa.contract.Base64Url
import app.rustmfa.contract.ContractErrorKind
import app.rustmfa.contract.ContractException
import app.rustmfa.contract.RequestSigning
import app.rustmfa.contract.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class RequestSignerTest {
    private val key = FakeDeviceKey().also { it.createKeys("t") }
    private val clock = Clock { 1_793_200_000_000L }
    private val fields = Regex("""RustMFA-Sig device_id="([^"]*)",ts="([^"]*)",nonce="([^"]*)",sig="([^"]*)"""")

    private fun verify(header: String, method: String, path: String, body: ByteArray) {
        val m = assertNotNull(fields.matchEntire(header), header)
        val (_, ts, nonce, sig) = m.destructured
        val signing = RequestSigning.signingString(method, path, ts.toLong(), nonce, body)
        Signature.verifyEs256Raw(
            Base64.getDecoder().decode(key.requestPublicKeyB64()),
            signing.toByteArray(Charsets.UTF_8),
            Base64Url.decode(sig),
        )
    }

    @Test
    fun theHeaderHasTheFourFieldsInOrder() {
        val signer = RequestSigner("dev_1", key, clock) { "n-1" }
        val h = signer.header("GET", "/api/v1/devices", ByteArray(0))
        val m = assertNotNull(fields.matchEntire(h), h)
        assertEquals(listOf("dev_1", "1793200000", "n-1"), m.groupValues.subList(1, 4))
    }

    @Test
    fun theSignatureVerifiesAgainstTheRequestKey() {
        val signer = RequestSigner("dev_1", key, clock) { "n-1" }
        verify(signer.header("GET", "/api/v1/mfa/challenges?status=pending", ByteArray(0)), "GET", "/api/v1/mfa/challenges?status=pending", ByteArray(0))
    }

    @Test
    fun theBodyHashIsOfTheExactBodyBytes() {
        val signer = RequestSigner("dev_1", key, clock) { "n-1" }
        val body = """{"a": 1}""".toByteArray()
        val h = signer.header("POST", "/x", body)
        verify(h, "POST", "/x", body)
        val e = assertFailsWith<ContractException> { verify(h, "POST", "/x", """{"a":1}""".toByteArray()) }
        assertEquals(ContractErrorKind.VerifyFailed, e.kind)
    }

    @Test
    fun twoCallsHaveDifferentNonces() {
        val signer = RequestSigner("dev_1", key, clock)
        val a = fields.matchEntire(signer.header("GET", "/x", ByteArray(0)))!!.groupValues[3]
        val b = fields.matchEntire(signer.header("GET", "/x", ByteArray(0)))!!.groupValues[3]
        assertNotEquals(a, b)
        assertEquals(22, a.length) // 16 random bytes, base64url without padding
    }
}
