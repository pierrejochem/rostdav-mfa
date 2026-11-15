package app.rustmfa.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.rustmfa.android.ui.RustMfaTheme

fun initialOf(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

/** A service tile: the first letter of the name on a soft orange tile. */
@Composable
fun AppIconTile(name: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(Tokens.radiusChip)).background(lerp(scheme.surface, scheme.secondary, 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initialOf(name), style = MaterialTheme.typography.titleMedium, color = scheme.onPrimaryContainer, modifier = Modifier.semantics { hideFromAccessibility() })
    }
}

@LightDarkPreview
@Composable
private fun TilePreview() = RustMfaTheme { AppIconTile("github") }
