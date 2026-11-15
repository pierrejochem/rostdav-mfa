package app.rustmfa.android.domain

import app.rustmfa.android.crypto.DeviceKey
import app.rustmfa.android.crypto.FakeDeviceKey
import app.rustmfa.android.enroll.DeviceStore
import app.rustmfa.android.enroll.Enrollment
import app.rustmfa.android.net.ApiError
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.net.ErrorCode
import app.rustmfa.android.net.PushChannel
import app.rustmfa.android.net.StubApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SessionGuardTest {
    private class MemoryStore(var saved: Enrollment?) : DeviceStore {
        override suspend fun save(e: Enrollment) { saved = e }
        override suspend fun load() = saved
        override suspend fun clear() { saved = null }
    }

    private val enrollment = Enrollment("https://rd.example", "dev_1", PushChannel.None, "")
    private val key = FakeDeviceKey().also { it.createKeys("T".repeat(40)) }
    private val store = MemoryStore(enrollment)
    private val guard = SessionGuard(store, key)

    @Test fun revokedDeviceLosesKeysAndEnrollment() = runTest {
        guard.onDeviceRevoked()
        assertFalse(key.exists())
        assertNull(store.saved)
    }

    @Test fun revokedDeviceStillForgetsTheEnrollmentIfTheKeyStoreThrows() = runTest {
        val broken = object : DeviceKey by key {
            override fun delete() = throw IllegalStateException("keystore gone")
        }
        SessionGuard(store, broken).onDeviceRevoked()
        assertNull(store.saved)
    }

    @Test fun invalidatedKeyKeepsTheServerAndAsksToReconnect() = runTest {
        guard.onKeyInvalidated()
        assertEquals("https://rd.example", store.saved?.server)
        assertTrue(store.saved!!.needsReconnect)
        assertFalse(key.exists())
    }

    @Test fun invalidatedKeyWithoutEnrollmentDoesNothing() = runTest {
        store.saved = null
        guard.onKeyInvalidated()
        assertNull(store.saved)
    }

    private class Failing(val code: ErrorCode) : StubApi() {
        override suspend fun listChallenges(): List<ChallengeDetails> = throw ApiError(code, 403, "x")
        override suspend fun getChallenge(id: String): ChallengeDetails = throw ApiError(code, 403, "x")
        override suspend fun approve(id: String, selectedNumber: Int, signature: String): Unit = throw ApiError(code, 403, "x")
    }

    @Test fun revokedFromAnyCallEndsTheSessionOnceAndStillThrows() = runTest {
        var ended = 0
        val api = GuardedApi(Failing(ErrorCode.DEVICE_REVOKED), guard) { ended++ }
        assertFailsWith<ApiError> { api.listChallenges() }
        assertFalse(key.exists())
        assertNull(store.saved)
        assertEquals(1, ended)
    }

    @Test fun approveCallIsGuardedToo() = runTest {
        var ended = 0
        val api = GuardedApi(Failing(ErrorCode.DEVICE_REVOKED), guard) { ended++ }
        assertFailsWith<ApiError> { api.approve("c", 1, "s") }
        assertEquals(1, ended)
    }

    @Test fun otherErrorsLeaveTheSessionAlone() = runTest {
        var ended = 0
        for (c in listOf(ErrorCode.RATE_LIMITED, ErrorCode.UNAUTHENTICATED, ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.CHALLENGE_EXPIRED)) {
            val api = GuardedApi(Failing(c), guard) { ended++ }
            assertFailsWith<ApiError> { api.getChallenge("c") }
        }
        assertTrue(key.exists())
        assertEquals(enrollment, store.saved)
        assertEquals(0, ended)
    }
}
