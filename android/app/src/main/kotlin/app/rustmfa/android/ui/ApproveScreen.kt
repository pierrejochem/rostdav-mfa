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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import app.rustmfa.android.ui.design.AppIconTile
import app.rustmfa.android.ui.design.BannerKind
import app.rustmfa.android.ui.design.BrandButton
import app.rustmfa.android.ui.design.ButtonKind
import app.rustmfa.android.ui.design.GlassSheet
import app.rustmfa.android.ui.design.ListCard
import app.rustmfa.android.ui.design.ListRow
import app.rustmfa.android.ui.design.NumberChoice
import app.rustmfa.android.ui.design.StatusBanner
import app.rustmfa.android.ui.design.brandWash
import app.rustmfa.android.ui.design.isUrgent

/** The screen with the window flag that blocks screenshots and the recents preview. */
@Composable
/** [revoked] shows the removed-phone result whatever the request state is. [onReconnect] gets `true` when the approval key is invalidated (the server address is kept) and `false` otherwise. */
fun ApproveScreen(vm: ApproveViewModel, revoked: Boolean = false, onClose: () -> Unit, onReconnect: (keyInvalidated: Boolean) -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val state by vm.state.collectAsState()
    val auth: Authenticator = { signer, msg -> authenticate(activity as FragmentActivity, signer, msg) }
    ApproveContent(
        state = if (revoked) ChallengeState.DeviceRevoked else state,
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
    Column(
        Modifier.fillMaxSize().brandWash().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        GlassSheet(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (state) {
                    ChallengeState.Loading, is ChallengeState.Signing, ChallengeState.Sending -> Working(R.string.approve_working)
                    is ChallengeState.Pending -> PendingBody(state, onPick, onDeny)
                    is ChallengeState.Offline -> Working(R.string.result_offline)
                    ChallengeState.KeyInvalidated -> {
                        StatusBanner(BannerKind.Error, stringResource(R.string.result_key_invalidated))
                        BrandButton(stringResource(R.string.result_reconnect), onClick = onReconnect, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        StatusBanner(resultKind(state), stringResource(resultText(state)))
                        BrandButton(stringResource(R.string.result_done), onClick = onClose, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun Working(message: Int) {
    CircularProgressIndicator()
    Text(stringResource(message))
}

@Composable
private fun PendingBody(state: ChallengeState.Pending, onPick: (Int) -> Unit, onDeny: () -> Unit) {
    val d = state.details
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppIconTile(d.serviceName)
        Text(
            stringResource(R.string.approve_from, d.serviceName),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    ListCard {
        ListRow(stringResource(R.string.approve_device, d.loginDevice))
        d.approximateLocation?.let { ListRow(stringResource(R.string.approve_location, it)) }
    }
    Text(
        stringResource(R.string.approve_left, state.secondsLeft),
        style = MaterialTheme.typography.titleMedium,
        color = if (isUrgent(state.secondsLeft)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    Text(stringResource(R.string.approve_pick))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        for (n in d.candidateNumbers) NumberChoice(n, onClick = { onPick(n) }, modifier = Modifier.weight(1f))
    }
    BrandButton(stringResource(R.string.approve_deny), onClick = onDeny, kind = ButtonKind.Outlined, modifier = Modifier.fillMaxWidth())
}

private fun resultKind(s: ChallengeState): BannerKind =
    when (s) {
        ChallengeState.Approved -> BannerKind.Protected
        ChallengeState.Denied, ChallengeState.Expired, ChallengeState.AlreadyHandled -> BannerKind.Warning
        else -> BannerKind.Error
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
