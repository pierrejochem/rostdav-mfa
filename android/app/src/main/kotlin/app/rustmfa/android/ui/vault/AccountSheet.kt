package app.rustmfa.android.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.vault.AccountRow
import app.rustmfa.android.vault.VaultUiState
import app.rustmfa.vault.OtpType

/** The long-press sheet of a row: Copy, Edit and Delete. Each runs its lambda and then [onDismiss]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(row: AccountRow, onCopy: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            SheetRow(stringResource(R.string.vault_action_copy)) { onCopy(); onDismiss() }
            SheetRow(stringResource(R.string.vault_action_edit)) { onEdit(); onDismiss() }
            SheetRow(stringResource(R.string.vault_action_delete)) { onDelete(); onDismiss() }
        }
    }
}

@Composable
private fun SheetRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Hosts the sheet and its two dialogs for the row [openId] names. A row that is gone (the vault locked) shows
 * nothing. Copy on a HOTP row whose code is not shown generates, like a tap.
 */
@Composable
fun AccountActions(
    state: VaultUiState,
    openId: String?,
    onClose: () -> Unit,
    onCopy: (String) -> Unit,
    onGenerate: (String) -> Unit,
    onUpdate: (id: String, issuer: String, label: String) -> Unit,
    onDelete: (String) -> Unit,
) {
    // ids, not rows: they survive a rotation, and the row is read from the live state every time
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = (state as? VaultUiState.Unlocked)?.rows.orEmpty()
    val selected = openId?.let { id -> rows.firstOrNull { it.id == id } }
    val editing = editingId?.let { id -> rows.firstOrNull { it.id == id } }
    val deleting = deletingId?.let { id -> rows.firstOrNull { it.id == id } }
    // a row that is gone (deleted elsewhere) or a vault that locked closes whatever is open for it
    LaunchedEffect(openId, selected == null) { if (openId != null && selected == null) onClose() }
    LaunchedEffect(editingId, editing == null) { if (editingId != null && editing == null) editingId = null }
    LaunchedEffect(deletingId, deleting == null) { if (deletingId != null && deleting == null) deletingId = null }
    if (selected != null) {
        AccountSheet(
            selected,
            onCopy = { if (selected.type == OtpType.HOTP && selected.code == null) onGenerate(selected.id) else onCopy(selected.id) },
            onEdit = { editingId = selected.id },
            onDelete = { deletingId = selected.id },
            onDismiss = onClose,
        )
    }
    if (editing != null) {
        EditAccountDialog(editing, { i, l -> onUpdate(editing.id, i, l); editingId = null }, { editingId = null })
    }
    if (deleting != null) {
        DeleteAccountDialog(deleting, { onDelete(deleting.id); deletingId = null }, { deletingId = null })
    }
}
