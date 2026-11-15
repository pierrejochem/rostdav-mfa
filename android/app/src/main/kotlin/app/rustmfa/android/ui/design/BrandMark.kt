package app.rustmfa.android.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rustmfa.android.R
import app.rustmfa.android.ui.RustMfaTheme

/** The Rostdav folder with a lock on its dark squircle. Decorative unless the caller adds a description. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = modifier.size(size))
}

/** `rost` and `-mfa` in ink, `dav` in the accent (Rostdav's own split), as text so it scales with the font size. */
@Composable
fun BrandWordmark(modifier: Modifier = Modifier, size: TextUnit = 28.sp) {
    val name = stringResource(R.string.brand_name)
    val scheme = MaterialTheme.colorScheme
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = scheme.onSurface)) { append("rost") }
            withStyle(SpanStyle(color = scheme.secondary)) { append("dav") }
            withStyle(SpanStyle(color = scheme.onSurface)) { append("-mfa") }
        },
        modifier.semantics { contentDescription = name },
        style = MaterialTheme.typography.headlineMedium.copy(fontSize = size, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    )
}

@LightDarkPreview
@Composable
private fun BrandPreview() = RustMfaTheme { androidx.compose.foundation.layout.Row { BrandMark(); BrandWordmark() } }
