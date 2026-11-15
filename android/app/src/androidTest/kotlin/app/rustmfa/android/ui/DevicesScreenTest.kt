package app.rustmfa.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.rustmfa.android.crypto.KeyStoreDeviceKey
import app.rustmfa.android.domain.SessionGuard
import app.rustmfa.android.enroll.DeviceStore
import app.rustmfa.android.enroll.Enrollment
import app.rustmfa.android.net.*
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class DevicesScreenTest {
    @get:Rule val rule = createComposeRule()

    private class Api : MfaApi {
        val revoked = mutableListOf<String>()
        private fun dev(id: String, name: String) = Device(id, name, DeviceStatus.Active, PushChannel.None, "2026-11-01T10:00:00Z", null, true, false)
        override suspend fun listDevices() = listOf(dev("dev_1", "Pixel"), dev("dev_2", "Old tablet"))
        override suspend fun listAudit() = listOf(AuditEvent("challenge_approved", "2026-11-14T10:00:00Z"), AuditEvent("weird", "2026-11-14T10:01:00Z"))
        override suspend fun revokeDevice(id: String) { revoked += id }
        override suspend fun register(r: RegisterRequest): DeviceStatusResponse = error("unused")
        override suspend fun listChallenges(): List<ChallengeDetails> = error("unused")
        override suspend fun getChallenge(id: String): ChallengeDetails = error("unused")
        override suspend fun approve(id: String, selectedNumber: Int, signature: String): Unit = error("unused")
        override suspend fun deny(id: String, signature: String): Unit = error("unused")
        override suspend fun updatePushToken(id: String, channel: PushChannel, token: String): Unit = error("unused")
    }

    private val store = object : DeviceStore {
        override suspend fun save(e: Enrollment) {}
        override suspend fun load(): Enrollment? = null
        override suspend fun clear() {}
    }

    @Test
    fun listsPhonesAsksBeforeRemovingAndShowsOnlyKnownEvents() {
        val api = Api()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val vm = DevicesViewModel(api, "dev_1", SessionGuard(store, KeyStoreDeviceKey(ctx)))
        rule.setContent { RustMfaTheme { DevicesScreen(vm, onThisPhoneRevoked = {}) } }
        rule.onNodeWithText("Pixel").assertIsDisplayed()
        rule.onNodeWithText("This phone").assertIsDisplayed()
        rule.onNodeWithText("Request approved", substring = true).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("weird", substring = true).fetchSemanticsNodes().size)
        rule.onAllNodesWithText("Remove")[1].performClick()
        rule.onNodeWithText("Old tablet will no longer approve sign-ins.").assertIsDisplayed()
        assertEquals(emptyList(), api.revoked)
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList(), api.revoked)
    }
}
