package app.rustmfa.android.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rustmfa.android.R
import app.rustmfa.android.enroll.EnrollFailure
import app.rustmfa.android.enroll.EnrollLink
import app.rustmfa.android.enroll.EnrollResult
import app.rustmfa.android.enroll.EnrollmentRepository
import app.rustmfa.android.net.ErrorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class FailureKind { BadLink, NoSecureLock, Rejected, Network, Storage }

sealed interface OnboardingState {
    data object Idle : OnboardingState

    data object Scanning : OnboardingState

    data class NeedsName(val link: EnrollLink) : OnboardingState

    data object Enrolling : OnboardingState

    data class Failed(val kind: FailureKind, val apiCode: ErrorCode? = null) : OnboardingState

    data object Done : OnboardingState
}

/** The text for a failure. A server code keeps its own message where the user can act on it. */
@StringRes
fun failureMessage(f: OnboardingState.Failed): Int =
    when (f.kind) {
        FailureKind.BadLink -> R.string.err_bad_link
        FailureKind.NoSecureLock -> R.string.err_no_secure_lock
        FailureKind.Network -> R.string.err_network
        FailureKind.Storage -> R.string.err_storage
        FailureKind.Rejected ->
            when (f.apiCode) {
                ErrorCode.RATE_LIMITED -> R.string.err_rate_limited
                ErrorCode.INVALID_REQUEST, ErrorCode.UNAUTHENTICATED, ErrorCode.RESOURCE_NOT_FOUND -> R.string.err_token_rejected
                else -> R.string.err_rejected
            }
    }

/**
 * Onboarding state. Every link, scanned or typed, goes through [EnrollLink.parse] first: a hostile
 * one never reaches the repository, so no key is made and no request is sent.
 */
class OnboardingViewModel(
    private val repo: EnrollmentRepository,
    private val allowHttp: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Idle)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun startScan() {
        _state.value = OnboardingState.Scanning
    }

    fun reset() {
        if (_state.value !is OnboardingState.Enrolling) _state.value = OnboardingState.Idle
    }

    fun onLink(text: String) {
        if (_state.value is OnboardingState.Enrolling || _state.value is OnboardingState.Done) return
        val link = EnrollLink.parse(text.trim(), allowHttp)
        _state.value = if (link == null) OnboardingState.Failed(FailureKind.BadLink) else OnboardingState.NeedsName(link)
    }

    /** The manual form builds the same link text, so it passes the same checks. */
    fun onManual(server: String, token: String) {
        val enc = java.net.URLEncoder.encode(server.trim(), "UTF-8")
        val tok = java.net.URLEncoder.encode(token.trim(), "UTF-8")
        onLink("rustmfa://enroll?server=$enc&token=$tok")
    }

    fun enroll(deviceName: String) {
        val s = _state.value as? OnboardingState.NeedsName ?: return
        if (deviceName.isBlank()) return
        _state.value = OnboardingState.Enrolling
        viewModelScope.launch {
            _state.value =
                when (val r = repo.enroll(s.link, deviceName)) {
                    is EnrollResult.Enrolled -> OnboardingState.Done
                    is EnrollResult.Failed ->
                        OnboardingState.Failed(
                            when (r.reason) {
                                EnrollFailure.KeyCreation -> FailureKind.NoSecureLock
                                EnrollFailure.Rejected -> FailureKind.Rejected
                                EnrollFailure.Network -> FailureKind.Network
                                EnrollFailure.Storage -> FailureKind.Storage
                            },
                            r.apiCode,
                        )
                }
        }
    }
}
