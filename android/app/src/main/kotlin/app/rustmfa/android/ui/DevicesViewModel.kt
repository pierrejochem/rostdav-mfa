package app.rustmfa.android.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rustmfa.android.R
import app.rustmfa.android.domain.SessionGuard
import app.rustmfa.android.net.ApiError
import app.rustmfa.android.net.AuditEvent
import app.rustmfa.android.net.Device
import app.rustmfa.android.net.ErrorCode
import app.rustmfa.android.net.MfaApi
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The event kinds of the contract's audit list; anything else the server sends is not shown. */
val AuditKinds =
    setOf(
        "device_registered", "device_revoked", "key_rotated", "challenge_created",
        "challenge_approved", "challenge_denied", "challenge_expired", "number_mismatch",
    )

sealed interface Failure {
    data object Network : Failure

    data class Api(val code: ErrorCode) : Failure
}

@StringRes
fun deviceFailureMessage(f: Failure): Int =
    when (f) {
        Failure.Network -> R.string.err_network
        is Failure.Api ->
            when (f.code) {
                ErrorCode.RATE_LIMITED -> R.string.err_rate_limited
                ErrorCode.DEVICE_REVOKED -> R.string.result_revoked
                else -> R.string.err_rejected
            }
    }

data class DeviceRow(val device: Device, val isThisPhone: Boolean)

data class DevicesState(
    val loading: Boolean = true,
    val devices: List<DeviceRow> = emptyList(),
    val audit: List<AuditEvent> = emptyList(),
    /** The device id the user is asked to confirm revoking. */
    val confirming: String? = null,
    val failure: Failure? = null,
    /** This phone was revoked: the guard ran, the screen leaves for onboarding. */
    val thisPhoneRevoked: Boolean = false,
)

class DevicesViewModel(
    private val api: MfaApi,
    private val ownDeviceId: String,
    private val guard: SessionGuard,
) : ViewModel() {
    private val _state = MutableStateFlow(DevicesState())
    val state: StateFlow<DevicesState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val devices = api.listDevices().map { DeviceRow(it, it.deviceId == ownDeviceId) }
                // the audit list is a courtesy: a failure there must not hide the devices
                val audit = runCatching { api.listAudit() }.getOrDefault(emptyList()).filter { it.event in AuditKinds }.take(20)
                _state.value = _state.value.copy(loading = false, devices = devices, audit = audit, failure = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, failure = failureOf(e))
            }
        }
    }

    fun askRevoke(id: String) { _state.value = _state.value.copy(confirming = id) }

    fun cancelRevoke() { _state.value = _state.value.copy(confirming = null) }

    fun confirmRevoke() {
        val id = _state.value.confirming ?: return
        _state.value = _state.value.copy(confirming = null)
        viewModelScope.launch {
            try {
                api.revokeDevice(id)
                if (id == ownDeviceId) {
                    // only after the server said yes: a failed call must not strand the phone without keys
                    guard.onDeviceRevoked()
                    _state.value = _state.value.copy(thisPhoneRevoked = true)
                } else {
                    refresh()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(failure = failureOf(e))
            }
        }
    }

    private fun failureOf(e: Exception): Failure =
        when (e) {
            is ApiError -> Failure.Api(e.code)
            is IOException -> Failure.Network
            else -> Failure.Network
        }
}
