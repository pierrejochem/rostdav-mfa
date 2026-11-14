package app.rustmfa.android.net

/** The device API of the contract. Errors are [ApiError]; a failed connection is an `IOException`. */
interface MfaApi {
    suspend fun register(r: RegisterRequest): DeviceStatusResponse
    suspend fun listChallenges(): List<ChallengeDetails>
    suspend fun getChallenge(id: String): ChallengeDetails
    suspend fun approve(id: String, selectedNumber: Int, signature: String)
    suspend fun deny(id: String, signature: String)
    suspend fun listDevices(): List<Device>
    suspend fun revokeDevice(id: String)
    suspend fun updatePushToken(id: String, channel: PushChannel, token: String)
    suspend fun listAudit(): List<AuditEvent>
}
