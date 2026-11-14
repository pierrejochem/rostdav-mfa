package app.rustmfa.android.net

import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [signer] is null only for a client that registers (registration has no device yet).
 * Release builds pass `allowHttp = false`: an `http://` server is refused here, before any request.
 */
class OkHttpMfaApi(
    private val server: HttpUrl,
    private val signer: RequestSigner?,
    private val skew: ClockSkew,
    allowHttp: Boolean = false,
    private val client: OkHttpClient = newClient(),
) : MfaApi {
    init {
        require(server.isHttps || allowHttp) { "the server address must use https" }
    }

    override suspend fun register(r: RegisterRequest): DeviceStatusResponse =
        send("POST", listOf("api", "v1", "devices", "register"), body = json.encodeToString(RegisterRequest.serializer(), r), signed = false)
            .decode(DeviceStatusResponse.serializer())

    override suspend fun listChallenges(): List<ChallengeDetails> =
        send("GET", listOf("api", "v1", "mfa", "challenges"), query = "status" to "pending")
            .decode(ChallengesResponse.serializer()).challenges

    override suspend fun getChallenge(id: String): ChallengeDetails =
        send("GET", listOf("api", "v1", "mfa", "challenges", segment(id))).decode(ChallengeDetails.serializer())

    override suspend fun approve(id: String, selectedNumber: Int, signature: String) {
        val body = json.encodeToString(ApproveRequest.serializer(), ApproveRequest(deviceId(), selectedNumber, signature))
        send("POST", listOf("api", "v1", "mfa", "challenges", segment(id), "approve"), body = body)
    }

    override suspend fun deny(id: String, signature: String) {
        val body = json.encodeToString(DenyRequest.serializer(), DenyRequest(deviceId(), signature))
        send("POST", listOf("api", "v1", "mfa", "challenges", segment(id), "deny"), body = body)
    }

    override suspend fun listDevices(): List<Device> =
        send("GET", listOf("api", "v1", "devices")).decode(DevicesResponse.serializer()).devices

    override suspend fun revokeDevice(id: String) {
        send("DELETE", listOf("api", "v1", "devices", segment(id)))
    }

    override suspend fun updatePushToken(id: String, channel: PushChannel, token: String) {
        val body = json.encodeToString(PushTokenRequest.serializer(), PushTokenRequest(channel, token))
        send("PUT", listOf("api", "v1", "devices", segment(id), "push-token"), body = body)
    }

    override suspend fun listAudit(): List<AuditEvent> =
        send("GET", listOf("api", "v1", "audit")).decode(AuditResponse.serializer()).events

    private fun deviceId(): String = checkNotNull(signer) { "this client has no signer" }.deviceId

    private fun segment(id: String): String {
        require(id.isNotEmpty() && id != "." && id != "..") { "bad id" }
        return id
    }

    private fun <T> ByteArray.decode(s: kotlinx.serialization.KSerializer<T>): T =
        try {
            json.decodeFromString(s, String(this, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw ApiError(ErrorCode.UNKNOWN, 200, "the answer of the server could not be read")
        }

    /** One call; an `UNAUTHENTICATED` answer is retried once, with the clock corrected from its `Date` header. */
    private suspend fun send(
        method: String,
        segments: List<String>,
        query: Pair<String, String>? = null,
        body: String? = null,
        signed: Boolean = true,
    ): ByteArray = withContext(Dispatchers.IO) {
        if (signed) checkNotNull(signer) { "this client has no signer" }
        for (attempt in 0..1) {
            val url = server.newBuilder().apply {
                segments.forEach { addPathSegment(it) }
                query?.let { addQueryParameter(it.first, it.second) }
            }.build()
            val bytes = body?.toByteArray(Charsets.UTF_8)
            val request = Request.Builder().url(url).method(
                method,
                bytes?.toRequestBody(JSON) ?: if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(null),
            ).apply {
                if (signed) {
                    val target = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
                    header("Authorization", signer!!.header(method, target, bytes ?: ByteArray(0)))
                }
                if (bytes != null) header("Content-Type", "application/json")
            }.build()
            val (status, answer) = client.newCall(request).execute().use { r ->
                skew.update(r.header("Date"))
                r.code to readCapped(r)
            }
            if (status in 200..299) return@withContext answer
            val code = errorCode(status, answer)
            if (code == ErrorCode.UNAUTHENTICATED && signed && attempt == 0) continue
            throw ApiError(
                code,
                status,
                errorMessage(status, answer),
                clockSkewSuspected = code == ErrorCode.UNAUTHENTICATED && abs(skew.offsetMillis) > SKEW_HINT_MILLIS,
            )
        }
        error("unreachable: the second attempt always returns or throws")
    }

    /** At most one mebibyte is read; more is an error, so a hostile server cannot fill the memory. */
    private fun readCapped(r: okhttp3.Response): ByteArray {
        val source = r.body.source()
        source.request(MAX_BODY + 1)
        if (source.buffer.size > MAX_BODY) throw ApiError(ErrorCode.UNKNOWN, r.code, "response too large")
        return source.buffer.readByteArray()
    }

    private fun parsedError(body: ByteArray): Pair<String, String>? =
        try {
            val e = Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject["error"]?.jsonObject
            e?.let { (it["code"]?.jsonPrimitive?.content ?: "") to (it["message"]?.jsonPrimitive?.content ?: "") }
        } catch (e: Exception) {
            null
        }

    private fun errorCode(status: Int, body: ByteArray): ErrorCode {
        val parsed = parsedError(body)
        if (parsed != null) return ErrorCode.entries.firstOrNull { it.name == parsed.first } ?: ErrorCode.UNKNOWN
        return if (status >= 500) ErrorCode.INTERNAL_ERROR else ErrorCode.UNKNOWN
    }

    /** Only the server's own short message is shown; a page from a proxy is never echoed. */
    private fun errorMessage(status: Int, body: ByteArray): String =
        parsedError(body)?.second?.takeIf { it.isNotBlank() }?.take(200) ?: "HTTP $status"

    companion object {
        private const val MAX_BODY = 1L shl 20
        private const val SKEW_HINT_MILLIS = 30_000L
        private val JSON = "application/json".toMediaType()
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = false }

        /** Ten seconds for a whole call, no redirects, no cookies. */
        fun newClient(): OkHttpClient =
            OkHttpClient.Builder()
                .callTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .build()
    }
}
