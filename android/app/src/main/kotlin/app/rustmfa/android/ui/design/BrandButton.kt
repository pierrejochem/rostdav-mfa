package app.rustmfa.android.ui.design

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rustmfa.android.ui.RustMfaTheme

enum class ButtonKind { Filled, Tonal, Outlined, Destructive }

/** Minimum height 56 dp, radius 16. */
@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Filled,
    enabled: Boolean = true,
) {
    val m = modifier.heightIn(min = 56.dp)
    val shape = RoundedCornerShape(Tokens.radiusButton)
    val label: @Composable () -> Unit = { Text(text, style = MaterialTheme.typography.labelLarge) }
    when (kind) {
        ButtonKind.Filled -> Button(onClick, m, enabled, shape = shape) { label() }
        ButtonKind.Tonal -> FilledTonalButton(onClick, m, enabled, shape = shape) { label() }
        ButtonKind.Outlined -> OutlinedButton(onClick, m, enabled, shape = shape) { label() }
        ButtonKind.Destructive ->
            Button(
                onClick, m, enabled, shape = shape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { label() }
    }
}

@LightDarkPreview
@Composable
private fun ButtonsPreview() =
    RustMfaTheme {
        androidx.compose.foundation.layout.Column {
            for (k in ButtonKind.entries) BrandButton(k.name, {}, kind = k)
        }
    }
