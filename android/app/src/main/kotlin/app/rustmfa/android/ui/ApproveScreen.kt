package app.rustmfa.android.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.rustmfa.android.R
import app.rustmfa.android.domain.ChallengeState

/** The screen with the window flag that blocks screenshots and the recents preview. */
@Composable
/** [onReconnect] gets `true` when the approval key is invalidated (the server address is kept) and `false` otherwise. */
fun ApproveScreen(vm: ApproveViewModel, onClose: () -> Unit, onReconnect: (keyInvalidated: Boolean) -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val state by vm.state.collectAsState()
    val auth: Authenticator = { signer, msg -> authenticate(activity as FragmentActivity, signer, msg) }
    ApproveContent(
        state = state,
        onPick = { vm.approve(it, auth) },
        onDeny = { vm.deny(auth) },
        onClose = onClose,
        onReconnect = { onReconnect(state == ChallengeState.KeyInvalidated) },
    )
}

/** Stateless, so the tests can show every state. */
@Composable
fun ApproveContent(
    state: ChallengeState,
    onPick: (Int) -> Unit,
    onDeny: () -> Unit,
    onClose: () -> Unit,
    onReconnect: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (state) {
            ChallengeState.Loading, is ChallengeState.Signing, ChallengeState.Sending -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.approve_working))
            }
            is ChallengeState.Pending -> {
                val d = state.details
                Text(stringResource(R.string.approve_from, d.serviceName), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.approve_device, d.loginDevice))
                d.approximateLocation?.let { Text(stringResource(R.string.approve_location, it)) }
                Text(stringResource(R.string.approve_left, state.secondsLeft), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.approve_pick))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    for (n in d.candidateNumbers) {
                        Button(onClick = { onPick(n) }, modifier = Modifier.weight(1f).heightIn(min = 72.dp)) {
                            Text(n.toString(), style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
                OutlinedButton(onClick = onDeny, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.approve_deny)) }
            }
            is ChallengeState.Offline -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.result_offline))
            }
            ChallengeState.KeyInvalidated -> {
                Text(stringResource(R.string.result_key_invalidated), color = MaterialTheme.colorScheme.error)
                Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.result_reconnect)) }
            }
            else -> {
                Text(stringResource(resultText(state)), style = MaterialTheme.typography.headlineMedium)
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.result_done)) }
            }
        }
    }
}

private fun resultText(s: ChallengeState): Int =
    when (s) {
        ChallengeState.Approved -> R.string.result_approved
        ChallengeState.Denied -> R.string.result_denied
        ChallengeState.NumberMismatch -> R.string.result_mismatch
        ChallengeState.Expired -> R.string.result_expired
        ChallengeState.AlreadyHandled -> R.string.result_handled
        ChallengeState.DeviceRevoked -> R.string.result_revoked
        else -> R.string.result_failed
    }
