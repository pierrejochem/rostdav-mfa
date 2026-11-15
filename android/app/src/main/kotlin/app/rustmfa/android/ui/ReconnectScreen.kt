package app.rustmfa.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R

/** Shown at start when the approval key is gone: the server address is kept, the button starts a fresh enrollment. */
@Composable
fun ReconnectScreen(server: String, onReconnect: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.reconnect_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.result_key_invalidated), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.reconnect_server, server))
        Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.result_reconnect)) }
    }
}
