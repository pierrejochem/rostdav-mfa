package app.rustmfa.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.ui.design.AppIconTile
import app.rustmfa.android.ui.design.BannerKind
import app.rustmfa.android.ui.design.EyebrowStyle
import app.rustmfa.android.ui.design.LightDarkPreview
import app.rustmfa.android.ui.design.ListCard
import app.rustmfa.android.ui.design.ListRow
import app.rustmfa.android.ui.design.StatusBanner
import app.rustmfa.android.ui.design.Tokens
import app.rustmfa.android.ui.design.brandWash
import app.rustmfa.android.ui.design.screenPadding

private const val OVERVIEW_ROWS = 3

/** The overview: greeting, the protected banner, the newest requests and the slot for the vault. */
@Composable
fun HomeScreen(
    server: String,
    pending: List<ChallengeDetails>,
    notificationsOff: Boolean,
    onOpen: (String) -> Unit,
    onSeeAll: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().brandWash().verticalScroll(rememberScrollState()).screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Eyebrow(stringResource(R.string.app_name))
            Text(stringResource(R.string.home_greeting), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.home_server, server), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusBanner(BannerKind.Protected, stringResource(R.string.home_protected_title), stringResource(R.string.home_protected_body))
        if (notificationsOff) StatusBanner(BannerKind.Warning, stringResource(R.string.home_notifications_off))
        Eyebrow(stringResource(R.string.home_title))
        if (pending.isEmpty()) {
            Text(stringResource(R.string.home_empty))
        } else {
            Text(pluralStringResource(R.plurals.home_pending_count, pending.size, pending.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
            RequestRows(pending.take(OVERVIEW_ROWS), onOpen) {
                if (pending.size > OVERVIEW_ROWS) ListRow(stringResource(R.string.home_see_all), trailing = pending.size.toString(), onClick = onSeeAll)
            }
        }
        Eyebrow(stringResource(R.string.home_accounts))
        DashedCard {
            Text(stringResource(R.string.home_vault_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.home_vault_planned), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The full list of open requests. */
@Composable
fun RequestsScreen(pending: List<ChallengeDetails>, onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().brandWash().verticalScroll(rememberScrollState()).screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Eyebrow(stringResource(R.string.app_name))
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
        }
        if (pending.isEmpty()) Text(stringResource(R.string.home_empty)) else RequestRows(pending, onOpen) {}
    }
}

@Composable
private fun RequestRows(list: List<ChallengeDetails>, onOpen: (String) -> Unit, extra: @Composable ColumnScope.() -> Unit) {
    ListCard {
        for (c in list) ListRow(c.serviceName, subtitle = c.loginDevice, leading = { AppIconTile(c.serviceName) }, onClick = { onOpen(c.challengeId) })
        extra()
    }
}

/** A small uppercase mono label above a section, as in the Rostdav web UI. */
@Composable
fun Eyebrow(text: String) {
    Text(text.uppercase(), style = EyebrowStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DashedCard(content: @Composable ColumnScope.() -> Unit) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Column(
        Modifier.fillMaxWidth().drawBehind {
            drawRoundRect(
                line,
                cornerRadius = CornerRadius(Tokens.radiusCard.toPx()),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
            )
        }.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@LightDarkPreview
@Composable
private fun HomePreview() = RustMfaTheme { HomeScreen("https://rostdav.example", emptyList(), false, {}, {}) }
