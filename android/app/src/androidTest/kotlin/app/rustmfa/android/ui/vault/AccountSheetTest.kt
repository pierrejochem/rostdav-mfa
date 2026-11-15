package app.rustmfa.android.ui.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.android.vault.AccountRow
import app.rustmfa.android.vault.VaultUiState
import app.rustmfa.vault.OtpType
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountSheetTest {
    @get:Rule val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val github = AccountRow("t1", "GitHub", "alice", OtpType.TOTP, "482 731", 22, 30, false)
    private val counter = AccountRow("h1", "Counter", "bob", OtpType.HOTP, null, 0, 30, false)

    private fun show(fontScale: Float = 1f, narrow: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(base, fontScale)) {
                RustMfaTheme { Box(if (narrow) Modifier.width(360.dp) else Modifier) { content() } }
            }
        }
    }

    private fun sheet(row: AccountRow = github, fontScale: Float = 1f, narrow: Boolean = false) =
        show(fontScale, narrow) {
            AccountSheet(row, { calls += "copy" }, { calls += "edit" }, { calls += "delete" }, { calls += "dismiss" })
        }

    @Test fun theSheetOffersThreeActions() {
        sheet()
        for (t in listOf("Copy", "Edit", "Delete")) rule.onNodeWithText(t).assertIsDisplayed()
    }

    @Test fun eachActionCallsItsLambdaThenDismiss() {
        sheet()
        rule.onNodeWithText("Copy").performClick()
        assertEquals(listOf("copy", "dismiss"), calls)
        calls.clear()
        rule.onNodeWithText("Edit").performClick()
        assertEquals(listOf("edit", "dismiss"), calls)
        calls.clear()
        rule.onNodeWithText("Delete").performClick()
        assertEquals(listOf("delete", "dismiss"), calls)
    }

    @Test fun theSheetWorksAtDoubleFontScale() {
        sheet(fontScale = 2f, narrow = true)
        for (t in listOf("Copy", "Edit", "Delete")) rule.onNodeWithText(t).assertIsDisplayed()
    }

    private fun edit(row: AccountRow = github, fontScale: Float = 1f, narrow: Boolean = false) =
        show(fontScale, narrow) {
            EditAccountDialog(row, { i, l -> calls += "save:$i:$l" }, { calls += "dismiss" })
        }

    @Test fun theEditDialogStartsWithTheRowsValues() {
        edit()
        rule.onNodeWithText("GitHub").assertIsDisplayed()
        rule.onNodeWithText("alice").assertIsDisplayed()
    }

    @Test fun saveIsDisabledForTwoBlanks() {
        edit()
        rule.onAllNodes(hasSetTextAction())[0].performTextClearance()
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onAllNodes(hasSetTextAction())[1].performTextClearance()
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test fun saveCallsOnSaveWithTheEditedValues() {
        edit()
        rule.onAllNodes(hasSetTextAction())[0].performTextClearance()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("New")
        rule.onNodeWithText("Save").performClick()
        assertEquals(listOf("save:New:alice"), calls)
    }

    @Test fun cancelOnTheEditDialogOnlyDismisses() {
        edit()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test fun theEditDialogWorksAtDoubleFontScale() {
        edit(fontScale = 2f, narrow = true)
        rule.onNodeWithText("Save").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    private fun delete(row: AccountRow = github, fontScale: Float = 1f, narrow: Boolean = false) =
        show(fontScale, narrow) { DeleteAccountDialog(row, { calls += "confirm" }, { calls += "dismiss" }) }

    private val text = " You can only get it back from a backup, or by adding it again at the service."

    @Test fun theDeleteDialogShowsTheExactText() {
        delete()
        rule.onNodeWithText("Delete GitHub?$text").assertIsDisplayed()
    }

    @Test fun theDeleteDialogNamesTheLabelWhenThereIsNoIssuer() {
        delete(github.copy(issuer = ""))
        rule.onNodeWithText("Delete alice?$text").assertIsDisplayed()
    }

    @Test fun cancelOnTheDeleteDialogOnlyDismisses() {
        delete()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test fun deleteConfirms() {
        delete()
        rule.onNodeWithText("Delete").performClick()
        assertEquals(listOf("confirm"), calls)
    }

    @Test fun theDeleteDialogWorksAtDoubleFontScale() {
        delete(fontScale = 2f, narrow = true)
        rule.onNodeWithText("Delete").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    private fun host(openId: String?) =
        show {
            var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(openId) }
            AccountActions(
                state = VaultUiState.Unlocked(listOf(github, counter)),
                openId = open,
                onClose = { calls += "close"; open = null },
                onCopy = { calls += "copy:$it" },
                onGenerate = { calls += "generate:$it" },
                onUpdate = { id, i, l -> calls += "update:$id:$i:$l" },
                onDelete = { calls += "delete:$it" },
            )
        }

    @Test fun theHostCopiesATotpRow() {
        host("t1")
        rule.onNodeWithText("Copy").performClick()
        assertEquals(listOf("copy:t1", "close"), calls)
    }

    @Test fun theHostGeneratesInsteadOfCopyingAHotpRowWithNoCode() {
        host("h1")
        rule.onNodeWithText("Copy").performClick()
        assertEquals(listOf("generate:h1", "close"), calls)
    }

    @Test fun theHostEditsThroughTheDialog() {
        host("t1")
        rule.onNodeWithText("Edit").performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextClearance()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("New")
        rule.onNodeWithText("Save").performClick()
        assertEquals(listOf("close", "update:t1:New:alice"), calls)
    }

    @Test fun theHostDeletesAfterConfirming() {
        host("t1")
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Delete GitHub?$text").assertIsDisplayed()
        assertEquals(listOf("close"), calls)
        rule.onNodeWithText("Delete").performClick()
        assertEquals(listOf("close", "delete:t1"), calls)
    }
}
