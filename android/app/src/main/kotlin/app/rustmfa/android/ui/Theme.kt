package app.rustmfa.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.lerp
import app.rustmfa.android.ui.design.Tokens
import app.rustmfa.android.ui.design.rostdavTypography

internal fun lightScheme(): ColorScheme =
    lightColorScheme(
        primary = Tokens.Light.rost600,
        onPrimary = Tokens.Light.onRost,
        primaryContainer = lerp(Tokens.Light.paper, Tokens.Light.rost500, 0.12f),
        onPrimaryContainer = Tokens.Light.rost700,
        secondary = Tokens.Light.rost500,
        background = Tokens.Light.paper,
        onBackground = Tokens.Light.ink,
        surface = Tokens.Light.surface,
        onSurface = Tokens.Light.ink,
        onSurfaceVariant = Tokens.Light.slate,
        error = Tokens.Light.danger,
    )

internal fun darkScheme(): ColorScheme =
    darkColorScheme(
        primary = Tokens.Dark.rost600,
        onPrimary = Tokens.Dark.onRost,
        primaryContainer = lerp(Tokens.Dark.paper, Tokens.Dark.rost500, 0.12f),
        onPrimaryContainer = Tokens.Dark.rost700,
        secondary = Tokens.Dark.rost500,
        background = Tokens.Dark.paper,
        onBackground = Tokens.Dark.ink,
        surface = Tokens.Dark.surface,
        onSurface = Tokens.Dark.ink,
        onSurfaceVariant = Tokens.Dark.slate,
        error = Tokens.Dark.danger,
    )

@Composable
fun RustMfaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) darkScheme() else lightScheme(), typography = rostdavTypography(), content = content)
}
