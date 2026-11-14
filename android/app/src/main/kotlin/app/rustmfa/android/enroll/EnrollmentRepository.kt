package app.rustmfa.android.enroll

import app.rustmfa.android.crypto.DeviceKey
import app.rustmfa.android.net.ApiError
import app.rustmfa.android.net.ErrorCode
import app.rustmfa.android.net.MfaApi
import app.rustmfa.android.net.PushChannel
import app.rustmfa.android.net.RegisterRequest
import java.io.IOException
import okhttp3.HttpUrl

/** The push channel and token this build can offer; a build without push says `None` and an empty token. */
data class PushInfo(val channel: PushChannel, val token: String)

fun interface PushProvider {
    suspend fun info(): PushInfo
}

enum class EnrollFailure { KeyCreation, Rejected, Network, Storage }

sealed interface EnrollResult {
    data class Enrolled(val enrollment: Enrollment) : EnrollResult

    data class Failed(val reason: EnrollFailure, val apiCode: ErrorCode?) : EnrollResult
}

/**
 * Enrolls this phone. [apiFor] gives an unsigned client for the server of the link (registration
 * has no device yet). Whatever fails, no key is left behind and nothing is stored.
 */
class EnrollmentRepository(
    private val apiFor: (HttpUrl) -> MfaApi,
    private val key: DeviceKey,
    private val store: DeviceStore,
    private val push: PushProvider,
) {
    suspend fun enroll(link: EnrollLink, deviceName: String): EnrollResult {
        val name = deviceName.trim()
        if (name.isEmpty()) return EnrollResult.Failed(EnrollFailure.Rejected, null)
        val pushInfo = push.info()
        val created = try {
            key.createKeys(link.token)
        } catch (e: Exception) {
            // for example: no secure lock screen or no enrolled biometric
            discardKeys()
            return EnrollResult.Failed(EnrollFailure.KeyCreation, null)
        }
        val registered = try {
            apiFor(link.server).register(
                RegisterRequest(
                    enrollToken = link.token,
                    deviceName = name,
                    requestPublicKey = created.requestPublicKey,
                    approvalPublicKey = created.approvalPublicKey,
                    attestationChain = created.attestationChain,
                    pushChannel = pushInfo.channel,
                    pushToken = pushInfo.token,
                ),
            )
        } catch (e: ApiError) {
            discardKeys()
            return EnrollResult.Failed(EnrollFailure.Rejected, e.code)
        } catch (e: IOException) {
            discardKeys()
            return EnrollResult.Failed(EnrollFailure.Network, null)
        } catch (e: Throwable) {
            discardKeys()
            throw e
        }
        val enrollment = Enrollment(link.server.toString(), registered.deviceId, pushInfo.channel, pushInfo.token)
        try {
            store.save(enrollment)
        } catch (e: Exception) {
            // the server now lists a phone this app cannot use; the user revokes it on the web page
            discardKeys()
            return EnrollResult.Failed(EnrollFailure.Storage, null)
        }
        return EnrollResult.Enrolled(enrollment)
    }

    private fun discardKeys() {
        try {
            key.delete()
        } catch (_: Exception) {
        }
    }
}
