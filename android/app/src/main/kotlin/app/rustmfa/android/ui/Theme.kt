package app.rustmfa.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.lerp
import app.rustmfa.android.ui.design.Tokens
import app.rustmfa.android.ui.design.rostdavTypography

internal fun lightScheme(): ColorScheme =
    lightColorScheme(
        primary = Tokens.Light.action,
        onPrimary = Tokens.Light.onAction,
        tertiary = Tokens.Light.rost600,
        onTertiary = Tokens.Light.paper,
        primaryContainer = lerp(Tokens.Light.paper, Tokens.Light.rost500, 0.12f),
        onPrimaryContainer = Tokens.Light.rost700,
        secondary = Tokens.Light.rost500,
        // the Tonal button (and anything else on secondaryContainer): the same rust tint as the number choice,
        // instead of Material's default lavender
        secondaryContainer = lerp(Tokens.Light.paper, Tokens.Light.rost500, 0.12f),
        onSecondaryContainer = Tokens.Light.rost700,
        background = Tokens.Light.paper,
        onBackground = Tokens.Light.ink,
        surface = Tokens.Light.surface,
        onSurface = Tokens.Light.ink,
        onSurfaceVariant = Tokens.Light.slate,
        error = Tokens.Light.danger,
    )

internal fun darkScheme(): ColorScheme =
    darkColorScheme(
        primary = Tokens.Dark.action,
        onPrimary = Tokens.Dark.onAction,
        tertiary = Tokens.Dark.rost600,
        onTertiary = Tokens.Dark.paper,
        primaryContainer = lerp(Tokens.Dark.paper, Tokens.Dark.rost500, 0.12f),
        onPrimaryContainer = Tokens.Dark.rost700,
        secondary = Tokens.Dark.rost500,
        // the Tonal button (and anything else on secondaryContainer): the same rust tint as the number choice,
        // instead of Material's default lavender
        secondaryContainer = lerp(Tokens.Dark.paper, Tokens.Dark.rost500, 0.12f),
        onSecondaryContainer = Tokens.Dark.rost700,
        background = Tokens.Dark.paper,
        onBackground = Tokens.Dark.ink,
        surface = Tokens.Dark.surface,
        onSurface = Tokens.Dark.ink,
        onSurfaceVariant = Tokens.Dark.slate,
        error = Tokens.Dark.danger,
    )

@Composable
fun RustMfaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) darkScheme() else lightScheme()
    MaterialTheme(colorScheme = scheme, typography = rostdavTypography()) {
        // text outside a Surface would otherwise be black, unreadable on the dark paper
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
