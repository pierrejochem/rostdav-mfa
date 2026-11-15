package app.rustmfa.android.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.rustmfa.android.ui.RustMfaTheme
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Tonal button is the brand's rust tint in both themes, not Material's default lavender. */
class TonalButtonColourTest {
    @get:Rule val rule = createComposeRule()

    private fun container(dark: Boolean): Color {
        rule.setContent {
            RustMfaTheme(dark = dark) {
                Box(Modifier.width(300.dp).testTag("tonal")) {
                    BrandButton("A", {}, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal)
                }
            }
        }
        val pixels = rule.onNodeWithTag("tonal").captureToImage().toPixelMap()
        // left of the centred label, half way down: plain container
        return pixels[pixels.width / 10, pixels.height / 2]
    }

    private fun assertClose(expected: Color, actual: Color) {
        val near = listOf(expected.red to actual.red, expected.green to actual.green, expected.blue to actual.blue)
            .all { (e, a) -> abs(e - a) <= 2f / 255 }
        assertTrue(near, "expected $expected, drawn $actual")
    }

    @Test fun lightTonalIsTheRustTint() = assertClose(lerp(Tokens.Light.paper, Tokens.Light.rost500, 0.12f), container(dark = false))

    @Test fun darkTonalIsTheRustTint() = assertClose(lerp(Tokens.Dark.paper, Tokens.Dark.rost500, 0.12f), container(dark = true))
}
