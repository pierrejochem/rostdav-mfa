package app.rustmfa.contract

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class VectorKey(
    @SerialName("spki_der_b64") val spkiDerB64: String,
    @SerialName("pkcs8_der_b64") val pkcs8DerB64: String,
)

@Serializable
data class ChallengeFieldsJson(
    @SerialName("challenge_id") val challengeId: String,
    val nonce: String,
    @SerialName("login_id") val loginId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("expires_at") val expiresAt: String,
    val audience: String,
)

@Serializable
data class ChallengeCase(
    val name: String,
    val fields: ChallengeFieldsJson,
    val message: String,
    @SerialName("signature_b64url") val signatureB64url: String,
    @SerialName("signature_der_b64url") val signatureDerB64url: String? = null,
    val expect: String,
)

@Serializable
data class RequestCase(
    val name: String,
    val method: String,
    val path: String,
    val ts: Long,
    val nonce: String,
    @SerialName("body_b64") val bodyB64: String,
    @SerialName("signing_string") val signingString: String,
    @SerialName("signature_b64url") val signatureB64url: String,
    val expect: String,
)

@Serializable
data class VectorFile(
    val version: Int,
    val key: VectorKey,
    @SerialName("challenge_cases") val challengeCases: List<ChallengeCase>,
    @SerialName("request_cases") val requestCases: List<RequestCase>,
)

object Vectors {
    private val json = Json { ignoreUnknownKeys = false }

    /** The vectors of the contract repository (`contract/vectors/v1.json`). */
    fun load(): VectorFile {
        val path = requireNotNull(System.getProperty("vectors.path")) { "vectors.path is not set" }
        return json.decodeFromString(File(path).readText())
    }
}
