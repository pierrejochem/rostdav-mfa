package app.rustmfa.contract

import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Runs every `challenge_cases` entry of the shared vectors. */
@RunWith(Parameterized::class)
class ChallengeVectorsTest(@Suppress("unused") private val name: String, private val case: ChallengeCase) {
    @Test
    fun theCaseGivesTheExpectedOutcome() {
        val f = case.fields
        val fields = ChallengeFields(f.challengeId, f.nonce, f.loginId, f.deviceId, f.expiresAt, f.audience)
        val outcome = outcomeOf { 
            // building errors come before verification
            val message = Canonical.message(fields)
            assertEquals(case.message, message, "canonical message")
            case.signatureDerB64url?.let {
                assertContentEquals(
                    Base64Url.decode(case.signatureB64url),
                    Signature.derToRaw(Base64Url.decode(it)),
                    "derToRaw",
                )
            }
            Signature.verifyEs256Raw(
                Base64.getDecoder().decode(Vectors.load().key.spkiDerB64),
                message.toByteArray(Charsets.UTF_8),
                Base64Url.decode(case.signatureB64url),
            )
        }
        assertEquals(case.expect, outcome)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = Vectors.load().challengeCases.map { arrayOf(it.name, it) }
    }
}

internal fun outcomeOf(block: () -> Unit): String =
    try {
        block()
        "ok"
    } catch (e: ContractException) {
        e.kind.name
    }

class ChallengeRulesTest {
    private val ok = ChallengeFields("ch_1", "n", "login_1", "dev_1", "2026-10-28T15:03:30Z", "rostdav")

    @Test
    fun aDerBlobAsTheSignatureIsBadSignatureEncoding() {
        val vectors = Vectors.load()
        val der = vectors.challengeCases.first { it.signatureDerB64url != null }.signatureDerB64url!!
        val e = assertFailsWith<ContractException> {
            Signature.verifyEs256Raw(
                Base64.getDecoder().decode(vectors.key.spkiDerB64),
                "x".toByteArray(),
                Base64Url.decode(der),
            )
        }
        assertEquals(ContractErrorKind.BadSignatureEncoding, e.kind)
    }

    @Test
    fun expiresAtWithAFractionOrAnOffsetIsInvalid() {
        for (bad in listOf("2026-10-28T15:03:30.5Z", "2026-10-28T15:03:30+00:00", "2026-10-28 15:03:30Z", "")) {
            val e = assertFailsWith<ContractException>(bad) { Canonical.message(ok.copy(expiresAt = bad)) }
            assertEquals(ContractErrorKind.InvalidExpiresAt, e.kind, bad)
        }
    }

    @Test
    fun anEmptyFieldIsInvalid() {
        val e = assertFailsWith<ContractException> { Canonical.message(ok.copy(audience = "")) }
        assertEquals(ContractErrorKind.InvalidField, e.kind)
    }

    @Test
    fun aNonAsciiDigitInExpiresAtIsInvalid() {
        // Arabic-indic digits must not pass as digits
        val e = assertFailsWith<ContractException> { Canonical.message(ok.copy(expiresAt = "٢٠٢٦-10-28T15:03:30Z")) }
        assertEquals(ContractErrorKind.InvalidExpiresAt, e.kind)
    }

    @Test
    fun aRawSignatureOfTheWrongLengthIsBadSignatureEncoding() {
        val key = Base64.getDecoder().decode(Vectors.load().key.spkiDerB64)
        for (len in listOf(0, 63, 65)) {
            val e = assertFailsWith<ContractException> { Signature.verifyEs256Raw(key, byteArrayOf(1), ByteArray(len)) }
            assertEquals(ContractErrorKind.BadSignatureEncoding, e.kind, "len $len")
        }
    }

    @Test
    fun aGarbagePublicKeyIsBadPublicKey() {
        val e = assertFailsWith<ContractException> { Signature.verifyEs256Raw(byteArrayOf(1, 2, 3), byteArrayOf(1), ByteArray(64)) }
        assertEquals(ContractErrorKind.BadPublicKey, e.kind)
    }

    @Test
    fun base64UrlHasNoPaddingAndNoStandardAlphabet() {
        assertEquals("-_8", Base64Url.encode(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
        for (bad in listOf("-_8=", "+/8", "a b")) {
            val e = assertFailsWith<ContractException>(bad) { Base64Url.decode(bad) }
            assertEquals(ContractErrorKind.BadSignatureEncoding, e.kind, bad)
        }
    }
}
