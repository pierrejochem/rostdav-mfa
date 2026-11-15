package app.rustmfa.android.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.ui.RustMfaTheme
import app.rustmfa.android.ui.design.BrandButton
import app.rustmfa.android.ui.design.ButtonKind
import app.rustmfa.android.ui.design.LightDarkPreview
import app.rustmfa.android.ui.design.ListCard
import app.rustmfa.android.ui.design.ListRow
import app.rustmfa.android.vault.VaultEvent
import kotlinx.coroutines.flow.Flow

/** The three ways to add an account: scan, by hand, or a backup (the restore flow). */
@Composable
fun AddAccountScreen(onScan: () -> Unit, onManual: () -> Unit, onImport: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.vault_add), style = MaterialTheme.typography.headlineMedium)
        ListCard {
            ListRow(stringResource(R.string.scan_button), onClick = onScan, titleMaxLines = 3)
            ListRow(stringResource(R.string.manual_button), onClick = onManual, titleMaxLines = 3)
            ListRow(stringResource(R.string.vault_import_backup), onClick = onImport, titleMaxLines = 3)
        }
        BrandButton(stringResource(R.string.back), onClick = onBack, kind = ButtonKind.Outlined, modifier = Modifier.fillMaxWidth())
    }
}

/** Routes the add results to a form: [onAdded] leaves it, [onDuplicate] shows the message. Other events are not the form's. */
@Composable
fun AddEvents(events: Flow<VaultEvent>, onAdded: () -> Unit, onDuplicate: () -> Unit) {
    val added by rememberUpdatedState(onAdded)
    val duplicate by rememberUpdatedState(onDuplicate)
    LaunchedEffect(events) {
        events.collect {
            when (it) {
                VaultEvent.Added -> added()
                VaultEvent.Duplicate -> duplicate()
                else -> Unit
            }
        }
    }
}

@LightDarkPreview
@Composable
private fun AddAccountPreview() = RustMfaTheme { AddAccountScreen({}, {}, {}, {}) }
