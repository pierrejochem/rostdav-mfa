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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.android.vault.BackupState
import app.rustmfa.vault.ImportResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class BackupScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf<BackupState>(BackupState.Idle)
    private var askRestore by mutableStateOf(false)

    private fun show(fontScale: Float = 1f, narrow: Boolean = false) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(base, fontScale)) {
                RustMfaTheme {
                    Box(if (narrow) Modifier.width(360.dp) else Modifier) {
                        BackupContent(
                            state = state,
                            askRestorePassphrase = askRestore,
                            onCreate = { p, r -> calls += "create:${String(p)}:${String(r)}" },
                            onPickSource = { calls += "pick" },
                            onRestore = { p -> calls += "restore:${String(p)}" },
                            onCancelRestore = {
                                calls += "cancel"
                                askRestore = false
                            },
                            onBack = { calls += "back" },
                        )
                    }
                }
            }
        }
    }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    private fun drawn(text: String) =
        SemanticsMatcher("draws '$text'") { it.config.getOrNull(SemanticsProperties.EditableText)?.text == text }

    @Test fun createIsDisabledUntilBothPassphrasesAreValidAndEqual() {
        show()
        val create = rule.onNodeWithText("Save backup file")
        create.performScrollTo().assertIsNotEnabled()
        field("Passphrase").performTextInput("short")
        create.performScrollTo().assertIsNotEnabled()
        field("Passphrase").performTextInput(" but now long")
        create.performScrollTo().assertIsNotEnabled()
        field("Repeat passphrase").performTextInput("short but now lon")
        create.performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("The two passphrases differ.").assertExists()
        field("Repeat passphrase").performTextInput("g")
        create.performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("create:short but now long:short but now long"), calls)
    }

    @Test fun aShortPassphraseSaysSo() {
        show()
        field("Passphrase").performTextInput("abcdefg")
        rule.onNodeWithText("The passphrase needs at least 8 characters.").assertExists()
    }

    @Test fun theShowToggleRevealsBothPassphrases() {
        show()
        field("Passphrase").performTextInput("correct horse")
        field("Repeat passphrase").performTextInput("correct horse")
        field("Passphrase").assert(drawn("•".repeat(13)))
        field("Repeat passphrase").assert(drawn("•".repeat(13)))
        rule.onNodeWithContentDescription("Show passphrase").performClick()
        field("Passphrase").assert(drawn("correct horse"))
        field("Repeat passphrase").assert(drawn("correct horse"))
        rule.onNodeWithContentDescription("Hide passphrase").performClick()
        field("Passphrase").assert(drawn("•".repeat(13)))
    }

    @Test fun workingShowsAProgressIndicatorAndDisablesTheButtons() {
        show()
        field("Passphrase").performTextInput("correct horse")
        field("Repeat passphrase").performTextInput("correct horse")
        rule.onAllNodesWithTag("backup-progress").assertCountEquals(0)
        state = BackupState.Working
        rule.onNodeWithTag("backup-progress").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Save backup file").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Choose backup file").performScrollTo().assertIsNotEnabled()
    }

    @Test fun theSavedTextAppearsAndTheFieldsAreCleared() {
        show()
        field("Passphrase").performTextInput("correct horse")
        field("Repeat passphrase").performTextInput("correct horse")
        state = BackupState.Saved
        rule.onNodeWithText("Backup saved. Keep the passphrase: it cannot be recovered.").performScrollTo().assertIsDisplayed()
        field("Passphrase").assert(drawn(""))
        field("Repeat passphrase").assert(drawn(""))
    }

    @Test fun theRestoredTextAppears() {
        state = BackupState.Restored(ImportResult(added = 2, skipped = 1))
        show()
        rule.onNodeWithText("Added 2, skipped 1").performScrollTo().assertIsDisplayed()
    }

    @Test fun aFailureShowsItsMessage() {
        state = BackupState.Failed(app.rustmfa.android.R.string.backup_error_decrypt)
        show()
        rule.onNodeWithText("Wrong passphrase or damaged file.").performScrollTo().assertIsDisplayed()
    }

    @Test fun theNoteAboutTheScreenLockIsShown() {
        show()
        rule.onNodeWithText("Removing the screen lock of this phone deletes the vault. A backup is the way back.").performScrollTo().assertIsDisplayed()
    }

    @Test fun restorePicksAFileThenAsksForThePassphrase() {
        show()
        rule.onNodeWithText("Choose backup file").performScrollTo().performClick()
        assertEquals(listOf("pick"), calls)
        askRestore = true
        val restore = rule.onNodeWithText("Restore")
        restore.assertIsNotEnabled()
        field("Passphrase of the backup").performTextInput("correct horse")
        restore.assertIsEnabled().performClick()
        assertEquals(listOf("pick", "restore:correct horse"), calls)
    }

    @Test fun theRestoreDialogCanBeCancelled() {
        askRestore = true
        show()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), calls)
        rule.onAllNodesWithTag("restore-passphrase").assertCountEquals(0)
    }

    @Test fun usableAt360DpAndTwiceTheFontSize() {
        show(fontScale = 2f, narrow = true)
        field("Passphrase").performScrollTo().performTextInput("correct horse")
        field("Repeat passphrase").performScrollTo().performTextInput("correct horse")
        rule.onNodeWithText("Save backup file").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("Choose backup file").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("Back").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("create:correct horse:correct horse", "pick", "back"), calls)
    }

    @Test fun theScreenHoldsFlagSecure() {
        show()
        rule.waitForIdle()
        assertTrue(rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0, "FLAG_SECURE missing")
    }
}
