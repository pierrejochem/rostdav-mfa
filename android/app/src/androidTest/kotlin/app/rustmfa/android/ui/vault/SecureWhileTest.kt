package app.rustmfa.android.ui.vault

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class SecureWhileTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun secure() = rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun theFlagFollowsActiveAndGoesOnDispose() {
        var active by mutableStateOf(true)
        var shown by mutableStateOf(true)
        rule.setContent { if (shown) SecureWhile(active) }
        rule.waitForIdle()
        assertTrue(secure(), "FLAG_SECURE missing while active")
        active = false
        rule.waitForIdle()
        assertFalse(secure(), "FLAG_SECURE must be cleared when active turns false")
        active = true
        rule.waitForIdle()
        assertTrue(secure(), "FLAG_SECURE missing after active turned true again")
        shown = false
        rule.waitForIdle()
        assertFalse(secure(), "FLAG_SECURE must be cleared on dispose")
    }

    @Test
    fun oneHolderLettingGoKeepsTheFlagForTheOther() {
        // the Approve screen and the unlocked vault may both want the flag at once
        var vault by mutableStateOf(true)
        rule.setContent {
            SecureWhile(true)
            SecureWhile(vault)
        }
        rule.waitForIdle()
        vault = false
        rule.waitForIdle()
        assertTrue(secure(), "the other holder still needs FLAG_SECURE")
    }
}
