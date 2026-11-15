package app.rustmfa.contract

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Runs every `request_cases` entry of the shared vectors. */
@RunWith(Parameterized::class)
class RequestVectorsTest(@Suppress("unused") private val name: String, private val case: RequestCase) {
    @Test
    fun theCaseGivesTheExpectedOutcome() {
        val outcome = outcomeOf {
            val body = Base64.getDecoder().decode(case.bodyB64)
            val s = RequestSigning.signingString(case.method, case.path, case.ts, case.nonce, body)
            assertEquals(case.signingString, s, "signing string")
            Signature.verifyEs256Raw(
                Base64.getDecoder().decode(Vectors.load().key.spkiDerB64),
                s.toByteArray(Charsets.UTF_8),
                Base64Url.decode(case.signatureB64url),
            )
        }
        assertEquals(case.expect, outcome)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = Vectors.load().requestCases.map { arrayOf(it.name, it) }
    }
}

class RequestRulesTest {
    @Test
    fun aMethodOutsideTheListIsInvalid() {
        val e = assertFailsWith<ContractException> { RequestSigning.signingString("PATCH", "/x", 1, "n", byteArrayOf()) }
        assertEquals(ContractErrorKind.InvalidField, e.kind)
    }

    @Test
    fun aNewlineInPathOrNonceIsInvalid() {
        assertFailsWith<ContractException> { RequestSigning.signingString("GET", "/a\nb", 1, "n", byteArrayOf()) }
        assertFailsWith<ContractException> { RequestSigning.signingString("GET", "/a", 1, "n\n", byteArrayOf()) }
        assertFailsWith<ContractException> { RequestSigning.signingString("GET", "", 1, "n", byteArrayOf()) }
        assertFailsWith<ContractException> { RequestSigning.signingString("GET", "/a", 1, "", byteArrayOf()) }
    }
}
