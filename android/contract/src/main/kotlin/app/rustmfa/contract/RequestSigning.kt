package app.rustmfa.contract

import java.security.MessageDigest

/** The request signing string, the same as `request.rs` of the Rust contract crate. */
object RequestSigning {
    fun signingString(method: String, path: String, ts: Long, nonce: String, body: ByteArray): String {
        if (method != "GET" && method != "POST" && method != "PUT" && method != "DELETE") {
            throw ContractException(ContractErrorKind.InvalidField)
        }
        if (path.isEmpty() || path.contains('\n')) throw ContractException(ContractErrorKind.InvalidField)
        if (nonce.isEmpty() || nonce.contains('\n')) throw ContractException(ContractErrorKind.InvalidField)
        val bodyHash = Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(body))
        return "$method\n$path\n$ts\n$nonce\n$bodyHash"
    }
}
