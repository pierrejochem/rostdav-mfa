package app.rustmfa.android.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The background of the splash-like screens: the page colour with two soft orange glow orbs, like the
 * Rostdav web UI. Plain gradients, no blur, so it costs nothing on any API level.
 */
@Composable
fun Modifier.brandWash(): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val bg = scheme.background
    val orb = scheme.secondary
    return drawBehind {
        drawRect(bg)
        val r = size.maxDimension * 0.7f
        drawRect(
            Brush.radialGradient(listOf(orb.copy(alpha = if (dark) 0.40f else 0.28f), Color.Transparent), center = Offset(size.width * 0.1f, size.height * 0.08f), radius = r),
        )
        drawRect(
            Brush.radialGradient(listOf(orb.copy(alpha = if (dark) 0.22f else 0.16f), Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.95f), radius = r),
        )
    }
}
