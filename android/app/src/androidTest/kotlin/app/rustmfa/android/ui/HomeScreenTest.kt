package app.rustmfa.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.net.ChallengeStatus
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun requests(n: Int) =
        (1..n).map {
            ChallengeDetails(
                challengeId = "ch_$it", serviceName = "Rostdav", loginDevice = "Device $it", createdAt = "2026-11-14T11:59:30Z",
                expiresAt = "2026-11-14T12:00:30Z", status = ChallengeStatus.Pending, candidateNumbers = listOf(12, 47, 83),
                nonce = "n", loginId = "l", audience = "rostdav", approximateLocation = null,
            )
        }

    @Test fun theProtectedBannerIsShown() {
        rule.setContent { RustMfaTheme { HomeScreen("https://rd.example", emptyList(), false, {}, {}) } }
        rule.onNodeWithText("Protected").assertIsDisplayed()
    }

    @Test fun anEmptyListSaysSo() {
        rule.setContent { RustMfaTheme { HomeScreen("https://rd.example", emptyList(), false, {}, {}) } }
        rule.onNodeWithText("No requests right now.").assertIsDisplayed()
    }

    @Test fun fiveRequestsShowThreeRowsAndSeeAll() {
        var seeAll = 0
        rule.setContent { RustMfaTheme { HomeScreen("https://rd.example", requests(5), false, {}, { seeAll++ }) } }
        for (i in 1..3) rule.onNodeWithText("Device $i").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Device 4").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Device 5").fetchSemanticsNodes().size)
        rule.onNodeWithText("See all").performClick()
        assertEquals(1, seeAll)
    }

    @Test fun threeRequestsShowNoSeeAll() {
        rule.setContent { RustMfaTheme { HomeScreen("https://rd.example", requests(3), false, {}, {}) } }
        assertEquals(0, rule.onAllNodesWithText("See all").fetchSemanticsNodes().size)
    }

    @Test fun tappingARequestOpensIt() {
        val opened = mutableListOf<String>()
        rule.setContent { RustMfaTheme { HomeScreen("https://rd.example", requests(2), false, { opened += it }, {}) } }
        rule.onNodeWithText("Device 2").performClick()
        assertEquals(listOf("ch_2"), opened)
    }

    @Test fun requestsListsAllOfThemAndAnEmptyStateWhenThereAreNone() {
        rule.setContent { RustMfaTheme { RequestsScreen(requests(5), {}) } }
        for (i in 1..5) rule.onNodeWithText("Device $i").assertIsDisplayed()
    }

    @Test fun settingsOffersDevicesAndActivity() {
        var opened = 0
        rule.setContent { RustMfaTheme { SettingsScreen("https://rd.example", "0.1.0", onOpenDevices = { opened++ }, onOpenBackup = {}, backupSubtitle = "No backup yet") } }
        rule.onNodeWithText("Devices and activity").performClick()
        assertEquals(1, opened)
    }
}
