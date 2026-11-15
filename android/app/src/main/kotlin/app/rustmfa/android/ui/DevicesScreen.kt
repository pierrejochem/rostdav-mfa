package app.rustmfa.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.net.DeviceStatus

@StringRes
private fun auditText(kind: String): Int =
    when (kind) {
        "device_registered" -> R.string.audit_device_registered
        "device_revoked" -> R.string.audit_device_revoked
        "key_rotated" -> R.string.audit_key_rotated
        "challenge_created" -> R.string.audit_challenge_created
        "challenge_approved" -> R.string.audit_challenge_approved
        "challenge_denied" -> R.string.audit_challenge_denied
        "challenge_expired" -> R.string.audit_challenge_expired
        else -> R.string.audit_number_mismatch
    }

/** The account's phones and the last events. [onThisPhoneRevoked] runs once after this phone removed itself. */
@Composable
fun DevicesScreen(vm: DevicesViewModel, onThisPhoneRevoked: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(s.thisPhoneRevoked) { if (s.thisPhoneRevoked) onThisPhoneRevoked() }
    s.confirming?.let { id ->
        val row = s.devices.firstOrNull { it.device.deviceId == id }
        AlertDialog(
            onDismissRequest = vm::cancelRevoke,
            title = { Text(stringResource(R.string.devices_revoke_title)) },
            text = {
                Text(
                    if (row?.isThisPhone == true) stringResource(R.string.devices_revoke_this)
                    else stringResource(R.string.devices_revoke_other, row?.device?.deviceName.orEmpty()),
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmRevoke) { Text(stringResource(R.string.devices_confirm)) } },
            dismissButton = { TextButton(onClick = vm::cancelRevoke) { Text(stringResource(R.string.devices_cancel)) } },
        )
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.headlineMedium)
        s.failure?.let { Text(stringResource(deviceFailureMessage(it)), color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.devices, key = { it.device.deviceId }) { row ->
                val d = row.device
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.deviceName, style = MaterialTheme.typography.titleMedium)
                            if (row.isThisPhone) Text(stringResource(R.string.devices_this))
                            Text(d.lastSeenAt?.let { stringResource(R.string.devices_last_seen, it) } ?: stringResource(R.string.devices_never_seen))
                            if (d.strongbox) Text(stringResource(R.string.devices_strongbox))
                            if (d.status == DeviceStatus.Revoked) Text(stringResource(R.string.devices_revoked), color = MaterialTheme.colorScheme.error)
                        }
                        if (d.status != DeviceStatus.Revoked) {
                            OutlinedButton(onClick = { vm.askRevoke(d.deviceId) }) { Text(stringResource(R.string.devices_revoke)) }
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.devices_audit), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
            if (s.audit.isEmpty()) item { Text(stringResource(R.string.devices_audit_empty)) }
            items(s.audit) { e -> Text("${e.at}  ${stringResource(auditText(e.event))}") }
        }
    }
}
