package app.rustmfa.android.ui.design

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.ui.RustMfaTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class FloatingTabBarTest {
    @get:Rule val rule = createComposeRule()

    private val tabs = listOf(
        FloatingTab("home", "Overview", R.drawable.ic_nav_home),
        FloatingTab("requests", "Requests", R.drawable.ic_nav_list, badge = 2),
        FloatingTab("settings", "Settings", R.drawable.ic_nav_settings),
    )

    @Test fun showsThreeLabelledTabsInAPillOf76dp() {
        rule.setContent { RustMfaTheme { FloatingTabBar(tabs, "home", {}) } }
        for (l in listOf("Overview", "Requests", "Settings")) rule.onNodeWithText(l).assertIsDisplayed()
        rule.onNodeWithTag("floating-tab-bar").assertHeightIsEqualTo(76.dp)
    }

    @Test fun everyTabIsAtLeast48dpTall() {
        rule.setContent { RustMfaTheme { FloatingTabBar(tabs, "home", {}) } }
        for (r in listOf("home", "requests", "settings")) rule.onNodeWithTag("tab-$r").assertHeightIsAtLeast(48.dp)
    }

    @Test fun onlyTheSelectedTabIsSelected() {
        rule.setContent { RustMfaTheme { FloatingTabBar(tabs, "requests", {}) } }
        rule.onNodeWithTag("tab-requests").assertIsSelected()
        rule.onNodeWithTag("tab-home").assertIsNotSelected()
        rule.onNodeWithTag("tab-settings").assertIsNotSelected()
    }

    @Test fun tappingATabReportsItsRoute() {
        val picked = mutableListOf<String>()
        rule.setContent { RustMfaTheme { FloatingTabBar(tabs, "home", { picked += it }) } }
        rule.onNodeWithText("Settings").performClick()
        assertEquals(listOf("settings"), picked)
    }

    @Test fun theBadgeShowsTheCountAndIsDescribed() {
        rule.setContent { RustMfaTheme { FloatingTabBar(tabs, "home", {}) } }
        rule.onNodeWithText("2").assertIsDisplayed()
        rule.onNodeWithContentDescription("2 waiting").assertExists()
    }

    @Test fun aHundredWaitingShowsNinetyNinePlus() {
        val many = tabs.map { if (it.badge != null) it.copy(badge = 150) else it }
        rule.setContent { RustMfaTheme { FloatingTabBar(many, "home", {}) } }
        rule.onNodeWithText("99+").assertIsDisplayed()
    }
}
