package app.rustmfa.vault

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class OtpAuthUriTest {
    private val s = "JBSWY3DPEHPK3PXP"

    private fun parse(uri: String) = OtpAuthUri.parse(uri) { "id" }

    private fun reason(uri: String): OtpAuthException.Reason =
        assertFailsWith<OtpAuthException> { parse(uri) }.reason

    @Test fun parsesBasicUri() {
        val a = parse("otpauth://totp/Example:alice@example.com?secret=$s&issuer=Example")
        assertEquals("Example", a.issuer)
        assertEquals("alice@example.com", a.label)
        assertContentEquals(Base32.decode(s), a.secret)
        assertContentEquals("Hello!".toByteArray() + byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), a.secret)
        assertEquals(OtpType.TOTP, a.type)
        assertEquals(OtpAlgorithm.SHA1, a.algorithm)
        assertEquals(6, a.digits)
        assertEquals(30, a.period)
        assertEquals("id", a.id)
    }

    @Test fun generatesIdByDefault() {
        val a = OtpAuthUri.parse("otpauth://totp/x?secret=$s")
        assertEquals(32, a.id.length)
    }

    @Test fun schemeAndTypeAreCaseInsensitive() {
        val a = parse("OTPAUTH://TOTP/Example:alice?secret=$s")
        assertEquals(OtpType.TOTP, a.type)
        assertEquals("Example", a.issuer)
    }

    @Test fun issuerOnlyInPath() {
        val a = parse("otpauth://totp/Acme:bob?secret=$s")
        assertEquals("Acme", a.issuer)
        assertEquals("bob", a.label)
    }

    @Test fun issuerOnlyAsParameter() {
        val a = parse("otpauth://totp/alice?secret=$s&issuer=Acme")
        assertEquals("Acme", a.issuer)
        assertEquals("alice", a.label)
    }

    @Test fun parameterIssuerWinsOverPath() {
        val a = parse("otpauth://totp/Acme:alice?secret=$s&issuer=ACME%20Corp")
        assertEquals("ACME Corp", a.issuer)
        assertEquals("alice", a.label)
    }

    @Test fun percentDecodesPath() {
        val a = parse("otpauth://totp/Acme%20Corp:bob%40x.org?secret=$s")
        assertEquals("Acme Corp", a.issuer)
        assertEquals("bob@x.org", a.label)
    }

    @Test fun severalColonsSplitOnFirst() {
        val a = parse("otpauth://totp/a:b:c?secret=$s")
        assertEquals("a", a.issuer)
        assertEquals("b:c", a.label)
    }

    @Test fun emptyIssuerInPath() {
        val a = parse("otpauth://totp/:alice?secret=$s")
        assertEquals("", a.issuer)
        assertEquals("alice", a.label)
    }

    @Test fun plusInQueryIsLiteral() {
        val a = parse("otpauth://totp/x?secret=$s&issuer=A+B")
        assertEquals("A+B", a.issuer)
    }

    @Test fun lenientSecretText() {
        val a = parse("otpauth://totp/x?secret=jbsw%20y3dp")
        assertContentEquals(Base32.decode("JBSWY3DP"), a.secret)
        val b = parse("otpauth://totp/x?secret=jbsw y3dp")
        assertContentEquals(Base32.decode("JBSWY3DP"), b.secret)
    }

    @Test fun readsOptionalParameters() {
        val a = parse("otpauth://totp/x?secret=$s&algorithm=SHA256&digits=8&period=60&foo=bar")
        assertEquals(OtpAlgorithm.SHA256, a.algorithm)
        assertEquals(8, a.digits)
        assertEquals(60, a.period)
    }

    @Test fun parsesHotpCounter() {
        val a = parse("otpauth://hotp/x?secret=$s&counter=5")
        assertEquals(OtpType.HOTP, a.type)
        assertEquals(5L, a.counter)
    }

    @Test fun rejectsBadUris() {
        assertEquals(OtpAuthException.Reason.NotOtpAuth, reason("http://x"))
        assertEquals(OtpAuthException.Reason.UnknownType, reason("otpauth://steam/x?secret=$s"))
        assertEquals(OtpAuthException.Reason.MissingSecret, reason("otpauth://totp/x"))
        assertEquals(OtpAuthException.Reason.MissingSecret, reason("otpauth://totp/x?issuer=a"))
        assertEquals(OtpAuthException.Reason.BadSecret, reason("otpauth://totp/x?secret=!!"))
        assertEquals(OtpAuthException.Reason.BadSecret, reason("otpauth://totp/x?secret=M"))
        assertEquals(OtpAuthException.Reason.BadSecret, reason("otpauth://totp/x?secret="))
        assertEquals(OtpAuthException.Reason.BadDigits, reason("otpauth://totp/x?secret=$s&digits=7"))
        assertEquals(OtpAuthException.Reason.BadDigits, reason("otpauth://totp/x?secret=$s&digits=x"))
        assertEquals(OtpAuthException.Reason.BadPeriod, reason("otpauth://totp/x?secret=$s&period=0"))
        assertEquals(OtpAuthException.Reason.BadPeriod, reason("otpauth://totp/x?secret=$s&period=3601"))
        assertEquals(OtpAuthException.Reason.MissingCounter, reason("otpauth://hotp/x?secret=$s"))
        assertEquals(OtpAuthException.Reason.BadCounter, reason("otpauth://hotp/x?secret=$s&counter=-1"))
        assertEquals(OtpAuthException.Reason.BadCounter, reason("otpauth://hotp/x?secret=$s&counter=x"))
        assertEquals(OtpAuthException.Reason.BadAlgorithm, reason("otpauth://totp/x?secret=$s&algorithm=MD5"))
    }

    @Test fun exceptionMessageHidesUri() {
        val e = assertFailsWith<OtpAuthException> { parse("otpauth://totp/x?secret=ZZZ!!$s") }
        assertFalse(e.message!!.contains(s))
        assertEquals("invalid otpauth URI: BadSecret", e.message)
    }

    @Test fun roundTripTotp() {
        val a = OtpAccount("id", OtpType.TOTP, "Acme Corp", "bob@x.org", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
            OtpAlgorithm.SHA512, 8, 45)
        assertEquals(a, parse(OtpAuthUri.build(a)))
    }

    @Test fun roundTripHotp() {
        val a = OtpAccount("id", OtpType.HOTP, "", "a:b c/d?e&f=g#h+i", byteArrayOf(9, 8, 7, 6, 5), counter = 42)
        assertEquals(a, parse(OtpAuthUri.build(a)))
    }

    @Test fun roundTripIssuerWithColon() {
        val a = OtpAccount("id", OtpType.TOTP, "A:B", "u", byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(a, parse(OtpAuthUri.build(a)))
    }

    @Test fun buildOmitsDefaults() {
        val a = OtpAccount("id", OtpType.TOTP, "Acme", "bob", byteArrayOf(1, 2, 3, 4, 5))
        val u = OtpAuthUri.build(a)
        assertFalse(u.contains("algorithm="))
        assertFalse(u.contains("digits="))
        assertFalse(u.contains("period="))
        assertTrue(u.startsWith("otpauth://totp/Acme:bob?"))
        assertTrue(u.contains("issuer=Acme"))
    }

    @Test fun buildEncodesLabel() {
        val a = OtpAccount("id", OtpType.TOTP, "", "a b@c", byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(OtpAuthUri.build(a).contains("a%20b%40c"))
    }
}
