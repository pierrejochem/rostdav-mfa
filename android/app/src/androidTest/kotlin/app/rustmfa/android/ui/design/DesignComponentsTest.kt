package app.rustmfa.android.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import app.rustmfa.android.ui.RustMfaTheme
import org.junit.Rule
import org.junit.Test

class DesignComponentsTest {
    @get:Rule val rule = createComposeRule()

    private fun both(content: @androidx.compose.runtime.Composable () -> Unit) {
        var dark by mutableStateOf(false)
        rule.setContent { RustMfaTheme(dark = dark) { content() } }
        rule.waitForIdle()
        dark = true
        rule.waitForIdle()
    }

    @Test fun everyComponentRendersInLightAndDark() = both {
        Column {
            BrandMark()
            BrandWordmark()
            BrandButton("Filled", {}, kind = ButtonKind.Filled)
            BrandButton("Tonal", {}, kind = ButtonKind.Tonal)
            BrandButton("Outlined", {}, kind = ButtonKind.Outlined)
            BrandButton("Destructive", {}, kind = ButtonKind.Destructive)
            StatusBanner(BannerKind.Protected, "Protected", "Body")
            StatusBanner(BannerKind.Warning, "Warning")
            StatusBanner(BannerKind.Error, "Error", "Body")
            ListCard { ListRow("Title", "Subtitle", leading = { AppIconTile("GitHub") }, trailing = "Value") }
            CodeRing("482731", 12, 30)
            NumberChoice(42, {})
            GlassSheet { BrandButton("In the sheet", {}) }
        }
    }

    @Test fun brandButtonIsAtLeast56dpHigh() {
        rule.setContent { RustMfaTheme { BrandButton("Go", {}) } }
        rule.onNodeWithText("Go").assertHeightIsAtLeast(56.dp)
    }

    @Test fun numberChoiceIsTallAndNamedByItsNumber() {
        rule.setContent { RustMfaTheme { NumberChoice(42, {}) } }
        rule.onNodeWithContentDescription("Number 42").assertHeightIsAtLeast(72.dp).assertIsDisplayed()
    }

    @Test fun aVeryLongTitleKeepsTheTrailingTextVisible() {
        val long = "x".repeat(300)
        rule.setContent { RustMfaTheme { ListCard(Modifier.width(360.dp)) { ListRow(long, trailing = "Trailing") } } }
        rule.onNodeWithText("Trailing").assertIsDisplayed()
    }

    @Test fun aLongTrailingValueDoesNotCutTheTitleDownToItsFirstLetter() {
        val base = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base, fontScale = 2f),
            ) { RustMfaTheme { ListCard(Modifier.width(360.dp)) { ListRow("Server", trailing = "http://10.0.2.2:18080/") } } }
        }
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText("Server").fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val l = results.first()
        org.junit.Assert.assertTrue("'Server' is cut: ${l.multiParagraph.maxIntrinsicWidth} > ${l.size.width}", l.multiParagraph.maxIntrinsicWidth <= l.size.width)
    }

    @Test fun codeRingDescribesTheTimeLeftNotTheDigits() {
        rule.setContent { RustMfaTheme { CodeRing("482731", 12, 30) } }
        rule.onNodeWithContentDescription("code, 12 seconds left").assertIsDisplayed()
    }

    @Test fun appIconTileShowsTheInitial() {
        rule.setContent { RustMfaTheme { AppIconTile("github") } }
        rule.onNodeWithText("G").assertIsDisplayed()
    }
}
