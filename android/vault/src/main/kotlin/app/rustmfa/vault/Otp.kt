package app.rustmfa.vault

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Otp {
    fun hotp(secret: ByteArray, algorithm: OtpAlgorithm, digits: Int, counter: Long): String {
        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(secret, algorithm.macName))
        val message = ByteArray(8) { i -> (counter ushr (56 - 8 * i)).toByte() }
        val hash = mac.doFinal(message)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    fun totp(account: OtpAccount, epochSeconds: Long): String =
        hotp(account.secret, account.algorithm, account.digits, epochSeconds / account.period)

    fun secondsLeft(period: Int, epochSeconds: Long): Int = (period - (epochSeconds % period)).toInt()
}
