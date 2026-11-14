package app.rustmfa.android.net

/** A [MfaApi] where every call fails; tests override what they use. */
open class StubApi : MfaApi {
    override suspend fun register(r: RegisterRequest): DeviceStatusResponse = error("unused")
    override suspend fun listChallenges(): List<ChallengeDetails> = error("unused")
    override suspend fun getChallenge(id: String): ChallengeDetails = error("unused")
    override suspend fun approve(id: String, selectedNumber: Int, signature: String): Unit = error("unused")
    override suspend fun deny(id: String, signature: String): Unit = error("unused")
    override suspend fun listDevices(): List<Device> = error("unused")
    override suspend fun revokeDevice(id: String): Unit = error("unused")
    override suspend fun updatePushToken(id: String, channel: PushChannel, token: String): Unit = error("unused")
    override suspend fun listAudit(): List<AuditEvent> = error("unused")
}
