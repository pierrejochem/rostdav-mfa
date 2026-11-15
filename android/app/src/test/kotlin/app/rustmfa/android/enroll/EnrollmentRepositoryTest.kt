package app.rustmfa.android.enroll

import app.rustmfa.android.crypto.CreatedKeys
import app.rustmfa.android.crypto.DeviceKey
import app.rustmfa.android.crypto.FakeDeviceKey
import app.rustmfa.android.net.ApiError
import app.rustmfa.android.net.DeviceStatus
import app.rustmfa.android.net.DeviceStatusResponse
import app.rustmfa.android.net.ErrorCode
import app.rustmfa.android.net.PushChannel
import app.rustmfa.android.net.RegisterRequest
import app.rustmfa.android.net.StubApi
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EnrollmentRepositoryTest {
    private class RecordingApi(val result: () -> DeviceStatusResponse) : StubApi() {
        var request: RegisterRequest? = null
        override suspend fun register(r: RegisterRequest): DeviceStatusResponse {
            request = r
            return result()
        }
    }

    private class MemoryStore(private val failSave: Boolean = false) : DeviceStore {
        var saved: Enrollment? = null
        override suspend fun save(e: Enrollment) {
            if (failSave) throw IOException("disk full")
            saved = e
        }
        override suspend fun load() = saved
        override suspend fun clear() { saved = null }
    }

    private val link = EnrollLink.parse("rustmfa://enroll?server=https%3A%2F%2Frd.example&token=" + "T".repeat(40), allowHttp = false)!!
    private val ok = { DeviceStatusResponse("dev_9", DeviceStatus.Active) }

    private fun repo(api: RecordingApi, key: DeviceKey, store: DeviceStore, push: PushProvider = PushProvider { PushInfo(PushChannel.Fcm, "fcm-tok") }) =
        EnrollmentRepository({ api }, key, store, push)

    @Test
    fun itCreatesTheKeysWithTheTokenAndSendsEverythingTheContractNeeds() = runBlocking {
        val key = FakeDeviceKey()
        val api = RecordingApi(ok)
        val store = MemoryStore()
        val r = repo(api, key, store).enroll(link, "My Pixel")
        assertIs<EnrollResult.Enrolled>(r)
        val q = api.request!!
        assertEquals("T".repeat(40), q.enrollToken)
        assertEquals("My Pixel", q.deviceName)
        assertEquals(key.requestPublicKeyB64(), q.requestPublicKey)
        assertEquals(key.approvalPublicKeyB64(), q.approvalPublicKey)
        assertEquals("ES256", q.keyAlgorithm)
        assertEquals(1, q.attestationChain.size) // the fake's chain carries SHA-256(token): the token reached createKeys
        assertEquals(java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(("T".repeat(40)).toByteArray())), q.attestationChain[0])
        assertEquals(PushChannel.Fcm, q.pushChannel)
        assertEquals("fcm-tok", q.pushToken)
    }

    @Test
    fun itStoresTheResultOnlyOnSuccess() = runBlocking {
        val store = MemoryStore()
        val r = repo(RecordingApi(ok), FakeDeviceKey(), store).enroll(link, "P") as EnrollResult.Enrolled
        assertEquals(Enrollment("https://rd.example/", "dev_9", PushChannel.Fcm, "fcm-tok"), store.saved)
        assertEquals(store.saved, r.enrollment)
    }

    @Test
    fun aPollingOnlyBuildRegistersWithNoneAndAnEmptyToken() = runBlocking {
        val api = RecordingApi(ok)
        repo(api, FakeDeviceKey(), MemoryStore(), PushProvider { PushInfo(PushChannel.None, "") }).enroll(link, "P")
        assertEquals(PushChannel.None, api.request!!.pushChannel)
        assertEquals("", api.request!!.pushToken)
    }

    @Test
    fun aRejectedRegistrationDeletesTheKeysAndStoresNothing() = runBlocking {
        val key = FakeDeviceKey()
        val store = MemoryStore()
        val api = RecordingApi { throw ApiError(ErrorCode.INVALID_REQUEST, 400, "enrollment token is invalid or expired") }
        val r = repo(api, key, store).enroll(link, "P")
        assertEquals(EnrollResult.Failed(EnrollFailure.Rejected, ErrorCode.INVALID_REQUEST), r)
        assertFalse(key.exists())
        assertNull(store.saved)
    }

    @Test
    fun aNetworkFailureDeletesTheKeysAndStoresNothing() = runBlocking {
        val key = FakeDeviceKey()
        val store = MemoryStore()
        val r = repo(RecordingApi { throw IOException("no route") }, key, store).enroll(link, "P")
        assertEquals(EnrollResult.Failed(EnrollFailure.Network, null), r)
        assertFalse(key.exists())
        assertNull(store.saved)
    }

    @Test
    fun aFailedSaveDeletesTheKeysToo() = runBlocking {
        val key = FakeDeviceKey()
        val r = repo(RecordingApi(ok), key, MemoryStore(failSave = true)).enroll(link, "P")
        assertEquals(EnrollResult.Failed(EnrollFailure.Storage, null), r)
        assertFalse(key.exists())
    }

    @Test
    fun androidRefusingToCreateTheKeyIsAKeyFailureAndNothingIsSent() = runBlocking {
        val api = RecordingApi(ok)
        val failing = object : DeviceKey by FakeDeviceKey() {
            override fun createKeys(enrollToken: String): CreatedKeys =
                throw java.security.InvalidAlgorithmParameterException("At least one biometric must be enrolled")
        }
        val r = repo(api, failing, MemoryStore()).enroll(link, "P")
        assertEquals(EnrollResult.Failed(EnrollFailure.KeyCreation, null), r)
        assertNull(api.request)
    }

    @Test
    fun anEmptyDeviceNameIsRefusedBeforeAnyKeyIsMade() = runBlocking {
        val key = FakeDeviceKey()
        val api = RecordingApi(ok)
        val r = repo(api, key, MemoryStore()).enroll(link, "   ")
        assertEquals(EnrollResult.Failed(EnrollFailure.Rejected, null), r)
        assertFalse(key.exists())
        assertNull(api.request)
        assertTrue(true)
    }
}
