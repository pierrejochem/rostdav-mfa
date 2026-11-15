package app.rustmfa.android.ui

import app.rustmfa.android.R
import app.rustmfa.android.crypto.FakeDeviceKey
import app.rustmfa.android.domain.SessionGuard
import app.rustmfa.android.enroll.DeviceStore
import app.rustmfa.android.enroll.Enrollment
import app.rustmfa.android.net.ApiError
import app.rustmfa.android.net.AuditEvent
import app.rustmfa.android.net.Device
import app.rustmfa.android.net.DeviceStatus
import app.rustmfa.android.net.ErrorCode
import app.rustmfa.android.net.PushChannel
import app.rustmfa.android.net.StubApi
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Store(var saved: Enrollment?) : DeviceStore {
        override suspend fun save(e: Enrollment) { saved = e }
        override suspend fun load() = saved
        override suspend fun clear() { saved = null }
    }

    private fun dev(id: String, status: DeviceStatus = DeviceStatus.Active) =
        Device(id, "Phone $id", status, PushChannel.None, "2026-11-01T10:00:00Z", "2026-11-14T10:00:00Z", true, id == "dev_1")

    private class Api : StubApi() {
        var devices = listOf<Device>()
        var audit = listOf<AuditEvent>()
        var failWith: Exception? = null
        val log = mutableListOf<String>()
        override suspend fun listDevices(): List<Device> = failWith?.let { throw it } ?: devices
        override suspend fun listAudit(): List<AuditEvent> = audit
        override suspend fun revokeDevice(id: String) {
            failWith?.let { throw it }
            log += "revoke:$id"
            devices = devices.filter { it.deviceId != id }
        }
    }

    private val api = Api().apply { devices = listOf(dev("dev_1"), dev("dev_2")) }
    private val store = Store(Enrollment("https://rd.example", "dev_1", PushChannel.None, ""))
    private val key = FakeDeviceKey().also { it.createKeys("T".repeat(40)) }
    private val guard = SessionGuard(store, key)
    private fun vm() = DevicesViewModel(api, "dev_1", guard)

    @Test fun listsThePhonesAndMarksThisOne() {
        val s = vm().state.value
        assertEquals(listOf("dev_1", "dev_2"), s.devices.map { it.device.deviceId })
        assertEquals(listOf(true, false), s.devices.map { it.isThisPhone })
        assertTrue(s.devices[0].device.strongbox)
    }

    @Test fun auditShowsOnlyTheContractsEightKinds() {
        api.audit = listOf(
            AuditEvent("device_registered", "2026-11-14T10:00:00Z"),
            AuditEvent("number_mismatch", "2026-11-14T10:01:00Z"),
            AuditEvent("admin_shell_opened", "2026-11-14T10:02:00Z"),
            AuditEvent("<b>x</b>", "2026-11-14T10:03:00Z"),
        )
        assertEquals(listOf("device_registered", "number_mismatch"), vm().state.value.audit.map { it.event })
    }

    @Test fun revokingAnotherPhoneAsksFirstThenRevokes() {
        val vm = vm()
        vm.askRevoke("dev_2")
        assertEquals("dev_2", vm.state.value.confirming)
        assertEquals(emptyList(), api.log)
        vm.confirmRevoke()
        assertEquals(listOf("revoke:dev_2"), api.log)
        assertNull(vm.state.value.confirming)
        assertEquals(listOf("dev_1"), vm.state.value.devices.map { it.device.deviceId })
        assertTrue(key.exists())
        assertFalse(vm.state.value.thisPhoneRevoked)
    }

    @Test fun cancelSendsNothing() {
        val vm = vm()
        vm.askRevoke("dev_2")
        vm.cancelRevoke()
        vm.confirmRevoke()
        assertEquals(emptyList(), api.log)
    }

    @Test fun revokingThisPhoneRunsTheGuardAfterTheServerCall() {
        val vm = vm()
        vm.askRevoke("dev_1")
        vm.confirmRevoke()
        assertEquals(listOf("revoke:dev_1"), api.log)
        assertTrue(vm.state.value.thisPhoneRevoked)
        assertFalse(key.exists())
        assertNull(store.saved)
    }

    @Test fun aFailedRevokeOfThisPhoneKeepsTheKeys() {
        api.failWith = IOException("down")
        val vm = DevicesViewModel(Api().apply { devices = listOf(dev("dev_1")); failWith = IOException("down") }, "dev_1", guard)
        vm.askRevoke("dev_1")
        vm.confirmRevoke()
        assertTrue(key.exists())
        assertFalse(vm.state.value.thisPhoneRevoked)
        assertEquals(Failure.Network, vm.state.value.failure)
    }

    @Test fun anErrorCodeShowsItsMessage() {
        api.failWith = ApiError(ErrorCode.RATE_LIMITED, 429, "slow")
        val s = vm().state.value
        assertEquals(Failure.Api(ErrorCode.RATE_LIMITED), s.failure)
        assertEquals(R.string.err_rate_limited, deviceFailureMessage(s.failure!!))
        assertEquals(R.string.err_network, deviceFailureMessage(Failure.Network))
        assertEquals(R.string.result_revoked, deviceFailureMessage(Failure.Api(ErrorCode.DEVICE_REVOKED)))
    }
}
