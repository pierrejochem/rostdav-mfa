package app.rustmfa.android.ui

import app.rustmfa.android.crypto.FakeDeviceKey
import app.rustmfa.android.enroll.DeviceStore
import app.rustmfa.android.enroll.Enrollment
import app.rustmfa.android.enroll.EnrollmentRepository
import app.rustmfa.android.enroll.PushInfo
import app.rustmfa.android.net.ApiError
import app.rustmfa.android.net.DeviceStatus
import app.rustmfa.android.net.DeviceStatusResponse
import app.rustmfa.android.net.ErrorCode
import app.rustmfa.android.net.PushChannel
import app.rustmfa.android.net.RegisterRequest
import app.rustmfa.android.net.StubApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private class CountingApi(val result: () -> DeviceStatusResponse) : StubApi() {
        var calls = 0
        override suspend fun register(r: RegisterRequest): DeviceStatusResponse {
            calls++
            return result()
        }
    }

    private class MemoryStore : DeviceStore {
        var saved: Enrollment? = null
        override suspend fun save(e: Enrollment) { saved = e }
        override suspend fun load() = saved
        override suspend fun clear() { saved = null }
    }

    private val good = "rustmfa://enroll?server=https%3A%2F%2Frd.example&token=" + "T".repeat(40)

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm(api: CountingApi, key: FakeDeviceKey = FakeDeviceKey(), store: MemoryStore = MemoryStore(), allowHttp: Boolean = false) =
        OnboardingViewModel(
            EnrollmentRepository({ api }, key, store) { PushInfo(PushChannel.None, "") },
            allowHttp,
        )

    private val ok = { DeviceStatusResponse("dev_1", DeviceStatus.Active) }

    @Test
    fun aScannedGoodLinkAsksForTheDeviceName() {
        val v = vm(CountingApi(ok))
        v.startScan()
        assertEquals(OnboardingState.Scanning, v.state.value)
        v.onLink(good)
        assertIs<OnboardingState.NeedsName>(v.state.value)
    }

    @Test
    fun aHostileLinkFailsAndTheRepositoryIsNeverCalled() {
        val api = CountingApi(ok)
        val key = FakeDeviceKey()
        val v = vm(api, key)
        for (bad in listOf(
            "http://evil.example/?server=x",
            "javascript:alert(1)",
            "rustmfa://enroll?server=http%3A%2F%2Frd.example&token=abc",
            "rustmfa://enroll?server=https%3A%2F%2Fu%3Ap%40rd.example&token=abc",
            "rustmfa://enroll?server=https%3A%2F%2Frd.example&token=" + "x".repeat(10_000),
        )) {
            v.onLink(bad)
            val s = assertIs<OnboardingState.Failed>(v.state.value, bad)
            assertEquals(FailureKind.BadLink, s.kind)
        }
        assertEquals(0, api.calls)
        assertEquals(false, key.exists())
    }

    @Test
    fun aDebugBuildMayUseHttp() {
        val v = vm(CountingApi(ok), allowHttp = true)
        v.onLink("rustmfa://enroll?server=http%3A%2F%2F10.0.2.2%3A8080&token=abc123")
        assertIs<OnboardingState.NeedsName>(v.state.value)
    }

    @Test
    fun theManualFormGoesThroughTheSameChecks() {
        val api = CountingApi(ok)
        val v = vm(api)
        v.onManual("https://rd.example", "T".repeat(40))
        assertIs<OnboardingState.NeedsName>(v.state.value)
        v.onManual("http://rd.example", "abc")
        assertEquals(FailureKind.BadLink, assertIs<OnboardingState.Failed>(v.state.value).kind)
        assertEquals(0, api.calls)
    }

    @Test
    fun successLeadsToDone() {
        val store = MemoryStore()
        val v = vm(CountingApi(ok), store = store)
        v.onLink(good)
        v.enroll("My Pixel")
        assertIs<OnboardingState.Done>(v.state.value)
        assertEquals("dev_1", store.saved?.deviceId)
    }

    @Test
    fun aServerErrorKeepsItsOwnCode() {
        for (code in listOf(ErrorCode.RATE_LIMITED, ErrorCode.INVALID_REQUEST, ErrorCode.UNAUTHENTICATED)) {
            val v = vm(CountingApi { throw ApiError(code, 400, "x") })
            v.onLink(good)
            v.enroll("My Pixel")
            val s = assertIs<OnboardingState.Failed>(v.state.value)
            assertEquals(FailureKind.Rejected, s.kind)
            assertEquals(code, s.apiCode)
        }
    }

    @Test
    fun rateLimitedAndTokenProblemsHaveDifferentMessages() {
        val rate = failureMessage(OnboardingState.Failed(FailureKind.Rejected, ErrorCode.RATE_LIMITED))
        val token = failureMessage(OnboardingState.Failed(FailureKind.Rejected, ErrorCode.INVALID_REQUEST))
        val lock = failureMessage(OnboardingState.Failed(FailureKind.NoSecureLock, null))
        val net = failureMessage(OnboardingState.Failed(FailureKind.Network, null))
        assertEquals(4, setOf(rate, token, lock, net).size)
        assertNotEquals(0, rate)
    }

    @Test
    fun aPhoneWithoutLockScreenOrFingerprintGetsItsOwnFailure() {
        val api = CountingApi(ok)
        val v = vm(api, FakeDeviceKey(failCreate = true))
        v.onLink(good)
        v.enroll("My Pixel")
        assertEquals(FailureKind.NoSecureLock, assertIs<OnboardingState.Failed>(v.state.value).kind)
        assertEquals(0, api.calls)
    }

    @Test
    fun anEmptyNameIsNotSent() {
        val api = CountingApi(ok)
        val v = vm(api)
        v.onLink(good)
        v.enroll("   ")
        assertIs<OnboardingState.NeedsName>(v.state.value)
        assertEquals(0, api.calls)
    }

    @Test
    fun resetGoesBackToIdle() {
        val v = vm(CountingApi(ok))
        v.onLink("nope")
        v.reset()
        assertEquals(OnboardingState.Idle, v.state.value)
    }
}
