package app.rustmfa.android.ui.vault

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.rustmfa.android.R
import app.rustmfa.android.ui.ScanScreen
import app.rustmfa.android.vault.otpAuthMessage
import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpAuthException
import app.rustmfa.vault.OtpAuthUri

/** How long the same rejected string stays quiet, so a QR code in view is not reported on every frame. */
const val SCAN_REPEAT_MILLIS = 3_000L

sealed interface ScanOutcome {
    data class Account(val account: OtpAccount) : ScanOutcome

    data class Rejected(@StringRes val messageRes: Int) : ScanOutcome

    data object Ignored : ScanOutcome
}

/** What one scanned [text] means; [lastRejected] at [lastRejectedAt] is ignored again until [SCAN_REPEAT_MILLIS] passed. */
fun scanOutcome(text: String, lastRejected: String?, lastRejectedAt: Long, now: Long): ScanOutcome {
    if (text == lastRejected && now - lastRejectedAt < SCAN_REPEAT_MILLIS) return ScanOutcome.Ignored
    return try {
        ScanOutcome.Account(OtpAuthUri.parse(text))
    } catch (e: OtpAuthException) {
        ScanOutcome.Rejected(otpAuthMessage(e.reason))
    }
}

/** The scanner for an authenticator code: an invalid code shows a banner and scanning goes on; a valid one is handed over once. */
@Composable
fun VaultScanScreen(onAccount: (OtpAccount) -> Unit, onManual: () -> Unit, onBack: () -> Unit) {
    var lastRejected by remember { mutableStateOf<String?>(null) }
    var lastRejectedAt by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<Int?>(null) }
    var delivered by remember { mutableStateOf(false) }
    ScanScreen(
        onText = { text ->
            if (!delivered) {
                val now = SystemClock.elapsedRealtime()
                when (val out = scanOutcome(text, lastRejected, lastRejectedAt, now)) {
                    is ScanOutcome.Account -> {
                        delivered = true
                        onAccount(out.account)
                    }
                    is ScanOutcome.Rejected -> {
                        lastRejected = text
                        lastRejectedAt = now
                        message = out.messageRes
                    }
                    ScanOutcome.Ignored -> Unit
                }
            }
        },
        onManual = onManual,
        onBack = onBack,
        message = message?.let { stringResource(it) },
        continuous = true,
        deniedText = R.string.vault_scan_denied,
    )
}
