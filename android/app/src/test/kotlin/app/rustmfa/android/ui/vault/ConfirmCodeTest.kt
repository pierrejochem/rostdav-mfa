package app.rustmfa.android.ui.vault

import app.rustmfa.vault.OtpAccount
import app.rustmfa.vault.OtpType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ConfirmCodeTest {
    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @Test
    fun `a TOTP account shows the code for the current time (RFC 6238)`() {
        val account = OtpAccount("new", OtpType.TOTP, "Example", "alice", rfcSecret, digits = 8)
        assertEquals("94287082", confirmCode(account, 59))
        assertEquals("07081804", confirmCode(account, 1_111_111_109))
    }

    @Test
    fun `a HOTP account shows the code for its counter without changing it (RFC 4226)`() {
        val account = OtpAccount("new", OtpType.HOTP, "Counter", "bob", rfcSecret, counter = 1)
        assertEquals("287082", confirmCode(account, 59))
        assertEquals("287082", confirmCode(account, 9_999))
        assertEquals(1L, account.counter)
        assertContentEquals("12345678901234567890".toByteArray(Charsets.US_ASCII), account.secret)
    }
}
