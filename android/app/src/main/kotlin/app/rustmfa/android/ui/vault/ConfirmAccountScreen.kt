package app.rustmfa.android.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.android.ui.design.BannerKind
import app.rustmfa.android.ui.design.BrandButton
import app.rustmfa.android.ui.design.ButtonKind
import app.rustmfa.android.ui.design.CodeStyle
import app.rustmfa.android.ui.design.LightDarkPreview
import app.rustmfa.android.ui.design.ListCard
import app.rustmfa.android.ui.design.ListRow
import app.rustmfa.android.ui.design.StatusBanner
import app.rustmfa.android.ui.design.groupCode
import app.rustmfa.vault.Otp
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpType

/** The code a scanned account shows now, computed without saving anything (a HOTP counter does not move). */
fun confirmCode(account: OtpAccount, epochSeconds: Long): String =
    if (account.type == OtpType.TOTP) {
        Otp.totp(account, epochSeconds)
    } else {
        Otp.hotp(account.secret, account.algorithm, account.digits, account.counter)
    }

/**
 * A scanned account before it is saved: the names can be changed, the settings are shown, and [firstCode] lets the
 * user compare with the service. Add is off while both names are blank. [message] is an error banner (a duplicate).
 */
@Composable
fun ConfirmAccountScreen(
    account: OtpAccount,
    firstCode: String,
    onAdd: (issuer: String, label: String) -> Unit,
    onBack: () -> Unit,
    message: String? = null,
) {
    // live codes of an account not yet saved: secure whatever the vault state
    SecureWhile(true)
    var issuer by rememberSaveable { mutableStateOf(account.issuer) }
    var label by rememberSaveable { mutableStateOf(account.label) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.vault_confirm_title), style = MaterialTheme.typography.headlineMedium)
        if (message != null) StatusBanner(BannerKind.Error, message)
        OutlinedTextField(issuer, { issuer = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.vault_field_service)) }, singleLine = true)
        OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.vault_field_account)) }, singleLine = true)
        ListCard {
            ListRow(stringResource(R.string.vault_field_type), trailing = account.type.name)
            ListRow(stringResource(R.string.vault_field_algorithm), trailing = account.algorithm.uriName)
            ListRow(stringResource(R.string.vault_field_digits), trailing = account.digits.toString())
            if (account.type == OtpType.TOTP) {
                ListRow(stringResource(R.string.vault_label_period), trailing = stringResource(R.string.vault_period_value, account.period))
            } else {
                ListRow(stringResource(R.string.vault_label_counter), trailing = account.counter.toString())
            }
        }
        Text(stringResource(R.string.vault_confirm_code_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(groupCode(firstCode), Modifier.testTag("confirm-code"), style = CodeStyle)
        BrandButton(
            stringResource(R.string.vault_add_button),
            onClick = { onAdd(issuer.trim(), label.trim()) },
            enabled = issuer.isNotBlank() || label.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        BrandButton(stringResource(R.string.back), onClick = onBack, kind = ButtonKind.Outlined, modifier = Modifier.fillMaxWidth())
    }
}

@LightDarkPreview
@Composable
private fun ConfirmPreview() =
    RustMfaTheme {
        ConfirmAccountScreen(OtpAccount("p", OtpType.TOTP, "GitHub", "alice", byteArrayOf(1)), "482731", { _, _ -> }, {})
    }
