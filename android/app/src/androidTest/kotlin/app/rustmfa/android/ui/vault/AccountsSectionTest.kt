package app.rustmfa.android.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.android.vault.AccountRow
import app.rustmfa.android.vault.VaultUiState
import app.rustmfa.vault.OtpType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountsSectionTest {
    @get:Rule val rule = createComposeRule()

    /** Records every callback as "name:arg". */
    private val calls = mutableListOf<String>()

    private fun totp(id: String = "t1", issuer: String = "GitHub", label: String = "alice", urgent: Boolean = false) =
        AccountRow(id, issuer, label, OtpType.TOTP, "482 731", if (urgent) 4 else 22, 30, urgent)

    private fun hotp(id: String = "h1") = AccountRow(id, "Counter", "bob", OtpType.HOTP, null, 0, 30, false)

    private fun show(state: VaultUiState, showNudge: Boolean = false, width360: Boolean = false, fontScale: Float = 1f) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(base, fontScale)) {
                RustMfaTheme {
                    val m = if (width360) Modifier.width(360.dp) else Modifier
                    Column(m.verticalScroll(rememberScrollState())) {
                        AccountsSection(
                            state = state,
                            showNudge = showNudge,
                            onUnlock = { calls += "unlock" },
                            onLock = { calls += "lock" },
                            onAdd = { calls += "add" },
                            onCopy = { calls += "copy:$it" },
                            onGenerate = { calls += "generate:$it" },
                            onOpenMenu = { calls += "menu:$it" },
                            onBackup = { calls += "backup" },
                            onDismissNudge = { calls += "dismiss" },
                            onRestore = { calls += "restore" },
                            onNewVault = { calls += "new" },
                        )
                    }
                }
            }
        }
    }

    @Test fun noVaultInvitesTheFirstAccount() {
        show(VaultUiState.NoVault)
        rule.onNodeWithText("Authenticator vault").assertIsDisplayed()
        rule.onNodeWithText("Add your first account").performClick()
        assertEquals(listOf("add"), calls)
    }

    @Test fun lockedShowsAnUnlockButton() {
        show(VaultUiState.Locked)
        rule.onNodeWithText("Vault locked").assertIsDisplayed()
        rule.onNodeWithText("Unlock").performClick()
        assertEquals(listOf("unlock"), calls)
    }

    @Test fun emptyOffersAddAccount() {
        show(VaultUiState.Empty)
        rule.onNodeWithText("No accounts yet").assertIsDisplayed()
        rule.onNodeWithText("Add account").performClick()
        assertEquals(listOf("add"), calls)
    }

    @Test fun needsResetOffersRestoreAndANewVault() {
        show(VaultUiState.NeedsReset)
        rule.onNodeWithText("The vault cannot be opened").assertIsDisplayed()
        rule.onNodeWithText("Restore from a backup").performClick()
        rule.onNodeWithText("Start a new vault").performClick()
        assertEquals(listOf("restore", "new"), calls)
    }

    @Test fun loadingRendersNothing() {
        show(VaultUiState.Loading)
        rule.onAllNodes(hasText("", substring = true)).assertCountEquals(0)
    }

    @Test fun theHeaderLocksAndAdds() {
        show(VaultUiState.Unlocked(listOf(totp())))
        rule.onNodeWithText("Lock now").performClick()
        rule.onNodeWithContentDescription("Add account").performClick()
        assertEquals(listOf("lock", "add"), calls)
    }

    @Test fun noLockButtonWhileLocked() {
        show(VaultUiState.Locked)
        rule.onAllNodesWithText("Lock now").assertCountEquals(0)
    }

    @Test fun unlockedRowsShowIssuerLabelAndTheGroupedCode() {
        show(VaultUiState.Unlocked(listOf(totp(), totp("t2", "Mail", "bob"))))
        for (t in listOf("GitHub", "alice", "Mail", "bob")) rule.onNodeWithText(t).assertIsDisplayed()
        rule.onAllNodesWithText("482 731", useUnmergedTree = true).assertCountEquals(2)
        val description = rule.onNodeWithTag("account-row-t1").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertEquals(listOf("GitHub, alice, 22 seconds left"), description)
        assertFalse(description.any { "482" in it || "731" in it }, "the code digits are in the content description")
    }

    @Test fun aHotpRowSaysTapToGenerateAndHasNoRing() {
        show(VaultUiState.Unlocked(listOf(totp(), hotp())))
        rule.onNodeWithText("Tap to generate").assertIsDisplayed()
        rule.onAllNodesWithTag("account-ring-h1", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("account-ring-t1", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun anUrgentRowIsMarked() {
        show(VaultUiState.Unlocked(listOf(totp("t1", urgent = true), totp("t2", "Mail", "bob"))))
        rule.onAllNodesWithTag("account-urgent-t1", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag("account-urgent-t2", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun aTapCopiesATotpCodeAndGeneratesAHotpCode() {
        show(VaultUiState.Unlocked(listOf(totp(), hotp())))
        rule.onNodeWithTag("account-row-t1").performClick()
        rule.onNodeWithTag("account-row-h1").performClick()
        assertEquals(listOf("copy:t1", "generate:h1"), calls)
    }

    @Test fun aLongPressOpensTheMenu() {
        show(VaultUiState.Unlocked(listOf(totp())))
        rule.onNodeWithTag("account-row-t1").performTouchInput { longClick() }
        assertEquals(listOf("menu:t1"), calls)
    }

    @Test fun aRowOffersCopyEditAndDeleteAsCustomActions() {
        show(VaultUiState.Unlocked(listOf(totp())))
        val actions = rule.onNodeWithTag("account-row-t1").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Copy", "Edit", "Delete"), actions.map { it.label })
        rule.runOnIdle { actions.forEach { it.action() } }
        assertEquals(listOf("copy:t1", "menu:t1", "menu:t1"), calls)
    }

    @Test fun theCopyActionOnAHotpRowWithNoCodeGenerates() {
        show(VaultUiState.Unlocked(listOf(hotp())))
        val actions = rule.onNodeWithTag("account-row-h1").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnIdle { actions.first { it.label == "Copy" }.action() }
        assertEquals(listOf("generate:h1"), calls)
    }

    @Test fun theNudgeShowsOnlyWhenAsked() {
        show(VaultUiState.Unlocked(listOf(totp())), showNudge = true)
        rule.onNodeWithText("No backup yet").assertIsDisplayed()
        rule.onNodeWithText("Make a backup").performClick()
        rule.onNodeWithText("Later").performClick()
        assertEquals(listOf("backup", "dismiss"), calls)
    }

    @Test fun noNudgeWhenNotAsked() {
        show(VaultUiState.Unlocked(listOf(totp())), showNudge = false)
        rule.onAllNodesWithText("No backup yet").assertCountEquals(0)
    }

    @Test fun unlockStaysReachableAt360DpAndTwiceTheFontSize() {
        show(VaultUiState.Locked, width360 = true, fontScale = 2f)
        rule.onNodeWithText("Unlock").performScrollTo().assertIsDisplayed()
    }

    @Test fun aLongIssuerIsCutAndTheCodeStaysAt360DpAndTwiceTheFontSize() {
        val long = "A very long service name that cannot possibly fit on one line"
        show(VaultUiState.Unlocked(listOf(totp(issuer = long))), width360 = true, fontScale = 2f)
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(long, useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val layout = results.first()
        assertEquals(1, layout.lineCount)
        assertTrue(layout.isLineEllipsized(0), "the long issuer is not ellipsised")
        rule.onNodeWithText("482 731", useUnmergedTree = true).assertIsDisplayed()
    }
}
