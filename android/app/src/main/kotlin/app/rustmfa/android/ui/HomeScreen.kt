package app.rustmfa.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R

/** Task 6 puts the list of open requests here. */
@Composable
fun HomeScreen(server: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_server, server))
        Text(stringResource(R.string.home_empty))
    }
}
