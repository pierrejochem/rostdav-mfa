package app.rustmfa.android.net

import app.rustmfa.android.crypto.DeviceKey
import app.rustmfa.contract.Base64Url
import app.rustmfa.contract.RequestSigning
import java.security.SecureRandom

/** Builds the `Authorization: RustMFA-Sig ...` header of a signed request. */
class RequestSigner(
    val deviceId: String,
    private val key: DeviceKey,
    private val clock: Clock,
    private val nonce: () -> String = ::randomNonce,
) {
    /** [pathWithQuery] is the request target exactly as it goes on the wire. A fresh nonce per call. */
    fun header(method: String, pathWithQuery: String, body: ByteArray): String {
        val ts = clock.nowMillis() / 1000
        val n = nonce()
        val signing = RequestSigning.signingString(method, pathWithQuery, ts, n, body)
        val sig = Base64Url.encode(key.signRequest(signing.toByteArray(Charsets.UTF_8)))
        return """RustMFA-Sig device_id="$deviceId",ts="$ts",nonce="$n",sig="$sig""""
    }

    private companion object {
        private val random = SecureRandom()

        fun randomNonce(): String = Base64Url.encode(ByteArray(16).also { random.nextBytes(it) })
    }
}
