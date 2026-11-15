package app.rustmfa.android.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import app.rustmfa.android.ui.design.Tokens
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

/** Text outside any Surface must still get the theme's text colour, in both themes. */
class ThemeContentColorTest {
    @get:Rule val rule = createComposeRule()

    private fun contentColor(dark: Boolean): Color {
        var c = Color.Unspecified
        rule.setContent { RustMfaTheme(dark = dark) { c = LocalContentColor.current } }
        rule.waitForIdle()
        return c
    }

    @Test fun darkThemeTextIsIvory() = assertEquals(Tokens.Dark.ink, contentColor(dark = true))

    @Test fun lightThemeTextIsEisen() = assertEquals(Tokens.Light.ink, contentColor(dark = false))
}
