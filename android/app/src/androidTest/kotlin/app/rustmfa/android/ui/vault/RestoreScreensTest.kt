package app.rustmfa.android.ui.vault

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.android.vault.RestoreState
import app.rustmfa.vault.BackupDecryptException
import app.rustmfa.vault.ImportResult
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class RestoreScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf<RestoreState>(RestoreState.Idle)
    private var askPassphrase by mutableStateOf(false)

    private fun scaled(fontScale: Float, narrow: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(base, fontScale)) {
                RustMfaTheme { Box(if (narrow) Modifier.width(360.dp) else Modifier) { content() } }
            }
        }
    }

    private fun show(fontScale: Float = 1f, narrow: Boolean = false) = scaled(fontScale, narrow) {
        RestoreContent(
            state = state,
            askPassphrase = askPassphrase,
            onPick = { calls += "pick" },
            onDecrypt = { p -> calls += "decrypt:${String(p)}" },
            onCancelPassphrase = {
                calls += "cancelPassphrase"
                askPassphrase = false
            },
            onConfirm = { calls += "confirm" },
            onFinish = { calls += "finish" },
            onAbandon = {
                calls += "abandon"
                state = RestoreState.Idle
            },
            onDone = { calls += "done" },
            onBack = { calls += "back" },
        )
    }

    @Test fun theConfirmTextShowsBeforeAnyResetAndCancelResetsNothing() {
        state = RestoreState.Decrypted
        show()
        rule.onNodeWithText("This replaces the unreadable vault").assertIsDisplayed()
        assertEquals(emptyList(), calls)
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        // the decrypted list is dropped; nothing was reset
        assertEquals(listOf("abandon"), calls)
        rule.onNodeWithText("This replaces the unreadable vault").assertDoesNotExist()
    }

    @Test fun replaceCallsConfirm() {
        state = RestoreState.Decrypted
        show()
        rule.onNodeWithText("Replace").performClick()
        assertEquals(listOf("confirm"), calls)
    }

    @Test fun aWrongPassphraseShowsTheDecryptMessageAndNoConfirm() {
        state = RestoreState.Failed(BackupDecryptException())
        show()
        rule.onNodeWithText("Wrong passphrase or damaged file.").assertIsDisplayed()
        rule.onNodeWithText("This replaces the unreadable vault").assertDoesNotExist()
        rule.onNodeWithText("Finish restoring").assertDoesNotExist()
        rule.onNodeWithText("Choose backup file").assertIsDisplayed()
    }

    @Test fun finishPendingShowsTheButtonAndAClickCallsFinish() {
        state = RestoreState.FinishPending
        show()
        rule.onNodeWithText("Finish restoring").assertIsDisplayed().performClick()
        assertEquals(listOf("finish"), calls)
        rule.onNodeWithText("Choose backup file").assertDoesNotExist()
    }

    @Test fun aFailureAfterTheResetShowsItsMessageAndFinish() {
        state = RestoreState.Failed(IOException("disk full"), canFinish = true)
        show()
        rule.onNodeWithText("Could not read or write the file.").assertIsDisplayed()
        rule.onNodeWithText("Finish restoring").performClick()
        assertEquals(listOf("finish"), calls)
    }

    @Test fun backWhileFinishPendingAsksBeforeTheListIsDropped() {
        state = RestoreState.FinishPending
        show()
        rule.onNodeWithText("Back").performScrollTo().performClick()
        rule.onNodeWithText("Stop restoring?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(emptyList(), calls)
        rule.onNodeWithText("Finish restoring").assertIsDisplayed()
        rule.onNodeWithText("Back").performScrollTo().performClick()
        rule.onNodeWithText("Stop").performClick()
        rule.waitForIdle()
        assertEquals(listOf("abandon", "back"), calls)
    }

    @Test fun backBeforeTheResetLeavesAndDropsTheList() {
        show()
        rule.onNodeWithText("Back").performScrollTo().performClick()
        assertEquals(listOf("abandon", "back"), calls)
    }

    @Test fun doneShowsTheCountsAndGoesBackToTheOverview() {
        state = RestoreState.Done(ImportResult(2, 1))
        show()
        rule.onNodeWithText("Added 2, skipped 1").assertIsDisplayed()
        rule.onNodeWithText("Back to the overview").performClick()
        assertEquals(listOf("done"), calls)
    }

    @Test fun pickThenThePassphraseDecrypts() {
        show()
        rule.onNodeWithText("Choose backup file").performClick()
        askPassphrase = true
        rule.onNode(hasSetTextAction()).performTextInput("correct-horse-9")
        rule.onNodeWithText("Restore").performClick()
        assertEquals(listOf("pick", "decrypt:correct-horse-9"), calls)
    }

    @Test fun workingShowsProgressAndTurnsTheButtonsOff() {
        state = RestoreState.Working
        show()
        rule.onNodeWithTag("restore-progress").assertIsDisplayed()
        rule.onNodeWithText("Choose backup file").assertIsNotEnabled()
        rule.onNodeWithText("Back").performScrollTo().assertIsNotEnabled()
    }

    @Test fun theNewVaultDialogShowsItsTextCancelDoesNothingConfirmCallsOnConfirm() {
        var open by mutableStateOf(true)
        scaled(1f, false) {
            if (open) {
                NewVaultDialog(onConfirm = { calls += "confirm" }, onDismiss = {
                    calls += "dismiss"
                    open = false
                })
            }
        }
        rule.onNodeWithText("This deletes the unreadable vault for good").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(listOf("dismiss"), calls)
        open = true
        rule.onNodeWithText("Delete and start new").performClick()
        assertTrue("confirm" in calls)
    }

    @Test fun usableAt360DpAndTwiceTheFontSize() {
        show(fontScale = 2f, narrow = true)
        rule.onNodeWithText("Choose backup file").performScrollTo().assertIsDisplayed().performClick()
        state = RestoreState.Decrypted
        rule.onNodeWithText("Replace").assertIsDisplayed().performClick()
        state = RestoreState.FinishPending
        rule.onNodeWithText("Finish restoring").performScrollTo().assertIsDisplayed().performClick()
        state = RestoreState.Done(ImportResult(2, 0))
        rule.onNodeWithText("Back to the overview").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("pick", "confirm", "finish", "done"), calls)
    }

    @Test fun theNewVaultDialogIsUsableAt360DpAndTwiceTheFontSize() {
        scaled(2f, true) { NewVaultDialog(onConfirm = { calls += "confirm" }, onDismiss = { calls += "dismiss" }) }
        rule.onNodeWithText("Delete and start new").assertIsDisplayed().performClick()
        assertEquals(listOf("confirm"), calls)
    }

    @Test fun theScreenHoldsFlagSecure() {
        show()
        rule.waitForIdle()
        assertTrue(rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0, "FLAG_SECURE missing")
    }
}
