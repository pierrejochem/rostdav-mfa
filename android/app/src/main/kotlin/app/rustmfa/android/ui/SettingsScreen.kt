package app.rustmfa.android.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.ui.design.LightDarkPreview
import app.rustmfa.android.ui.design.ListCard
import app.rustmfa.android.ui.design.ListRow
import app.rustmfa.android.ui.design.brandWash
import app.rustmfa.android.ui.design.screenPadding
import java.text.DateFormat
import java.util.Date

/** The Backup row's subtitle: `Last backup: <date>` (local date, medium style) or `No backup yet`. */
fun backupSubtitle(context: Context, lastBackupAt: Long?): String =
    if (lastBackupAt == null) {
        context.getString(R.string.settings_backup_none)
    } else {
        context.getString(R.string.settings_backup_last, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(lastBackupAt)))
    }

@Composable
fun SettingsScreen(server: String, version: String, onOpenDevices: () -> Unit, onOpenBackup: () -> Unit, backupSubtitle: String) {
    Column(
        Modifier.fillMaxSize().brandWash().verticalScroll(rememberScrollState()).screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Eyebrow(stringResource(R.string.app_name))
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        }
        ListCard {
            ListRow(stringResource(R.string.settings_devices), subtitle = stringResource(R.string.settings_devices_hint), trailing = "›", onClick = onOpenDevices)
            ListRow(stringResource(R.string.settings_backup), subtitle = backupSubtitle, trailing = "›", onClick = onOpenBackup)
            ListRow(stringResource(R.string.settings_server), trailing = server)
            ListRow(stringResource(R.string.settings_version, version))
        }
    }
}

@LightDarkPreview
@Composable
private fun SettingsPreview() = RustMfaTheme { SettingsScreen("https://rostdav.example", "0.1.0", {}, {}, "No backup yet") }
