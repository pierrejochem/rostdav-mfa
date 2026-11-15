package app.rustmfa.android.net

import app.rustmfa.android.crypto.FakeDeviceKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/** The phone clock is minutes off: the server says UNAUTHENTICATED and its Date header shows the right time. */
class SkewRetryTest {
    private val now = 1_793_200_000_000L
    private val server = MockWebServer().also { it.start() }
    private val key = FakeDeviceKey().also { it.createKeys("t") }
    private val skew = ClockSkew { now }
    private var n = 0
    private val api = OkHttpMfaApi(server.url("/"), RequestSigner("dev_1", key, skew) { "n${n++}" }, skew, allowHttp = true)

    @AfterTest fun stop() = server.close()

    private fun ts(h: String?) = Regex("""ts="(\d+)"""").find(h!!)!!.groupValues[1].toLong()
    private fun nonce(h: String?) = Regex("""nonce="([^"]+)"""").find(h!!)!!.groupValues[1]

    private fun answer(code: Int, dateAhead: Long, body: String) = server.enqueue(
        MockResponse.Builder().code(code).addHeader("Date", ClockSkewTest.httpDate(now + dateAhead)).body(body).build(),
    )

    private val unauth = """{"error":{"code":"UNAUTHENTICATED","message":"bad signature or clock"}}"""

    @Test
    fun aDateHeaderCorrectsTheNextRequest() = runBlocking {
        answer(200, 300_000, """{"devices":[]}""")
        api.listDevices()
        answer(200, 300_000, """{"devices":[]}""")
        api.listDevices()
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals(now / 1000, ts(first.headers["Authorization"]))
        assertEquals(now / 1000 + 300, ts(second.headers["Authorization"]))
    }

    @Test
    fun unauthenticatedIsRetriedOnceWithTheCorrectedClock() = runBlocking {
        answer(401, 300_000, unauth)
        answer(200, 300_000, """{"devices":[]}""")
        assertEquals(emptyList(), api.listDevices())
        assertEquals(2, server.requestCount)
        val a = server.takeRequest().headers["Authorization"]
        val b = server.takeRequest().headers["Authorization"]
        assertEquals(now / 1000, ts(a))
        assertEquals(now / 1000 + 300, ts(b))
        assertNotEquals(nonce(a), nonce(b)) // a retry is a new request, never a replay
    }

    @Test
    fun aSecondUnauthenticatedGivesUpWithASkewHint() = runBlocking {
        answer(401, 300_000, unauth)
        answer(401, 300_000, unauth)
        val e = assertFailsWith<ApiError> { api.listDevices() }
        assertEquals(ErrorCode.UNAUTHENTICATED, e.code)
        assertTrue(e.clockSkewSuspected)
        assertEquals(2, server.requestCount) // not a loop
    }

    @Test
    fun withAGoodClockNoSkewIsSuspected() = runBlocking {
        answer(401, 0, unauth)
        answer(401, 0, unauth)
        val e = assertFailsWith<ApiError> { api.listDevices() }
        assertEquals(ErrorCode.UNAUTHENTICATED, e.code)
        assertFalse(e.clockSkewSuspected)
    }

    @Test
    fun otherErrorsAreNotRetried() = runBlocking {
        answer(403, 300_000, """{"error":{"code":"DEVICE_REVOKED","message":"gone"}}""")
        assertFailsWith<ApiError> { api.listDevices() }
        assertEquals(1, server.requestCount)
    }
}
