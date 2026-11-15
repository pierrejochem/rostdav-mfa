package app.rustmfa.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class OtpTest {
    private val sha1 = "12345678901234567890".toByteArray()
    private val sha256 = "12345678901234567890123456789012".toByteArray()
    private val sha512 = "1234567890123456789012345678901234567890123456789012345678901234".toByteArray()

    @Test
    fun hotpMatchesRfc4226() {
        val expected = listOf("755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489")
        expected.forEachIndexed { counter, code ->
            assertEquals(code, Otp.hotp(sha1, OtpAlgorithm.SHA1, 6, counter.toLong()))
        }
    }

    @Test
    fun totpMatchesRfc6238() {
        val table = listOf(
            59L to listOf("94287082", "46119246", "90693936"),
            1111111109L to listOf("07081804", "68084774", "25091201"),
            1111111111L to listOf("14050471", "67062674", "99943326"),
            1234567890L to listOf("89005924", "91819424", "93441116"),
            2000000000L to listOf("69279037", "90698825", "38618901"),
            20000000000L to listOf("65353130", "77737706", "47863826"),
        )
        val setups = listOf(sha1 to OtpAlgorithm.SHA1, sha256 to OtpAlgorithm.SHA256, sha512 to OtpAlgorithm.SHA512)
        for ((time, codes) in table) {
            setups.forEachIndexed { i, (secret, alg) ->
                val account = OtpAccount("a", OtpType.TOTP, "i", "l", secret, alg, digits = 8, period = 30)
                assertEquals(codes[i], Otp.totp(account, time), "$alg at $time")
            }
        }
    }

    @Test
    fun secondsLeft() {
        assertEquals(1, Otp.secondsLeft(30, 59))
        assertEquals(30, Otp.secondsLeft(30, 60))
        assertEquals(29, Otp.secondsLeft(30, 61))
    }

    @Test
    fun accountValidation() {
        fun make(secret: ByteArray = sha1, digits: Int = 6, period: Int = 30, counter: Long = 0) =
            OtpAccount("a", OtpType.TOTP, "i", "l", secret, digits = digits, period = period, counter = counter)
        assertFailsWith<IllegalArgumentException> { make(secret = ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { make(digits = 7) }
        assertFailsWith<IllegalArgumentException> { make(period = 0) }
        assertFailsWith<IllegalArgumentException> { make(period = 3601) }
        assertFailsWith<IllegalArgumentException> { make(counter = -1) }
    }

    @Test
    fun accountEqualityComparesSecretContent() {
        val a = OtpAccount("a", OtpType.TOTP, "i", "l", sha1.copyOf())
        val b = OtpAccount("a", OtpType.TOTP, "i", "l", sha1.copyOf())
        val c = OtpAccount("a", OtpType.TOTP, "i", "l", sha256)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun toStringHidesSecret() {
        val text = OtpAccount("a", OtpType.TOTP, "i", "l", sha1).toString()
        assertFalse(text.contains(Base32.encode(sha1)))
        assertFalse(text.contains(Base32.encode(sha1).trimEnd('=')))
        assertFalse(text.uppercase().contains(sha1.joinToString("") { "%02x".format(it) }.uppercase()))
    }

    @Test
    fun newAccountIdIsSixteenBytesOfLowercaseHex() {
        val id = newAccountId()
        assertEquals(32, id.length)
        assertEquals(id.lowercase(), id)
        assertFalse(id.any { !it.isDigit() && it !in 'a'..'f' })
        assertNotEquals(id, newAccountId())
    }
}
