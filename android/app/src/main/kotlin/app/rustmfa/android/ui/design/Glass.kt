package app.rustmfa.android.ui.design

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.rustmfa.android.ui.RustMfaTheme

enum class GlassStyle { Translucent, Solid }

/** Translucent only from API 30 and only while animations are on; otherwise a solid surface. */
fun glassStyle(sdk: Int, animationsReduced: Boolean): GlassStyle =
    if (sdk >= 30 && !animationsReduced) GlassStyle.Translucent else GlassStyle.Solid

@Composable
internal fun rememberGlassStyle(): GlassStyle {
    val resolver = LocalContext.current.contentResolver
    return remember {
        val scale = runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        glassStyle(Build.VERSION.SDK_INT, animationsReduced = scale == 0f)
    }
}

@Composable
internal fun animationsReduced(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) == 0f }
}

@Composable
private fun glassColors(style: GlassStyle): Pair<Color, BorderStroke?> {
    val surface = MaterialTheme.colorScheme.surface
    return if (style == GlassStyle.Translucent) {
        val edge = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.7f)
        surface.copy(alpha = 0.7f) to BorderStroke(1.dp, edge)
    } else {
        surface to null
    }
}

/** A sheet-shaped surface (radius 28). Translucent on API 30+, solid otherwise. */
@Composable
fun GlassSheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val (color, border) = glassColors(rememberGlassStyle())
    Surface(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(Tokens.radiusSheet), color = color, border = border) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

/** The bottom bar container: same glass rules, square bottom edge. */
@Composable
fun GlassBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val (color, border) = glassColors(rememberGlassStyle())
    Surface(modifier, color = color, border = border) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@LightDarkPreview
@Composable
private fun GlassPreview() = RustMfaTheme { GlassSheet { androidx.compose.material3.Text("Glass sheet") } }
