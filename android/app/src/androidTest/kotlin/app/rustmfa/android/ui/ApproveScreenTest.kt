package app.rustmfa.android.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.rustmfa.android.crypto.KeyStoreDeviceKey
import app.rustmfa.android.domain.ChallengeFlow
import app.rustmfa.android.domain.ChallengeState
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.net.ChallengeStatus
import app.rustmfa.android.net.Clock
import app.rustmfa.android.net.Device
import app.rustmfa.android.net.AuditEvent
import app.rustmfa.android.net.DeviceStatusResponse
import app.rustmfa.android.net.MfaApi
import app.rustmfa.android.net.PushChannel
import app.rustmfa.android.net.RegisterRequest
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

private fun Modifier.androidx_width360(): Modifier = this.width(360.dp)

class ApproveScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val details = ChallengeDetails(
        challengeId = "ch_1", serviceName = "Rostdav", loginDevice = "Firefox on Linux", createdAt = "2026-11-14T11:59:30Z",
        expiresAt = "2026-11-14T12:00:30Z", status = ChallengeStatus.Pending, candidateNumbers = listOf(12, 47, 83),
        nonce = "n", loginId = "l", audience = "rostdav", approximateLocation = "Amsterdam",
    )

    @Test
    fun threeNumbersADenyButtonAndTheCountdown() {
        val picked = mutableListOf<Int>()
        var denied = 0
        rule.setContent {
            RustMfaTheme {
                ApproveContent(ChallengeState.Pending(details, 27), { picked += it }, { denied++ }, {}, {})
            }
        }
        for (n in listOf("12", "47", "83")) rule.onNodeWithText(n).assertIsDisplayed()
        rule.onNodeWithText("27 s left").assertIsDisplayed()
        rule.onNodeWithText("Firefox on Linux", substring = true).assertIsDisplayed()
        rule.onNodeWithText("47").performClick()
        rule.onNodeWithText("Deny").performClick()
        assertEquals(listOf(47), picked)
        assertEquals(1, denied)
    }

    @Test
    fun numberChoicesAreNamedByTheirNumber() {
        rule.setContent { RustMfaTheme { ApproveContent(ChallengeState.Pending(details, 27), {}, {}, {}, {}) } }
        for (n in listOf(12, 47, 83)) rule.onNodeWithContentDescription("Number $n").assertIsDisplayed()
    }

    @Test
    fun everythingStaysReachableAtTwiceTheFontSize() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(base, fontScale = 2f)) {
                RustMfaTheme { ApproveContent(ChallengeState.Pending(details, 27), {}, {}, {}, {}) }
            }
        }
        for (n in listOf(12, 47, 83)) rule.onNodeWithContentDescription("Number $n").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Deny").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aVeryLongServiceNameKeepsDenyReachable() {
        val long = details.copy(serviceName = "S".repeat(300), loginDevice = "D".repeat(300))
        rule.setContent { RustMfaTheme { ApproveContent(ChallengeState.Pending(long, 27), {}, {}, {}, {}) } }
        rule.onNodeWithText("Deny").performScrollTo().assertIsDisplayed()
    }

    private fun layoutOf(text: String): androidx.compose.ui.text.TextLayoutResult {
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText(text).fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first()
    }

    @Test
    fun theNumberDigitsStayOnOneLineAtTwiceTheFontSize() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(base, fontScale = 2f)) {
                RustMfaTheme { androidx.compose.foundation.layout.Box(Modifier.androidx_width360()) { ApproveContent(ChallengeState.Pending(details, 27), {}, {}, {}, {}) } }
            }
        }
        for (n in listOf("12", "47", "83")) assertEquals(1, layoutOf(n).lineCount, "'$n' wraps")
    }

    @Test
    fun aLongDeviceNameIsShownOnSeveralLinesNotCutAtOne() {
        val long = details.copy(loginDevice = "Firefox on Linux (Ubuntu 24.04) via the office VPN in the second building")
        rule.setContent { RustMfaTheme { ApproveContent(ChallengeState.Pending(long, 27), {}, {}, {}, {}) } }
        val l = layoutOf("Device: ${long.loginDevice}")
        assertTrue(l.lineCount in 2..3, "device line count ${l.lineCount}")
        assertTrue(!l.hasVisualOverflow || l.lineCount == 3)
    }

    @Test
    fun denyIsThereInEveryOpenState() {
        // while the request is open, deny is always offered; the other states end the request
        rule.setContent { RustMfaTheme { ApproveContent(ChallengeState.Pending(details, 1), {}, {}, {}, {}) } }
        rule.onNodeWithText("Deny").assertIsDisplayed()
    }

    @Test
    fun theEndStatesShowTheirTexts() {
        var state by mutableStateOf<ChallengeState>(ChallengeState.Expired)
        rule.setContent { RustMfaTheme { ApproveContent(state, {}, {}, {}, {}) } }
        rule.onNodeWithText("Expired").assertIsDisplayed()
        state = ChallengeState.AlreadyHandled
        rule.onNodeWithText("Already handled").assertIsDisplayed()
        state = ChallengeState.NumberMismatch
        rule.onNodeWithText("Wrong number. The request was denied.").assertIsDisplayed()
        state = ChallengeState.KeyInvalidated
        rule.onNodeWithText("Reconnect device").assertIsDisplayed()
    }

    @Test
    fun theWindowIsSecureWhileTheApproveScreenIsShown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val api = object : MfaApi {
            override suspend fun getChallenge(id: String) = details
            override suspend fun register(r: RegisterRequest): DeviceStatusResponse = TODO()
            override suspend fun listChallenges(): List<ChallengeDetails> = TODO()
            override suspend fun approve(id: String, selectedNumber: Int, signature: String) = TODO()
            override suspend fun deny(id: String, signature: String) = TODO()
            override suspend fun listDevices(): List<Device> = TODO()
            override suspend fun revokeDevice(id: String) = TODO()
            override suspend fun updatePushToken(id: String, channel: PushChannel, token: String) = TODO()
            override suspend fun listAudit(): List<AuditEvent> = TODO()
        }
        val clock = Clock { Instant.parse("2026-11-14T12:00:00Z").toEpochMilli() }
        val vm = ApproveViewModel(ChallengeFlow(api, KeyStoreDeviceKey(ctx), "dev_1", clock) { kotlinx.coroutines.awaitCancellation() }, "ch_1")
        var show by mutableStateOf(true)
        rule.setContent { RustMfaTheme { if (show) ApproveScreen(vm, onClose = {}, onReconnect = {}) } }
        rule.waitForIdle()
        fun secure() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        assertTrue(secure(), "FLAG_SECURE missing while the Approve screen is shown")
        show = false
        rule.waitForIdle()
        assertTrue(!secure(), "FLAG_SECURE must be cleared when the screen is left")
    }
}
