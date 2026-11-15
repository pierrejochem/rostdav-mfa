package app.rustmfa.vault

import java.security.SecureRandom

enum class OtpType { TOTP, HOTP }

enum class OtpAlgorithm(val macName: String, val uriName: String) {
    SHA1("HmacSHA1", "SHA1"),
    SHA256("HmacSHA256", "SHA256"),
    SHA512("HmacSHA512", "SHA512"),
}

data class OtpAccount(
    val id: String,
    val type: OtpType,
    val issuer: String,
    val label: String,
    val secret: ByteArray,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
    val counter: Long = 0,
) {
    init {
        require(secret.isNotEmpty()) { "secret must not be empty" }
        require(digits == 6 || digits == 8) { "digits must be 6 or 8" }
        require(period in 1..3600) { "period must be 1..3600" }
        require(counter >= 0) { "counter must not be negative" }
    }

    override fun equals(other: Any?): Boolean =
        other is OtpAccount &&
            id == other.id && type == other.type && issuer == other.issuer && label == other.label &&
            secret.contentEquals(other.secret) && algorithm == other.algorithm &&
            digits == other.digits && period == other.period && counter == other.counter

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + type.hashCode()
        h = 31 * h + issuer.hashCode()
        h = 31 * h + label.hashCode()
        h = 31 * h + secret.contentHashCode()
        h = 31 * h + algorithm.hashCode()
        h = 31 * h + digits
        h = 31 * h + period
        h = 31 * h + counter.hashCode()
        return h
    }

    override fun toString(): String =
        "OtpAccount(id=$id, type=$type, issuer=$issuer, label=$label, secret=<redacted>, " +
            "algorithm=$algorithm, digits=$digits, period=$period, counter=$counter)"
}

fun newAccountId(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
