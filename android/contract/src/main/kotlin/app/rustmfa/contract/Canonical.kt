package app.rustmfa.contract

data class ChallengeFields(
    val challengeId: String,
    val nonce: String,
    val loginId: String,
    val deviceId: String,
    val expiresAt: String,
    val audience: String,
)

/** The canonical challenge message, the same as `canonical.rs` of the Rust contract crate. */
object Canonical {
    private fun check(v: String) {
        if (v.isEmpty() || v.contains('\n')) throw ContractException(ContractErrorKind.InvalidField)
    }

    private fun isDigit(c: Char) = c in '0'..'9'

    /** Exactly `YYYY-MM-DDTHH:MM:SSZ`: a real calendar date, no fraction, no leap second. */
    private fun validExpiry(s: String): Boolean {
        if (s.length != 20) return false
        for (i in s.indices) {
            val ok = when (i) {
                4, 7 -> s[i] == '-'
                10 -> s[i] == 'T'
                13, 16 -> s[i] == ':'
                19 -> s[i] == 'Z'
                else -> isDigit(s[i])
            }
            if (!ok) return false
        }
        val year = s.substring(0, 4).toInt()
        val month = s.substring(5, 7).toInt()
        val day = s.substring(8, 10).toInt()
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val days = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (leap) 29 else 28
            else -> return false
        }
        return day in 1..days &&
            s.substring(11, 13).toInt() < 24 &&
            s.substring(14, 16).toInt() < 60 &&
            s.substring(17, 19).toInt() < 60
    }

    fun message(f: ChallengeFields): String {
        check(f.challengeId)
        check(f.nonce)
        check(f.loginId)
        check(f.deviceId)
        check(f.audience)
        if (!validExpiry(f.expiresAt)) throw ContractException(ContractErrorKind.InvalidExpiresAt)
        return listOf("v1", f.challengeId, f.nonce, f.loginId, f.deviceId, f.expiresAt, f.audience).joinToString("\n")
    }
}
