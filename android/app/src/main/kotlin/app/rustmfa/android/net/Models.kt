package app.rustmfa.android.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The models mirror the schemas of `contract/openapi/rustmfa-v1.yaml`. */

@Serializable
enum class PushChannel {
    @SerialName("fcm") Fcm,
    @SerialName("unifiedpush") UnifiedPush,

    /** A phone without a push service: polling only, empty push token. */
    @SerialName("none") None,
}

@Serializable
enum class DeviceStatus {
    @SerialName("pending_verification") PendingVerification,
    @SerialName("active") Active,
    @SerialName("revoked") Revoked,
}

@Serializable
enum class ChallengeStatus {
    @SerialName("pending") Pending,
    @SerialName("approved") Approved,
    @SerialName("denied") Denied,
    @SerialName("expired") Expired,
}

@Serializable
data class RegisterRequest(
    @SerialName("enroll_token") val enrollToken: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("request_public_key") val requestPublicKey: String,
    @SerialName("approval_public_key") val approvalPublicKey: String,
    @SerialName("attestation_chain") val attestationChain: List<String>,
    @SerialName("push_channel") val pushChannel: PushChannel,
    @SerialName("push_token") val pushToken: String,
    @SerialName("key_algorithm") val keyAlgorithm: String = "ES256",
)

@Serializable
data class DeviceStatusResponse(
    @SerialName("device_id") val deviceId: String,
    val status: DeviceStatus,
)

@Serializable
data class ChallengeDetails(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("service_name") val serviceName: String,
    @SerialName("login_device") val loginDevice: String,
    @SerialName("created_at") val createdAt: String,
    /** Exactly `YYYY-MM-DDTHH:MM:SSZ`; signed verbatim. */
    @SerialName("expires_at") val expiresAt: String,
    val status: ChallengeStatus,
    @SerialName("candidate_numbers") val candidateNumbers: List<Int>,
    val nonce: String,
    @SerialName("login_id") val loginId: String,
    val audience: String,
    @SerialName("approximate_location") val approximateLocation: String? = null,
)

@Serializable
data class ChallengeStatusResponse(
    @SerialName("challenge_id") val challengeId: String,
    val status: ChallengeStatus,
)

@Serializable
data class Device(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    val status: DeviceStatus,
    @SerialName("push_channel") val pushChannel: PushChannel,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("hardware_backed") val hardwareBacked: Boolean = false,
    val strongbox: Boolean = false,
)

@Serializable
data class AuditEvent(
    val event: String,
    val at: String,
    @SerialName("device_id") val deviceId: String? = null,
    val detail: String? = null,
)

@Serializable internal data class ChallengesResponse(val challenges: List<ChallengeDetails>)

@Serializable internal data class DevicesResponse(val devices: List<Device>)

@Serializable internal data class AuditResponse(val events: List<AuditEvent>)

@Serializable
internal data class ApproveRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("selected_number") val selectedNumber: Int,
    val signature: String,
)

@Serializable
internal data class DenyRequest(
    @SerialName("device_id") val deviceId: String,
    val signature: String,
)

@Serializable
internal data class PushTokenRequest(
    @SerialName("push_channel") val pushChannel: PushChannel,
    @SerialName("push_token") val pushToken: String,
)
