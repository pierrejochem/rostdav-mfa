package app.rustmfa.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import app.rustmfa.android.domain.ChallengeState
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.net.ChallengeStatus
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * Renders the Approve screen to PNG files in the app's external files directory, because the real
 * window has `FLAG_SECURE` and cannot be captured. Not an assertion test: it is how the pictures in
 * `media/` were made. Pull them with `adb pull /sdcard/Android/data/app.rustmfa.android/files/screens`.
 */
class ScreenshotsTest {
    @get:Rule val rule = createComposeRule()

    private val details = ChallengeDetails(
        challengeId = "ch_1", serviceName = "Rostdav", loginDevice = "Firefox on Linux", createdAt = "2026-11-15T12:00:00Z",
        expiresAt = "2026-11-15T12:01:30Z", status = ChallengeStatus.Pending, candidateNumbers = listOf(41, 17, 30),
        nonce = "n", loginId = "l", audience = "rostdav", approximateLocation = "Amsterdam, NL",
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { RustMfaTheme { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() } } } }
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun approveScreens() {
        val suffix = if (InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.uiMode and 0x30 == 0x20) "dark" else "light"
        shot("approve-pending-$suffix") { ApproveContent(ChallengeState.Pending(details, 84), {}, {}, {}, {}) }
    }

    @Test
    fun approvedScreen() {
        val suffix = if (InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.uiMode and 0x30 == 0x20) "dark" else "light"
        shot("approve-approved-$suffix") { ApproveContent(ChallengeState.Approved, {}, {}, {}, {}) }
    }
}
