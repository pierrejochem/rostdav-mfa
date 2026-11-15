package app.rustmfa.android.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.ui.RustMfaTheme

enum class RingState { Normal, Urgent }

fun ringColor(secondsLeft: Int, total: Int): RingState = if (secondsLeft <= 5) RingState.Urgent else RingState.Normal

fun ringProgress(secondsLeft: Int, total: Int): Float = if (total <= 0) 0f else (secondsLeft.toFloat() / total).coerceIn(0f, 1f)

/** "482731" becomes "482 731", "48217316" becomes "4821 7316"; other lengths stay as they are. */
fun groupCode(code: String): String = if (code.length == 6 || code.length == 8) code.substring(0, code.length / 2) + " " + code.substring(code.length / 2) else code

/**
 * A one-time code in the mono face with a countdown ring around the seconds left. TalkBack reads
 * the time left, not the digits.
 */
@Composable
fun CodeRing(code: String, secondsLeft: Int, total: Int, modifier: Modifier = Modifier) {
    val urgent = ringColor(secondsLeft, total) == RingState.Urgent
    val color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val target = ringProgress(secondsLeft, total)
    val progress = if (animationsReduced()) target else animateFloatAsState(target, label = "ring").value
    val description = stringResource(R.string.code_ring_description, secondsLeft)
    Row(modifier.clearAndSetSemantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        Text(groupCode(code), style = CodeStyle, color = MaterialTheme.colorScheme.onSurface)
        Box(Modifier.padding(start = 12.dp).size(36.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(36.dp)) {
                val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                val inset = stroke.width / 2
                val arc = Size(size.width - stroke.width, size.height - stroke.width)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = stroke)
                drawArc(color, -90f, 360f * progress, false, Offset(inset, inset), arc, style = stroke)
            }
            Text(secondsLeft.toString(), style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

@LightDarkPreview
@Composable
private fun RingPreview() = RustMfaTheme { androidx.compose.foundation.layout.Column { CodeRing("482731", 22, 30); CodeRing("903214", 4, 30) } }
