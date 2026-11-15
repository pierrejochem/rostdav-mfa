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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.rustmfa.android.R
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.android.ui.design.BannerKind
import app.rustmfa.android.ui.design.BrandButton
import app.rustmfa.android.ui.design.ButtonKind
import app.rustmfa.android.ui.design.LightDarkPreview
import app.rustmfa.android.ui.design.StatusBanner
import app.rustmfa.android.vault.BackupState
import app.rustmfa.android.vault.BackupViewModel
import app.rustmfa.android.vault.PassphraseProblem
import app.rustmfa.android.vault.backupFileName
import app.rustmfa.android.vault.passphraseProblem

private val BACKUP_TYPES = arrayOf("application/json", "*/*")

/**
 * The Backup screen with the system file pickers: Create opens `CreateDocument` with [backupFileName], Restore
 * opens `OpenDocument` and then asks for the passphrase. [startRestore] opens the restore picker once on arrival
 * (from "Import a backup" on the Add screen).
 */
@Composable
fun BackupScreen(vm: BackupViewModel, onBack: () -> Unit, startRestore: Boolean = false) {
    val state by vm.state.collectAsState()
    // the typed passphrases, held only while the save picker is open; never saved state
    val pending = remember { arrayOfNulls<CharArray>(2) }
    fun dropPending() {
        pending.forEach { it?.fill(Char(0)) }
        pending.fill(null)
    }
    DisposableEffect(Unit) { onDispose { dropPending() } }
    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val passphrase = pending[0]
        val repeat = pending[1]
        pending.fill(null)
        if (uri != null && passphrase != null && repeat != null) {
            vm.create(passphrase, repeat, uri)
        } else {
            passphrase?.fill(Char(0))
            repeat?.fill(Char(0))
        }
    }
    // the picked file is no secret: it may survive a rotation while the passphrase dialog is open
    var source by rememberSaveable { mutableStateOf<Uri?>(null) }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) source = uri }
    var startedRestore by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (startRestore && !startedRestore) {
            startedRestore = true
            openLauncher.launch(BACKUP_TYPES)
        }
    }
    BackupContent(
        state = state,
        askRestorePassphrase = source != null,
        onCreate = { passphrase, repeat ->
            dropPending()
            pending[0] = passphrase
            pending[1] = repeat
            createLauncher.launch(backupFileName(System.currentTimeMillis()))
        },
        onPickSource = { openLauncher.launch(BACKUP_TYPES) },
        onRestore = { passphrase ->
            val picked = source
            source = null
            if (picked != null) vm.restore(picked, passphrase) else passphrase.fill(Char(0))
        },
        onCancelRestore = { source = null },
        onBack = onBack,
    )
}

/**
 * The Backup screen without the pickers. Create is on only while both passphrases are valid and equal and nothing
 * is running; [onCreate] gets fresh arrays the receiver zeroes. While [BackupState.Working] a progress indicator
 * shows and the buttons and Back are off. The passphrases live in plain `remember` (gone after a recreation) and are
 * cleared once the backup is saved. The screen holds FLAG_SECURE itself.
 */
@Composable
fun BackupContent(
    state: BackupState,
    askRestorePassphrase: Boolean,
    onCreate: (passphrase: CharArray, repeat: CharArray) -> Unit,
    onPickSource: () -> Unit,
    onRestore: (passphrase: CharArray) -> Unit,
    onCancelRestore: () -> Unit,
    onBack: () -> Unit,
) {
    SecureWhile(true)
    var passphrase by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { show = false }
    LaunchedEffect(state) {
        if (state == BackupState.Saved) {
            passphrase = ""
            repeat = ""
        }
    }
    val working = state == BackupState.Working
    // leaving while the key is derived would drop the result half way
    BackHandler(enabled = working) {}
    val problem = run {
        val p = passphrase.toCharArray()
        val r = repeat.toCharArray()
        passphraseProblem(p, r).also {
            p.fill(Char(0))
            r.fill(Char(0))
        }
    }
    val noAutofill = Modifier.fillMaxWidth().semantics { contentDataType = ContentDataType.None }
    val transformation = if (show) VisualTransformation.None else PasswordVisualTransformation()
    val keyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineMedium)
        StatusBanner(BannerKind.Warning, stringResource(R.string.backup_note), modifier = Modifier.fillMaxWidth())
        Status(state)

        Text(stringResource(R.string.backup_create_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        Text(stringResource(R.string.backup_create_text), style = MaterialTheme.typography.bodyMedium)
        val shortError = problem == PassphraseProblem.TooShort && passphrase.isNotEmpty()
        OutlinedTextField(
            passphrase,
            { passphrase = it },
            noAutofill,
            enabled = !working,
            label = { Text(stringResource(R.string.backup_passphrase)) },
            singleLine = true,
            visualTransformation = transformation,
            keyboardOptions = keyboard,
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(
                        painterResource(if (show) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        contentDescription = stringResource(if (show) R.string.backup_passphrase_hide else R.string.backup_passphrase_show),
                    )
                }
            },
            isError = shortError,
            supportingText = if (shortError) ({ Text(stringResource(R.string.backup_error_short)) }) else null,
        )
        val mismatchError = problem == PassphraseProblem.Mismatch && repeat.isNotEmpty()
        OutlinedTextField(
            repeat,
            { repeat = it },
            noAutofill,
            enabled = !working,
            label = { Text(stringResource(R.string.backup_passphrase_repeat)) },
            singleLine = true,
            visualTransformation = transformation,
            keyboardOptions = keyboard,
            isError = mismatchError,
            supportingText = if (mismatchError) ({ Text(stringResource(R.string.backup_error_mismatch)) }) else null,
        )
        BrandButton(
            stringResource(R.string.backup_create_button),
            onClick = { onCreate(passphrase.toCharArray(), repeat.toCharArray()) },
            enabled = problem == null && !working,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(stringResource(R.string.vault_restore), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        Text(stringResource(R.string.backup_restore_text), style = MaterialTheme.typography.bodyMedium)
        BrandButton(
            stringResource(R.string.backup_restore_button),
            onClick = onPickSource,
            enabled = !working,
            kind = ButtonKind.Tonal,
            modifier = Modifier.fillMaxWidth(),
        )
        BrandButton(stringResource(R.string.back), onClick = onBack, enabled = !working, kind = ButtonKind.Outlined, modifier = Modifier.fillMaxWidth())
    }

    if (askRestorePassphrase) RestorePassphraseDialog(onRestore, onCancelRestore)
}

@Composable
private fun Status(state: BackupState) {
    when (state) {
        BackupState.Idle -> Unit
        BackupState.Working -> {
            val working = stringResource(R.string.backup_working)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(32.dp).testTag("backup-progress").semantics { contentDescription = working })
                Text(working, style = MaterialTheme.typography.bodyMedium)
            }
        }
        BackupState.Saved -> StatusBanner(BannerKind.Protected, stringResource(R.string.backup_saved), modifier = Modifier.fillMaxWidth())
        is BackupState.Restored ->
            StatusBanner(BannerKind.Protected, stringResource(R.string.backup_restored, state.result.added, state.result.skipped), modifier = Modifier.fillMaxWidth())
        is BackupState.Failed -> StatusBanner(BannerKind.Error, stringResource(state.messageRes), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RestorePassphraseDialog(onRestore: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_dialog_title)) },
        text = {
            OutlinedTextField(
                passphrase,
                { passphrase = it },
                Modifier.fillMaxWidth().testTag("restore-passphrase").semantics { contentDataType = ContentDataType.None },
                label = { Text(stringResource(R.string.backup_restore_passphrase)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chars = passphrase.toCharArray()
                    passphrase = ""
                    onRestore(chars)
                },
                enabled = passphrase.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.backup_restore_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.vault_cancel)) }
        },
    )
}

@LightDarkPreview
@Composable
private fun BackupPreview() = RustMfaTheme { BackupContent(BackupState.Idle, false, { _, _ -> }, {}, {}, {}, {}) }
