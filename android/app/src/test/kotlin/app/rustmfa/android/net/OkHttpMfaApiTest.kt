package app.rustmfa.android.net

import app.rustmfa.android.crypto.FakeDeviceKey
import app.rustmfa.contract.Base64Url
import app.rustmfa.contract.RequestSigning
import app.rustmfa.contract.Signature
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

class OkHttpMfaApiTest {
    private val server = MockWebServer().also { it.start() }
    private val key = FakeDeviceKey().also { it.createKeys("t") }
    private val skew = ClockSkew { 1_793_200_000_000L }
    private var counter = 0
    private val signer = RequestSigner("dev_1", key, skew) { "nonce-${counter++}" }
    private val api = OkHttpMfaApi(server.url("/"), signer, skew, allowHttp = true)

    @AfterTest fun stop() = server.close()

    private fun json(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())

    private fun RecordedRequest.bodyText(): String = body?.utf8() ?: ""

    /** The Authorization header must be a valid signature for exactly this request. */
    private fun assertSigned(r: RecordedRequest) {
        val h = assertNotNull(r.headers["Authorization"])
        val m = assertNotNull(Regex("""RustMFA-Sig device_id="dev_1",ts="(\d+)",nonce="([^"]+)",sig="([^"]+)"""").matchEntire(h), h)
        val (ts, nonce, sig) = m.destructured
        val body = r.body?.toByteArray() ?: ByteArray(0)
        val signing = RequestSigning.signingString(r.method!!, r.target!!, ts.toLong(), nonce, body)
        Signature.verifyEs256Raw(Base64.getDecoder().decode(key.requestPublicKeyB64()), signing.toByteArray(), Base64Url.decode(sig))
    }

    private val challenge = """{"challenge_id":"ch_1","service_name":"Rostdav","login_device":"Firefox","created_at":"2026-10-28T15:02:00Z","expires_at":"2026-10-28T15:03:30Z","status":"pending","candidate_numbers":[12,34,56],"nonce":"n0nce","login_id":"login_1","audience":"rostdav","approximate_location":null,"unknown_future_field":true}"""

    @Test
    fun listChallengesAsksForPendingOnes() = runBlocking {
        json("""{"challenges":[$challenge]}""")
        val list = api.listChallenges()
        val r = server.takeRequest()
        assertEquals("GET", r.method)
        assertEquals("/api/v1/mfa/challenges?status=pending", r.target)
        assertSigned(r)
        assertEquals("ch_1", list.single().challengeId)
        assertEquals(listOf(12, 34, 56), list.single().candidateNumbers)
        assertEquals(ChallengeStatus.Pending, list.single().status)
    }

    @Test
    fun getChallengeUsesTheIdAsAPathSegment() = runBlocking {
        json(challenge)
        val c = api.getChallenge("ch_1")
        val r = server.takeRequest()
        assertEquals("GET /api/v1/mfa/challenges/ch_1", "${r.method} ${r.target}")
        assertSigned(r)
        assertEquals("2026-10-28T15:03:30Z", c.expiresAt)
        // an id with a slash or a dot-dot cannot climb out of the path
        json(challenge)
        api.getChallenge("../devices")
        val r2 = server.takeRequest()
        assertEquals("/api/v1/mfa/challenges/..%2Fdevices", r2.target)
        assertSigned(r2)
    }

    @Test
    fun approveSendsTheNumberAndTheSignature() = runBlocking {
        json("""{"challenge_id":"ch_1","status":"approved"}""")
        api.approve("ch_1", 34, "SIG")
        val r = server.takeRequest()
        assertEquals("POST /api/v1/mfa/challenges/ch_1/approve", "${r.method} ${r.target}")
        assertSigned(r)
        val b = Json.parseToJsonElement(r.bodyText()).jsonObject
        assertEquals(setOf("device_id", "selected_number", "signature"), b.keys)
        assertEquals("dev_1", b["device_id"]!!.jsonPrimitive.content)
        assertEquals(34, b["selected_number"]!!.jsonPrimitive.int)
        assertEquals("SIG", b["signature"]!!.jsonPrimitive.content)
    }

    @Test
    fun denySendsNoNumber() = runBlocking {
        json("""{"challenge_id":"ch_1","status":"denied"}""")
        api.deny("ch_1", "SIG")
        val r = server.takeRequest()
        assertEquals("POST /api/v1/mfa/challenges/ch_1/deny", "${r.method} ${r.target}")
        assertSigned(r)
        assertEquals(setOf("device_id", "signature"), Json.parseToJsonElement(r.bodyText()).jsonObject.keys)
    }

    @Test
    fun listDevicesAndAuditAndRevokeAndPushToken() = runBlocking {
        json("""{"devices":[{"device_id":"dev_1","device_name":"Pixel","status":"active","push_channel":"none","created_at":"2026-10-28T15:00:00Z","last_seen_at":null,"hardware_backed":true,"strongbox":false}]}""")
        val d = api.listDevices().single()
        assertEquals(PushChannel.None, d.pushChannel)
        assertEquals(DeviceStatus.Active, d.status)
        assertEquals("GET /api/v1/devices", server.takeRequest().let { assertSigned(it); "${it.method} ${it.target}" })

        json("""{"events":[{"event":"device_registered","at":"2026-10-28T15:00:00Z","device_id":"dev_1","detail":null}]}""")
        assertEquals("device_registered", api.listAudit().single().event)
        assertEquals("GET /api/v1/audit", server.takeRequest().let { assertSigned(it); "${it.method} ${it.target}" })

        server.enqueue(MockResponse.Builder().code(204).build())
        api.revokeDevice("dev_2")
        assertEquals("DELETE /api/v1/devices/dev_2", server.takeRequest().let { assertSigned(it); "${it.method} ${it.target}" })

        json("""{"device_id":"dev_1","status":"active"}""")
        api.updatePushToken("dev_1", PushChannel.Fcm, "tok")
        val r = server.takeRequest()
        assertEquals("PUT /api/v1/devices/dev_1/push-token", "${r.method} ${r.target}")
        assertSigned(r)
        val b = Json.parseToJsonElement(r.bodyText()).jsonObject
        assertEquals("fcm", b["push_channel"]!!.jsonPrimitive.content)
        assertEquals("tok", b["push_token"]!!.jsonPrimitive.content)
    }

    @Test
    fun registerIsNotSignedAndCarriesTheConstantAlgorithm() = runBlocking {
        json("""{"device_id":"dev_9","status":"active"}""", 201)
        val unsigned = OkHttpMfaApi(server.url("/"), signer = null, skew, allowHttp = true)
        val s = unsigned.register(
            RegisterRequest("tok", "Pixel", "REQ", "APP", listOf("C1", "C2"), PushChannel.None, ""),
        )
        assertEquals("dev_9", s.deviceId)
        val r = server.takeRequest()
        assertEquals("POST /api/v1/devices/register", "${r.method} ${r.target}")
        assertNull(r.headers["Authorization"])
        val b = Json.parseToJsonElement(r.bodyText()).jsonObject
        assertEquals("ES256", b["key_algorithm"]!!.jsonPrimitive.content)
        assertEquals("none", b["push_channel"]!!.jsonPrimitive.content)
        assertEquals("", b["push_token"]!!.jsonPrimitive.content)
        assertEquals(setOf("enroll_token", "device_name", "request_public_key", "approval_public_key", "key_algorithm", "attestation_chain", "push_channel", "push_token"), b.keys)
    }

    @Test
    fun aSignedCallWithoutASignerIsAnError() = runBlocking {
        val unsigned = OkHttpMfaApi(server.url("/"), signer = null, skew, allowHttp = true)
        assertFailsWith<IllegalStateException> { unsigned.listDevices() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun everyContractCodeMapsToItsErrorCode() = runBlocking {
        val codes = listOf("INVALID_REQUEST", "UNAUTHENTICATED", "DEVICE_NOT_AUTHORIZED", "DEVICE_REVOKED", "RESOURCE_NOT_FOUND", "CHALLENGE_ALREADY_CONSUMED", "CHALLENGE_NUMBER_MISMATCH", "CHALLENGE_EXPIRED", "RATE_LIMITED", "INTERNAL_ERROR")
        for (c in codes) {
            // UNAUTHENTICATED is retried once, so serve it twice
            repeat(if (c == "UNAUTHENTICATED") 2 else 1) { json("""{"error":{"code":"$c","message":"m"}}""", 409) }
            val e = assertFailsWith<ApiError>(c) { api.getChallenge("ch_1") }
            assertEquals(ErrorCode.valueOf(c), e.code, c)
            assertEquals(409, e.status)
            assertEquals("m", e.message)
        }
        assertEquals(10, ErrorCode.entries.count { it != ErrorCode.UNKNOWN })
    }

    @Test
    fun anUnknownCodeIsUnknown() = runBlocking {
        json("""{"error":{"code":"NEW_THING","message":"hm"}}""", 400)
        assertEquals(ErrorCode.UNKNOWN, assertFailsWith<ApiError> { api.listDevices() }.code)
    }

    @Test
    fun aFiveHundredWithoutABodyIsInternalError() = runBlocking {
        server.enqueue(MockResponse.Builder().code(502).build())
        val e = assertFailsWith<ApiError> { api.listDevices() }
        assertEquals(ErrorCode.INTERNAL_ERROR, e.code)
        assertEquals(502, e.status)
    }

    @Test
    fun aNonJsonErrorBodyIsNotEchoed() = runBlocking {
        json("<html>secret-ish proxy page</html>", 400)
        val e = assertFailsWith<ApiError> { api.listDevices() }
        assertEquals(ErrorCode.UNKNOWN, e.code)
        assertFalse(e.message!!.contains("secret-ish"))
    }

    @Test
    fun aResponseOverOneMebibyteIsRefused() = runBlocking {
        json("""{"devices":[],"pad":"${"x".repeat(1_048_576)}"}""")
        val e = assertFailsWith<ApiError> { api.listDevices() }
        assertEquals(ErrorCode.UNKNOWN, e.code)
        assertTrue(e.message!!.contains("too large"))
    }

    @Test
    fun aRedirectIsNotFollowed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://other.invalid/x").build())
        val e = assertFailsWith<ApiError> { api.listDevices() }
        assertEquals(302, e.status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun noCookiesAreKept() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "a=b").body("""{"devices":[]}""").build())
        api.listDevices()
        server.enqueue(MockResponse.Builder().code(200).body("""{"devices":[]}""").build())
        api.listDevices()
        server.takeRequest()
        assertNull(server.takeRequest().headers["Cookie"])
    }

    @Test
    fun httpIsRefusedBeforeAnyRequestInARelease() {
        val e = assertFailsWith<IllegalArgumentException> { OkHttpMfaApi(server.url("/"), signer, skew, allowHttp = false) }
        assertNotNull(e)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theHttpClientHasNoRedirectsNoCookiesAndATenSecondTimeout() {
        val c: OkHttpClient = OkHttpMfaApi.newClient()
        assertFalse(c.followRedirects)
        assertFalse(c.followSslRedirects)
        assertEquals(10_000, c.callTimeoutMillis)
        assertEquals(okhttp3.CookieJar.NO_COOKIES, c.cookieJar)
    }
}
