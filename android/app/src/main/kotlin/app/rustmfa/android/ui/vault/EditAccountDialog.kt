package app.rustmfa.android.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.vault.AccountRow

/** Changes the service and the account name; Save is off while both are blank. */
@Composable
fun EditAccountDialog(row: AccountRow, onSave: (issuer: String, label: String) -> Unit, onDismiss: () -> Unit) {
    var issuer by remember { mutableStateOf(row.issuer) }
    var label by remember { mutableStateOf(row.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(issuer, { issuer = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.vault_field_service)) }, singleLine = true)
                OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.vault_field_account)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(issuer, label) }, enabled = issuer.isNotBlank() || label.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.vault_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.vault_cancel)) }
        },
    )
}

/** Asks before a delete; [row]'s issuer (or its label without one) names the service. */
@Composable
fun DeleteAccountDialog(row: AccountRow, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.vault_delete_text, row.issuer.ifEmpty { row.label })) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.vault_action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.vault_cancel)) }
        },
    )
}
