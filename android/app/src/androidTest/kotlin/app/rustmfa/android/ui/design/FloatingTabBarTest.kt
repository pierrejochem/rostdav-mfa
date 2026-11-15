package app.rustmfa.android.ui.design

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertTrue
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
import androidx.compose.foundation.layout.width
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

    private val german = listOf(
        FloatingTab("home", "Übersicht", R.drawable.ic_nav_home),
        FloatingTab("requests", "Anfragen", R.drawable.ic_nav_list, badge = 2),
        FloatingTab("settings", "Einstellungen", R.drawable.ic_nav_settings),
    )

    private fun layout(label: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(label).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first()
    }

    @Test fun germanLabelsFitAtTheDefaultFontSize() {
        rule.setContent { RustMfaTheme { FloatingTabBar(german, "home", {}) } }
        for (t in german) {
            val l = layout(t.label)
            assertEquals(1, l.lineCount, "'${t.label}' wraps")
            assertTrue(l.multiParagraph.maxIntrinsicWidth <= l.size.width, "'${t.label}' is clipped")
        }
    }

    @Test fun labelsStayOnOneLineAndTheBarKeepsItsHeightAtTwiceTheFontSize() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(base, fontScale = 2f)) {
                RustMfaTheme { FloatingTabBar(german, "home", {}) }
            }
        }
        for (t in german) {
            val l = layout(t.label)
            assertEquals(1, l.lineCount, "'${t.label}' wraps")
            assertTrue(l.multiParagraph.maxIntrinsicWidth <= l.size.width, "'${t.label}' is cut: ${l.multiParagraph.maxIntrinsicWidth} > ${l.size.width}")
        }
        rule.onNodeWithTag("floating-tab-bar").assertHeightIsEqualTo(76.dp)
    }

    @Test fun germanLabelsFitOnA320dpPhoneToo() {
        rule.setContent { RustMfaTheme { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(320.dp)) { FloatingTabBar(german, "home", {}) } } }
        for (t in german) {
            val l = layout(t.label)
            assertTrue(l.multiParagraph.maxIntrinsicWidth <= l.size.width, "'${t.label}' is cut on 320 dp: ${l.multiParagraph.maxIntrinsicWidth} > ${l.size.width}")
        }
    }

    @Test fun twiceTheFontSizeEnlargesTheBarLabelsByAtMostAThird() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        var scale by mutableStateOf(1f)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(base, fontScale = scale)) {
                RustMfaTheme { FloatingTabBar(german, "home", {}) }
            }
        }
        val normal = layout("Übersicht").size.height
        scale = 2f
        rule.waitForIdle()
        val large = layout("Übersicht").size.height
        assertTrue(large <= normal * 1.4f, "label height $large px at 2x against $normal px at 1x")
    }

    @Test fun aHundredWaitingShowsNinetyNinePlus() {
        val many = tabs.map { if (it.badge != null) it.copy(badge = 150) else it }
        rule.setContent { RustMfaTheme { FloatingTabBar(many, "home", {}) } }
        rule.onNodeWithText("99+").assertIsDisplayed()
    }
}
