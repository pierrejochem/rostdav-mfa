package app.rustmfa.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The Rost accent, #D2691E. */
val Rost = Color(0xFFD2691E)

private val Light =
    lightColorScheme(
        primary = Color(0xFF9C4A0C),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDBC4),
        onPrimaryContainer = Color(0xFF331200),
        secondary = Rost,
        background = Color(0xFFFFFBF8),
        surface = Color(0xFFFFFBF8),
    )

private val Dark =
    darkColorScheme(
        primary = Color(0xFFFFB68A),
        onPrimary = Color(0xFF532200),
        primaryContainer = Color(0xFF773300),
        onPrimaryContainer = Color(0xFFFFDBC4),
        secondary = Rost,
        background = Color(0xFF1C1411),
        surface = Color(0xFF1C1411),
    )

@Composable
fun RustMfaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
