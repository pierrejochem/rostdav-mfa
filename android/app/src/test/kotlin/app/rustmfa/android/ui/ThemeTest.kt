package app.rustmfa.android.ui

import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.sp
import app.rustmfa.android.ui.design.Tokens
import app.rustmfa.android.ui.design.rostdavTypography
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeTest {
    @Test fun lightSchemeMapsTheLightTokens() {
        val s = lightScheme()
        assertEquals(Tokens.Light.action, s.primary)
        assertEquals(Tokens.Light.onAction, s.onPrimary)
        assertEquals(Tokens.Light.rost600, s.tertiary)
        assertEquals(Tokens.Light.rost500, s.secondary)
        assertEquals(Tokens.Light.paper, s.background)
        assertEquals(Tokens.Light.surface, s.surface)
        assertEquals(Tokens.Light.ink, s.onSurface)
        assertEquals(Tokens.Light.slate, s.onSurfaceVariant)
        assertEquals(Tokens.Light.danger, s.error)
        assertEquals(lerp(Tokens.Light.paper, Tokens.Light.rost500, 0.12f), s.primaryContainer)
    }

    @Test fun darkSchemeMapsTheDarkTokens() {
        val s = darkScheme()
        assertEquals(Tokens.Dark.action, s.primary)
        assertEquals(Tokens.Dark.onAction, s.onPrimary)
        assertEquals(Tokens.Dark.rost600, s.tertiary)
        assertEquals(Tokens.Dark.paper, s.background)
        assertEquals(Tokens.Dark.danger, s.error)
    }

    @Test fun typographyFollowsTheScale() {
        val t = rostdavTypography()
        assertEquals(28.sp, t.headlineMedium.fontSize)
        assertEquals(20.sp, t.titleMedium.fontSize)
        assertEquals(16.sp, t.bodyLarge.fontSize)
        assertEquals(14.sp, t.labelLarge.fontSize)
        assertEquals(12.sp, t.bodySmall.fontSize)
    }
}
