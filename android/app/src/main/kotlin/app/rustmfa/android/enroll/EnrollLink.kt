package app.rustmfa.android.enroll

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The enrollment link of the QR code: `rustmfa://enroll?server=<percent-encoded base URL>&token=<token>`.
 * Anything that is not exactly that is refused before any network call.
 */
class EnrollLink private constructor(val server: HttpUrl, val token: String) {
    companion object {
        private const val PREFIX = "rustmfa://enroll?"
        private const val MAX_INPUT = 2048
        private const val MAX_TOKEN = 128

        /** [allowHttp] is true only in debug builds (the emulator reaches the host as `http://10.0.2.2`). */
        fun parse(text: String, allowHttp: Boolean): EnrollLink? {
            if (text.length > MAX_INPUT || !text.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) return null
            val query = text.substring(PREFIX.length)
            if ('#' in query) return null
            // the query is read by HttpUrl on a stand-in address; the custom scheme is checked above
            val params = "https://enroll.invalid/?$query".toHttpUrlOrNull() ?: return null
            val servers = params.queryParameterValues("server")
            val tokens = params.queryParameterValues("token")
            if (servers.size != 1 || tokens.size != 1) return null
            val token = tokens[0] ?: return null
            if (token.isEmpty() || token.length > MAX_TOKEN || token.any { it !in '!'..'~' }) return null
            val url = (servers[0] ?: return null).toHttpUrlOrNull() ?: return null
            if (!url.isHttps && !allowHttp) return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (url.query != null || url.fragment != null) return null
            val base = if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
            return EnrollLink(base, token)
        }
    }
}
