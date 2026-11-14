package app.rustmfa.android.domain

import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.net.ErrorCode

/** A signed answer that could not be sent yet. Held in memory only; never written anywhere. */
class PendingAnswer(val approve: Boolean, val selected: Int?, val signature: String) {
    override fun toString() = "PendingAnswer(approve=$approve)" // no signature or number in logs
}

sealed interface ChallengeState {
    data object Loading : ChallengeState

    data class Pending(val details: ChallengeDetails, val secondsLeft: Long) : ChallengeState

    data class Signing(val selected: Int?) : ChallengeState

    data object Sending : ChallengeState

    data object Approved : ChallengeState

    data object Denied : ChallengeState

    /** The picked number was wrong; the server denied the challenge. */
    data object NumberMismatch : ChallengeState

    data object Expired : ChallengeState

    /** Someone, perhaps this phone a moment ago, answered already. */
    data object AlreadyHandled : ChallengeState

    data class Offline(val pendingAnswer: PendingAnswer) : ChallengeState

    /** The approval key is gone (for example a new fingerprint): the phone must be connected again. */
    data object KeyInvalidated : ChallengeState

    data object DeviceRevoked : ChallengeState

    /** [code] is null when no server answer exists (the network failed). */
    data class Failed(val code: ErrorCode?) : ChallengeState
}
