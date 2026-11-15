package app.rustmfa.android.domain

import app.rustmfa.android.crypto.DeviceKey
import app.rustmfa.android.enroll.DeviceStore
import app.rustmfa.android.net.ApiError
import app.rustmfa.android.net.ChallengeDetails
import app.rustmfa.android.net.Device
import app.rustmfa.android.net.AuditEvent
import app.rustmfa.android.net.DeviceStatusResponse
import app.rustmfa.android.net.ErrorCode
import app.rustmfa.android.net.MfaApi
import app.rustmfa.android.net.PushChannel
import app.rustmfa.android.net.RegisterRequest

/**
 * What the phone does when its identity is gone. Nothing else in the app deletes keys or the
 * enrollment on these events; the API layer and the screens call here.
 */
class SessionGuard(private val store: DeviceStore, private val key: DeviceKey) {
    /** The server revoked this phone (or the user did): both keys and the enrollment go; the app returns to onboarding. */
    suspend fun onDeviceRevoked() {
        runCatching { key.delete() }
        store.clear()
    }

    /** The approval key is invalidated: keep the server address, drop the dead keys, ask to reconnect. */
    suspend fun onKeyInvalidated() {
        val e = store.load() ?: return
        runCatching { key.delete() }
        store.save(e.copy(needsReconnect = true))
    }
}

/**
 * The one place that sees `DEVICE_REVOKED` from the server. Screens, the poller and the push
 * service all use the guarded client, so none of them handles it on its own. The error is thrown on.
 */
class GuardedApi(private val inner: MfaApi, private val guard: SessionGuard, private val onEnded: () -> Unit) : MfaApi {
    private suspend fun <T> guarded(call: suspend () -> T): T =
        try {
            call()
        } catch (e: ApiError) {
            if (e.code == ErrorCode.DEVICE_REVOKED) {
                guard.onDeviceRevoked()
                onEnded()
            }
            throw e
        }

    override suspend fun register(r: RegisterRequest): DeviceStatusResponse = guarded { inner.register(r) }

    override suspend fun listChallenges(): List<ChallengeDetails> = guarded { inner.listChallenges() }

    override suspend fun getChallenge(id: String): ChallengeDetails = guarded { inner.getChallenge(id) }

    override suspend fun approve(id: String, selectedNumber: Int, signature: String) = guarded { inner.approve(id, selectedNumber, signature) }

    override suspend fun deny(id: String, signature: String) = guarded { inner.deny(id, signature) }

    override suspend fun listDevices(): List<Device> = guarded { inner.listDevices() }

    override suspend fun revokeDevice(id: String) = guarded { inner.revokeDevice(id) }

    override suspend fun updatePushToken(id: String, channel: PushChannel, token: String) =
        guarded { inner.updatePushToken(id, channel, token) }

    override suspend fun listAudit(): List<AuditEvent> = guarded { inner.listAudit() }
}
