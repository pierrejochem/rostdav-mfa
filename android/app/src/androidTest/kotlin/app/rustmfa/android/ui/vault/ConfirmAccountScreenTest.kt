package app.rustmfa.android.ui.vault

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.android.ui.design.groupCode
import app.rustmfa.vault.Base32
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpType
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class ConfirmAccountScreenTest {
    @get:Rule val rule = createComposeRule()

    private val added = mutableListOf<Pair<String, String>>()
    private val account = OtpAccount("new", OtpType.TOTP, "Example", "bob", Base32.decode("JBSWY3DPEHPK3PXP"))

    private fun show(firstCode: String = "123456", message: String? = null) =
        rule.setContent {
            RustMfaTheme { ConfirmAccountScreen(account, firstCode, onAdd = { i, l -> added += i to l }, onBack = {}, message = message) }
        }

    @Test fun showsTheAccountAndTheFirstCode() {
        show()
        rule.onNode(hasSetTextAction() and hasText("Example")).assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("bob")).assertIsDisplayed()
        for (t in listOf("TOTP", "SHA1", "6", "30 s")) rule.onNodeWithText(t).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("confirm-code").performScrollTo().assertIsDisplayed()
    }

    @Test fun theCodeIsTheFirstCodePassedIn() {
        show(firstCode = "987654")
        rule.onNodeWithTag("confirm-code").assertTextEquals(groupCode("987654"))
    }

    @Test fun editingTheNameThenAddPassesTheNewNames() {
        show()
        rule.onNode(hasSetTextAction() and hasText("Example")).performTextClearance()
        rule.onNode(hasSetTextAction() and hasText("Service")).performTextInput("New")
        rule.onNodeWithText("Add").performScrollTo().performClick()
        assertEquals(listOf("New" to "bob"), added)
    }

    @Test fun aDuplicateMessageShows() {
        show(message = "This account is already in the vault")
        rule.onNodeWithText("This account is already in the vault").assertIsDisplayed()
    }
}
