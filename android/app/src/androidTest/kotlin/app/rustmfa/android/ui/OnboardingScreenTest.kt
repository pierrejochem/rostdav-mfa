package app.rustmfa.android.ui

import android.Manifest
import android.content.pm.PackageManager
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
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTextCount("Camera access was denied. You can still enter the address and token by hand.") == 1
        }
        rule.onNodeWithText("Enter by hand").assertExists()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(t: String) =
        onAllNodes(androidx.compose.ui.test.hasText(t)).fetchSemanticsNodes().size
}
