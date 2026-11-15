package app.rustmfa.android.push

import app.rustmfa.android.domain.Delayer
import app.rustmfa.android.net.ApiError
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.net.ChallengeStatus
import app.rustmfa.android.net.Clock
import app.rustmfa.android.net.ErrorCode
import app.rustmfa.android.net.MfaApi
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Lists the open sign-in requests while the app is in the foreground: every [idleMs], faster
 * ([awaitedMs]) while the user waits for a login. Errors double the wait up to [maxBackoffMs].
 * In the background nothing is polled; the push channel (or the next start) wakes the app.
 */
class PendingPoller(
    private val api: MfaApi,
    private val clock: Clock,
    private val delayer: Delayer,
    private val idleMs: Long = 15_000,
    private val awaitedMs: Long = 3_000,
    private val maxBackoffMs: Long = 120_000,
) {
    private val foreground = MutableStateFlow(false)
    @Volatile private var awaiting = false
    private val _pending = MutableStateFlow<List<ChallengeDetails>>(emptyList())
    val pending: StateFlow<List<ChallengeDetails>> = _pending.asStateFlow()

    fun setForeground(on: Boolean) { foreground.value = on }

    fun setAwaiting(on: Boolean) { awaiting = on }

    suspend fun run() {
        var failures = 0
        while (true) {
            foreground.first { it }
            val base = if (awaiting) awaitedMs else idleMs
            val wait =
                try {
                    _pending.value = open(api.listChallenges())
                    failures = 0
                    base
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    if (e.code == ErrorCode.UNAUTHENTICATED || e.code == ErrorCode.DEVICE_REVOKED) _pending.value = emptyList()
                    backoff(base, ++failures)
                } catch (e: IOException) {
                    backoff(base, ++failures)
                }
            // a switch to the background during the wait stops the next poll at the top of the loop
            delayer.delay(wait)
        }
    }

    private fun backoff(base: Long, failures: Int): Long {
        var w = base
        repeat(failures) { w = minOf(w * 2, maxBackoffMs) }
        return w
    }

    private fun open(list: List<ChallengeDetails>): List<ChallengeDetails> =
        list.filter { it.status == ChallengeStatus.Pending && !expired(it) }

    private fun expired(d: ChallengeDetails): Boolean =
        try {
            Instant.parse(d.expiresAt).toEpochMilli() <= clock.nowMillis()
        } catch (_: Exception) {
            true
        }
}
