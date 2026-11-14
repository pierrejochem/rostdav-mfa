package app.rustmfa.contract

import java.util.Base64

/** base64url without padding, strict: a padded or standard-alphabet string is refused. */
object Base64Url {
    fun encode(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun decode(s: String): ByteArray {
        if (s.contains('=')) throw ContractException(ContractErrorKind.BadSignatureEncoding)
        return try {
            Base64.getUrlDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            throw ContractException(ContractErrorKind.BadSignatureEncoding)
        }
    }
}
