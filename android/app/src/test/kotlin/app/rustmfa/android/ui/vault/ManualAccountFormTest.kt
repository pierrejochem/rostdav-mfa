package app.rustmfa.android.ui.vault

import app.rustmfa.android.R
import app.rustmfa.android.ui.vault.ManualAccountForm.Field
import app.rustmfa.vault.Base32
import app.rustmfa.vault.OtpAlgorithm
import app.rustmfa.vault.OtpType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManualAccountFormTest {
    private fun form(secret: String = "JBSWY3DPEHPK3PXP", issuer: String = "Example", label: String = "") =
        ManualAccountForm().apply {
            this.secret = secret
            this.issuer = issuer
            this.label = label
        }

    private fun ManualAccountForm.errors() = assertIs<FormResult.Invalid>(validate()).errors

    @Test
    fun `a base32 secret with an issuer is valid with the defaults`() {
        val account = assertIs<FormResult.Valid>(form().validate()).account
        assertEquals(OtpType.TOTP, account.type)
        assertEquals("Example", account.issuer)
        assertEquals("", account.label)
        assertEquals(OtpAlgorithm.SHA1, account.algorithm)
        assertEquals(6, account.digits)
        assertEquals(30, account.period)
        assertContentEquals(Base32.decode("JBSWY3DPEHPK3PXP"), account.secret)
    }

    @Test
    fun `an empty, an invalid and an empty-decoding secret give the Secret error`() {
        for (s in listOf("", "!!", "====")) {
            val errors = form(secret = s).errors()
            assertEquals(R.string.vault_error_secret, errors[Field.Secret], "secret '$s'")
        }
    }

    @Test
    fun `both names blank give the Name error`() {
        val errors = form(issuer = " ", label = "").errors()
        assertEquals(R.string.vault_error_name, errors[Field.Name])
        assertEquals(setOf(Field.Name), errors.keys)
    }

    @Test
    fun `an account name alone is enough`() {
        assertIs<FormResult.Valid>(form(issuer = "", label = "alice").validate())
    }

    @Test
    fun `a TOTP period of 0 or 3601 gives the Period error`() {
        for (p in listOf("0", "3601", "abc", "")) {
            val f = form().apply { period = p }
            assertEquals(R.string.vault_error_period, f.errors()[Field.Period], "period '$p'")
        }
    }

    @Test
    fun `a HOTP form ignores the period`() {
        val f = form().apply {
            type = OtpType.HOTP
            period = "0"
        }
        assertIs<FormResult.Valid>(f.validate())
    }

    @Test
    fun `a HOTP form needs a counter of 0 or more`() {
        for (c in listOf("-1", "abc", "")) {
            val f = form().apply {
                type = OtpType.HOTP
                counter = c
            }
            assertEquals(R.string.vault_error_counter, f.errors()[Field.Counter], "counter '$c'")
        }
        val ok = form().apply {
            type = OtpType.HOTP
            counter = "0"
        }
        val account = assertIs<FormResult.Valid>(ok.validate()).account
        assertEquals(OtpType.HOTP, account.type)
        assertEquals(0L, account.counter)
        val five = form().apply {
            type = OtpType.HOTP
            counter = "5"
        }
        assertEquals(5L, assertIs<FormResult.Valid>(five.validate()).account.counter)
    }

    @Test
    fun `a TOTP form ignores the counter`() {
        assertIs<FormResult.Valid>(form().apply { counter = "-1" }.validate())
    }

    @Test
    fun `the advanced settings are carried into the account`() {
        val f = form().apply {
            algorithm = OtpAlgorithm.SHA256
            digits = 8
            period = "60"
        }
        val account = assertIs<FormResult.Valid>(f.validate()).account
        assertEquals(OtpAlgorithm.SHA256, account.algorithm)
        assertEquals(8, account.digits)
        assertEquals(60, account.period)
    }

    @Test
    fun `a secret with spaces and lowercase letters is accepted`() {
        val account = assertIs<FormResult.Valid>(form(secret = "jbsw y3dp ehpk 3pxp").validate()).account
        assertContentEquals(Base32.decode("JBSWY3DPEHPK3PXP"), account.secret)
    }

    @Test
    fun `the names are trimmed`() {
        val account = assertIs<FormResult.Valid>(form(issuer = "  Example ", label = " alice ").validate()).account
        assertEquals("Example", account.issuer)
        assertEquals("alice", account.label)
    }

    @Test
    fun `clear empties the secret and the names`() {
        val f = form(label = "alice")
        f.clear()
        assertEquals("", f.secret)
        assertEquals("", f.issuer)
        assertEquals("", f.label)
        assertTrue(f.errors().containsKey(Field.Secret))
    }
}
