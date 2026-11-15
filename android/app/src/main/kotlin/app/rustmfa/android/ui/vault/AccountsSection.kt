package app.rustmfa.android.ui.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.ui.DashedCard
import app.rustmfa.android.ui.Eyebrow
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.android.ui.design.AppIconTile
import app.rustmfa.android.ui.design.BannerKind
import app.rustmfa.android.ui.design.BrandButton
import app.rustmfa.android.ui.design.ButtonKind
import app.rustmfa.android.ui.design.CodeStyle
import app.rustmfa.android.ui.design.LightDarkPreview
import app.rustmfa.android.ui.design.ListCard
import app.rustmfa.android.ui.design.StatusBanner
import app.rustmfa.android.ui.design.Tokens
import app.rustmfa.android.ui.design.ringProgress
import app.rustmfa.android.vault.AccountRow
import app.rustmfa.android.vault.VaultUiState
import app.rustmfa.vault.OtpType

/** The code in a row: the mono face one step smaller than the headline, so it fits next to the names at 200% font. */
private val RowCodeStyle = CodeStyle.copy(fontSize = Tokens.sizeTitle)

/**
 * The Accounts section of the Overview: the header ("Lock now" while open, `+`), the backup nudge, and one card per
 * state. [VaultUiState.Loading] shows nothing.
 */
@Composable
fun AccountsSection(
    state: VaultUiState,
    showNudge: Boolean,
    onUnlock: () -> Unit,
    onLock: () -> Unit,
    onAdd: () -> Unit,
    onCopy: (String) -> Unit,
    onGenerate: (String) -> Unit,
    onOpenMenu: (String) -> Unit,
    onBackup: () -> Unit,
    onDismissNudge: () -> Unit,
    onRestore: () -> Unit,
    onNewVault: () -> Unit,
) {
    if (state == VaultUiState.Loading) return
    val open = state is VaultUiState.Unlocked || state == VaultUiState.Empty
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Eyebrow(stringResource(R.string.home_accounts)) }
            if (open) {
                TextButton(onClick = onLock) {
                    Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.vault_lock_now))
                }
            }
            if (state != VaultUiState.NeedsReset) {
                IconButton(onClick = onAdd, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.vault_add))
                }
            }
        }
        if (showNudge) Nudge(onBackup, onDismissNudge)
        when (state) {
            VaultUiState.Loading -> Unit
            VaultUiState.NoVault ->
                DashedCard {
                    Text(stringResource(R.string.home_vault_title), style = MaterialTheme.typography.titleSmall)
                    BrandButton(stringResource(R.string.vault_add_first), onAdd, Modifier.fillMaxWidth().padding(top = 12.dp), ButtonKind.Tonal)
                }
            VaultUiState.Locked ->
                StateCard {
                    Text(stringResource(R.string.vault_locked), style = MaterialTheme.typography.titleSmall)
                    BrandButton(stringResource(R.string.vault_unlock), onUnlock, Modifier.fillMaxWidth())
                }
            VaultUiState.Empty ->
                StateCard {
                    Text(stringResource(R.string.vault_empty), style = MaterialTheme.typography.titleSmall)
                    BrandButton(stringResource(R.string.vault_add), onAdd, Modifier.fillMaxWidth(), ButtonKind.Tonal)
                }
            VaultUiState.NeedsReset ->
                StateCard {
                    Text(stringResource(R.string.vault_needs_reset), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    BrandButton(stringResource(R.string.vault_restore), onRestore, Modifier.fillMaxWidth())
                    BrandButton(stringResource(R.string.vault_new), onNewVault, Modifier.fillMaxWidth(), ButtonKind.Outlined)
                }
            is VaultUiState.Unlocked ->
                ListCard {
                    for (row in state.rows) AccountRowItem(row, onCopy, onGenerate, onOpenMenu)
                }
        }
    }
}

@Composable
private fun StateCard(content: @Composable ColumnScope.() -> Unit) {
    ListCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun Nudge(onBackup: () -> Unit, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusBanner(BannerKind.Warning, stringResource(R.string.vault_nudge_title), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandButton(stringResource(R.string.vault_nudge_backup), onBackup, Modifier.weight(1f, fill = false), ButtonKind.Tonal)
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.vault_nudge_later)) }
        }
    }
}

/**
 * Tap copies (or generates a HOTP code that is not shown yet), long-press opens the menu. TalkBack reads the names
 * and the seconds left, never the digits, and gets Copy, Edit and Delete as custom actions.
 */
@Composable
private fun AccountRowItem(row: AccountRow, onCopy: (String) -> Unit, onGenerate: (String) -> Unit, onOpenMenu: (String) -> Unit) {
    val generate = row.type == OtpType.HOTP && row.code == null
    val title = row.issuer.ifEmpty { row.label }
    val subtitle = row.label.takeIf { row.issuer.isNotEmpty() && it.isNotEmpty() }
    val names = listOfNotNull(title, subtitle).joinToString(", ")
    val description =
        if (row.type == OtpType.TOTP) pluralStringResource(R.plurals.vault_row_description, row.secondsLeft, names, row.secondsLeft) else names
    val copy = stringResource(R.string.vault_action_copy)
    val edit = stringResource(R.string.vault_action_edit)
    val delete = stringResource(R.string.vault_action_delete)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("account-row-${row.id}")
            .combinedClickable(
                onClick = { if (generate) onGenerate(row.id) else onCopy(row.id) },
                onLongClick = { onOpenMenu(row.id) },
            )
            .semantics {
                contentDescription = description
                customActions =
                    listOf(
                        CustomAccessibilityAction(copy) { onCopy(row.id); true },
                        CustomAccessibilityAction(edit) { onOpenMenu(row.id); true },
                        CustomAccessibilityAction(delete) { onOpenMenu(row.id); true },
                    )
            }
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconTile(title)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val code = row.code
        if (code == null) {
            Text(
                stringResource(R.string.vault_tap_to_generate),
                Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
            )
        } else {
            val color = if (row.urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            Text(
                code,
                Modifier.padding(start = 12.dp).then(if (row.urgent) Modifier.testTag("account-urgent-${row.id}") else Modifier),
                style = RowCodeStyle,
                color = color,
                maxLines = 1,
                softWrap = false,
            )
            if (row.type == OtpType.TOTP) {
                Ring(row.secondsLeft, row.period, row.urgent, Modifier.padding(start = 8.dp).testTag("account-ring-${row.id}"))
            } else {
                IconButton(onClick = { onGenerate(row.id) }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.vault_hotp_next))
                }
            }
        }
    }
}

/** A small countdown ring around the seconds left; the danger colour in the last five seconds. */
@Composable
private fun Ring(secondsLeft: Int, period: Int, urgent: Boolean, modifier: Modifier = Modifier) {
    val color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val progress = ringProgress(secondsLeft, period)
    Box(modifier.size(36.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(36.dp)) {
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arc = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = stroke)
            drawArc(color, -90f, 360f * progress, false, Offset(inset, inset), arc, style = stroke)
        }
        Text(secondsLeft.toString(), style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, softWrap = false)
    }
}

@LightDarkPreview
@Composable
private fun AccountsPreview() =
    RustMfaTheme {
        AccountsSection(
            VaultUiState.Unlocked(
                listOf(
                    AccountRow("1", "GitHub", "pierrejochem", OtpType.TOTP, "482 731", 22, 30, false),
                    AccountRow("2", "Mail", "me@example.org", OtpType.TOTP, "903 214", 4, 30, true),
                    AccountRow("3", "Counter", "bob", OtpType.HOTP, null, 0, 30, false),
                ),
            ),
            showNudge = true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
