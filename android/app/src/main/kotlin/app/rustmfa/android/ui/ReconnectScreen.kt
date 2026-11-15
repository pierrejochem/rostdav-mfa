package app.rustmfa.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rustmfa.android.R
import app.rustmfa.android.ui.design.BannerKind
import app.rustmfa.android.ui.design.BrandButton
import app.rustmfa.android.ui.design.BrandMark
import app.rustmfa.android.ui.design.StatusBanner
import app.rustmfa.android.ui.design.brandWash

/** Shown at start when the approval key is gone: the server address is kept, the button starts a fresh enrollment. */
@Composable
fun ReconnectScreen(server: String, onReconnect: () -> Unit) {
    Column(Modifier.fillMaxSize().brandWash().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BrandMark(size = 56.dp)
        Text(stringResource(R.string.reconnect_title), style = MaterialTheme.typography.headlineMedium)
        StatusBanner(BannerKind.Error, stringResource(R.string.result_key_invalidated))
        Text(stringResource(R.string.reconnect_server, server))
        BrandButton(stringResource(R.string.result_reconnect), onClick = onReconnect, modifier = Modifier.fillMaxWidth())
    }
}
