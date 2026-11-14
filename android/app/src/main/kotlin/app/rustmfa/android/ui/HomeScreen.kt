package app.rustmfa.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.net.ChallengeDetails

/** The open sign-in requests. [notificationsOff] adds a hint: then polling is the only wake-up. */
@Composable
fun HomeScreen(
    server: String,
    pending: List<ChallengeDetails>,
    notificationsOff: Boolean,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_server, server))
        if (notificationsOff) Text(stringResource(R.string.home_notifications_off), color = MaterialTheme.colorScheme.error)
        if (pending.isEmpty()) {
            Text(stringResource(R.string.home_empty))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pending, key = { it.challengeId }) { c ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(c.challengeId) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(c.serviceName, style = MaterialTheme.typography.titleMedium)
                            Text(c.loginDevice)
                        }
                    }
                }
            }
        }
    }
}
