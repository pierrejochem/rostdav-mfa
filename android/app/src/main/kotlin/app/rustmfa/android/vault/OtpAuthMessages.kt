package app.rustmfa.android.vault

import androidx.annotation.StringRes
import app.rustmfa.android.R
import app.rustmfa.vault.OtpAuthException.Reason

/** One message per group of reasons, for the scanner's error banner. */
@StringRes
fun otpAuthMessage(reason: Reason): Int = when (reason) {
    Reason.NotOtpAuth -> R.string.otpauth_error_not_otpauth
    Reason.UnknownType -> R.string.otpauth_error_type
    Reason.MissingSecret, Reason.BadSecret -> R.string.otpauth_error_secret
    Reason.BadDigits, Reason.BadPeriod, Reason.BadAlgorithm -> R.string.otpauth_error_settings
    Reason.MissingCounter, Reason.BadCounter -> R.string.otpauth_error_counter
}
