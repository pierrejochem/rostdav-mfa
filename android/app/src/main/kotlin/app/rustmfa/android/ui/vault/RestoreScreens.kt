package app.rustmfa.android.ui.vault

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.android.ui.design.BannerKind
import app.rustmfa.android.ui.design.BrandButton
import app.rustmfa.android.ui.design.ButtonKind
import app.rustmfa.android.ui.design.LightDarkPreview
import app.rustmfa.android.ui.design.StatusBanner
import app.rustmfa.android.vault.NO_MESSAGE
import app.rustmfa.android.vault.RestoreState
import app.rustmfa.android.vault.VaultViewModel
import app.rustmfa.android.vault.backupMessage

/**
 * The restore over a vault that cannot be opened, with the system file picker. The work runs in [vm] (activity
 * scoped), so a rotation or a trip to the Overview does not drop a running restore or the list kept behind
 * "Finish restoring". Only the picked file (no secret) survives a recreation; the passphrase never does.
 */
@Composable
fun RestoreScreen(vm: VaultViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val state by vm.restore.state.collectAsState()
    var source by rememberSaveable { mutableStateOf<Uri?>(null) }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) source = uri }
    RestoreContent(
        state = state,
        askPassphrase = source != null,
        onPick = { openLauncher.launch(BACKUP_TYPES) },
        onDecrypt = { passphrase ->
            val picked = source
            source = null
            if (picked != null) vm.restoreStart(picked, passphrase) else passphrase.fill(Char(0))
        },
        onCancelPassphrase = { source = null },
        onConfirm = vm::restoreConfirm,
        onFinish = vm::restoreFinish,
        onAbandon = vm::restoreAbandon,
        onDone = {
            vm.restoreAbandon()
            onDone()
        },
        onBack = onBack,
    )
}

/**
 * The restore screen without the picker. Pick a file, enter the passphrase ([onDecrypt] gets a fresh array the
 * receiver zeroes), then [RestoreState.Decrypted] asks "This replaces the unreadable vault": Replace calls
 * [onConfirm], Cancel drops the decrypted list ([onAbandon]) and resets nothing. [RestoreState.FinishPending] (and a
 * failure after the reset) offers "Finish restoring"; leaving then asks first, because the old vault is gone and the
 * kept list is the only copy. Leaving before the reset drops the list. While working, the buttons and Back are off.
 * The screen holds FLAG_SECURE itself.
 */
@Composable
fun RestoreContent(
    state: RestoreState,
    askPassphrase: Boolean,
    onPick: () -> Unit,
    onDecrypt: (passphrase: CharArray) -> Unit,
    onCancelPassphrase: () -> Unit,
    onConfirm: () -> Unit,
    onFinish: () -> Unit,
    onAbandon: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    SecureWhile(true)
    val working = state == RestoreState.Working
    val afterReset = state == RestoreState.FinishPending || (state as? RestoreState.Failed)?.canFinish == true
    var askAbandon by rememberSaveable { mutableStateOf(false) }
    val leave = {
        when {
            working -> Unit
            state is RestoreState.Done -> onDone()
            afterReset -> askAbandon = true
            else -> {
                onAbandon()
                onBack()
            }
        }
    }
    BackHandler(onBack = leave)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.vault_restore), style = MaterialTheme.typography.headlineMedium)
        when (state) {
            RestoreState.Idle, RestoreState.Decrypted -> Text(stringResource(R.string.restore_text), style = MaterialTheme.typography.bodyMedium)
            RestoreState.Working -> {
                val text = stringResource(R.string.restore_working)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(32.dp).testTag("restore-progress").semantics { contentDescription = text })
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            RestoreState.FinishPending -> StatusBanner(BannerKind.Warning, stringResource(R.string.restore_finish_text), modifier = Modifier.fillMaxWidth())
            is RestoreState.Done ->
                StatusBanner(BannerKind.Protected, stringResource(R.string.backup_restored, state.result.added, state.result.skipped), modifier = Modifier.fillMaxWidth())
            is RestoreState.Failed -> {
                val message = backupMessage(state.error)
                if (message != NO_MESSAGE) StatusBanner(BannerKind.Error, stringResource(message), modifier = Modifier.fillMaxWidth())
                if (state.canFinish) Text(stringResource(R.string.restore_finish_text), style = MaterialTheme.typography.bodyMedium)
            }
        }
        when {
            state is RestoreState.Done ->
                BrandButton(stringResource(R.string.restore_done_button), onClick = onDone, modifier = Modifier.fillMaxWidth())
            afterReset ->
                BrandButton(stringResource(R.string.restore_finish), onClick = onFinish, modifier = Modifier.fillMaxWidth())
            else ->
                BrandButton(stringResource(R.string.backup_restore_button), onClick = onPick, enabled = !working, modifier = Modifier.fillMaxWidth())
        }
        if (state !is RestoreState.Done) {
            BrandButton(stringResource(R.string.back), onClick = leave, enabled = !working, kind = ButtonKind.Outlined, modifier = Modifier.fillMaxWidth())
        }
    }

    if (askPassphrase && !working) RestorePassphraseDialog(onDecrypt, onCancelPassphrase)
    if (state == RestoreState.Decrypted) ConfirmReplaceDialog(onConfirm = onConfirm, onDismiss = onAbandon)
    if (askAbandon && afterReset) {
        AbandonDialog(
            onConfirm = {
                askAbandon = false
                onAbandon()
                onBack()
            },
            onDismiss = { askAbandon = false },
        )
    }
}

@Composable
private fun ConfirmReplaceDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_restore)) },
        text = { Text(stringResource(R.string.restore_confirm_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.restore_replace)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.vault_cancel)) }
        },
    )
}

@Composable
private fun AbandonDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_abandon_title)) },
        text = { Text(stringResource(R.string.restore_abandon_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.restore_abandon_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.vault_cancel)) }
        },
    )
}

/** "Start a new vault": asks first, because the reset deletes what is left of the unreadable vault. */
@Composable
fun NewVaultDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_new)) },
        text = { Text(stringResource(R.string.vault_new_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.vault_new_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.vault_cancel)) }
        },
    )
}

@LightDarkPreview
@Composable
private fun RestorePreview() = RustMfaTheme { RestoreContent(RestoreState.FinishPending, false, {}, {}, {}, {}, {}, {}, {}, {}) }
