package app.rustmfa.android.ui.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.ui.RustMfaTheme

/** The large number-match button: mono digits, at least 72 dp high, named "Number N" for TalkBack. */
@Composable
fun NumberChoice(number: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.number_choice_description, number)
    FilledTonalButton(
        onClick,
        modifier.heightIn(min = 72.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(Tokens.radiusButton),
        contentPadding = PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Text(number.toString(), style = CodeStyle, maxLines = 1, softWrap = false)
    }
}

@LightDarkPreview
@Composable
private fun NumberPreview() = RustMfaTheme { NumberChoice(42, {}) }
