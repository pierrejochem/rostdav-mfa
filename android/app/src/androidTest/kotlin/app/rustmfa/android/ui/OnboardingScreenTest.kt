package app.rustmfa.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlin.test.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun manualFormNeedsBothFieldsBeforeTheButtonWorks() {
        rule.setContent { RustMfaTheme { ManualEnrollScreen(onContinue = { _, _ -> }, onBack = {}) } }
        rule.onNodeWithText("Server address").assertExists()
        rule.onNodeWithText("Enrollment token").assertExists()
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        rule.onNodeWithText("Server address").performTextInput("https://rd.example")
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        rule.onNodeWithText("Enrollment token").performTextInput("T".repeat(40))
        rule.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun scanningAsksForTheCameraAndADenialKeepsManualEntryUsable() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "camera permission already granted on this install",
            ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED,
        )
        rule.setContent { RustMfaTheme { ScanScreen(onText = {}, onManual = {}, onBack = {}) } }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val deny = device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_deny_button")), 10_000)
        assertNotNull(deny, "the system camera permission dialog did not appear")
        deny.click()
        rule.waitUntil(10_000) {
            // the activity is recreated around the system dialog: no compose hierarchy for a moment
            try {
                rule.onAllNodesWithTextCount("Camera access was denied. You can still enter the address and token by hand.") == 1
            } catch (_: IllegalStateException) {
                false
            }
        }
        rule.onNodeWithText("Enter by hand").assertExists()
    }

    @Test
    fun startScreenShowsTheWordmarkAndTallButtons() {
        rule.setContent { RustMfaTheme { OnboardingStart(onScan = {}, onManual = {}) } }
        rule.onNodeWithContentDescription("rostdav-mfa").assertIsDisplayed()
        rule.onNodeWithText("Scan QR code").assertHeightIsAtLeast(56.dp)
        rule.onNodeWithText("Enter by hand").assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun germanLabelsAreNotClippedAtTheDefaultFontScale() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val conf = Configuration(base.resources.configuration).apply { setLocale(Locale.GERMAN) }
        val de = base.createConfigurationContext(conf)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides de, LocalConfiguration provides conf) {
                RustMfaTheme { OnboardingStart(onScan = {}, onManual = {}) }
            }
        }
        for (label in listOf(de.getString(app.rustmfa.android.R.string.scan_button), de.getString(app.rustmfa.android.R.string.manual_button))) {
            rule.onNodeWithText(label).assertIsDisplayed()
            val layout = textLayout(rule.onNodeWithText(label).fetchSemanticsNode().config)
            assertEquals(1, layout.lineCount, "'$label' wraps")
            assertTrue(layout.multiParagraph.maxIntrinsicWidth <= layout.size.width, "'$label' is clipped: ${layout.multiParagraph.maxIntrinsicWidth} > ${layout.size.width}")
            assertFalse(layout.didOverflowHeight, "'$label' overflows its height")
        }
    }

    @Test
    fun theFailureScreenScrollsToItsButtonAtTwiceTheFontSize() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(base, fontScale = 2f)) {
                RustMfaTheme { OnboardingFailed("The server could not be reached. ".repeat(6), onRetry = {}) }
            }
        }
        rule.onNodeWithText("Try again").performScrollTo().assertIsDisplayed()
    }

    private fun textLayout(config: androidx.compose.ui.semantics.SemanticsConfiguration): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(t: String) =
        onAllNodes(androidx.compose.ui.test.hasText(t)).fetchSemanticsNodes().size
}
