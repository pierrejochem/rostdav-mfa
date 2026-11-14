package app.rustmfa.android.push

import app.rustmfa.android.net.ApiError
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.net.ChallengeStatus
import app.rustmfa.android.net.Clock
import app.rustmfa.android.net.ErrorCode
import app.rustmfa.android.net.MfaApi
import java.io.IOException
import java.time.Instant

/**
 * What a notification may show. There is no field for a number: the user reads it on the login
 * screen and picks it here, after unlocking.
 */
data class NotificationSpec(val challengeId: String, val serviceName: String, val loginDevice: String)

/** The Android side: posts or removes one high-priority notification per challenge id. */
interface NotificationSink {
    fun show(spec: NotificationSpec)

    fun cancel(challengeId: String)
}

/** One notification per open challenge; gone when the challenge is answered, expired or unknown. */
class ChallengeNotifier(private val api: MfaApi, private val sink: NotificationSink, private val clock: Clock) {
    private val shown = HashSet<String>()

    /** Makes the notifications match the list of open challenges. */
    @Synchronized
    fun sync(open: List<ChallengeDetails>) {
        val live = open.filter { isOpen(it) }
        for (gone in shown - live.map { it.challengeId }.toSet()) cancel(gone)
        live.forEach { show(it) }
    }

    /** A push named [id]: fetch it through the signed GET, notify if it is still open. */
    suspend fun onPush(id: String) {
        val d =
            try {
                api.getChallenge(id)
            } catch (e: ApiError) {
                if (e.code == ErrorCode.RESOURCE_NOT_FOUND || e.code == ErrorCode.CHALLENGE_EXPIRED) answered(id)
                return
            } catch (_: IOException) {
                return
            }
        if (isOpen(d)) show(d) else answered(id)
    }

    @Synchronized
    fun answered(id: String) = cancel(id)

    @Synchronized
    private fun show(d: ChallengeDetails) {
        if (shown.add(d.challengeId)) sink.show(NotificationSpec(d.challengeId, d.serviceName, d.loginDevice))
    }

    private fun cancel(id: String) {
        shown.remove(id)
        sink.cancel(id)
    }

    private fun isOpen(d: ChallengeDetails): Boolean =
        d.status == ChallengeStatus.Pending &&
            try {
                Instant.parse(d.expiresAt).toEpochMilli() > clock.nowMillis()
            } catch (_: Exception) {
                false
            }
}
