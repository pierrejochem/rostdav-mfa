package app.rustmfa.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.ui.RustMfaTheme

enum class BannerKind { Protected, Warning, Error }

/** The green "protected" strip and its warning and error variants. */
@Composable
fun StatusBanner(kind: BannerKind, title: String, body: String? = null, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val accent =
        when (kind) {
            BannerKind.Protected -> if (androidx.compose.foundation.isSystemInDarkTheme()) Tokens.Dark.ok else Tokens.Light.ok
            BannerKind.Warning -> scheme.primary
            BannerKind.Error -> scheme.error
        }
    val icon = when (kind) { BannerKind.Protected -> R.drawable.ic_check; BannerKind.Warning -> R.drawable.ic_info; BannerKind.Error -> R.drawable.ic_warning }
    Row(
        modifier.clip(RoundedCornerShape(Tokens.radiusCard)).background(lerp(scheme.surface, accent, 0.12f)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = accent)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
        }
    }
}

@LightDarkPreview
@Composable
private fun BannersPreview() =
    RustMfaTheme {
        Column {
            StatusBanner(BannerKind.Protected, "Protected", "Approvals need your fingerprint")
            StatusBanner(BannerKind.Warning, "Notifications are off")
            StatusBanner(BannerKind.Error, "Could not connect", "Check the address")
        }
    }
