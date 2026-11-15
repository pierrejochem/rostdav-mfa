package app.rustmfa.android.ui.vault

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.vault.Base32
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpType
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/** The add-flow screens show secret material: they hold FLAG_SECURE themselves, whatever the vault state. */
class AddFlowSecureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun secure() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    /** [screen] next to the root holder of MainActivity (on while unlocked); the vault locks, then the screen leaves. */
    private fun holdsTheFlagThroughALock(screen: @Composable () -> Unit) {
        var unlocked by mutableStateOf(true)
        var shown by mutableStateOf(true)
        rule.setContent {
            SecureWhile(unlocked)
            RustMfaTheme { if (shown) screen() }
        }
        rule.waitForIdle()
        assertTrue(secure(), "FLAG_SECURE missing while unlocked")
        unlocked = false
        rule.waitForIdle()
        assertTrue(secure(), "the screen must keep FLAG_SECURE after the vault locked")
        shown = false
        rule.waitForIdle()
        assertFalse(secure(), "FLAG_SECURE must go when the screen leaves on a locked vault")
    }

    @Test fun theManualFormKeepsTheFlagThroughALock() =
        holdsTheFlagThroughALock { ManualAccountScreen(ManualAccountForm(), onAdd = {}, onBack = {}) }

    @Test fun theConfirmScreenKeepsTheFlagThroughALock() {
        val account = OtpAccount("new", OtpType.TOTP, "Example", "bob", Base32.decode("JBSWY3DPEHPK3PXP"))
        holdsTheFlagThroughALock { ConfirmAccountScreen(account, "123456", onAdd = { _, _ -> }, onBack = {}) }
    }

    @Test fun theManualFormStartsSecureOnALockedVault() {
        // after a process restart the form comes back before the vault is unlocked
        rule.setContent { RustMfaTheme { ManualAccountScreen(ManualAccountForm(), onAdd = {}, onBack = {}) } }
        rule.waitForIdle()
        assertTrue(secure(), "FLAG_SECURE missing on the manual form")
    }

    @Test fun theShownSecretIsHiddenAgainAfterTheAppWasLeft() {
        val secret = "JBSWY3DPEHPK3PXP"
        rule.setContent { RustMfaTheme { ManualAccountScreen(ManualAccountForm(), onAdd = {}, onBack = {}) } }
        val field = rule.onNode(hasSetTextAction() and hasText("Secret key"))
        field.performTextInput(secret)
        rule.onNodeWithContentDescription("Show secret key").performClick()
        field.assert(drawn(secret))
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
        field.assert(drawn("•".repeat(secret.length)))
        rule.onNodeWithContentDescription("Show secret key").assertExists()
    }

    private fun drawn(text: String) = SemanticsMatcher("draws '$text'") { it.config.getOrNull(SemanticsProperties.EditableText)?.text == text }
}
