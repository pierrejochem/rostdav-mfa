package app.rustmfa.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp

/** The soft orange-to-paper wash behind the splash-like screens (onboarding, reconnect). */
@Composable
fun Modifier.brandWash(): Modifier {
    val scheme = MaterialTheme.colorScheme
    return background(Brush.verticalGradient(listOf(lerp(scheme.background, scheme.secondary, 0.16f), scheme.background)))
}
