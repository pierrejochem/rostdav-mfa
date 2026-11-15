package app.rustmfa.android.ui.vault

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.android.vault.VaultEvent
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test

class AddAccountScreenTest {
    @get:Rule val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    @Test fun theThreeRowsCallTheirLambdas() {
        rule.setContent {
            RustMfaTheme { AddAccountScreen(onScan = { calls += "scan" }, onManual = { calls += "manual" }, onImport = { calls += "import" }, onBack = { calls += "back" }) }
        }
        rule.onNodeWithText("Scan QR code").assertIsDisplayed().performClick()
        rule.onNodeWithText("Enter by hand").assertIsDisplayed().performClick()
        rule.onNodeWithText("Import a backup").assertIsDisplayed().performClick()
        rule.onNodeWithText("Back").performClick()
        assertEquals(listOf("scan", "manual", "import", "back"), calls)
    }

    @Test fun addEventsRouteAddedAndDuplicateOnly() {
        val events = MutableSharedFlow<VaultEvent>(extraBufferCapacity = 8)
        rule.setContent { AddEvents(events, onAdded = { calls += "added" }, onDuplicate = { calls += "duplicate" }) }
        rule.waitForIdle()
        events.tryEmit(VaultEvent.Copied)
        events.tryEmit(VaultEvent.UnlockToSave)
        events.tryEmit(VaultEvent.Duplicate)
        events.tryEmit(VaultEvent.Added)
        rule.waitUntil(5_000) { calls.size == 2 }
        assertEquals(listOf("duplicate", "added"), calls)
    }
}
