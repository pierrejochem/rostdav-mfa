package app.rustmfa.android.ui.vault

import app.rustmfa.android.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VaultScanParsingTest {
    private val valid = "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example"
    private val invalid = "https://example.com/not-a-code"

    @Test
    fun `a valid URI gives the account`() {
        val account = assertIs<ScanOutcome.Account>(scanOutcome(valid, null, 0, 1_000)).account
        assertEquals("Example", account.issuer)
        assertEquals("alice", account.label)
    }

    @Test
    fun `an invalid URI is rejected with its message`() {
        val out = assertIs<ScanOutcome.Rejected>(scanOutcome(invalid, null, 0, 1_000))
        assertEquals(R.string.otpauth_error_not_otpauth, out.messageRes)
        val noSecret = assertIs<ScanOutcome.Rejected>(scanOutcome("otpauth://totp/x?issuer=y", null, 0, 1_000))
        assertEquals(R.string.otpauth_error_secret, noSecret.messageRes)
    }

    @Test
    fun `the same invalid string is ignored for three seconds`() {
        assertEquals(ScanOutcome.Ignored, scanOutcome(invalid, invalid, 10_000, 12_999))
        assertIs<ScanOutcome.Rejected>(scanOutcome(invalid, invalid, 10_000, 13_000))
    }

    @Test
    fun `a different invalid string is rejected at once`() {
        assertIs<ScanOutcome.Rejected>(scanOutcome("otpauth://foo/x?secret=AA", invalid, 10_000, 10_001))
    }
}
