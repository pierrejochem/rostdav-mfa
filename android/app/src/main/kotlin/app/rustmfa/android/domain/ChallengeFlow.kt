package app.rustmfa.android.domain

import app.rustmfa.android.crypto.ApprovalSigner
import app.rustmfa.android.crypto.DeviceKey
import app.rustmfa.android.crypto.KeyInvalidatedException
import app.rustmfa.android.net.ApiError
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.net.ChallengeStatus
import app.rustmfa.android.net.Clock
import app.rustmfa.android.net.ErrorCode
import app.rustmfa.android.net.MfaApi
import app.rustmfa.contract.Base64Url
import app.rustmfa.contract.Canonical
import app.rustmfa.contract.ChallengeFields
import app.rustmfa.contract.ContractException
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun interface Delayer {
    suspend fun delay(ms: Long)
}

/** The user closed the biometric prompt without a result. */
class AuthCancelledException : Exception("authentication cancelled")

/**
 * One sign-in request, from the signed GET to the signed answer.
 *
 * The answer is signed once. If the network fails it is sent again as it is, with back-off, until
 * `expires_at` by the corrected clock; after that nothing is sent. The signed answer lives only in
 * [ChallengeState.Offline]: this class has no reference to a store or a file.
 */
class ChallengeFlow(
    private val api: MfaApi,
    private val key: DeviceKey,
    private val deviceId: String,
    private val clock: Clock,
    private val delayer: Delayer,
) {
    private val _state = MutableStateFlow<ChallengeState>(ChallengeState.Loading)
    val state: StateFlow<ChallengeState> = _state.asStateFlow()

    private var details: ChallengeDetails? = null
    private var expiresMs = 0L
    private val busy = AtomicBoolean(false)

    suspend fun load(id: String) {
        _state.value = ChallengeState.Loading
        val d =
            try {
                api.getChallenge(id)
            } catch (e: ApiError) {
                _state.value = fromApiError(e)
                return
            } catch (e: IOException) {
                _state.value = ChallengeState.Failed(null)
                return
            }
        when (d.status) {
            ChallengeStatus.Approved, ChallengeStatus.Denied -> { _state.value = ChallengeState.AlreadyHandled; return }
            ChallengeStatus.Expired -> { _state.value = ChallengeState.Expired; return }
            ChallengeStatus.Pending -> Unit
        }
        expiresMs =
            try {
                Instant.parse(d.expiresAt).toEpochMilli()
            } catch (e: Exception) {
                _state.value = ChallengeState.Failed(ErrorCode.INVALID_REQUEST)
                return
            }
        details = d
        _state.value = pendingNow(d) ?: ChallengeState.Expired
    }

    /** Runs until the challenge is no longer open; ends as [ChallengeState.Expired] at `expires_at`. */
    suspend fun countdown() {
        while (true) {
            val d = details ?: return
            if (_state.value !is ChallengeState.Pending) return
            val s = pendingNow(d)
            if (s == null) {
                _state.value = ChallengeState.Expired
                return
            }
            _state.value = s
            delayer.delay(1000)
        }
    }

    private fun pendingNow(d: ChallengeDetails): ChallengeState.Pending? {
        val ms = expiresMs - clock.nowMillis()
        if (ms <= 0) return null
        return ChallengeState.Pending(d, (ms + 999) / 1000)
    }

    /** [authenticate] shows the biometric prompt and returns the raw signature of the message it is given. */
    suspend fun approve(selected: Int, authenticate: suspend (ApprovalSigner, ByteArray) -> ByteArray) = answer(selected, authenticate)

    suspend fun deny(authenticate: suspend (ApprovalSigner, ByteArray) -> ByteArray) = answer(null, authenticate)

    private suspend fun answer(selected: Int?, authenticate: suspend (ApprovalSigner, ByteArray) -> ByteArray) {
        val d = details ?: return
        if (_state.value !is ChallengeState.Pending) return
        if (!busy.compareAndSet(false, true)) return // double tap: one answer only
        try {
            if (clock.nowMillis() >= expiresMs) {
                _state.value = ChallengeState.Expired
                return
            }
            val message =
                try {
                    Canonical.message(ChallengeFields(d.challengeId, d.nonce, d.loginId, deviceId, d.expiresAt, d.audience))
                } catch (e: ContractException) {
                    _state.value = ChallengeState.Failed(ErrorCode.INVALID_REQUEST)
                    return
                }
            _state.value = ChallengeState.Signing(selected)
            val raw =
                try {
                    authenticate(key.approvalSigner(), message.toByteArray(Charsets.UTF_8))
                } catch (e: KeyInvalidatedException) {
                    _state.value = ChallengeState.KeyInvalidated
                    return
                } catch (e: AuthCancelledException) {
                    _state.value = pendingNow(d) ?: ChallengeState.Expired
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = ChallengeState.Failed(null)
                    return
                }
            send(d.challengeId, PendingAnswer(selected != null, selected, Base64Url.encode(raw)))
        } finally {
            busy.set(false)
        }
    }

    private suspend fun send(id: String, answer: PendingAnswer) {
        var wait = 1000L
        while (true) {
            if (clock.nowMillis() >= expiresMs) {
                _state.value = ChallengeState.Expired
                return
            }
            _state.value = ChallengeState.Sending
            try {
                if (answer.approve) api.approve(id, answer.selected!!, answer.signature) else api.deny(id, answer.signature)
                _state.value = if (answer.approve) ChallengeState.Approved else ChallengeState.Denied
                return
            } catch (e: ApiError) {
                _state.value = fromApiError(e)
                return
            } catch (e: IOException) {
                _state.value = ChallengeState.Offline(answer)
                delayer.delay(wait)
                wait = minOf(wait * 2, 8000L)
            }
        }
    }

    private fun fromApiError(e: ApiError): ChallengeState =
        when (e.code) {
            ErrorCode.CHALLENGE_NUMBER_MISMATCH -> ChallengeState.NumberMismatch
            ErrorCode.CHALLENGE_EXPIRED -> ChallengeState.Expired
            ErrorCode.CHALLENGE_ALREADY_CONSUMED -> ChallengeState.AlreadyHandled
            ErrorCode.DEVICE_REVOKED -> ChallengeState.DeviceRevoked
            else -> ChallengeState.Failed(e.code)
        }
}
