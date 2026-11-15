package app.rustmfa.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.text.DateFormat
import java.util.Date
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theBackupRowShowsTheSubtitleAndOpensBackup() {
        var opened = 0
        rule.setContent {
            RustMfaTheme {
                SettingsScreen("https://rd.example", "0.1.0", onOpenDevices = {}, onOpenBackup = { opened++ }, backupSubtitle = "Last backup: 14 Nov 2023")
            }
        }
        rule.onNodeWithText("Last backup: 14 Nov 2023").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Backup").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test fun theSubtitleNamesTheLastBackupOrSaysThereIsNone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("No backup yet", backupSubtitle(context, null))
        val at = 1_700_000_000_000L
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
        assertEquals("Last backup: $date", backupSubtitle(context, at))
    }
}
