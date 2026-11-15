package app.rustmfa.android.vault

import app.rustmfa.android.R
import app.rustmfa.vault.OtpAuthException.Reason
import kotlin.test.Test
import kotlin.test.assertEquals

class OtpAuthMessagesTest {
    @Test
    fun `every reason maps to its group`() {
        val expected = mapOf(
            Reason.NotOtpAuth to R.string.otpauth_error_not_otpauth,
            Reason.UnknownType to R.string.otpauth_error_type,
            Reason.MissingSecret to R.string.otpauth_error_secret,
            Reason.BadSecret to R.string.otpauth_error_secret,
            Reason.BadDigits to R.string.otpauth_error_settings,
            Reason.BadPeriod to R.string.otpauth_error_settings,
            Reason.BadAlgorithm to R.string.otpauth_error_settings,
            Reason.MissingCounter to R.string.otpauth_error_counter,
            Reason.BadCounter to R.string.otpauth_error_counter,
        )
        assertEquals(Reason.entries.toSet(), expected.keys)
        for ((reason, res) in expected) assertEquals(res, otpAuthMessage(reason), reason.name)
    }

    @Test
    fun `there are five distinct messages`() {
        assertEquals(5, Reason.entries.map { otpAuthMessage(it) }.toSet().size)
    }
}
